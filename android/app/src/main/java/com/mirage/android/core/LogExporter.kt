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
 * 汇聚 Native/Kotlin 日志 (敏感信息脱敏)、系统环境、连接度量与规则统计，生成一键诊断包 (.zip)。
 */
object LogExporter {

    private val PASSWORD_REGEX = Regex("mirage://([^:@]+)@")
    private val TOKEN_REGEX = Regex("(?i)(password|token|bearer|key)\\s*[:=]\\s*['\"]?([^'\"\\s,]+)['\"]?")

    /** 对日志内容进行隐私脱敏处理 (密码、Token 自动掩码)。 */
    fun sanitize(raw: String): String {
        var s = PASSWORD_REGEX.replace(raw) { matchResult ->
            val pwd = matchResult.groupValues[1]
            val masked = if (pwd.length > 4) pwd.take(2) + "****" + pwd.takeLast(2) else "****"
            "mirage://$masked@"
        }
        s = TOKEN_REGEX.replace(s) { matchResult ->
            val key = matchResult.groupValues[1]
            "$key=********"
        }
        return s
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

    /** 打包生成完整的诊断 Zip 文件。 */
    suspend fun createDiagnosticZip(context: Context): File = withContext(Dispatchers.IO) {
        val diagDir = File(context.cacheDir, "diagnostics").apply { mkdirs() }
        val timeTag = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val zipFile = File(diagDir, "mirage_diag_$timeTag.zip")

        // 汇聚 Native + Kotlin 日志
        val nativeLogs = runCatching { CoreController.recentLogs().toList() }.getOrDefault(emptyList()).ifEmpty {
            runCatching { MirageNative.recentLogs().toList() }.getOrDefault(emptyList())
        }
        val appLogs = LogStore.all()
        val combinedLogs = (appLogs + nativeLogs).distinct().map { sanitize(it) }.joinToString("\n")

        val sysInfo = collectSystemInfo(context)
        val statsSnapshot = runCatching { CoreController.getDiagnosticSnapshotJson() }.getOrDefault("{}")
        val connectionsJson = runCatching { CoreController.getConnectionsJson() }.getOrDefault("[]")
        val ruleHitsJson = runCatching { CoreController.getRuleHits() }.getOrDefault("{}")

        ZipOutputStream(FileOutputStream(zipFile)).use { zos ->
            fun addZipEntry(name: String, content: String) {
                zos.putNextEntry(ZipEntry(name))
                zos.write(content.toByteArray(Charsets.UTF_8))
                zos.closeEntry()
            }

            addZipEntry("mirage_core.log", combinedLogs)
            addZipEntry("system_info.json", sysInfo)
            addZipEntry("stats_snapshot.json", statsSnapshot)
            addZipEntry("active_connections.json", connectionsJson)
            addZipEntry("rule_hits.json", ruleHitsJson)
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
