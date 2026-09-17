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
                startedActivities.incrementAndGet()
                isAppForeground.set(true)
                // 无条件调用: bind() 自身幂等 (bindRequested 守卫)。
                //
                // 不能写成「计数从 0 变 1 时才绑定」—— 本仓库是懒构造的
                // (ViewModel 首次访问时), 注册生命周期回调时首个 Activity 的
                // onStart 往往已经过去, 计数从一开始就少算一次。真机实测该偏差
                // 会让回前台时 incrementAndGet() 得 0、条件不成立, 绑定永不恢复,
                // 于是 CoreController.stop() 静默失效 —— 用户点断开没有反应。
                CoreController.bind(context)
                telemetryWakeChannel.trySend(Unit)
            }
            override fun onActivityStopped(activity: Activity) {
                // 钳位到 0: 计数可能因上述偏差而偏低, 不钳位会变负并再也回不到 0
                val remaining = startedActivities.updateAndGet { (it - 1).coerceAtLeast(0) }
                if (remaining == 0) {
                    isAppForeground.set(false)
                    // 最后一个 Activity 不可见时解绑。
                    //
                    // 此前 bind 在 init 里、且 VpnRepository 是进程级单例, 于是
                    // BIND_AUTO_CREATE 这条引用与 UI 进程同寿, :core 永不退出 ——
                    // 真机实测: 断开 VPN 后 stopSelf 已生效 (dumpsys 无 started=true),
                    // 进程却仍被 AppBindRecord 吊着。这正是 :core 的 SharedPreferences
                    // 缓存永不刷新的根因。
                    //
                    // 解绑不影响运行中的 VPN: CoreService 是 started foreground service,
                    // 只有 stopSelf/stopService 能终止它。
                    CoreController.unbind(context)
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
        // 初次绑定兜底: 本仓库若在某个 Activity 已 onStart 之后才被构造
        // (ViewModel 懒初始化), 就错过了那一次 onActivityStarted。此处补一次。
        //
        // 这不会退回「进程级常驻绑定」—— 关键不变式由 onActivityStopped 保证:
        // 最后一个 Activity 不可见时必定解绑, 之后再进前台才重新绑定。
        CoreController.bind(context)
        // registerCallback 与绑定时机解耦: CoreController 缓存回调集合,
        // 并在每次 onServiceConnected 时重新注册。
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
        // 未绑定时不要改状态。
        //
        // 绑定改为跟随 Activity 可见性后, 每次回前台都有一段 bindService 到
        // onServiceConnected 的异步窗口 (真机实测约 120–170ms)。窗口内
        // CoreController.isRunning() 恒为 false, 若照写就会把正在连接的 VPN
        // 显示成已断开, 闪一下再被 onServiceConnected 的状态同步纠正。
        // 绑定就绪后 CoreController 会主动推一次真实状态, 这里直接跳过即可。
        if (!CoreController.isBound()) {
            android.util.Log.d("Mirage", "[vpn] checkCurrentState: 尚未绑定, 等待 onServiceConnected 同步")
            return
        }
        val isRunning = CoreController.isRunning()
        android.util.Log.d("Mirage", "[vpn] checkCurrentState: isRunning=$isRunning")
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
     * 推送时机由**绑定事件**驱动而非轮询重试: `:core` 是独立进程, startForegroundService
     * 之后它还要冷启动并加载数 MB 原生库。真机实测推送在 :core 起来前 0.63 秒就把
     * 10×300ms 的重试预算耗尽了, 回落到 :core 侧的陈旧 SharedPreferences 读取 ——
     * 正是这套改动想消除的东西。CoreController.runWhenConnected 在已绑定时立即执行,
     * 否则挂到 onServiceConnected 上, 不存在预算给多少的问题。
     *
     * 启动 Intent 仍携带 `uri`（当前选中节点）, 因此内核在本次推送到达之前
     * 就已具备建连所需的全部信息, 不存在时序依赖。
     */
    private fun pushNodesToCore() {
        pushNodesJob?.cancel()
        pushNodesJob = scope.launch(Dispatchers.IO) {
            val json = runCatching {
                com.mirage.android.core.NodeStore.getNodesJson(context)
            }.getOrNull() ?: return@launch
            // 同 key 覆盖: 连点连接时只保留最新快照, 避免旧快照后到覆盖新的
            CoreController.runWhenConnected(PENDING_PUSH_NODES) {
                if (!CoreController.updateNodes(json)) {
                    android.util.Log.w("VpnRepository", "节点列表推送被 :core 拒绝")
                }
            }
        }
    }

    fun stopVpn() {
        // 停止后推送不再有意义, 且 :core 实例跨停止/启动存活, 迟到的推送会污染
        // 下一次连接的 serviceConfig。
        pushNodesJob?.cancel()
        pushNodesJob = null
        CoreController.cancelPending(PENDING_PUSH_NODES)
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
        /** CoreController 待办动作的 key: 节点全表推送。同 key 覆盖, 只保留最新快照。 */
        private const val PENDING_PUSH_NODES = "pushNodes"
        @Volatile
        private var instance: VpnRepository? = null

        fun getInstance(context: Context): VpnRepository {
            return instance ?: synchronized(this) {
                instance ?: VpnRepository(context.applicationContext).also { instance = it }
            }
        }
    }
}
