package com.mirage.android.core

import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.os.Build
import android.os.Process
import android.util.Log
import android.util.LruCache
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress

/**
 * Android 应用连接归属精准溯源解析器 (Connection Owner Resolver)。
 *
 * 架构特性:
 * 1. Android 10+ (API 29+): 优先调用 `ConnectivityManager.getConnectionOwnerUid(protocol, localAddr, remoteAddr)`，
 *    由于 CoreService 运行为活动 VpnService，具备免特权系统级查询权限。
 * 2. Android 9 及以下 (API 28, 如 Sony XZ1 Compact / BH905W2A9G): 读取 `/proc/net/tcp`、`/proc/net/udp`、
 *    `/proc/net/tcp6`、`/proc/net/udp6` 表项，通过客户端临时端口 (ephemeral src_port) 快速匹配 UID。
 * 3. 性能保护: 仅在 TCP 握手完成与 UDP 流新建时单次查询，并结合双层 LRU 缓存 (UID 映射持久缓存 + 端口临时缓存)。
 */
object ConnectionOwnerResolver {
    private const val TAG = "ConnOwnerResolver"

    @Volatile
    private var appContext: Context? = null
    @Volatile
    private var connectivityManager: ConnectivityManager? = null
    @Volatile
    private var packageManager: PackageManager? = null

    // UID -> Package Name 缓存 (常驻)
    private val uidToPackageCache = LruCache<Int, String>(256)

    // (protocol, srcPort) -> (timestamp, packageName) 临时缓存 (TTL: 5000ms)
    private data class PortCacheEntry(val timestamp: Long, val packageName: String)
    private val portCache = LruCache<Long, PortCacheEntry>(512)

    fun init(context: Context) {
        val app = context.applicationContext
        appContext = app
        connectivityManager = app.getSystemService(ConnectivityManager::class.java)
        packageManager = app.packageManager
    }

    /**
     * 解析连接归属包名。由 Rust 原生层通过 JNI 静态方法回调。
     *
     * @param protocol 6 为 TCP, 17 为 UDP
     * @param srcIp 客户端源 IP
     * @param srcPort 客户端临时端口
     * @param dstIp 目标 IP
     * @param dstPort 目标端口
     * @return 应用程序包名 (如 com.tencent.mm, org.telegram.messenger)，未找到返回 null
     */
    @JvmStatic
    fun resolve(protocol: Int, srcIp: String, srcPort: Int, dstIp: String, dstPort: Int): String? {
        if (srcPort <= 0) return null

        val cacheKey = ((protocol.toLong() and 0xFFL) shl 32) or (srcPort.toLong() and 0xFFFFL)
        val now = System.currentTimeMillis()

        synchronized(portCache) {
            val cached = portCache.get(cacheKey)
            if (cached != null && (now - cached.timestamp) < 5000) {
                return cached.packageName
            }
        }

        Log.i("ConnOwnerResolver", "resolve: proto=$protocol, src=$srcIp:$srcPort, dst=$dstIp:$dstPort")
        var resolvedUid: Int? = null

        // 1. Android 10+ (API 29+) 优先使用 ConnectivityManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val cm = connectivityManager
            if (cm != null) {
                resolvedUid = runCatching {
                    val local = InetSocketAddress(InetAddress.getByName(srcIp), srcPort)
                    val remote = InetSocketAddress(InetAddress.getByName(dstIp), dstPort)
                    val uid = cm.getConnectionOwnerUid(protocol, local, remote)
                    Log.i("ConnOwnerResolver", "getConnectionOwnerUid(proto=$protocol, local=$local, remote=$remote) -> $uid")
                    if (uid > 0 && uid != Process.INVALID_UID) uid else null
                }.onFailure { e ->
                    Log.w("ConnOwnerResolver", "getConnectionOwnerUid 抛出异常: ${e.message}", e)
                }.getOrNull()
            } else {
                Log.w("ConnOwnerResolver", "connectivityManager 为 null!")
            }
        }

        // 2. Android 9 / API 28 或系统 API 失败时，通过 procfs 读取
        if (resolvedUid == null) {
            resolvedUid = resolveUidFromProcfs(protocol, srcIp, srcPort)
            Log.i("ConnOwnerResolver", "resolveUidFromProcfs -> $resolvedUid")
        }

        val uid = resolvedUid ?: return null

        // 过滤自身 VPN 应用
        if (uid == Process.myUid()) {
            return appContext?.packageName ?: "com.mirage.android"
        }

        val packageName = getPackageNameForUid(uid)
        Log.i("ConnOwnerResolver", "uid=$uid -> package=$packageName")
        if (!packageName.isNullOrBlank()) {
            synchronized(portCache) {
                portCache.put(cacheKey, PortCacheEntry(now, packageName))
            }
        }
        return packageName
    }

    /**
     * 根据 UID 获取对应的应用程序包名
     */
    fun getPackageNameForUid(uid: Int): String? {
        synchronized(uidToPackageCache) {
            val cached = uidToPackageCache.get(uid)
            if (cached != null) return cached
        }

        val pm = packageManager ?: return null
        val pkg = runCatching {
            val list = pm.getPackagesForUid(uid)
            val name = pm.getNameForUid(uid)
            Log.d("ConnOwnerResolver", "getPackagesForUid($uid) -> ${list?.contentToString()}, getNameForUid -> $name")
            list?.firstOrNull() ?: name
        }.onFailure { e ->
            Log.w("ConnOwnerResolver", "getPackageNameForUid($uid) 失败: ${e.message}", e)
        }.getOrNull()

        if (!pkg.isNullOrBlank()) {
            synchronized(uidToPackageCache) {
                uidToPackageCache.put(uid, pkg)
            }
        }
        return pkg
    }

    private val appLabelCache = object : LinkedHashMap<String, String>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean {
            return size > 128
        }
    }

    /**
     * 获取包名对应的友好应用名 (如 "com.tencent.mm" -> "微信")
     */
    fun getAppLabel(packageName: String, context: Context? = null): String {
        synchronized(appLabelCache) {
            val cached = appLabelCache[packageName]
            if (cached != null) return cached
        }
        val pm = packageManager ?: context?.packageManager ?: return packageName
        val label = runCatching {
            val appInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getApplicationInfo(packageName, PackageManager.ApplicationInfoFlags.of(0))
            } else {
                pm.getApplicationInfo(packageName, 0)
            }
            pm.getApplicationLabel(appInfo).toString()
        }.getOrDefault(packageName)

        synchronized(appLabelCache) {
            appLabelCache[packageName] = label
        }
        return label
    }

    /**
     * 从 Linux 内核 procfs 表项快速匹配连接所属 UID
     */
    private fun resolveUidFromProcfs(protocol: Int, srcIp: String, srcPort: Int): Int? {
        val hexPort = String.format("%04X", srcPort)
        val isIpv6 = srcIp.contains(":")

        val procFiles = when (protocol) {
            6 -> if (isIpv6) listOf("/proc/net/tcp6", "/proc/net/tcp") else listOf("/proc/net/tcp", "/proc/net/tcp6")
            17 -> if (isIpv6) listOf("/proc/net/udp6", "/proc/net/udp") else listOf("/proc/net/udp", "/proc/net/udp6")
            else -> emptyList()
        }

        for (path in procFiles) {
            val file = File(path)
            if (!file.canRead()) continue

            val uid = parseProcNetFile(file, hexPort)
            if (uid != null && uid > 0) {
                return uid
            }
        }
        return null
    }

    private fun parseProcNetFile(file: File, hexPort: String): Int? {
        return runCatching {
            file.useLines { lines ->
                for (line in lines) {
                    val trimmed = line.trim()
                    if (trimmed.isEmpty() || trimmed.startsWith("sl")) continue

                    // 检查行内是否包含指定的目标端口后缀
                    val portIndex = trimmed.indexOf(":$hexPort")
                    if (portIndex == -1) continue

                    // 以空白字符分割字段
                    val parts = trimmed.split(Regex("\\s+"))
                    // 格式: sl local_address rem_address st tx_queue rx_queue tr tm->when retrnsmt uid timeout inode
                    // 索引: 0:sl, 1:local, 2:rem, 3:st, 4:tx:rx, 5:tr:tm, 6:retrnsmt, 7:uid
                    if (parts.size >= 8) {
                        val local = parts[1]
                        if (local.endsWith(":$hexPort")) {
                            return@useLines parts[7].toIntOrNull()
                        }
                    }
                }
                null
            }
        }.getOrNull()
    }
}
