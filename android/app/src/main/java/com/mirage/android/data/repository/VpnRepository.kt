package com.mirage.android.data.repository

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Bundle
import com.mirage.android.CoreService
import com.mirage.android.core.CoreController
import com.mirage.android.core.ICoreCallback
import com.mirage.android.data.model.TrafficStats
import com.mirage.android.data.model.VpnState
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * VPN 核心连接状态与遥测数据仓库。
 */
class VpnRepository(private val context: Context) {

    private val nodeRepo = NodeRepository.getInstance(context)
    private val ruleRepo = RuleRepository.getInstance(context)
    private val dnsRepo = DnsRepository.getInstance(context)

    private val _vpnState = MutableStateFlow<VpnState>(VpnState.Disconnected)
    val vpnState: StateFlow<VpnState> = _vpnState.asStateFlow()

    private val _trafficStats = MutableStateFlow(TrafficStats())
    val trafficStats: StateFlow<TrafficStats> = _trafficStats.asStateFlow()

    private val _latencyMs = MutableStateFlow(-1L)
    val latencyMs: StateFlow<Long> = _latencyMs.asStateFlow()

    private val _logs = MutableStateFlow<List<String>>(emptyList())
    val logs: StateFlow<List<String>> = _logs.asStateFlow()

    private val _connections = MutableStateFlow<List<com.mirage.android.data.model.ConnectionInfo>>(emptyList())
    val connections: StateFlow<List<com.mirage.android.data.model.ConnectionInfo>> = _connections.asStateFlow()

    private val _recentRequests = MutableStateFlow<List<com.mirage.android.data.model.RecentRequestInfo>>(emptyList())
    val recentRequests: StateFlow<List<com.mirage.android.data.model.RecentRequestInfo>> = _recentRequests.asStateFlow()

    private val routingPrefs = context.getSharedPreferences("mirage_routing_prefs", Context.MODE_PRIVATE)
    private val _outboundMode = MutableStateFlow(routingPrefs.getInt("outbound_mode", 0))
    val outboundMode: StateFlow<Int> = _outboundMode.asStateFlow()

    private val prefs = context.getSharedPreferences("mirage_vpn_prefs", Context.MODE_PRIVATE)
    private val _isBypassLanEnabled = MutableStateFlow(com.mirage.android.core.TunConfigStore.isBypassLanEnabled(context))
    val isBypassLanEnabled: StateFlow<Boolean> = _isBypassLanEnabled.asStateFlow()

    private val _isIpv6Enabled = MutableStateFlow(com.mirage.android.core.TunConfigStore.isIpv6Enabled(context))
    val isIpv6Enabled: StateFlow<Boolean> = _isIpv6Enabled.asStateFlow()

    private val _isBlockQuic = MutableStateFlow(prefs.getBoolean("block_quic", true))
    val isBlockQuic: StateFlow<Boolean> = _isBlockQuic.asStateFlow()

    private val _isUdpMux = MutableStateFlow(prefs.getBoolean("udp_mux", true))
    val isUdpMux: StateFlow<Boolean> = _isUdpMux.asStateFlow()

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var telemetryJob: Job? = null
    private var commandBusStatsJob: Job? = null
    private var commandBusReqsJob: Job? = null
    private val telemetryWakeChannel = Channel<Unit>(Channel.CONFLATED)
    private val isAppForeground = AtomicBoolean(true)
    private val isMonitorActive = AtomicBoolean(false)
    private val startedActivities = AtomicInteger(0)

    init {
        (context.applicationContext as? Application)?.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) {
                if (startedActivities.incrementAndGet() > 0) {
                    isAppForeground.set(true)
                    telemetryWakeChannel.trySend(Unit)
                }
            }
            override fun onActivityStopped(activity: Activity) {
                if (startedActivities.decrementAndGet() <= 0) {
                    startedActivities.set(0)
                    isAppForeground.set(false)
                }
            }
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityResumed(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
    }

    /**
     * 标记当前用户是否正在观察【监控/日志】页面。
     * 当用户离开监控 Tab 或 App 退到后台时挂起重型 JSON 轮询；切入时即时唤醒更新。
     */
    fun setMonitorActive(active: Boolean) {
        val prev = isMonitorActive.getAndSet(active)
        if (!prev && active) {
            telemetryWakeChannel.trySend(Unit)
        }
    }

    private val callback = object : ICoreCallback.Stub() {
        override fun onStateChanged(running: Boolean) {
            scope.launch {
                if (running) {
                    dnsRepo.applyDns()
                    _vpnState.value = VpnState.Connected(nodeRepo.getSelectedNode())
                    startTelemetry()
                } else {
                    _vpnState.value = VpnState.Disconnected
                    stopTelemetry()
                }
            }
        }

        override fun onLog(line: String?) {
            if (!line.isNullOrBlank()) {
                _logs.update { it.plus(line).takeLast(150) }
            }
        }

        override fun onNodeChanged(index: Int, uri: String?) {
            scope.launch {
                nodeRepo.setSelected(index)
                val node = nodeRepo.getSelectedNode()
                if (_vpnState.value is VpnState.Connected) {
                    _vpnState.value = VpnState.Connected(node)
                }
            }
        }
    }

    private val broadcastReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            when (intent?.action) {
                CoreService.ACTION_VPN_STOPPED -> {
                    scope.launch {
                        _vpnState.value = VpnState.Disconnected
                        _connections.value = emptyList()
                        stopTelemetry()
                    }
                }
                CoreService.ACTION_VPN_STARTED -> {
                    scope.launch {
                        _vpnState.value = VpnState.Connected(nodeRepo.getSelectedNode())
                        startTelemetry()
                    }
                }
            }
        }
    }

    init {
        CoreController.bind(context)
        CoreController.registerCallback(callback)
        val filter = android.content.IntentFilter().apply {
            addAction(CoreService.ACTION_VPN_STOPPED)
            addAction(CoreService.ACTION_VPN_STARTED)
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(broadcastReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(broadcastReceiver, filter)
        }
        startTelemetry()
    }


    fun checkCurrentState() {
        val isRunning = CoreController.isRunning()
        android.util.Log.d("Mirage", "[vpn] checkCurrentState: isRunning=$isRunning bound=${com.mirage.android.core.CoreController.isBound()}")
        if (isRunning) {
            _vpnState.value = VpnState.Connected(nodeRepo.getSelectedNode())
            startTelemetry()
        } else {
            _vpnState.value = VpnState.Disconnected
            stopTelemetry()
        }
    }

    fun startVpn() {
        val selected = nodeRepo.getSelectedNode()
        if (selected == null) {
            _vpnState.value = VpnState.Error("请先选择或添加节点")
            return
        }

        _vpnState.value = VpnState.Connecting
        // 注入规则与 DNS 配置
        ruleRepo.applyRules()
        dnsRepo.applyDns()
        // 启动前清理残留 Fake-IP 与 DNS 缓存
        runCatching { CoreController.clearDnsCache() }

        val appFilterConfig = com.mirage.android.core.AppFilterStore.getConfig(context)
        val intent = Intent(context, CoreService::class.java).apply {
            putExtra("uri", selected.uri)
            putExtra("pool_size", nodeRepo.getPoolSize())
            putExtra("bypass_lan", _isBypassLanEnabled.value)
            putExtra("enable_ipv6", _isIpv6Enabled.value)
            putExtra("mtu", com.mirage.android.core.TunConfigStore.getMtu(context))
            putExtra("app_filter_json", com.mirage.android.core.AppFilterStore.toJson(appFilterConfig))
            putExtra("direct_dns", dnsRepo.getDirectDns())
            putExtra("remote_dns", dnsRepo.getRemoteDns())
            putExtra("block_quic", _isBlockQuic.value)
            putExtra("udp_mux", _isUdpMux.value)
            putExtra("auto_reconnect", com.mirage.android.core.SettingsStore.isAutoReconnect(context))
            putExtra("check_interval", com.mirage.android.core.SettingsStore.getCheckIntervalSec(context))
            putExtra("failover_mode", com.mirage.android.core.SettingsStore.getFailoverMode(context))
            putExtra("outbound_mode", _outboundMode.value)
            // 注意: 节点全表不走 Intent, 见下方 pushNodesToCore 的说明。
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
        pushNodesToCore()
        startTelemetry()
    }

    /**
     * 把节点全表推送给 :core（failover 选优需要）。
     *
     * 刻意不放进启动 Intent:
     * 1. 体积无上限。订阅动辄数百个节点, 序列化后可达上百 KB, 而启动 Intent 走
     *    Binder 事务并由 ActivityManager 持有, 大订阅下会抛 TransactionTooLargeException,
     *    直接表现为 VPN 启动失败。
     * 2. 节点 URI 含明文密码, 放在 Intent 里会随 ActivityManager 状态进入 dumpsys
     *    与 bugreport。走 AIDL 则只在两个进程之间点对点传递。
     *
     * 启动 Intent 仍携带 `uri`（当前选中节点）, 因此内核在本次推送到达之前
     * 就已具备建连所需的全部信息, 不存在时序依赖。
     */
    @Volatile
    private var pushNodesJob: Job? = null

    private fun pushNodesToCore() {
        // 取消上一次未完成的推送: 每次推送携带的是启动时刻的节点快照, 若两次推送
        // 并存, 慢的那次会用更旧的快照覆盖新的 (updateNodes 是无版本号的盲写)。
        pushNodesJob?.cancel()
        pushNodesJob = scope.launch(Dispatchers.IO) {
            val json = runCatching {
                com.mirage.android.core.NodeStore.getNodesJson(context)
            }.getOrNull() ?: return@launch
            // :core 刚被拉起, binder 可能尚未就绪, 重试几次
            repeat(10) {
                if (runCatching { CoreController.updateNodes(json) }.getOrDefault(false)) {
                    return@launch
                }
                delay(300)
            }
            android.util.Log.w("VpnRepository", "节点列表推送失败, failover 将回退到 :core 本地读取")
        }
    }

    fun stopVpn() {
        // 停止后推送不再有意义, 且 :core 实例跨停止/启动存活, 迟到的推送会污染
        // 下一次连接的 serviceConfig。
        pushNodesJob?.cancel()
        pushNodesJob = null
        _vpnState.value = VpnState.Stopping
        runCatching { CoreController.clearDnsCache() }
        runCatching { CoreController.stop() }
        _vpnState.value = VpnState.Disconnected
        _connections.value = emptyList()
        stopTelemetry()
    }

    fun switchNode(uri: String): Boolean {
        val success = CoreController.setNode(uri)
        if (success && _vpnState.value is VpnState.Connected) {
            _vpnState.value = VpnState.Connected(nodeRepo.getSelectedNode())
        }
        return success
    }

    fun clearLogs() {
        _logs.value = emptyList()
        com.mirage.android.core.LogStore.clear()
        CoreController.clearNativeLogs()
    }

    fun setLogLevel(level: com.mirage.android.data.model.LogLevel): Boolean {
        val levelStr = when (level) {
            com.mirage.android.data.model.LogLevel.ALL, com.mirage.android.data.model.LogLevel.DEBUG -> "debug"
            com.mirage.android.data.model.LogLevel.TRACE -> "trace"
            com.mirage.android.data.model.LogLevel.INFO -> "info"
            com.mirage.android.data.model.LogLevel.WARN -> "warn"
            com.mirage.android.data.model.LogLevel.ERROR -> "error"
        }
        return CoreController.setLogLevel(levelStr)
    }

    fun setBypassLanEnabled(enabled: Boolean) {
        _isBypassLanEnabled.value = enabled
        com.mirage.android.core.TunConfigStore.setBypassLanEnabled(context, enabled)
    }

    fun setIpv6Enabled(enabled: Boolean) {
        _isIpv6Enabled.value = enabled
        com.mirage.android.core.TunConfigStore.setIpv6Enabled(context, enabled)
    }

    fun setBlockQuic(block: Boolean): Boolean {
        _isBlockQuic.value = block
        prefs.edit().putBoolean("block_quic", block).apply()
        return CoreController.setBlockQuic(block)
    }

    fun setUdpMux(enabled: Boolean): Boolean {
        _isUdpMux.value = enabled
        prefs.edit().putBoolean("udp_mux", enabled).apply()
        return CoreController.setUdpMux(enabled)
    }

    fun setOutboundMode(mode: Int): Boolean {
        _outboundMode.value = mode
        routingPrefs.edit().putInt("outbound_mode", mode).apply()
        return CoreController.setOutboundMode(mode)
    }

    private fun startTelemetry() {
        com.mirage.android.core.CommandBusClient.start()
        if (commandBusStatsJob?.isActive != true) {
            commandBusStatsJob = scope.launch(Dispatchers.Main) {
                com.mirage.android.core.CommandBusClient.statsFlow.collect { stats ->
                    _trafficStats.value = stats
                }
            }
        }
        if (commandBusReqsJob?.isActive != true) {
            commandBusReqsJob = scope.launch(Dispatchers.Main) {
                com.mirage.android.core.CommandBusClient.recentRequestsFlow.collect { reqs ->
                    _recentRequests.value = reqs
                }
            }
        }

        telemetryJob?.cancel()
        telemetryJob = scope.launch(Dispatchers.IO) {
            var tick = 0L
            var lastConnJson = ""
            var lastReqsJson = ""
            while (isActive) {
                tick++
                val isRunning = CoreController.isRunning()
                if (isRunning) {
                    if (_vpnState.value !is VpnState.Connected) {
                        withContext(Dispatchers.Main) {
                            _vpnState.value = VpnState.Connected(nodeRepo.getSelectedNode())
                        }
                    }

                    val foreground = isAppForeground.get()
                    val monitorActive = isMonitorActive.get()
                    val isBusConnected = com.mirage.android.core.CommandBusClient.isConnected.value

                    // 1. 基础流量与延迟统计:
                    // 若命令总线已通过 Unix Domain Socket 建立流式推送，则跳过 Binder IPC 轮询；未连接时降级走 Binder 采样
                    if (foreground || tick % 5 == 0L) {
                        if (!isBusConnected) {
                            val statsArr = CoreController.getStats()
                            if (statsArr != null && statsArr.size >= 7) {
                                _trafficStats.value = TrafficStats.fromArray(statsArr)
                            }
                        }
                        val lat = CoreController.latencyMs()
                        _latencyMs.value = lat
                    }

                    // 2. 日志与活跃连接/近期请求拉取:
                    // 仅当处于前台且用户正停留在【监控】Tab (isMonitorActive = true) 时才拉取与解析 JSON
                    if (foreground && monitorActive) {
                        if (tick % 2 == 0L) {
                            val remoteLogs = CoreController.recentLogs().toList()
                            if (remoteLogs.isNotEmpty()) {
                                _logs.value = remoteLogs.takeLast(150)
                            }

                            val connJson = CoreController.getConnectionsJson()
                            if (connJson != lastConnJson) {
                                lastConnJson = connJson
                                if (connJson.isNotBlank() && connJson != "[]") {
                                    val parsedList = mutableListOf<com.mirage.android.data.model.ConnectionInfo>()
                                    runCatching {
                                        val arr = org.json.JSONArray(connJson)
                                        for (i in 0 until arr.length()) {
                                            parsedList.add(com.mirage.android.data.model.ConnectionInfo.fromJson(arr.getJSONObject(i)))
                                        }
                                    }
                                    _connections.value = parsedList
                                } else {
                                    _connections.value = emptyList()
                                }
                            }
                        }

                        // 最近请求流: 若命令总线未连接，降级走 Binder 轮询；总线已推送则无需 Binder 调取
                        if (!isBusConnected) {
                            if (tick % 2 == 0L || lastReqsJson.isEmpty()) {
                                val reqsJson = CoreController.getRecentRequestsJson()
                                if (reqsJson != lastReqsJson) {
                                    lastReqsJson = reqsJson
                                    if (reqsJson.isNotBlank() && reqsJson != "[]") {
                                        val parsedReqs = mutableListOf<com.mirage.android.data.model.RecentRequestInfo>()
                                        runCatching {
                                            val arr = org.json.JSONArray(reqsJson)
                                            for (i in 0 until arr.length()) {
                                                parsedReqs.add(com.mirage.android.data.model.RecentRequestInfo.fromJson(arr.getJSONObject(i)))
                                            }
                                        }
                                        _recentRequests.value = parsedReqs
                                    } else {
                                        _recentRequests.value = emptyList()
                                    }
                                }
                            }
                        }
                    }
                } else {
                    if (_vpnState.value !is VpnState.Disconnected && _vpnState.value !is VpnState.Connecting && _vpnState.value !is VpnState.Stopping) {
                        withContext(Dispatchers.Main) {
                            _vpnState.value = VpnState.Disconnected
                        }
                    }
                }

                // 自适应等待: 前台 1000ms, 后台 5000ms; 用户切入监控页或回到前台时通过 channel 即时唤醒
                val intervalMs = if (isAppForeground.get()) 1000L else 5000L
                withTimeoutOrNull(intervalMs) {
                    telemetryWakeChannel.receive()
                }
            }
        }
    }

    private fun stopTelemetry() {
        telemetryJob?.cancel()
        telemetryJob = null
        commandBusStatsJob?.cancel()
        commandBusStatsJob = null
        commandBusReqsJob?.cancel()
        commandBusReqsJob = null
        com.mirage.android.core.CommandBusClient.stop()
        _trafficStats.value = TrafficStats()
        _latencyMs.value = -1L
    }

    fun destroy() {
        stopTelemetry()
        CoreController.unregisterCallback(callback)
        CoreController.unbind(context)
    }

    companion object {
        @Volatile
        private var instance: VpnRepository? = null

        fun getInstance(context: Context): VpnRepository {
            return instance ?: synchronized(this) {
                instance ?: VpnRepository(context.applicationContext).also { instance = it }
            }
        }
    }
}
