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
import android.net.NetworkRequest
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
import com.mirage.android.data.model.Node
import com.mirage.android.core.NodeStore
import com.mirage.android.core.RuleStore
import com.mirage.android.core.SettingsStore
import com.mirage.android.core.TrafficStatsStore
import com.mirage.android.core.TunConfigStore
import kotlinx.coroutines.*
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

        /**
         * onStartCommand 收到 ACTION_STOP 时，是否接受本次停止指令 (时序与幂等防护)。
         *
         * 若指定了 stopSeq 且严格小于当前已激活的 activeSessionSeq，说明此 ACTION_STOP 来自
         * 上一轮连接、因系统队列排队而在新连接启动后才送达，必须丢弃以防误杀新启动的 VPN。
         * 若 stopSeq == 0L (未带序号，如通知栏/磁贴无差别停止) 或 stopSeq >= activeSessionSeq，予以放行。
         */
        fun shouldAcceptStopCommand(stopSeq: Long, activeSessionSeq: Long): Boolean =
            stopSeq <= 0L || stopSeq >= activeSessionSeq
    }

    /**
     * 故障切换策略的编解码与裁决 (纯逻辑 / 测试解耦)。
     *
     * 下沉方案 P3 把看门狗整体搬进内核后，这个对象就是外壳对故障切换的**全部**
     * 控制面。它必须可单测，原因是失败形态完全无声：Rust 侧 `FailoverPolicy`
     * 每个字段都带 `#[serde(default)]`，拼错的键被静默丢弃而不是报错。
     * 若 `enabled` 写错，内核看门狗永久关闭，而外壳的看门狗已经删了 ——
     * 结果是**无人**做故障切换，日志上却只看到一行 `ok=true`。
     */
    internal object FailoverControl {

        /** Rust 侧 `failover::FailoverPolicy` 的字段名，逐字一致。 */
        const val KEY_MODE = "mode"
        const val KEY_INTERVAL_SEC = "interval_sec"
        const val KEY_BACKOFF_SEC = "backoff_sec"
        const val KEY_RESTART_DELAY_MS = "restart_delay_ms"
        const val KEY_ENABLED = "enabled"

        /** 内核请求外壳重启前的等待，沿用外壳原实现的 3000ms。 */
        const val RESTART_DELAY_MS = 3000

        /**
         * 下发给内核的策略 JSON。
         *
         * `enabled` 直接取「断线自动重连」开关：外壳原看门狗在 `autoReconnect`
         * 为 false 时只记日志不动手，内核跳过整轮与之等价。
         *
         * `backoff_sec` 固定为 0 —— 它是静态基线，真正的退避递增由内核自己按
         * `failover::escalate_backoff` 累加（外壳原来的 failoverBackoffSec）。
         * 在这里填非 0 会与内核的动态退避叠加，两处都变慢。
         */
        fun policyJson(mode: String, intervalSec: Int, autoReconnect: Boolean): String =
            org.json.JSONObject()
                .put(KEY_MODE, mode)
                .put(KEY_INTERVAL_SEC, intervalSec)
                .put(KEY_BACKOFF_SEC, 0)
                .put(KEY_RESTART_DELAY_MS, RESTART_DELAY_MS)
                .put(KEY_ENABLED, autoReconnect)
                .toString()

        /**
         * 是否执行内核送来的故障切换事件。只有 [ServiceState.Running] 放行。
         *
         * 事件在内核 runtime 线程上产生、异步落到外壳，与用户点断开同时发生是常态
         * 而非边角，两类事件各有一种在非 Running 下的具体危害：
         *
         * - 「请求重启」：Stopping 时放行会复活用户刚停掉的 VPN；Stopped 时放行
         *   等于凭一条迟到的事件把服务重新拉起来。
         * - 「已热切换」：下标回流会经 `ICoreCallback` 落盘到 UI 的 `selected_index`。
         *   用户停掉 VPN 之后收到一条在途事件就照样回流，表现为选中的节点被悄悄换了。
         */
        fun shouldHonorEvent(state: ServiceState): Boolean =
            state == ServiceState.Running
    }

    /**
     * 原生调用安全守卫 (纯逻辑 / 测试解耦)。
     *
     * 解决的致命缺陷 (在 SO-02K 真机复现):
     * APK 缺 libmirage_jni.so (或自定义内核损坏) 时，UI 绑定 :core 后调用 isRunning() / getStats() 等，
     * binder 直接调 MirageNative.*，抛 java.lang.UnsatisfiedLinkError (属于 java.lang.Error，非 Exception)，
     * 在 Binder 线程上未捕获导致 :core 进程崩溃，App 反复弹出「屡次停止运行」。
     *
     * 守卫原则:
     * 1. 严格检查 [NativeLoader.isLoaded()]，未加载成功时绝不触碰 [MirageNative] 外部方法，直接返回安全默认值。
     * 2. 即使已加载，仍用 [runCatching] 兜底，防止底层 JNI 链接失效或抛出异常。
     * 3. 绝不在查询/设置等非启动路径隐式触发 [NativeLoader.load] (内核选择必须只发生在受控的启动流程中)。
     */
    internal object NativeGuard {
        /** 是否允许向原生层发起调用: 仅当原生动态库已成功装载时放行。 */
        fun shouldDispatchNative(isLoaded: Boolean): Boolean = isLoaded

        /**
         * 原生调用保护包装:
         * 当原生动态库未加载时安全短路返回 [default]，绝不执行 [block]；已加载时执行并兜底异常。
         */
        inline fun <T> runGuarded(isLoaded: Boolean, default: T, block: () -> T): T {
            if (!shouldDispatchNative(isLoaded)) return default
            return runCatching(block).getOrDefault(default)
        }
    }

    /**
     * 原生库调用保护包装:
     * 检查 [NativeLoader.isLoaded]，未加载时短路返回安全默认值 [default]，绝不触发 [MirageNative] 调用。
     */
    private inline fun <T> native(default: T, block: () -> T): T =
        NativeGuard.runGuarded(NativeLoader.isLoaded(), default, block)

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
    /**
     * 当前生效的服务配置。
     *
     * **写入一律走 [updateConfig]，不要直接改字段。** 这个对象被三类线程碰:
     * AIDL setter 跑在 Binder 线程池, `startLocked` 跑在服务线程,
     * `onKernelSwitched` 跑在 [scope] 的协程上。原先它是普通 `var` 持可变 data class,
     * 字段全是裸 `var`, 既没有 `@Volatile` 也不在 [stateLock] 临界区内 ——
     * JMM 下没有 happens-before 保证, UI 刚下发的节点、路由模式或 DNS 参数
     * 可能在本次连接里根本不生效, 而且不会有任何报错。
     *
     * 现在: 引用 `@Volatile` 保证发布, 写入在 [stateLock] 下整体替换 (copy-on-write)
     * 保证不丢更新, 读取方拿到的永远是某一次写入的完整快照。
     * 多字段连读请先取一次本地快照 (`val cfg = serviceConfig`), 否则仍可能跨写入撕裂。
     */
    @Volatile
    private var serviceConfig = ServiceConfig()

    /** 在 [stateLock] 下整体替换配置。所有字段写入的唯一入口。 */
    private inline fun updateConfig(block: (ServiceConfig) -> ServiceConfig) {
        synchronized(stateLock) { serviceConfig = block(serviceConfig) }
    }

    private val stateLock = Any()
    @Volatile
    private var serviceState = ServiceState.Stopped
    @Volatile
    private var activeSessionSeq: Long = 0L
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
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    /**
     * 第二个网络回调, 按 `NetworkRequest`(INTERNET + NOT_VPN) 注册。
     *
     * **为什么不能只有 `registerDefaultNetworkCallback`**: 它只跟踪「默认网络」这一条的
     * 生命周期。分应用白名单模式下本应用自己也在 VPN 里 (见 startLocked: allowed 与
     * disallowed 互斥, 白名单模式下无法再自排除), 隧道起来之后默认网络就是 VPN,
     * 物理网消失时它的 onLost 根本不会送到这个回调, `currentPhysicalNetwork` 就此冻结。
     *
     * 2026-11-09 真机实测 (SO-02K, 连着本地节点时关掉 Wi-Fi):
     * 系统侧 `dumpsys connectivity` 已是 `Active default network: none`,
     * 而应用日志里只有 `底层物理网络切换: null -> 409`, **从未出现 `409 -> null`**。
     * 后果不止故障切换的闸门读到陈旧值: `setUnderlyingNetworks` 也一直指着死网络。
     *
     * 两个回调分工: 默认网络回调是「系统偏好哪一条」的权威 (Wi-Fi 与蜂窝同时在线时
     * 不能擅自绑到蜂窝, 那会产生流量费); 本回调是「物理网还在不在」的权威。
     */
    private var physicalNetworkCallback: ConnectivityManager.NetworkCallback? = null

    /** 当前仍存活的物理网络。由 [netLock] 保护, 顺序即到达顺序, 末位是最新的一条。 */
    private val physicalNetworks = LinkedHashSet<Network>()
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
        // 网络回调可能在任何一次 start 之前触发, 此时原生库尚未装载:
        // 直接调用会连抛三次 UnsatisfiedLinkError, 虽被 runCatching 兜住但会污染日志。
        if (!NativeLoader.isLoaded()) {
            LogStore.append("[core] 原生库未装载, 跳过本次网络变更的内核转发")
        } else {
            runCatching { MirageNative.setActiveNetwork(n?.networkHandle ?: 0L) }
            runCatching { MirageNative.flushPool() }
            // 下沉方案 P1: 转发给内核。P2 据此区分「节点不可达」与「全网不可达」。
            runCatching { MirageNative.onNetworkChanged(n?.networkHandle ?: 0L, n != null) }
                .onFailure { e ->
                    LogStore.append("[core] 网络变更转发内核异常: ${e::class.java.simpleName}: ${e.message}")
                }
        }
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
     * 必须在 startInternal 开头也调用一次: 内核请求的重启路径直接调 startInternal
     * 而不经过 stopInternal, 若不先取消, 每次重启都会再起一套 log/notif/traffic,
     * 而旧的那套仍在跑。
     */
    private fun cancelPeriodicJobs() {
        logJob?.cancel(); logJob = null
        notifJob?.cancel(); notifJob = null
        trafficJob?.cancel(); trafficJob = null
    }

    private fun cancelAllJobs() {
        cancelPeriodicJobs()
        screenJob?.cancel(); screenJob = null
        networkCallback?.let { cb ->
            runCatching { getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(cb) }
            networkCallback = null
        }
        physicalNetworkCallback?.let { cb ->
            runCatching { getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(cb) }
            physicalNetworkCallback = null
        }
        synchronized(netLock) { physicalNetworks.clear() }
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
            val stopSeq = intent.getLongExtra("stop_sequence", 0L)
            if (!StateMachine.shouldAcceptStopCommand(stopSeq, activeSessionSeq)) {
                log("[core] 忽略过期 ACTION_STOP (stopSeq=$stopSeq < activeSessionSeq=$activeSessionSeq)")
                return START_NOT_STICKY
            }
            stopInternal()
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent == null) {
            // 系统在内存不足/停止后若尝试重启服务，无启动参数时不自启，通知系统终止
            stopSelf(startId)
            return START_NOT_STICKY
        }
        val startSeq = intent.getLongExtra("start_sequence", 0L)
        if (startSeq > 0L) {
            activeSessionSeq = startSeq
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

        // 多字段连读先取一次快照: 逐个读 volatile 字段时, 中途被 Binder 线程写入
        // 就会读到半旧半新的组合。
        val cfg = serviceConfig
        val uri = intent.getStringExtra("uri") ?: cfg.uri
        val poolSize = intent.getIntExtra("pool_size", serviceConfig.poolSize)
        val bypassLan = if (intent.hasExtra("bypass_lan")) intent.getBooleanExtra("bypass_lan", false) else cfg.bypassLan
        val ipv6 = if (intent.hasExtra("enable_ipv6")) intent.getBooleanExtra("enable_ipv6", false) else cfg.ipv6Enabled
        val mtu = if (intent.hasExtra("mtu")) intent.getIntExtra("mtu", 1500) else cfg.mtu
        val blockQuic = if (intent.hasExtra("block_quic")) intent.getBooleanExtra("block_quic", true) else cfg.blockQuic
        val udpMux = if (intent.hasExtra("udp_mux")) intent.getBooleanExtra("udp_mux", true) else cfg.udpMux
        val autoReconnect = if (intent.hasExtra("auto_reconnect")) intent.getBooleanExtra("auto_reconnect", true) else cfg.autoReconnect
        val checkInterval = if (intent.hasExtra("check_interval")) intent.getIntExtra("check_interval", 15) else cfg.checkIntervalSec
        val failoverMode = intent.getStringExtra("failover_mode") ?: cfg.failoverMode
        val directDns = intent.getStringExtra("direct_dns") ?: cfg.directDns
        val remoteDns = intent.getStringExtra("remote_dns") ?: cfg.remoteDns
        val outboundMode = if (intent.hasExtra("outbound_mode")) intent.getIntExtra("outbound_mode", 0) else cfg.outboundMode
        val appFilterJson = intent.getStringExtra("app_filter_json")
        val appFilterConfig = if (!appFilterJson.isNullOrBlank()) com.mirage.android.core.AppFilterStore.fromJson(appFilterJson) else cfg.appFilterConfig
        val nodesJson = intent.getStringExtra("nodes_json")
        val nodes = if (!nodesJson.isNullOrBlank()) NodeStore.parseNodesJson(nodesJson) else cfg.nodes

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

        // 下沉方案 P3: 把切换策略下发内核。外壳已无看门狗, 这一次下发就是
        // 故障切换的全部开关 —— 失败即整条链路失效, 故日志必须留痕。
        pushFailoverPolicy()

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
        // 这是「用户点断开后 VPN 自己回来」的根因守卫 —— 内核的「请求重启」事件
        // 在独立线程上异步落地, 它可能恰好与用户点断开同时发生并在此排队。
        val nativeRunning = isRunningInternal()
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
            //
            // 用 cancelAllJobs 而不是 cancelPeriodicJobs: 后者只停协程, 不注销
            // screenReceiver 与两个 NetworkCallback。而 startLocked 在 MirageNative.start
            // 失败之前就已经把它们注册进系统了 —— UI 进程持 BIND_AUTO_CREATE,
            // 服务组件不会走 onDestroy, 那些回调会永久驻留并继续向已停止的内核转发事件。
            cancelAllJobs()
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

        // 原生库未装载时提前退出: 否则下面整块注入会连抛十余次 UnsatisfiedLinkError。
        if (!NativeLoader.isLoaded()) {
            log("[core] 内核启动失败: 原生动态库未加载")
            runCatching { fd.close() }
            tunFd = null
            notifyState()
            return -1
        }

        // 注入规则、Geo 文件与 DNS 配置 (修复 M5: 自主完成全量注入，杜绝冷启动 AIDL 竞态)
        runCatching { GeoManager.loadGeoFilesToNative(this) }
        runCatching { MirageNative.setRules(RuleStore.toJson(this)) }
        runCatching { MirageNative.setOutboundMode(serviceConfig.outboundMode) }
        runCatching { MirageNative.setDnsServers(serviceConfig.directDns, serviceConfig.remoteDns) }
        runCatching { MirageNative.setBlockQuic(serviceConfig.blockQuic) }
        runCatching { MirageNative.setUdpMux(serviceConfig.udpMux) }
        // 下沉方案 P1: 节点全表必须在这里补发一次。
        // AIDL updateNodes 可能在任何 start 之前到达, 那时原生库尚未装载而被守卫短路,
        // 若不在此补发, 内核会一直拿不到候选节点, 看门狗将永远「无可切换节点」。
        runCatching {
            val nodesJson = NodeStore.getNodesJson(this)
            val ok = MirageNative.setNodeList(nodesJson)
            updateConfig { it.copy(nodes = NodeStore.parseNodesJson(nodesJson)) }
            LogStore.append("[core] 启动前补发节点全表: ok=$ok count=${serviceConfig.nodes.size}")
        }.onFailure { e ->
            LogStore.append("[core] 启动前补发节点全表异常: ${e::class.java.simpleName}: ${e.message}")
        }

        val poolSize = if (poolSizeOverride > 0) {
            poolSizeOverride
        } else if (serviceConfig.poolSize > 0) {
            serviceConfig.poolSize
        } else {
            NodeStore.getPoolSize(this)
        }
        log("[core] 开始启动内核 (uri=${Node.redact(uri)}, poolSize=$poolSize, mtu=$mtu)")
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
                            // 不直接置 null: 可能还有别的物理网在线 (关 Wi-Fi 落到蜂窝)。
                            switchTo(physicalNetworks.lastOrNull { it != network })
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

            // 第二个回调: 只管「物理网还在不在」。理由见 physicalNetworkCallback 的注释 ——
            // 默认网络回调在隧道起来之后可能再也收不到物理网的 onLost。
            val physCb = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    if (!isPhysicalInternet(cm, network, null)) return
                    synchronized(netLock) {
                        physicalNetworks.remove(network)
                        physicalNetworks.add(network)
                        // 只在当前无网时顶上。有网时不抢 —— 选哪一条是默认网络回调的职责,
                        // 这里擅自切换会在 Wi-Fi 与蜂窝同时在线时把流量绑到蜂窝上。
                        if (currentPhysicalNetwork == null) switchTo(network)
                    }
                }

                override fun onCapabilitiesChanged(
                    network: Network,
                    networkCapabilities: NetworkCapabilities,
                ) {
                    val ok = isPhysicalInternet(cm, network, networkCapabilities)
                    synchronized(netLock) {
                        if (ok) {
                            physicalNetworks.remove(network)
                            physicalNetworks.add(network)
                            if (currentPhysicalNetwork == null) switchTo(network)
                        } else {
                            // 失去上网能力等同于消失 (受限 APN、认证门户未过等)。
                            physicalNetworks.remove(network)
                            if (currentPhysicalNetwork == network) {
                                switchTo(physicalNetworks.lastOrNull())
                            }
                        }
                    }
                }

                override fun onLost(network: Network) {
                    synchronized(netLock) {
                        physicalNetworks.remove(network)
                        if (currentPhysicalNetwork == network) {
                            // 退到还活着的那条; 一条都不剩才是真正的全网不可达。
                            switchTo(physicalNetworks.lastOrNull())
                        }
                    }
                }
            }
            physicalNetworkCallback = physCb
            runCatching {
                val req = NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    // 必须显式排除 VPN: 否则我们自己的 tun0 也会匹配进来, 隧道一起
                    // 就把自己当成物理网, 闸门永远判「有网」。
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                    .build()
                cm.registerNetworkCallback(req, physCb)
            }.onFailure {
                physicalNetworkCallback = null
                LogStore.append("[core] registerNetworkCallback(物理网) 异常: ${it.message}")
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
        // 断线检测与节点故障切换已下沉内核 (方案 P3)。外壳在此不再起看门狗:
        // 内核的 spawn_failover_watchdog 在 MirageNative.start 内已随引擎启动,
        // 外壳只经 onFailoverEvent 响应「请求重启」。
        return 0
    }

    /**
     * 把故障切换策略下发内核 (下沉方案 P3)。
     *
     * 启动时下发一次, 之后三个 AIDL setter 每次改动都重推。原生库未装载时直接
     * 跳过: 启动路径随后会再调一次, 而此刻调用只会抛 UnsatisfiedLinkError。
     */
    private fun pushFailoverPolicy() {
        if (!NativeLoader.isLoaded()) {
            LogStore.append("[core] 原生库未装载, 切换策略改由启动时下发")
            return
        }
        val policy = FailoverControl.policyJson(
            mode = serviceConfig.failoverMode,
            intervalSec = serviceConfig.checkIntervalSec,
            autoReconnect = serviceConfig.autoReconnect
        )
        runCatching { MirageNative.setFailoverPolicy(policy) }
            .onSuccess { ok -> LogStore.append("[core] 切换策略下发内核: ok=$ok policy=$policy") }
            .onFailure { e ->
                LogStore.append("[core] 切换策略下发内核异常: ${e::class.java.simpleName}: ${e.message}")
            }
    }

    /**
     * 把内核的故障切换事件转到自己的 scope 上执行 (下沉方案 P3)。
     *
     * **异步是正确性要求, 不是风格选择。** 调用链是: 内核看门狗任务 →
     * `failover::emit_event` → JNI 上行 → 这里, 全程跑在 tokio worker 线程上。
     * 若在该线程上同步走到 `MirageNative.stop()`, Rust 侧会 take 走 RUNTIME 并
     * drop `Arc<Runtime>`; 一旦那是最后一个引用, 就等于在 runtime 自己的 worker
     * 线程里 drop runtime —— tokio 对此直接 panic, 且 panic 发生在 spawn 的任务里,
     * `jni_boundary!` 捕不到, 表现为内核静默失去看门狗。切线程后这条路径不存在。
     */
    private fun dispatchFailoverEvent(kind: Int, nodeUri: String) {
        scope.launch {
            // 状态在转线程之后才读: 排队期间用户完全可能已经点了断开。
            val state = synchronized(stateLock) { serviceState }
            if (!FailoverControl.shouldHonorEvent(state)) {
                LogStore.append("[core] 故障切换事件被忽略 (serviceState=$state)")
                return@launch
            }
            when (kind) {
                MirageNative.FAILOVER_SWITCHED -> onKernelSwitched(nodeUri)
                MirageNative.FAILOVER_RESTART_REQUESTED -> onKernelRestartRequested()
                else -> LogStore.append("[core] 未知故障切换事件 kind=$kind, 忽略")
            }
        }
    }

    /** 内核已自行热切换到新节点: 外壳只需对齐自己的权威副本并把下标回流 UI。 */
    private fun onKernelSwitched(nodeUri: String) {
        if (nodeUri.isBlank()) {
            LogStore.append("[core] 热切换事件缺节点 URI, 跳过")
            return
        }
        // 必须同步: startLocked 在没有 uriOverride 时正是取 serviceConfig.uri 建连。
        // 不更新的话, 下一次重启 (无论来自内核请求还是用户) 都会回到刚被判死的节点。
        updateConfig { it.copy(uri = nodeUri) }
        val nodes = if (serviceConfig.nodes.isNotEmpty()) {
            serviceConfig.nodes
        } else {
            NodeStore.getNodes(this)
        }
        val idx = nodes.indexOfFirst { it.uri == nodeUri }
        if (idx < 0) {
            // 内核清单比外壳的新 (用户刚删节点而重推还没到) 时会落到这里。
            // 不猜下标: 写错的 selected_index 会让 UI 显示另一个节点。
            LogStore.append("[core] 内核切到的节点不在外壳清单内, 跳过下标回流")
            return
        }
        // 单向回流: :core 不写 selected_index, 由 UI 进程经 ICoreCallback 落盘,
        // 避免两个进程并发写同一份 prefs (与旧 doFailover 同一约定)。
        broadcast { it.onNodeChanged(idx, nodeUri) }
    }

    /** 内核要求重建隧道 (它拿不到 TUN fd): 由外壳执行重启。 */
    private fun onKernelRestartRequested() {
        // 内核在 emit 之前已经等过 policy.restart_delay_ms, 这里不再叠加延迟。
        // 刻意不关 tunFd: 重启窗口里撤掉 TUN, 流量会立刻改走物理网卡明文外发。
        LogStore.append("[core] 按内核请求重启隧道 (保持 TUN)")
        runCatching { MirageNative.stop() }
        val rc = startInternal()
        if (rc != 0) {
            LogStore.append("[core] 内核请求的重启失败 rc=$rc")
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
     * 守卫形同虚设, 排队中的重启照样会复活用户刚停掉的 VPN。
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
        updateConfig { it.copy(uri = uri) }
        return if (isRunningInternal()) {
            native(false) { MirageNative.setNode(uri) }
        } else true
    }

    fun setPoolSizeInternal(poolSize: Int): Boolean {
        updateConfig { it.copy(poolSize = poolSize) }
        return if (isRunningInternal()) {
            native(false) { MirageNative.setPoolSize(poolSize) }
        } else true
    }

    fun getPoolSizeInternal(): Int {
        // 守卫的默认值必须是「可用的回退值」, 不能是 0:
        // 0 会被 UI 当成真实池大小显示, 也会被下次启动当成合法 override。
        val fallback = if (serviceConfig.poolSize > 0) serviceConfig.poolSize else NodeStore.getPoolSize(this)
        return if (isRunningInternal()) native(fallback) { MirageNative.getPoolSize() } else fallback
    }

    fun setRulesInternal(json: String): Boolean = native(false) { MirageNative.setRules(json) }
    fun setBlockQuicInternal(block: Boolean): Boolean {
        updateConfig { it.copy(blockQuic = block) }
        return if (isRunningInternal()) native(false) { MirageNative.setBlockQuic(block) } else true
    }
    fun isBlockQuicInternal(): Boolean =
        if (isRunningInternal()) native(serviceConfig.blockQuic) { MirageNative.isBlockQuic() } else serviceConfig.blockQuic
    fun setUdpMuxInternal(enabled: Boolean): Boolean {
        log("[core] 切换 UDP Mux: $enabled")
        updateConfig { it.copy(udpMux = enabled) }
        return if (isRunningInternal()) native(false) { MirageNative.setUdpMux(enabled) } else true
    }
    fun isUdpMuxInternal(): Boolean =
        if (isRunningInternal()) native(serviceConfig.udpMux) { MirageNative.isUdpMux() } else serviceConfig.udpMux
    fun clearDnsCacheInternal(): Boolean = native(false) { MirageNative.clearDnsCache() }
    fun setDnsServersInternal(directDns: String, remoteDns: String): Boolean {
        updateConfig { it.copy(directDns = directDns) }
        updateConfig { it.copy(remoteDns = remoteDns) }
        return if (isRunningInternal()) native(false) { MirageNative.setDnsServers(directDns, remoteDns) } else true
    }
    fun getDirectDnsInternal(): String = serviceConfig.directDns
    fun getRemoteDnsInternal(): String = serviceConfig.remoteDns

    fun isRunningInternal(): Boolean = native(false) { MirageNative.isRunning() }
    fun isHealthyInternal(): Boolean = native(false) { MirageNative.isHealthy() }
    fun latencyMsInternal(): Long = native(-1L) { MirageNative.latencyMs() }
    fun getStatsInternal(): DoubleArray = native(DoubleArray(0)) { MirageNative.getStats() }
    fun getConnectionsJsonInternal(): String = native("[]") { MirageNative.getConnectionsJson() }
    fun recentLogsInternal(): Array<String> {
        val nativeLogs = native(emptyArray()) { MirageNative.recentLogs() }
        return (LogStore.all() + nativeLogs.toList()).takeLast(150).toTypedArray()
    }
    fun testNodeInternal(uri: String, timeoutMs: Int): Long = native(-1L) { MirageNative.testNode(uri, timeoutMs) }

    fun registerCallbackInternal(cb: ICoreCallback?) {
        if (cb == null) return
        // register() 按 binder 身份去重, 重连后重复注册不会累积
        synchronized(callbacks) { callbacks.register(cb) }
        // 注册后立即推一次当前运行状态
        runCatching { cb.onStateChanged(isRunningInternal()) }
    }

    fun unregisterCallbackInternal(cb: ICoreCallback?) {
        if (cb == null) return
        synchronized(callbacks) { callbacks.unregister(cb) }
    }

    private fun notifyState() {
        // 原生库未成功加载时 isRunning() 抛 UnsatisfiedLinkError(Error), 必须兜住:
        // failAndStop 正是在这种情况下调用本函数的。
        val running = isRunningInternal()
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
        override fun stop() = stopInternal()
        override fun setNode(uri: String): Boolean = setNodeInternal(uri)
        override fun setPoolSize(poolSize: Int): Boolean = setPoolSizeInternal(poolSize)
        override fun getPoolSize(): Int = getPoolSizeInternal()
        override fun setRules(json: String): Boolean = setRulesInternal(json)
        override fun getRuleHits(): String = native("[]") { MirageNative.getRuleHits() }
        override fun resetRuleHits(): Boolean = native(false) { MirageNative.resetRuleHits() }
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
        override fun testNode(uri: String, timeoutMs: Int): Long = testNodeInternal(uri, timeoutMs)
        override fun loadGeoFiles(geositePath: String?, geoipPath: String?): String =
            native("{\"status\":\"error\"}") {
                GeoManager.loadGeoFilesToNative(this@CoreService, geositePath, geoipPath)
            }
        override fun getGeoTagsDetail(): String =
            native("{}") { MirageNative.getGeoTagsDetail() }
        override fun getDiagnosticSnapshotJson(): String =
            native("{}") { MirageNative.getDiagnosticSnapshotJson() }
        override fun clearNativeLogs(): Boolean {
            LogStore.clear()
            runCatching { java.io.File(this@CoreService.filesDir, "core.log").delete() }
            return native(false) { MirageNative.clearNativeLogs() }
        }
        override fun closeConnection(id: Long): Boolean =
            native(false) { MirageNative.closeConnection(id) }
        override fun closeAllConnections(): Int =
            native(0) { MirageNative.closeAllConnections() }
        override fun setOutboundMode(mode: Int): Boolean {
            updateConfig { it.copy(outboundMode = mode) }
            return if (isRunningInternal()) native(false) { MirageNative.setOutboundMode(mode) } else true
        }
        override fun getRecentRequestsJson(): String =
            native("[]") { MirageNative.getRecentRequestsJson() }
        // 下沉方案 P3: 这三项原先由外壳看门狗每轮实时读 serviceConfig, 改后用户的
        // 设置必须显式重推, 否则内核一直用启动时那份快照 —— 表现为「改了检查间隔
        // 要重连才生效」「关掉自动重连后仍会自己切节点」。
        override fun setAutoReconnect(enabled: Boolean): Boolean {
            updateConfig { it.copy(autoReconnect = enabled) }
            pushFailoverPolicy()
            return true
        }
        override fun isAutoReconnect(): Boolean = serviceConfig.autoReconnect
        override fun setCheckInterval(interval: Int): Boolean {
            updateConfig { it.copy(checkIntervalSec = interval.coerceAtLeast(5)) }
            pushFailoverPolicy()
            return true
        }
        override fun setFailoverMode(mode: String?): Boolean {
            updateConfig { it.copy(failoverMode = mode ?: "best") }
            pushFailoverPolicy()
            return true
        }
        override fun getFailoverMode(): String = serviceConfig.failoverMode
        override fun updateNodes(nodesJson: String?): Boolean {
            if (!nodesJson.isNullOrBlank()) {
                updateConfig { it.copy(nodes = NodeStore.parseNodesJson(nodesJson)) }
                // 下沉方案 P3: 同步给内核, 内核的看门狗据此选路。形状与
                // parseNodesJson 一致, 无需转换。UI 进程没装原生库, 只能走这条
                // AIDL 路径下发, 不能在 VpnRepository 里直接调 setNodeList。
                if (!NativeLoader.isLoaded()) {
                    LogStore.append("[core] 原生库未装载, 节点全表改由启动前补发 count=${serviceConfig.nodes.size}")
                } else {
                    runCatching { MirageNative.setNodeList(nodesJson) }
                        .onSuccess { ok ->
                            LogStore.append("[core] 节点全表下发内核: ok=$ok count=${serviceConfig.nodes.size}")
                        }
                        .onFailure { e ->
                            // UnsatisfiedLinkError 等链接问题必须显形, 不能被 runCatching 吞掉
                            LogStore.append("[core] 节点全表下发内核异常: ${e::class.java.simpleName}: ${e.message}")
                        }
                }
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
        /**
         * 内核的故障切换事件 (下沉方案 P3: 外壳看门狗已拆除, 这里接管执行)。
         *
         * 由 Rust 的 `failover::emit_event` 经 JavaVM 上行调用, 线程是内核
         * runtime 的 worker —— 实际工作必须交给实例的 scope, 见
         * [dispatchFailoverEvent] 里关于 drop runtime 会 panic 的说明。
         */
        fun onFailoverEvent(kind: Int, nodeUri: String) {
            val kindName = when (kind) {
                MirageNative.FAILOVER_SWITCHED -> "已热切换"
                MirageNative.FAILOVER_RESTART_REQUESTED -> "请求重启"
                else -> "未知($kind)"
            }
            // 节点 URI 含密码, 只记 host:port —— core.log 可被用户导出分享。
            // 不要改回前缀截断: mirage:// 占 9 字符, 截断等于把口令前缀写进日志。
            val redacted = Node.redact(nodeUri)
            val inst = active
            if (inst == null) {
                // 服务已销毁而内核任务尚未退出时会落到这里。丢弃是对的:
                // 没有 CoreService 实例就没有 TUN, 也无从重启。
                LogStore.append("[core] 故障切换事件: $kindName node=$redacted (无活跃实例, 丢弃)")
                return
            }
            LogStore.append("[core] 故障切换事件: $kindName node=$redacted")
            inst.dispatchFailoverEvent(kind, nodeUri)
        }

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
            // 168.0.0.0/6 会覆盖 168-171, 把 169.254.0.0/16 (链路本地 / APIPA) 一并吞进隧道,
            // 与上面注释声明的排除项矛盾。拆成十段, 精确挖掉 169.254/16:
            // 无 DHCP 时的直连设备 (部分打印机、投屏回退) 走的就是这个网段,
            // 被卷进隧道后在「绕过局域网」开启时仍然不通, 而用户以为自己已经绕过了。
            "168.0.0.0" to 8,
            "169.0.0.0" to 9,
            "169.128.0.0" to 10,
            "169.192.0.0" to 11,
            "169.224.0.0" to 12,
            "169.240.0.0" to 13,
            "169.248.0.0" to 14,
            "169.252.0.0" to 15,
            // 169.254.0.0/16 刻意缺席 —— 这一行的"没有"就是修复本身, 不要补上。
            "169.255.0.0" to 16,
            "170.0.0.0" to 7,
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
