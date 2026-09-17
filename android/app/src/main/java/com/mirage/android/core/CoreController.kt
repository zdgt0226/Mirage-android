package com.mirage.android.core

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.concurrent.CopyOnWriteArraySet

/**
 * CoreController: App (UI) 侧的控制层客户端。
 * 通过 AIDL 绑定 :core 进程的 CoreService, 所有内核操作都走这里。
 */
object CoreController {

    private var service: ICoreService? = null
    private var bound = false
    private var ctx: Context? = null
    private val callbacks = CopyOnWriteArraySet<ICoreCallback>()
    private var currentBinder: IBinder? = null

    /**
     * 绑定就绪后要补跑的动作。
     *
     * `:core` 是独立进程, `startForegroundService` 之后它还要冷启动、加载数 MB 原生库,
     * 期间 binder 尚不可用。对这种情况轮询重试是错的 —— 预算给小了必然失败
     * (真机实测: 推送在 :core 起来前 0.63 秒就耗尽了 10×300ms), 给大了又平白拖延。
     * 正确做法是挂在 onServiceConnected 上, 由绑定事件驱动。
     *
     * 同一 key 的动作后者覆盖前者: 重复入队只保留最新意图 (如最新的节点快照)。
     */
    private val pendingOnConnect = java.util.concurrent.ConcurrentHashMap<String, () -> Unit>()

    private val deathRecipient: IBinder.DeathRecipient = object : IBinder.DeathRecipient {
        override fun binderDied() {
            currentBinder?.unlinkToDeath(this, 0)
            currentBinder = null
            service = null
            bound = false
            running.value = false
            callbacks.forEach { cb -> runCatching { cb.onStateChanged(false) } }
        }
    }

    /** 内核运行状态 (UI 轮询/回调更新)。 */
    val running = MutableStateFlow(false)

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val s = ICoreService.Stub.asInterface(binder)
            service = s
            bound = true
            currentBinder = binder
            runCatching { binder?.linkToDeath(deathRecipient, 0) }

            // 重新注册所有待生效回调
            callbacks.forEach { cb ->
                runCatching { s.registerCallback(cb) }
            }
            // 立即同步一次状态
            runCatching {
                val isRun = s.isRunning
                running.value = isRun
                callbacks.forEach { cb -> runCatching { cb.onStateChanged(isRun) } }
            }

            android.util.Log.d("CoreController", "onServiceConnected")
            // 补跑绑定期间入队的动作
            if (pendingOnConnect.isNotEmpty()) {
                val actions = pendingOnConnect.entries.toList()
                pendingOnConnect.clear()
                actions.forEach { (key, action) ->
                    runCatching { action() }
                        .onFailure { android.util.Log.w("CoreController", "待执行动作 $key 失败: ${it.message}") }
                }
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            runCatching { currentBinder?.unlinkToDeath(deathRecipient, 0) }
            currentBinder = null
            service = null
            bound = false
            running.value = false
            callbacks.forEach { cb -> runCatching { cb.onStateChanged(false) } }
        }
    }

    /**
     * 是否已发起过 bindService (与 [bound] 不同: 后者表示 onServiceConnected 已回调)。
     *
     * unbindService 对未绑定的 connection 会抛 IllegalArgumentException, 因此必须
     * 单独记录「已请求绑定」这个事实, 不能复用 bound。
     */
    private var bindRequested = false

    /**
     * 绑定 :core 进程服务。
     *
     * 绑定生命周期跟随 **Activity 可见性**, 不再跟随进程 —— 见 [unbind] 的说明。
     */
    fun bind(context: Context) {
        if (bindRequested) return
        ctx = context.applicationContext
        val intent = Intent(context, Class.forName("com.mirage.android.CoreService"))
        val ok = runCatching {
            context.applicationContext.bindService(intent, conn, Context.BIND_AUTO_CREATE)
        }.getOrDefault(false)
        bindRequested = ok
        android.util.Log.d("CoreController", "bind() -> requested=$ok")
    }

    /**
     * 解绑 :core。
     *
     * 关键点: 这**不会**停掉正在运行的 VPN。CoreService 是用 startForegroundService
     * 起的 started service, 只有 stopSelf/stopService 能终止它; 解绑只撤掉
     * BIND_AUTO_CREATE 这条「保活」引用。
     *
     * 因此:
     * - VPN 运行中 → 解绑后服务照常运行, 隧道不受影响
     * - VPN 已停止 → 解绑后没有任何引用, :core 进程随之退出, 其 SharedPreferences
     *   进程内缓存一并释放 (这正是跨进程配置陈旧问题的根因)
     */
    fun unbind(context: Context) {
        if (!bindRequested) return
        runCatching { context.applicationContext.unbindService(conn) }
            .onFailure { android.util.Log.w("CoreController", "unbindService 失败: ${it.message}") }
        bindRequested = false
        android.util.Log.d("CoreController", "unbind() 完成")
        runCatching { currentBinder?.unlinkToDeath(deathRecipient, 0) }
        currentBinder = null
        service = null
        bound = false
    }

    fun isBound(): Boolean = bound && service != null

    /**
     * 绑定就绪时执行 [action]; 若已就绪则立即执行。
     *
     * [key] 相同的重复调用只保留最后一次 —— 避免连点连接时堆积多个陈旧动作。
     */
    fun runWhenConnected(key: String, action: () -> Unit) {
        if (isBound()) {
            runCatching { action() }
                .onFailure { android.util.Log.w("CoreController", "动作 $key 失败: ${it.message}") }
            return
        }
        pendingOnConnect[key] = action
    }

    /** 撤销尚未执行的待办动作 (如用户已停止, 推送不再有意义)。 */
    fun cancelPending(key: String) {
        pendingOnConnect.remove(key)
    }

    private inline fun <T> call(block: (ICoreService) -> T): T? {
        val s = service ?: return null
        return try { block(s) } catch (e: Exception) { null }
    }

    // ── 控制 ──
    fun start(): Int = call { it.start() } ?: -100
    fun stop() { call { it.stop() } }
    fun setNode(uri: String): Boolean = call { it.setNode(uri) } ?: false
    fun setPoolSize(poolSize: Int): Boolean = call { it.setPoolSize(poolSize) } ?: false
    fun getPoolSize(): Int = call { it.poolSize } ?: 16
    fun setRules(json: String): Boolean = call { it.setRules(json) } ?: false
    fun getRuleHits(): String = call { it.ruleHits } ?: "[]"
    fun resetRuleHits(): Boolean = call { it.resetRuleHits() } ?: false
    fun setLogLevel(level: String): Boolean = call { it.setLogLevel(level) } ?: false
    fun setBlockQuic(block: Boolean): Boolean = call { it.setBlockQuic(block) } ?: false
    fun isBlockQuic(): Boolean = call { it.isBlockQuic } ?: true
    fun setUdpMux(enabled: Boolean): Boolean = call { it.setUdpMux(enabled) } ?: false
    fun isUdpMux(): Boolean = call { it.isUdpMux } ?: true
    fun clearDnsCache(): Boolean = call { it.clearDnsCache() } ?: false
    fun setDnsServers(directDns: String, remoteDns: String): Boolean =
        call { it.setDnsServers(directDns, remoteDns) } ?: false
    fun getDirectDns(): String = call { it.directDns } ?: "223.5.5.5"
    fun getRemoteDns(): String = call { it.remoteDns } ?: "1.1.1.1"

    // ── 状态 ──
    fun isRunning(): Boolean = call { it.isRunning() } ?: false
    fun isHealthy(): Boolean = call { it.isHealthy() } ?: false
    fun latencyMs(): Long = call { it.latencyMs() } ?: -1
    fun getStats(): DoubleArray? = call { it.stats }
    fun getConnectionsJson(): String = call { it.connectionsJson } ?: "[]"
    fun recentLogs(): Array<String> = call { it.recentLogs() } ?: emptyArray()
    fun getBuiltinDomains(): Array<String> = call { it.builtinDomains } ?: emptyArray()
    fun getBuiltinIpCount(): Long = call { it.builtinIpCount } ?: 0
    fun testNode(uri: String, timeoutMs: Int): Long = call { it.testNode(uri, timeoutMs) } ?: -1
    fun loadGeoFiles(geositePath: String = "", geoipPath: String = ""): String =
        call { it.loadGeoFiles(geositePath, geoipPath) } ?: runCatching {
            MirageNative.loadGeoFiles(geositePath, geoipPath)
        }.getOrDefault("{\"status\":\"error\"}")
    fun getGeoTags(): String =
        call { it.geoTags } ?: runCatching {
            MirageNative.getGeoTags()
        }.getOrDefault("{}")
    fun getGeoTagsDetail(): String =
        call { it.geoTagsDetail } ?: runCatching {
            MirageNative.getGeoTagsDetail()
        }.getOrDefault("{}")
    fun getDiagnosticSnapshotJson(): String =
        call { it.diagnosticSnapshotJson } ?: "{}"
    fun clearNativeLogs(): Boolean =
        call { it.clearNativeLogs() } ?: true
    fun getLogs(): String =
        call { it.logs } ?: ""
    fun closeConnection(id: Long): Boolean {
        if (CommandBusClient.isConnected.value) {
            CommandBusClient.sendCloseConnection(id)
            return true
        }
        return call { it.closeConnection(id) } ?: false
    }
    fun closeAllConnections(): Int =
        call { it.closeAllConnections() } ?: 0
    fun setOutboundMode(mode: Int): Boolean =
        call { it.setOutboundMode(mode) } ?: false
    fun getOutboundMode(): Int =
        call { it.outboundMode } ?: 0
    fun getRecentRequestsJson(): String =
        call { it.recentRequestsJson } ?: "[]"
    fun setAutoReconnect(enabled: Boolean): Boolean = call { it.setAutoReconnect(enabled) } ?: false
    fun isAutoReconnect(): Boolean = call { it.isAutoReconnect } ?: true
    fun setCheckInterval(interval: Int): Boolean = call { it.setCheckInterval(interval) } ?: false
    fun getCheckInterval(): Int = call { it.checkInterval } ?: 15
    fun setFailoverMode(mode: String): Boolean = call { it.setFailoverMode(mode) } ?: false
    fun getFailoverMode(): String = call { it.failoverMode } ?: "best"
    fun updateNodes(nodesJson: String): Boolean = call { it.updateNodes(nodesJson) } ?: false

    fun registerCallback(cb: ICoreCallback) {
        callbacks.add(cb)
        service?.let { s -> runCatching { s.registerCallback(cb) } }
    }

    fun unregisterCallback(cb: ICoreCallback) {
        callbacks.remove(cb)
        service?.let { s -> runCatching { s.unregisterCallback(cb) } }
    }
}
