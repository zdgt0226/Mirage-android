package com.mirage.android.data.repository

import android.content.Context
import android.content.Intent
import com.mirage.android.CoreService
import com.mirage.android.core.CoreController
import com.mirage.android.core.ICoreCallback
import com.mirage.android.data.model.TrafficStats
import com.mirage.android.data.model.VpnState
import kotlinx.coroutines.*
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

        val intent = Intent(context, CoreService::class.java).apply {
            putExtra("uri", selected.uri)
            putExtra("pool_size", nodeRepo.getPoolSize())
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
        startTelemetry()
    }

    fun stopVpn() {
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
                    // 1. 每 1s: 极轻量基础流量与延迟 (7个浮点 + 1个长整型，0 CPU/GC 开销)
                    val statsArr = CoreController.getStats()
                    if (statsArr != null && statsArr.size >= 7) {
                        _trafficStats.value = TrafficStats.fromArray(statsArr)
                    }
                    val lat = CoreController.latencyMs()
                    _latencyMs.value = lat

                    // 2. 每 2s: 日志与活跃连接拉取 (避免高频 IPC 与解析)
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

                    // 3. 每 3s: 最近请求历史 (300 条 JSON)，仅在内容变化时才反序列化
                    if (tick % 3 == 0L) {
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
                delay(1000)
            }
        }
    }

    private fun stopTelemetry() {
        telemetryJob?.cancel()
        telemetryJob = null
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
