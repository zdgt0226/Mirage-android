package com.mirage.android

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.VpnService
import android.os.Build
import android.os.IBinder
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import com.mirage.android.core.GeoManager
import com.mirage.android.core.ICoreCallback
import com.mirage.android.core.ICoreService
import com.mirage.android.core.LogStore
import com.mirage.android.core.MirageNative
import com.mirage.android.core.NativeLoader
import com.mirage.android.core.NodeStore
import com.mirage.android.core.RuleStore
import com.mirage.android.core.SettingsStore
import com.mirage.android.core.TrafficStatsStore
import com.mirage.android.core.TunConfigStore
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.net.InetAddress

/**
 * 独立内核进程 (:core) 的 CoreService。
 *
 * - 继承 VpnService: 在本进程创建 TUN
 * - 实现 ICoreService (AIDL): App 跨进程控制内核
 * - 内部通过 JNI 驱动 Rust 内核 (mirage-core)
 *
 * App (UI) 只做: bindService + 调 ICoreService 接口, 不直接碰 JNI。
 */
class CoreService : VpnService() {

    /**
     * 服务状态机。
     *
     * `MirageNative.isRunning()` 不足以充当状态: 它是原生原子量, 在停止流程中途
     * 会短暂为 false, 恰好让被 stateLock 挡住的 startInternal 误判为「可以启动」,
     * 于是用户点了断开、VPN 却自己回来。这里用显式状态在同一把锁内判定,
     * Stopping 一旦置位, 任何排队中的 startInternal 立即放弃。
     */
    internal enum class ServiceState { Stopped, Starting, Running, Stopping }

    /** [startInternal] 的入口裁决结果。 */
    internal sealed interface StartVerdict {
        /** 继续执行启动流程。 */
        object Proceed : StartVerdict
        /** 已在运行且原生内核确认存活，直接返回 0。 */
        object AlreadyRunning : StartVerdict
        /** 停止流程进行中，放弃本次启动并返回 [RC_REJECTED_WHILE_STOPPING]。 */
        object RejectStopping : StartVerdict
    }

    /**
     * 服务状态机的纯裁决逻辑。
     *
     * 与 IO、Context、原生库全部解耦，因此可以直接被单元测试覆盖 —— 三处守卫
     * （onStartCommand 重入、startInternal 入口、stopInternal 入口）都只调用这里，
     * 测试断言的就是生产路径本身，而不是在测试里重写一遍同样的条件。
     */
    internal object StateMachine {

        /** onStartCommand 是否接受这次启动请求。Starting/Running 时拒绝重入。 */
        fun acceptsStartCommand(state: ServiceState): Boolean =
            state == ServiceState.Stopped || state == ServiceState.Stopping

        /**
         * startInternal 的入口裁决。
         *
         * [nativeRunning] 只在 [ServiceState.Running] 下参与判断：原生原子量在停止
         * 流程中途会短暂为 false，单凭它判断会让排队中的启动请求复活已被用户停止的 VPN。
         */
        fun verdictForStart(state: ServiceState, nativeRunning: Boolean): StartVerdict = when {
            state == ServiceState.Stopping -> StartVerdict.RejectStopping
            state == ServiceState.Running && nativeRunning -> StartVerdict.AlreadyRunning
            else -> StartVerdict.Proceed
        }

        /** stopInternal 是否需要真正执行拆除。已停止或正在停止时为 false。 */
        fun shouldRunStop(state: ServiceState): Boolean =
            state != ServiceState.Stopping && state != ServiceState.Stopped

        /** 启动结束后应进入的状态。 */
        fun stateAfterStart(rc: Int): ServiceState =
            if (rc == 0) ServiceState.Running else ServiceState.Stopped
    }

    data class ServiceConfig(
        var uri: String = "",
        var poolSize: Int = -1,
        var bypassLan: Boolean = false,
        var ipv6Enabled: Boolean = false,
        var mtu: Int = 1500,
        var appFilterConfig: com.mirage.android.data.model.AppFilterConfig? = null,
        var directDns: String = "223.5.5.5",
        var remoteDns: String = "1.1.1.1",
        var blockQuic: Boolean = true,
        var udpMux: Boolean = true,
        var autoReconnect: Boolean = true,
        var checkIntervalSec: Int = 15,
        var failoverMode: String = "best",
        var nodes: List<NodeStore.Node> = emptyList(),
        var outboundMode: Int = 0,
    )
    private var serviceConfig = ServiceConfig()

    private val stateLock = Any()
    @Volatile
    private var serviceState = ServiceState.Stopped
    private var tunFd: ParcelFileDescriptor? = null
    private var scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    /**
     * 用 RemoteCallbackList 而非普通集合: 它按 binder 身份去重, 并在客户端进程
     * 死亡时通过 death recipient 自动摘除。此前用 CopyOnWriteArrayList,
     * UI 进程被杀后死条目永久留存, 之后每条日志都对死 binder 发一次注定失败的事务。
     */
    private val callbacks = android.os.RemoteCallbackList<ICoreCallback>()

    private var logJob: Job? = null
    private var notifJob: Job? = null
    private var trafficJob: Job? = null
    private var watchdogJob: Job? = null
    private var failoverRestartJob: Job? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var screenReceiver: BroadcastReceiver? = null
    private var screenJob: Job? = null
    @Volatile
    var currentPhysicalNetwork: Network? = null
    private var lastRecordedUp = -1L
    private var lastRecordedDown = -1L

    private val netLock = Any()

    /**
     * 原子切换底层物理网络。
     * 严格同步 currentPhysicalNetwork、Rust 侧 ACTIVE_NET_HANDLE、以及 VpnService 底层网络映射。
     */
    private fun switchTo(n: Network?) = synchronized(netLock) {
        if (n == currentPhysicalNetwork) return
        val old = currentPhysicalNetwork
        currentPhysicalNetwork = n
        LogStore.append("[core] 底层物理网络切换: $old -> $n (handle=${n?.networkHandle ?: 0L})")
        runCatching { MirageNative.setActiveNetwork(n?.networkHandle ?: 0L) }
        runCatching { MirageNative.flushPool() }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            runCatching { setUnderlyingNetworks(n?.let { arrayOf(it) }) }
        }
    }

    /**
     * 判定指定网络是否为合法的公网物理数据出口。
     * 严格校验:
     * 1. 排除 VPN 自身虚拟网卡 (!TRANSPORT_VPN && NET_CAPABILITY_NOT_VPN);
     * 2. 具备公网访问能力 (NET_CAPABILITY_INTERNET);
     * 3. 非受限网络 (NET_CAPABILITY_NOT_RESTRICTED, 杜绝电信/移动 IMS、VoLTE、MMS 专网 APN 黑洞).
     */
    private fun isPhysicalInternet(cm: ConnectivityManager?, network: Network, caps: NetworkCapabilities? = null): Boolean {
        val c = caps ?: cm?.getNetworkCapabilities(network) ?: return false
        return !c.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
                c.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN) &&
                c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                c.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)
    }

    private fun flushLogsAndStats() {
        runCatching {
            val logs = (LogStore.all() + MirageNative.recentLogs().toList()).joinToString("\n")
            java.io.File(filesDir, "core.log").writeText(logs.takeLast(30000))
        }
        runCatching {
            val profileFile = java.io.File(filesDir, "traffic_profiles.json").absolutePath
            MirageNative.saveTrafficProfiles(profileFile)
        }
        runCatching {
            val st = MirageNative.getStats()
            if (st.size >= 2) {
                val up = st[0].toLong()
                val down = st[1].toLong()
                if (lastRecordedUp >= 0 && lastRecordedDown >= 0 && up >= lastRecordedUp && down >= lastRecordedDown) {
                    TrafficStatsStore.add(this@CoreService, up - lastRecordedUp, down - lastRecordedDown)
                }
                lastRecordedUp = up
                lastRecordedDown = down
            }
        }
    }

    /**
     * 取消全部周期性后台任务。
     *
     * 必须在 startInternal 开头也调用一次: failover 重启路径直接调 startInternal
     * 而不经过 stopInternal, 若不先取消, 每次重启都会再起一套 log/notif/traffic/watchdog,
     * 而旧的那套仍在跑 —— 多个 watchdog 各自触发 failover, 增长快于线性。
     */
    private fun cancelPeriodicJobs() {
        logJob?.cancel(); logJob = null
        notifJob?.cancel(); notifJob = null
        trafficJob?.cancel(); trafficJob = null
        watchdogJob?.cancel(); watchdogJob = null
    }

    private fun cancelAllJobs() {
        cancelPeriodicJobs()
        failoverRestartJob?.cancel(); failoverRestartJob = null
        screenJob?.cancel(); screenJob = null
        networkCallback?.let { cb ->
            runCatching { getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(cb) }
            networkCallback = null
        }
        screenReceiver?.let {
            runCatching { unregisterReceiver(it) }
            screenReceiver = null
        }
        switchTo(null)
    }

    override fun onCreate() {
        super.onCreate()
        setActive(this)
        com.mirage.android.core.ConnectionOwnerResolver.init(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        setActive(this)
        val action = intent?.action
        if (action == ACTION_STOP) {
            stopInternal()
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent == null) {
            // 系统在内存不足/停止后若尝试重启服务，无启动参数时不自启，通知系统终止
            stopSelf(startId)
            return START_NOT_STICKY
        }
        // 启动中/已运行时不重入 (meow BaseService 的 onStartCommand 守卫同理)
        if (!StateMachine.acceptsStartCommand(serviceState)) {
            log("[core] 已在运行或启动中, 忽略重复启动请求")
            return START_NOT_STICKY
        }
        // startForegroundService 启动: 5 秒内必须 startForeground, 否则系统杀服务/崩溃。
        // 此时隧道尚未建立, 通知文案必须是「正在连接」, 不能一上来就写「已连接」。
        startForegroundCompat(connected = false)

        // 加载选中的内核 (自定义或内置)。返回值必须检查: 加载失败后任何
        // MirageNative.* 调用都会抛 UnsatisfiedLinkError(Error), 直接杀掉 :core。
        if (!NativeLoader.load(this)) {
            log("[core] 原生内核加载失败, 无法启动")
            failAndStop(startId)
            return START_NOT_STICKY
        }

        val uri = intent.getStringExtra("uri") ?: serviceConfig.uri
        val poolSize = intent.getIntExtra("pool_size", serviceConfig.poolSize)
        val bypassLan = if (intent.hasExtra("bypass_lan")) intent.getBooleanExtra("bypass_lan", false) else serviceConfig.bypassLan
        val ipv6 = if (intent.hasExtra("enable_ipv6")) intent.getBooleanExtra("enable_ipv6", false) else serviceConfig.ipv6Enabled
        val mtu = if (intent.hasExtra("mtu")) intent.getIntExtra("mtu", 1500) else serviceConfig.mtu
        val blockQuic = if (intent.hasExtra("block_quic")) intent.getBooleanExtra("block_quic", true) else serviceConfig.blockQuic
        val udpMux = if (intent.hasExtra("udp_mux")) intent.getBooleanExtra("udp_mux", true) else serviceConfig.udpMux
        val autoReconnect = if (intent.hasExtra("auto_reconnect")) intent.getBooleanExtra("auto_reconnect", true) else serviceConfig.autoReconnect
        val checkInterval = if (intent.hasExtra("check_interval")) intent.getIntExtra("check_interval", 15) else serviceConfig.checkIntervalSec
        val failoverMode = intent.getStringExtra("failover_mode") ?: serviceConfig.failoverMode
        val directDns = intent.getStringExtra("direct_dns") ?: serviceConfig.directDns
        val remoteDns = intent.getStringExtra("remote_dns") ?: serviceConfig.remoteDns
        val outboundMode = if (intent.hasExtra("outbound_mode")) intent.getIntExtra("outbound_mode", 0) else serviceConfig.outboundMode
        val appFilterJson = intent.getStringExtra("app_filter_json")
        val appFilterConfig = if (!appFilterJson.isNullOrBlank()) com.mirage.android.core.AppFilterStore.fromJson(appFilterJson) else serviceConfig.appFilterConfig
        val nodesJson = intent.getStringExtra("nodes_json")
        val nodes = if (!nodesJson.isNullOrBlank()) NodeStore.parseNodesJson(nodesJson) else serviceConfig.nodes

        serviceConfig = ServiceConfig(
            uri = uri,
            poolSize = poolSize,
            bypassLan = bypassLan,
            ipv6Enabled = ipv6,
            mtu = mtu,
            appFilterConfig = appFilterConfig,
            directDns = directDns,
            remoteDns = remoteDns,
            blockQuic = blockQuic,
            udpMux = udpMux,
            autoReconnect = autoReconnect,
            checkIntervalSec = checkInterval,
            failoverMode = failoverMode,
            nodes = nodes,
            outboundMode = outboundMode
        )

        // 直接驱动启动 (建 TUN + 内核), 不依赖 UI 后续 AIDL 调用
        val rc = startInternal(uri, poolSize)
        if (rc != 0) {
            log("[core] 启动失败 rc=$rc, 停止服务")
            failAndStop(startId)
            return START_NOT_STICKY
        }
        // 隧道就绪后才把通知改成「已连接」
        updateNotif("流量经 Mirage 隧道转发")
        return START_NOT_STICKY
    }

    /** 启动失败的统一收尾: 撤掉前台通知并终止服务, 不留「已连接」假象。 */
    private fun failAndStop(startId: Int) {
        notifyState()
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
        }
        runCatching { getSystemService(NotificationManager::class.java)?.cancel(1) }
        serviceState = ServiceState.Stopped
        stopSelf(startId)
    }

    // ── ICoreService 实现 ────────────────────────────────────────────────

    /**
     * 向所有存活的客户端回调广播。
     *
     * RemoteCallbackList 的 beginBroadcast/finishBroadcast 不可重入, 而 log() 会从
     * binder 线程、主线程和多个协程同时触达, 故必须整体加锁。
     */
    private fun broadcast(action: (ICoreCallback) -> Unit) {
        synchronized(callbacks) {
            val n = runCatching { callbacks.beginBroadcast() }.getOrDefault(0)
            try {
                for (i in 0 until n) {
                    runCatching { action(callbacks.getBroadcastItem(i)) }
                }
            } finally {
                if (n >= 0) runCatching { callbacks.finishBroadcast() }
            }
        }
    }

    fun log(msg: String) {
        LogStore.append(msg)
        broadcast { it.onLog(msg) }
    }

    /**
     * 启动 TUN 与内核。返回 0 成功, 负数为错误码。
     *
     * 调用方必须处理非 0 返回 —— 失败后服务不该继续以「已连接」的前台通知驻留。
     */
    fun startInternal(uriOverride: String? = null, poolSizeOverride: Int = -1): Int = synchronized(stateLock) {
        // 停止流程进行中 (可能正持锁或刚释放锁): 放弃本次启动。
        // 这是「用户点断开后 VPN 自己回来」的根因守卫 —— failoverRestartJob 的
        // cancel() 是协作式的, 不保证已 join, 它可能已经越过 isActive 检查在此排队。
        val nativeRunning = runCatching { MirageNative.isRunning() }.getOrDefault(false)
        when (StateMachine.verdictForStart(serviceState, nativeRunning)) {
            StartVerdict.RejectStopping -> {
                log("[core] 正在停止中, 忽略本次启动请求")
                return RC_REJECTED_WHILE_STOPPING
            }
            StartVerdict.AlreadyRunning -> {
                setActive(this)
                notifyState()
                return 0
            }
            StartVerdict.Proceed -> Unit
        }
        setActive(this)
        serviceState = ServiceState.Starting
        // failover 重启路径不经过 stopInternal, 必须在此清掉上一轮的周期任务,
        // 否则每次重启都叠加一套, watchdog 会成倍增长。
        cancelPeriodicJobs()

        val rc = try {
            startLocked(uriOverride, poolSizeOverride)
        } catch (t: Throwable) {
            // 必须捕 Throwable 而非 Exception: 原生库加载失败抛的是 UnsatisfiedLinkError,
            // 属于 Error, 此前无人接管, 会直接带走整个 :core 进程, 而用户只看到
            // 通知栏「已连接」闪一下然后一切消失。
            log("[core] 启动异常: ${t.javaClass.simpleName}: ${t.message ?: "无详情"}")
            -7
        }

        serviceState = StateMachine.stateAfterStart(rc)
        if (rc != 0) {
            // 任何失败都必须回到干净状态: 否则服务会带着「已连接」的前台通知
            // 和零隧道继续驻留, 用户以为自己受保护。
            cancelPeriodicJobs()
            runCatching { MirageNative.stop() }
            tunFd?.let { runCatching { it.close() } }
            tunFd = null
        }
        notifyState()
        return rc
    }

    /** startInternal 的实际执行体; 调用方已持有 stateLock 并负责 serviceState 与失败清理。 */
    private fun startLocked(uriOverride: String?, poolSizeOverride: Int): Int {
        val uri = if (!uriOverride.isNullOrBlank()) {
            uriOverride
        } else if (serviceConfig.uri.isNotBlank()) {
            serviceConfig.uri
        } else {
            NodeStore.getSelectedUri(this)
        }
        if (uri.isEmpty()) {
            log("[core] 无选中节点")
            return -1
        }
        // 已授权检查 (包级授权, 与 UI 进程同包)
        if (VpnService.prepare(this) != null) {
            log("[core] VPN 未授权")
            return -2
        }

        val mtu = if (serviceConfig.mtu in 1280..1500) serviceConfig.mtu else TunConfigStore.getMtu(this)

        // 修复 2.1: 重连/failover 时复用已建立的 TUN 描述符，杜绝关闭重建窗口内的明文泄露
        val fd = if (tunFd?.fileDescriptor?.valid() == true) {
            log("[core] 复用现有 TUN 描述符 (fd=${tunFd?.fd})，保持 VPN 接口存活")
            tunFd!!
        } else {
            // 清理并关闭可能遗留的失效 tunFd
            tunFd?.let { runCatching { it.close() } }
            tunFd = null

            val builder = Builder()
            builder.setSession("Mirage")
            builder.addAddress("198.18.0.1", 32)
            val bypassLan = serviceConfig.bypassLan
            if (bypassLan) {
                log("[core] 启用绕过局域网: 路由排除 RFC 1918 / 组播 / 广播私有网段")
                NON_LAN_IPV4_ROUTES.forEach { (net, prefix) ->
                    builder.addRoute(net, prefix)
                }
            } else {
                builder.addRoute("0.0.0.0", 0)
            }
            builder.addRoute("198.18.0.0", 15)
            builder.addDnsServer(InetAddress.getByName("198.19.0.53"))
            // 捕获 IPv6 流量，防止 Android 14/15/16 5G 蜂窝网络 IPv6 绕过 VPN 直连物理网卡被 GFW 阻断
            if (serviceConfig.ipv6Enabled) {
                runCatching {
                    builder.addAddress("fdfe:dcba:9876::1", 128)
                    if (!bypassLan) {
                        builder.addRoute("::", 0)
                    } else {
                        // IPv6 绕过链路本地 fe80::/10 与 ULA fc00::/7
                        builder.addRoute("2000::", 3) // 全球单播公网地址 (2000::/3)
                    }
                }
            }
            builder.setMtu(mtu)

            // 分应用代理 (Per-App Proxy / Split Tunneling)
            //
            // addAllowedApplication 与 addDisallowedApplication 在同一个 Builder 上互斥,
            // 后调用的一方会抛 UnsupportedOperationException。usedAllowList 记录白名单是否已生效,
            // 供下方的自我排除判断该不该调用 addDisallowedApplication。
            var usedAllowList = false
            runCatching {
                val filterConfig = serviceConfig.appFilterConfig ?: com.mirage.android.core.AppFilterStore.getConfig(this)
                val installedPackages = packageManager.getInstalledApplications(0).map { it.packageName }
                when (filterConfig.mode) {
                    com.mirage.android.data.model.AppFilterMode.ALLOW -> {
                        val allowed = com.mirage.android.data.repository.AppFilterManager.computeEffectiveAllowed(filterConfig, installedPackages)
                        if (allowed.isNotEmpty()) {
                            log("[filter] 启用白名单分应用代理: 仅代理 ${allowed.size} 款应用")
                            // computeEffectiveAllowed 已剔除自身包名, 白名单模式下本应用天然在 VPN 之外,
                            // 无需 (也不能) 再调 addDisallowedApplication。
                            usedAllowList = true
                            allowed.forEach { pkg ->
                                runCatching { builder.addAllowedApplication(pkg) }
                            }
                        }
                    }
                    com.mirage.android.data.model.AppFilterMode.DISALLOW -> {
                        val disallowed = com.mirage.android.data.repository.AppFilterManager.computeEffectiveDisallowed(filterConfig, installedPackages)
                        if (disallowed.isNotEmpty()) {
                            log("[filter] 启用黑名单分应用代理: 绕过 ${disallowed.size} 款应用")
                            disallowed.forEach { pkg ->
                                runCatching { builder.addDisallowedApplication(pkg) }
                            }
                        }
                    }
                }
            }.onFailure {
                // getInstalledApplications 跨 Binder 传输在应用极多的设备上可能抛
                // TransactionTooLargeException / DeadObjectException。此前这里静默吞掉,
                // 分应用配置失效且无任何痕迹。
                log("[filter] 分应用代理配置失败, 本次回退为全局代理: ${it.message}")
            }

            // 自身应用强制排除在 VPN 之外 (防止自环)。
            // 必须放在上面的 runCatching 之外: 分应用配置抛异常时, 自我排除仍要生效,
            // 否则本应用自己的非 protect socket (订阅更新 / Geo OTA 等) 会绕回 TUN。
            if (!usedAllowList) {
                runCatching { builder.addDisallowedApplication(packageName) }
                    .onFailure { log("[core] 自身应用排除 VPN 失败: ${it.message}") }
            }

            val established = try { builder.establish() } catch (e: Exception) {
                log("[core] TUN establish 异常: ${e.message}")
                return -3
            } ?: run {
                log("[core] TUN establish 返回 null")
                return -4
            }
            tunFd = established
            established
        }
        val rawFd = try {
            fd.fd
        } catch (e: Exception) {
            log("[core] 获取 TUN 文件描述符异常: ${e.message}")
            runCatching { fd.close() }
            tunFd = null
            return -5
        }
        // TUN 已建立但内核尚未启动, 仍处于连接中
        startForegroundCompat(connected = false)

        // 显式绑定底层物理网络 (解决 Xiaomi HyperOS / Samsung OneUI / 5G 防火墙静默丢包与内核 eBPF 穿透)
        runCatching {
            val cm = getSystemService(ConnectivityManager::class.java)
            val active = cm?.activeNetwork
            val physical = if (active != null && isPhysicalInternet(cm, active)) {
                active
            } else {
                null
            }
            if (physical != null) {
                switchTo(physical)
            }
        }

        // 注入规则、Geo 文件与 DNS 配置 (修复 M5: 自主完成全量注入，杜绝冷启动 AIDL 竞态)
        runCatching { GeoManager.loadGeoFilesToNative(this) }
        runCatching { MirageNative.setRules(RuleStore.toJson(this)) }
        runCatching { MirageNative.setOutboundMode(serviceConfig.outboundMode) }
        runCatching { MirageNative.setDnsServers(serviceConfig.directDns, serviceConfig.remoteDns) }
        runCatching { MirageNative.setBlockQuic(serviceConfig.blockQuic) }
        runCatching { MirageNative.setUdpMux(serviceConfig.udpMux) }

        val poolSize = if (poolSizeOverride > 0) {
            poolSizeOverride
        } else if (serviceConfig.poolSize > 0) {
            serviceConfig.poolSize
        } else {
            NodeStore.getPoolSize(this)
        }
        log("[core] 开始启动内核 (uri=${uri.take(30)}..., poolSize=$poolSize, mtu=$mtu)")
        val rc = MirageNative.start(rawFd, uri, poolSize, mtu)
        if (rc != 0) {
            log("[core] 内核启动失败 rc=$rc")
            runCatching { fd.close() }
            tunFd = null
            notifyState()
            return rc
        }
        log("[core] 内核已启动")

        // 恢复加载历史流量画像字典 (Phase 3 持久化)
        runCatching {
            val profileFile = java.io.File(filesDir, "traffic_profiles.json").absolutePath
            val loadedProfiles = MirageNative.loadTrafficProfiles(profileFile)
            if (loadedProfiles > 0) {
                log("[profile] 成功恢复加载 $loadedProfiles 条历史流量画像")
            }
        }

        notifyState()
        runCatching { sendBroadcast(Intent(ACTION_VPN_STARTED).setPackage(packageName)) }

        // 注册屏幕亮灭广播 (自适应低功耗动态连接池)
        if (screenReceiver == null) {
            val screenFilter = IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_SCREEN_ON)
            }
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    when (intent?.action) {
                        Intent.ACTION_SCREEN_OFF -> {
                            screenJob?.cancel()
                            screenJob = scope.launch {
                                delay(15000)
                                val base = com.mirage.android.core.NodeStore.getPoolSize(this@CoreService)
                                val target = com.mirage.android.data.repository.AppFilterManager.calculateAdaptivePoolSize(
                                    screenOn = false,
                                    hasActiveHighTraffic = false,
                                    basePoolSize = base
                                )
                                LogStore.append("[power] 息屏低功耗模式: 连接池缩容至 $target 条 (基准: $base 条)")
                                runCatching { MirageNative.setPoolSize(target) }
                            }
                        }
                        Intent.ACTION_SCREEN_ON -> {
                            screenJob?.cancel()
                            screenJob = null
                            val base = com.mirage.android.core.NodeStore.getPoolSize(this@CoreService)
                            val target = com.mirage.android.data.repository.AppFilterManager.calculateAdaptivePoolSize(
                                screenOn = true,
                                hasActiveHighTraffic = false,
                                basePoolSize = base
                            )
                            LogStore.append("[power] 屏幕点亮: 连接池恢复至用户设定 $target 条")
                            runCatching { MirageNative.setPoolSize(target) }
                        }
                    }
                }
            }
            screenReceiver = receiver
            runCatching { registerReceiver(receiver, screenFilter) }
        }

        // 注册底层物理网络监听 (Wi-Fi <-> 蜂窝移动网络切换时即时冲刷暖池坏死连接，并绑定底层物理网络)
        val cm = getSystemService(ConnectivityManager::class.java)
        if (cm != null && networkCallback == null) {
            val cb = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    if (isPhysicalInternet(cm, network, null)) {
                        switchTo(network)
                    }
                }

                override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                    if (isPhysicalInternet(cm, network, networkCapabilities)) {
                        switchTo(network)
                    } else {
                        synchronized(netLock) {
                            if (currentPhysicalNetwork == network) {
                                switchTo(null)
                            }
                        }
                    }
                }

                override fun onLost(network: Network) {
                    synchronized(netLock) {
                        if (currentPhysicalNetwork == network) {
                            switchTo(null)
                        }
                    }
                }
            }
            networkCallback = cb
            runCatching {
                cm.registerDefaultNetworkCallback(cb)
            }.onFailure {
                LogStore.append("[core] registerDefaultNetworkCallback 异常: ${it.message}")
            }
        }

        // 文件日志与画像字典: 降低落盘频次至 60 秒 (大幅减少 Flash 闪存 I/O 与 CPU 唤醒，允许 SoC 深度休眠)
        logJob = scope.launch {
            while (isActive) {
                delay(60000)
                runCatching {
                    val logs = (LogStore.all() + MirageNative.recentLogs().toList()).joinToString("\n")
                    java.io.File(filesDir, "core.log").writeText(logs.takeLast(30000))
                }
                runCatching {
                    val profileFile = java.io.File(filesDir, "traffic_profiles.json").absolutePath
                    MirageNative.saveTrafficProfiles(profileFile)
                }
            }
        }
        // 通知栏流量 (节流刷新，无变化不重绘)
        notifJob = scope.launch {
            var last = ""
            while (isActive) {
                runCatching {
                    val st = MirageNative.getStats()
                    if (st.size >= 7) {
                        val t = "↑ ${fmtRate(st[2])}  ↓ ${fmtRate(st[3])}  ·  ${fmtBytes(st[0])}/${fmtBytes(st[1])}"
                        if (t != last) { last = t; updateNotif(t) }
                    }
                }
                delay(4000)
            }
        }
        // 流量统计持久化 (30 秒累加一次)
        TrafficStatsStore.prune(this@CoreService) // 启动时清理一次 30 天前旧数据
        trafficJob = scope.launch {
            while (isActive) {
                delay(30000)
                flushLogsAndStats()
            }
        }
        // 断线自动重连 / 节点 failover watchdog
        watchdogJob = startFailoverWatchdog()
        return 0
    }

    /** 断线检测 + 自动重连 + failover watchdog。 */
    private fun startFailoverWatchdog(): Job = scope.launch {
        var consecutiveFailures = 0
        var failoverBackoffSec = 0L
        while (isActive) {
            val baseInterval = serviceConfig.checkIntervalSec.toLong().coerceAtLeast(5)
            val interval = baseInterval + failoverBackoffSec
            delay(interval * 1000)
            if (currentPhysicalNetwork == null) continue
            if (!MirageNative.isRunning()) continue
            // 修复 M1: Fail-Closed (异常/JNI失败时视为不健康，防止假死与自愈失效)
            val healthy = runCatching { MirageNative.isHealthy() }.getOrDefault(false)
            if (healthy) {
                consecutiveFailures = 0
                failoverBackoffSec = 0L
                continue
            }

            consecutiveFailures++
            LogStore.append("[failover] 检测到连接异常 (第 $consecutiveFailures 次)")
            if (!serviceConfig.autoReconnect) continue

            // 连续 2 次异常才触发 failover (避免瞬时抖动)
            if (consecutiveFailures >= 2) {
                val switched = doFailover()
                consecutiveFailures = 0
                if (!switched) {
                    // 全网不可达/所有节点离线，梯度退避 (最多 30s)，避免高频空转与耗电
                    failoverBackoffSec = (failoverBackoffSec + 5).coerceAtMost(30)
                } else {
                    failoverBackoffSec = 0L
                }
            }
        }
    }

    /** failover: 测活选最优节点 (best) 或换下一个 (next), 然后热切换。返回是否成功选中可用节点。 */
    private suspend fun doFailover(): Boolean {
        val nodes = if (serviceConfig.nodes.isNotEmpty()) serviceConfig.nodes else NodeStore.getNodes(this)
        if (nodes.size <= 1) {
            // 单节点: 完整重启连接 (清 stale 隧道, 保持 TUN 避免明文泄露)
            LogStore.append("[failover] 仅一个节点, 重启连接 (保持 TUN)")
            runCatching { MirageNative.stop() }
            // 修复 2.1: 不关闭 tunFd, 避免重启等待期间物理网络明文泄露
            failoverRestartJob?.cancel()
            failoverRestartJob = scope.launch {
                delay(3000)
                if (isActive && !MirageNative.isRunning()) startInternal()
            }
            return false // 修复 2.1: 单节点重连未能切换可用节点，返回 false 允许 failoverBackoffSec 递增退避
        }
        val mode = serviceConfig.failoverMode
        LogStore.append("[failover] 触发节点切换 (mode=$mode, ${nodes.size} 个节点)")
        val selectedUri = if (serviceConfig.uri.isNotBlank()) serviceConfig.uri else NodeStore.getSelectedUri(this)
        val sorted = if (mode == "best") {
            // 修复 M3: 并发并行测活 (各节点独立 3000ms 超时, 避免 N*5s 阻塞 watchdog 导致监控停摆)。
            // 每个 testNode 是完整协议握手 (引擎+拨号), 用信号量限流防 N 个并发握手同时打服务器
            // (thundering herd) —— 与内核 pool 的 on-demand 信号量同理。
            val sem = Semaphore(4)
            withContext(Dispatchers.IO) {
                nodes.map { n ->
                    async {
                        sem.withPermit {
                            val rtt = runCatching { MirageNative.testNode(n.uri, 3000) }.getOrDefault(-1L)
                            n to rtt
                        }
                    }
                }.awaitAll()
            }.filter { it.second >= 0 }.sortedBy { it.second }
        } else {
            // 顺序: 选当前之后的下一个
            val idx = nodes.indexOfFirst { it.uri == selectedUri }
            listOfNotNull(nodes.getOrNull(idx + 1) ?: nodes.firstOrNull()).map { it to 0L }
        }
        val best = sorted.firstOrNull()
        if (best == null) {
            LogStore.append("[failover] 所有节点测活均无响应 (网络中断/服务器维护)")
            return false
        }
        if (best.first.uri != selectedUri) {
            LogStore.append("[failover] 切换到: ${best.first.displayName} (${best.second}ms)")
            serviceConfig.uri = best.first.uri
            runCatching { MirageNative.setNode(best.first.uri) }
            val newIdx = nodes.indexOfFirst { it.uri == best.first.uri }
            if (newIdx >= 0) {
                // 修复 3.1: 删掉 NodeStore.setSelected(this, newIdx)，单向回流经 ICoreCallback 由 UI 进程落盘持久化
                broadcast { it.onNodeChanged(newIdx, best.first.uri) }
            }
            return true
        } else {
            // 最优还是当前 → 重启连接 (清 stale 隧道, 保持 TUN 避免明文泄露)
            LogStore.append("[failover] 当前节点仍最优, 重启连接 (保持 TUN)")
            runCatching { MirageNative.stop() }
            // 修复 2.1: 不关闭 tunFd
            failoverRestartJob?.cancel()
            failoverRestartJob = scope.launch {
                delay(3000)
                if (isActive && !MirageNative.isRunning()) startInternal()
            }
            return false // 修复 2.1: 未能切换到不同可用节点，返回 false 允许退避
        }
    }

    /**
     * 停止引擎并释放 TUN。
     *
     * 锁的划分是这里的关键, 不是实现细节。
     *
     * 早前版本把「置 Stopping → 拆除 → 置 Stopped」整段放在一个 synchronized 块里,
     * 期望排队中的 startInternal 拿到锁时看到 Stopping 而放弃。那是无效的:
     * 临界区内没有任何挂起点, 按 JMM 的 monitor happens-before, 阻塞在同一把锁上的
     * 线程重新获得锁时观测到的必然已经是 Stopped —— Stopping 对外永不可见,
     * 守卫形同虚设, failoverRestartJob 排队的重启照样会复活用户刚停掉的 VPN。
     *
     * 现在 Stopping 在一把短锁里提交并立即释放, 拆除在锁外进行。这样排队者能真正
     * 观测到 Stopping 并在守卫处退出。锁外拆除是安全的: 此刻任何 startInternal
     * 都会被 Stopping 挡住, 不存在与 startLocked 并发的可能。
     */
    fun stopInternal() {
        synchronized(stateLock) {
            if (!StateMachine.shouldRunStop(serviceState)) {
                return
            }
            serviceState = ServiceState.Stopping
        }
        log("[core] stop()")
        clearActive(this)
        cancelAllJobs()
        flushLogsAndStats()
        runCatching { MirageNative.clearDnsCache() }
        runCatching { MirageNative.stop() }
        tunFd?.let { runCatching { it.close() } }
        tunFd = null
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
        }
        runCatching { getSystemService(NotificationManager::class.java)?.cancel(1) }
        synchronized(stateLock) { serviceState = ServiceState.Stopped }
        notifyState()
        runCatching { sendBroadcast(Intent(ACTION_VPN_STOPPED).setPackage(packageName)) }
        // 服务是用 startForegroundService 起的, 属于 started service —— 不调 stopSelf
        // 就会一直驻留, :core 进程连同已加载的原生库与整个堆永不释放。
        stopSelf()
    }

    fun setNodeInternal(uri: String): Boolean {
        serviceConfig.uri = uri
        return if (MirageNative.isRunning()) {
            runCatching { MirageNative.setNode(uri) }.getOrDefault(false)
        } else true
    }

    fun setPoolSizeInternal(poolSize: Int): Boolean {
        serviceConfig.poolSize = poolSize
        return if (MirageNative.isRunning()) {
            runCatching { MirageNative.setPoolSize(poolSize) }.getOrDefault(false)
        } else true
    }

    fun getPoolSizeInternal(): Int =
        if (MirageNative.isRunning()) MirageNative.getPoolSize() else if (serviceConfig.poolSize > 0) serviceConfig.poolSize else NodeStore.getPoolSize(this)

    fun setRulesInternal(json: String): Boolean = MirageNative.setRules(json)
    fun setBlockQuicInternal(block: Boolean): Boolean {
        serviceConfig.blockQuic = block
        return MirageNative.setBlockQuic(block)
    }
    fun isBlockQuicInternal(): Boolean =
        if (MirageNative.isRunning()) MirageNative.isBlockQuic() else serviceConfig.blockQuic
    fun setUdpMuxInternal(enabled: Boolean): Boolean {
        log("[core] 切换 UDP Mux: $enabled")
        serviceConfig.udpMux = enabled
        return MirageNative.setUdpMux(enabled)
    }
    fun isUdpMuxInternal(): Boolean =
        if (MirageNative.isRunning()) MirageNative.isUdpMux() else serviceConfig.udpMux
    fun clearDnsCacheInternal(): Boolean = MirageNative.clearDnsCache()
    fun setDnsServersInternal(directDns: String, remoteDns: String): Boolean {
        serviceConfig.directDns = directDns
        serviceConfig.remoteDns = remoteDns
        return MirageNative.setDnsServers(directDns, remoteDns)
    }
    fun getDirectDnsInternal(): String = serviceConfig.directDns
    fun getRemoteDnsInternal(): String = serviceConfig.remoteDns

    fun isRunningInternal(): Boolean = MirageNative.isRunning()
    fun isHealthyInternal(): Boolean = MirageNative.isHealthy()
    fun latencyMsInternal(): Long = MirageNative.latencyMs()
    fun getStatsInternal(): DoubleArray = MirageNative.getStats()
    fun getConnectionsJsonInternal(): String = MirageNative.getConnectionsJson()
    fun recentLogsInternal(): Array<String> =
        (LogStore.all() + MirageNative.recentLogs().toList()).takeLast(150).toTypedArray()
    fun getBuiltinDomainsInternal(): Array<String> = MirageNative.getBuiltinDomains()
    fun getBuiltinIpCountInternal(): Long = MirageNative.getBuiltinIpCount()
    fun testNodeInternal(uri: String, timeoutMs: Int): Long = MirageNative.testNode(uri, timeoutMs)

    fun registerCallbackInternal(cb: ICoreCallback?) {
        if (cb == null) return
        // register() 按 binder 身份去重, 重连后重复注册不会累积
        synchronized(callbacks) { callbacks.register(cb) }
        // 注册后立即推一次当前运行状态
        runCatching { cb.onStateChanged(MirageNative.isRunning()) }
    }

    fun unregisterCallbackInternal(cb: ICoreCallback?) {
        if (cb == null) return
        synchronized(callbacks) { callbacks.unregister(cb) }
    }

    private fun notifyState() {
        // 原生库未成功加载时 isRunning() 抛 UnsatisfiedLinkError(Error), 必须兜住:
        // failAndStop 正是在这种情况下调用本函数的。
        val running = runCatching { MirageNative.isRunning() }.getOrDefault(false)
        broadcast { it.onStateChanged(running) }
    }

    // ── 前台通知 ─────────────────────────────────────────────────────────

    private fun startForegroundCompat(connected: Boolean = true) {
        val channel = NotificationChannel("mirage_status", "Mirage VPN 运行状态", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "Mirage VPN 运行状态、流量监控与快捷断开"
            setShowBadge(false)
            setSound(null, null)
            enableVibration(false)
            enableLights(false)
        }
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)

        val pi = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stopIntent = Intent(this, com.mirage.android.receiver.CoreActionReceiver::class.java).setAction(ACTION_STOP)
        val stopPi = PendingIntent.getBroadcast(
            this,
            1001,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notif: Notification = NotificationCompat.Builder(this, "mirage_status")
            .setContentTitle(if (connected) "Mirage 已连接" else "Mirage 正在连接…")
            .setContentText(if (connected) "流量经 Mirage 隧道转发" else "正在建立隧道")
            .setSmallIcon(R.drawable.ic_notification_mirage)
            .setColor(0xFF2481CC.toInt())
            .setContentIntent(pi)
            .addAction(R.drawable.ic_notification_mirage, "断开连接", stopPi)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val fgsType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            } else {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            }
            try {
                startForeground(1, notif, fgsType)
            } catch (e: Throwable) {
                try {
                    startForeground(1, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
                } catch (e2: Throwable) {
                    try {
                        startForeground(1, notif)
                    } catch (e3: Throwable) {
                        log("[core] startForeground 兜底失败: ${e3.message}")
                    }
                }
            }
        } else {
            try {
                startForeground(1, notif)
            } catch (e: Throwable) {
                log("[core] startForeground 失败: ${e.message}")
            }
        }
    }

    private fun updateNotif(text: String) {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        val pi = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stopIntent = Intent(this, com.mirage.android.receiver.CoreActionReceiver::class.java).setAction(ACTION_STOP)
        val stopPi = PendingIntent.getBroadcast(
            this,
            1001,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notif = NotificationCompat.Builder(this, "mirage_status")
            .setContentTitle("Mirage 已连接")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_notification_mirage)
            .setColor(0xFF2481CC.toInt())
            .setContentIntent(pi)
            .addAction(R.drawable.ic_notification_mirage, "断开连接", stopPi)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()
        nm.notify(1, notif)
    }

    private fun fmtRate(bps: Double): String {
        val b = bps.coerceAtLeast(0.0)
        return when {
            b >= 1 shl 20 -> "%.2fMB/s".format(b / (1 shl 20))
            b >= 1 shl 10 -> "%.1fKB/s".format(b / (1 shl 10))
            else -> "%.0fB/s".format(b)
        }
    }

    private fun fmtBytes(b: Double): String = when {
        b >= 1 shl 30 -> "%.1fG".format(b / (1 shl 30))
        b >= 1 shl 20 -> "%.1fM".format(b / (1 shl 20))
        b >= 1 shl 10 -> "%.1fK".format(b / (1 shl 10))
        else -> "%.0fB".format(b)
    }

    fun setLogLevelInternal(level: String): Boolean {
        log("[core] 切换内核日志级别: $level")
        return MirageNative.setLogLevel(level)
    }

    override fun onRevoke() {
        log("[core] 系统任务栏/设置断开 VPN 连接 (onRevoke)")
        try {
            stopInternal()
        } finally {
            stopSelf()
            super.onRevoke()
        }
    }

    override fun onDestroy() {
        clearActive(this)
        log("[core] onDestroy()")
        // 与 stopInternal 同样的锁划分: Stopping 必须在短锁内提交并释放,
        // 否则排队中的 startInternal 永远观测不到它。
        synchronized(stateLock) { serviceState = ServiceState.Stopping }
        cancelAllJobs()
        flushLogsAndStats()
        runCatching { MirageNative.stop() }
        runCatching { tunFd?.close() }
        tunFd = null
        synchronized(stateLock) { serviceState = ServiceState.Stopped }
        // 解除所有 binder death recipient, 否则注册表随服务对象一起泄漏
        runCatching { synchronized(callbacks) { callbacks.kill() } }
        scope.cancel()
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
        }
        runCatching { getSystemService(NotificationManager::class.java)?.cancel(1) }
        super.onDestroy()
    }

    /** AIDL binder: App 跨进程控制的入口。 */
    private val binder = object : ICoreService.Stub() {
        override fun start(): Int = startInternal()
        override fun stop() = stopInternal()
        override fun setNode(uri: String): Boolean = setNodeInternal(uri)
        override fun setPoolSize(poolSize: Int): Boolean = setPoolSizeInternal(poolSize)
        override fun getPoolSize(): Int = getPoolSizeInternal()
        override fun setRules(json: String): Boolean = setRulesInternal(json)
        override fun getRuleHits(): String = MirageNative.getRuleHits()
        override fun resetRuleHits(): Boolean = MirageNative.resetRuleHits()
        override fun setLogLevel(level: String?): Boolean =
            level?.let { setLogLevelInternal(it) } ?: false
        override fun setBlockQuic(block: Boolean): Boolean = setBlockQuicInternal(block)
        override fun isBlockQuic(): Boolean = isBlockQuicInternal()
        override fun setUdpMux(enabled: Boolean): Boolean = setUdpMuxInternal(enabled)
        override fun isUdpMux(): Boolean = isUdpMuxInternal()
        override fun clearDnsCache(): Boolean = clearDnsCacheInternal()
        override fun setDnsServers(directDns: String?, remoteDns: String?): Boolean =
            setDnsServersInternal(directDns ?: "223.5.5.5", remoteDns ?: "1.1.1.1")
        override fun getDirectDns(): String = getDirectDnsInternal()
        override fun getRemoteDns(): String = getRemoteDnsInternal()
        override fun isRunning(): Boolean = isRunningInternal()
        override fun isHealthy(): Boolean = isHealthyInternal()
        override fun latencyMs(): Long = latencyMsInternal()
        override fun getStats(): DoubleArray = getStatsInternal()
        override fun getConnectionsJson(): String = getConnectionsJsonInternal()
        override fun recentLogs(): Array<String> = recentLogsInternal()
        override fun getBuiltinDomains(): Array<String> = getBuiltinDomainsInternal()
        override fun getBuiltinIpCount(): Long = getBuiltinIpCountInternal()
        override fun testNode(uri: String, timeoutMs: Int): Long = testNodeInternal(uri, timeoutMs)
        override fun loadGeoFiles(geositePath: String?, geoipPath: String?): String =
            GeoManager.loadGeoFilesToNative(this@CoreService, geositePath, geoipPath)
        override fun getGeoTags(): String =
            runCatching { MirageNative.getGeoTags() }.getOrDefault("{}")
        override fun getGeoTagsDetail(): String =
            runCatching { MirageNative.getGeoTagsDetail() }.getOrDefault("{}")
        override fun getDiagnosticSnapshotJson(): String =
            runCatching { MirageNative.getDiagnosticSnapshotJson() }.getOrDefault("{}")
        override fun clearNativeLogs(): Boolean {
            LogStore.clear()
            runCatching { java.io.File(this@CoreService.filesDir, "core.log").delete() }
            return runCatching { MirageNative.clearNativeLogs() }.getOrDefault(false)
        }
        override fun getLogs(): String =
            runCatching { MirageNative.getLogs() }.getOrDefault("")
        override fun closeConnection(id: Long): Boolean =
            runCatching { MirageNative.closeConnection(id) }.getOrDefault(false)
        override fun closeAllConnections(): Int =
            runCatching { MirageNative.closeAllConnections() }.getOrDefault(0)
        override fun setOutboundMode(mode: Int): Boolean {
            serviceConfig.outboundMode = mode
            return runCatching { MirageNative.setOutboundMode(mode) }.getOrDefault(false)
        }
        override fun getOutboundMode(): Int {
            return if (MirageNative.isRunning()) {
                MirageNative.getOutboundMode()
            } else {
                serviceConfig.outboundMode
            }
        }
        override fun getRecentRequestsJson(): String =
            runCatching { MirageNative.getRecentRequestsJson() }.getOrDefault("[]")
        override fun setAutoReconnect(enabled: Boolean): Boolean {
            serviceConfig.autoReconnect = enabled
            return true
        }
        override fun isAutoReconnect(): Boolean = serviceConfig.autoReconnect
        override fun setCheckInterval(interval: Int): Boolean {
            serviceConfig.checkIntervalSec = interval.coerceAtLeast(5)
            return true
        }
        override fun getCheckInterval(): Int = serviceConfig.checkIntervalSec
        override fun setFailoverMode(mode: String?): Boolean {
            serviceConfig.failoverMode = mode ?: "best"
            return true
        }
        override fun getFailoverMode(): String = serviceConfig.failoverMode
        override fun updateNodes(nodesJson: String?): Boolean {
            if (!nodesJson.isNullOrBlank()) {
                serviceConfig.nodes = NodeStore.parseNodesJson(nodesJson)
            }
            return true
        }
        override fun registerCallback(cb: ICoreCallback?) = registerCallbackInternal(cb)
        override fun unregisterCallback(cb: ICoreCallback?) = unregisterCallbackInternal(cb)
    }

    override fun onBind(intent: Intent?): IBinder? {
        if (intent != null && SERVICE_INTERFACE == intent.action) {
            return super.onBind(intent)
        }
        return binder
    }

    companion object {
        const val ACTION_STOP = "com.mirage.android.STOP"
        const val ACTION_VPN_STOPPED = "com.mirage.android.VPN_STOPPED"
        const val ACTION_VPN_STARTED = "com.mirage.android.VPN_STARTED"

        /** startInternal: 停止流程进行中，本次启动请求被拒绝。 */
        const val RC_REJECTED_WHILE_STOPPING = -6

        /** 当前活跃实例 (Rust protect 回调用: VpnService.protect 防隧道环路)。 */
        @Volatile
        private var active: CoreService? = null

        @JvmStatic
        fun protectFd(fd: Int) {
            val inst = active
            if (inst == null) {
                LogStore.append("[core] protect 失败: active 未设置!")
                return
            }
            // 传统 protect (SO_MARK 策略路由，物理网卡绑定已下沉至 Rust NDK android_setsocknetwork)
            val ok = runCatching { inst.protect(fd) }.getOrDefault(false)
            if (!ok) {
                LogStore.append("[core] protect(fd=$fd) 失败")
            }
        }

        @JvmStatic
        fun setActive(s: CoreService) { active = s }

        @JvmStatic
        fun getActive(): CoreService? = active

        /**
         * 清除活跃实例引用。
         *
         * 必须传入调用方自身并做同一性比较: Android 异步销毁旧 Service 对象,
         * 若用户快速「停止 → 再连接」, 新实例可能已经 setActive(this) 完成建连,
         * 此时旧实例的 onDestroy 才跑到这里。无条件置空会把活跃实例抹掉,
         * 导致 protectFd 找不到实例 → 隧道 socket 不受保护 → 被路由回 TUN 自环,
         * 表现为「显示已连接但零吞吐」。
         */
        @JvmStatic
        fun clearActive(s: CoreService) {
            if (active === s) active = null
        }

        /**
         * 绕过局域网 (Bypass LAN) 的非私有 IPv4 网段分解列表。
         * 精确排除:
         * - 0.0.0.0/8 (本网络)
         * - 10.0.0.0/8 (RFC 1918 私有 A 类)
         * - 100.64.0.0/10 (RFC 6598 运营商级 NAT)
         * - 127.0.0.0/8 (环回地址)
         * - 169.254.0.0/16 (链路本地 / APIPA)
         * - 172.16.0.0/12 (RFC 1918 私有 B 类)
         * - 192.168.0.0/16 (RFC 1918 私有 C 类)
         * - 224.0.0.0/4 (组播: mDNS 224.0.0.251, SSDP 239.255.255.250 等)
         * - 240.0.0.0/4 (保留 / 255.255.255.255 广播)
         */
        val NON_LAN_IPV4_ROUTES = listOf(
            "1.0.0.0" to 8,
            "2.0.0.0" to 7,
            "4.0.0.0" to 6,
            "8.0.0.0" to 7,
            "11.0.0.0" to 8,
            "12.0.0.0" to 6,
            "16.0.0.0" to 4,
            "32.0.0.0" to 3,
            "64.0.0.0" to 3,
            "72.0.0.0" to 5,
            "76.0.0.0" to 6,
            "78.0.0.0" to 7,
            "80.0.0.0" to 4,
            "96.0.0.0" to 6,
            "100.0.0.0" to 10,
            "100.128.0.0" to 9,
            "101.0.0.0" to 8,
            "102.0.0.0" to 7,
            "104.0.0.0" to 5,
            "112.0.0.0" to 5,
            "120.0.0.0" to 6,
            "124.0.0.0" to 7,
            "126.0.0.0" to 8,
            "128.0.0.0" to 3,
            "160.0.0.0" to 5,
            "168.0.0.0" to 6,
            "172.0.0.0" to 12,
            "172.32.0.0" to 11,
            "172.64.0.0" to 10,
            "172.128.0.0" to 9,
            "173.0.0.0" to 8,
            "174.0.0.0" to 7,
            "176.0.0.0" to 4,
            "192.0.0.0" to 9,
            "192.128.0.0" to 11,
            "192.160.0.0" to 13,
            "192.169.0.0" to 16,
            "192.170.0.0" to 15,
            "192.172.0.0" to 14,
            "192.176.0.0" to 12,
            "192.188.0.0" to 14,
            "192.192.0.0" to 10,
            "193.0.0.0" to 8,
            "194.0.0.0" to 7,
            "196.0.0.0" to 6,
            "200.0.0.0" to 5,
            "208.0.0.0" to 4,
        )
    }
}
