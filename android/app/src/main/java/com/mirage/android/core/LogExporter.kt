package com.mirage.android.core

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.Process
import androidx.core.content.FileProvider
import com.mirage.android.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 诊断日志与系统度量导出管理器。
 * 具备高等级的隐私安全与脱敏体系 (解决 AI 审计 S1/S3 纰漏)：
 * 1. 节点 URI 全字段脱敏 (掩码密码、服务器 IP、端口及 SNI 伪装域名)；
 * 2. 诊断包中的活跃连接与规则命中历史进行域名脱敏 (如 g*****.com)，杜绝浏览隐私泄露；
 * 3. 词边界防护，避免正则误伤正常日志；
 * 4. 汇聚 Native/Kotlin 日志、系统环境与度量快照，生成一键诊断包 (.zip)。
 */
object LogExporter {

    // 匹配 mirage:// 完整节点 URI
    private val MIRAGE_URI_REGEX = Regex("mirage://([^:@]+)@([^:/?#]+)(?::([0-9]+))?([^\\s\"']*)")
    // 匹配特定敏感字段 (带词边界)
    private val TOKEN_REGEX = Regex("\\b(?i)(password|token|bearer|key|secret)\\b\\s*[:=]\\s*['\"]?([^'\"\\s,]+)['\"]?")
    // 匹配日志中独立出现的 sni 参数
    private val SNI_PARAM_REGEX = Regex("(?i)sni=([^&\\s,\"']+)")
    // 匹配日志中独立出现的 server= 参数
    private val SERVER_PARAM_REGEX = Regex("(?i)server=([^&\\s,\"']+)")

    /** 对域名或主机进行部分掩码 (如 google.com -> g*****.com) */
    fun maskHost(host: String): String {
        if (host.isBlank()) return host
        // 如果是 IPv4 地址
        if (host.matches(Regex("^\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}$"))) {
            val parts = host.split(".")
            return "${parts[0]}.${parts[1]}.*.*"
        }
        val dotIdx = host.lastIndexOf('.')
        if (dotIdx > 0) {
            val name = host.substring(0, dotIdx)
            val tld = host.substring(dotIdx)
            val maskedName = if (name.length > 2) {
                name.first() + "*****" + name.last()
            } else {
                "***"
            }
            return maskedName + tld
        }
        return if (host.length > 2) host.first() + "***" + host.last() else "***"
    }

    /** 对 target 字符串 (如 domain.com:443) 进行脱敏 */
    fun maskTarget(target: String): String {
        if (target.isBlank()) return target
        val parts = target.split(":")
        val host = parts[0]
        val port = if (parts.size > 1) ":${parts[1]}" else ""
        return maskHost(host) + port
    }

    /** 对日志内容进行全方位隐私脱敏处理 (密码、服务器 IP、SNI、Token 自动掩码)。 */
    fun sanitize(raw: String): String {
        // 1. 脱敏完整 mirage:// URI
        var s = MIRAGE_URI_REGEX.replace(raw) { match ->
            val host = match.groupValues[2]
            val port = match.groupValues[3]
            val query = match.groupValues[4]

            val maskedHost = maskHost(host)
            val maskedPort = if (port.isNotEmpty()) ":***" else ""
            val maskedQuery = SNI_PARAM_REGEX.replace(query) { m ->
                "sni=" + maskHost(m.groupValues[1])
            }
            "mirage://***@$maskedHost$maskedPort$maskedQuery"
        }

        // 2. 脱敏独立 server=
        s = SERVER_PARAM_REGEX.replace(s) { match ->
            "server=" + maskTarget(match.groupValues[1])
        }

        // 3. 脱敏独立 sni=
        s = SNI_PARAM_REGEX.replace(s) { match ->
            "sni=" + maskHost(match.groupValues[1])
        }

        // 4. 脱敏常见 Token/Password/Key
        s = TOKEN_REGEX.replace(s) { match ->
            val key = match.groupValues[1]
            "$key=********"
        }

        return s
    }

    /** 脱敏活跃连接 JSON 快照中的访问目标 */
    private fun sanitizeConnectionsJson(jsonStr: String): String {
        return runCatching {
            val array = JSONArray(jsonStr)
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val target = obj.optString("target", "")
                if (target.isNotEmpty()) {
                    obj.put("target", maskTarget(target))
                }
            }
            array.toString(2)
        }.getOrDefault(jsonStr)
    }

    /** 脱敏规则命中统计 JSON 中的目标 Pattern */
    private fun sanitizeRuleHitsJson(jsonStr: String): String {
        return runCatching {
            val obj = JSONObject(jsonStr)
            val keys = obj.keys()
            val newObj = JSONObject()
            while (keys.hasNext()) {
                val k = keys.next()
                val hits = obj.getLong(k)
                val maskedKey = if (k.contains(":")) {
                    val parts = k.split(":", limit = 2)
                    "${parts[0]}:${maskHost(parts[1])}"
                } else {
                    maskHost(k)
                }
                newObj.put(maskedKey, hits)
            }
            newObj.toString(2)
        }.getOrDefault(jsonStr)
    }

    /** 收集当前运行环境系统信息 JSON。 */
    fun collectSystemInfo(context: Context): String {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val activeNetwork = cm?.activeNetwork
        val caps = cm?.getNetworkCapabilities(activeNetwork)
        val netType = when {
            caps == null -> "None/Disconnected"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Cellular (Mobile Data)"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "VPN Active"
            else -> "Other"
        }

        val json = JSONObject()
        val dev = JSONObject()
        dev.put("manufacturer", Build.MANUFACTURER)
        dev.put("model", Build.MODEL)
        dev.put("device", Build.DEVICE)
        dev.put("product", Build.PRODUCT)
        dev.put("brand", Build.BRAND)
        dev.put("supported_abis", Build.SUPPORTED_ABIS.joinToString(","))
        json.put("device", dev)

        val os = JSONObject()
        os.put("release", Build.VERSION.RELEASE)
        os.put("sdk_int", Build.VERSION.SDK_INT)
        os.put("display_build", Build.DISPLAY)
        os.put("fingerprint", Build.FINGERPRINT)
        json.put("os", os)

        val app = JSONObject()
        app.put("app_id", BuildConfig.APPLICATION_ID)
        app.put("version_name", BuildConfig.VERSION_NAME)
        app.put("version_code", BuildConfig.VERSION_CODE)
        app.put("build_type", BuildConfig.BUILD_TYPE)
        app.put("build_time", BuildConfig.BUILD_TIME)
        app.put("build_tag", BuildConfig.BUILD_TAG)
        json.put("app", app)

        val runtime = JSONObject()
        runtime.put("main_pid", Process.myPid())
        runtime.put("network_type", netType)
        runtime.put("is_running", CoreController.isRunning())
        runtime.put("direct_dns", runCatching { CoreController.getDirectDns() }.getOrDefault("Unknown"))
        runtime.put("remote_dns", runCatching { CoreController.getRemoteDns() }.getOrDefault("Unknown"))
        runtime.put("block_quic", runCatching { CoreController.isBlockQuic() }.getOrDefault(false))
        runtime.put("udp_mux", runCatching { CoreController.isUdpMux() }.getOrDefault(false))
        runtime.put("pool_size", runCatching { CoreController.getPoolSize() }.getOrDefault(0))
        json.put("runtime", runtime)

        return json.toString(2)
    }

    /** 打包生成完整的诊断 Zip 文件 (全方位脱敏保护隐私)。 */
    suspend fun createDiagnosticZip(context: Context): File = withContext(Dispatchers.IO) {
        val diagDir = File(context.cacheDir, "diagnostics").apply { mkdirs() }
        val timeTag = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val zipFile = File(diagDir, "mirage_diag_$timeTag.zip")

        // 汇聚 Native + Kotlin 日志 (统一脱敏)
        val nativeLogs = runCatching { CoreController.recentLogs().toList() }.getOrDefault(emptyList()).ifEmpty {
            runCatching { MirageNative.recentLogs().toList() }.getOrDefault(emptyList())
        }
        val appLogs = LogStore.all()
        val combinedLogs = (appLogs + nativeLogs).distinct().map { sanitize(it) }.joinToString("\n")

        val sysInfo = collectSystemInfo(context)
        val statsSnapshot = runCatching { CoreController.getDiagnosticSnapshotJson() }.getOrDefault("{}")
        val rawConnections = runCatching { CoreController.getConnectionsJson() }.getOrDefault("[]")
        val rawRuleHits = runCatching { CoreController.getRuleHits() }.getOrDefault("{}")

        // 对连接列表与规则命中中的域名进行脱敏
        val sanitizedConnections = sanitizeConnectionsJson(rawConnections)
        val sanitizedRuleHits = sanitizeRuleHitsJson(rawRuleHits)

        ZipOutputStream(FileOutputStream(zipFile)).use { zos ->
            fun addZipEntry(name: String, content: String) {
                zos.putNextEntry(ZipEntry(name))
                zos.write(content.toByteArray(Charsets.UTF_8))
                zos.closeEntry()
            }

            addZipEntry("mirage_core.log", combinedLogs)
            addZipEntry("system_info.json", sysInfo)
            addZipEntry("stats_snapshot.json", statsSnapshot)
            addZipEntry("active_connections.json", sanitizedConnections)
            addZipEntry("rule_hits.json", sanitizedRuleHits)
        }

        zipFile
    }

    /** 导出并通过系统 Intent 分享诊断 Zip 包。 */
    suspend fun shareDiagnosticZip(context: Context) {
        val zipFile = createDiagnosticZip(context)
        val uri: Uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            zipFile
        )

        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/zip"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "Mirage 诊断日志包 (${zipFile.name})")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        val chooser = Intent.createChooser(intent, "导出并分享 Mirage 诊断包").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(chooser)
    }

    /** 将诊断包直接保存到用户指定的 SAF 目标 Uri (如 Downloads)。 */
    suspend fun saveToUri(context: Context, destUri: Uri): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val zipFile = createDiagnosticZip(context)
            context.contentResolver.openOutputStream(destUri)?.use { out ->
                zipFile.inputStream().use { input ->
                    input.copyTo(out)
                }
            }
            true
        }.getOrDefault(false)
    }
}
