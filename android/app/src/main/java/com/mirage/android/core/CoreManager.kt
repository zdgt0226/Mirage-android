package com.mirage.android.core

import android.content.Context
import android.os.Build
import com.mirage.android.data.model.CoreInfo
import com.mirage.android.data.model.OnlineReleaseInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.util.UUID

/**
 * Mirage 内核管理器: 管理内置内核与自定义导入的 .so 内核。
 */
class CoreManager private constructor(private val context: Context) {

    private val coresDir = File(context.filesDir, "cores").apply { mkdirs() }
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _cores = MutableStateFlow<List<CoreInfo>>(emptyList())
    val cores: StateFlow<List<CoreInfo>> = _cores.asStateFlow()

    private val _activeCoreId = MutableStateFlow(CoreInfo.BUILTIN_ID)
    val activeCoreId: StateFlow<String> = _activeCoreId.asStateFlow()

    init {
        loadCores()
    }

    private fun loadCores() {
        val raw = prefs.getString(KEY_CORES_JSON, "[]") ?: "[]"
        val customList = runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.getJSONObject(i)
                val path = o.getString("file_path")
                val file = File(path)
                if (file.exists()) {
                    CoreInfo(
                        id = o.getString("id"),
                        name = o.getString("name"),
                        version = o.optString("version", "自定义版本"),
                        abi = o.optString("abi", "arm64-v8a"),
                        filePath = path,
                        fileSize = file.length(),
                        isBuiltin = false,
                        addedTime = o.optLong("added_time", System.currentTimeMillis())
                    )
                } else null
            }
        }.getOrDefault(emptyList())

        val all = listOf(CoreInfo.builtin()) + customList
        _cores.value = all

        val savedActiveId = prefs.getString(KEY_ACTIVE_CORE, CoreInfo.BUILTIN_ID) ?: CoreInfo.BUILTIN_ID
        _activeCoreId.value = if (all.any { it.id == savedActiveId }) savedActiveId else CoreInfo.BUILTIN_ID
    }

    private fun saveCustomCores(list: List<CoreInfo>) {
        val arr = JSONArray()
        for (c in list.filter { !it.isBuiltin }) {
            arr.put(
                JSONObject()
                    .put("id", c.id)
                    .put("name", c.name)
                    .put("version", c.version)
                    .put("abi", c.abi)
                    .put("file_path", c.filePath)
                    .put("added_time", c.addedTime)
            )
        }
        prefs.edit().putString(KEY_CORES_JSON, arr.toString()).apply()
    }

    fun getActiveCore(): CoreInfo {
        val id = _activeCoreId.value
        return _cores.value.firstOrNull { it.id == id } ?: CoreInfo.builtin()
    }

    fun setActiveCore(id: String): Boolean {
        if (_cores.value.any { it.id == id }) {
            _activeCoreId.value = id
            prefs.edit().putString(KEY_ACTIVE_CORE, id).apply()
            return true
        }
        return false
    }

    /**
     * 导入外部 .so 文件。
     */
    fun importCore(inputStream: InputStream, displayName: String): Result<CoreInfo> {
        val tempFile = File(coresDir, "temp_${System.currentTimeMillis()}.so")
        try {
            tempFile.outputStream().use { output ->
                inputStream.copyTo(output)
            }

            // 检查 ELF ABI 兼容性
            val abiResult = ElfChecker.checkAbi(tempFile)
            if (abiResult.isFailure) {
                tempFile.delete()
                return Result.failure(abiResult.exceptionOrNull() ?: IllegalArgumentException("非法 ELF 文件"))
            }

            val abi = abiResult.getOrThrow()
            if (!ElfChecker.isDeviceCompatible(abi)) {
                tempFile.delete()
                val devAbis = Build.SUPPORTED_ABIS.joinToString(", ")
                return Result.failure(
                    IllegalArgumentException("架构不兼容！该内核为 $abi，但当前设备支持: $devAbis")
                )
            }

            val id = UUID.randomUUID().toString()
            val targetFile = File(coresDir, "libmirage_$id.so")
            if (!tempFile.renameTo(targetFile)) {
                tempFile.copyTo(targetFile, overwrite = true)
                tempFile.delete()
            }

            val coreInfo = CoreInfo(
                id = id,
                name = displayName.ifBlank { "Mirage 内核 ($abi)" },
                version = "自定义版本 ($abi)",
                abi = abi,
                filePath = targetFile.absolutePath,
                fileSize = targetFile.length(),
                isBuiltin = false
            )

            val current = _cores.value.toMutableList()
            current.add(coreInfo)
            _cores.value = current
            saveCustomCores(current)

            return Result.success(coreInfo)
        } catch (e: Exception) {
            tempFile.delete()
            return Result.failure(e)
        }
    }

    fun deleteCore(id: String): Boolean {
        if (id == CoreInfo.BUILTIN_ID) return false
        val current = _cores.value.toMutableList()
        val item = current.firstOrNull { it.id == id } ?: return false

        item.file?.delete()
        current.remove(item)
        _cores.value = current
        saveCustomCores(current)

        if (_activeCoreId.value == id) {
            setActiveCore(CoreInfo.BUILTIN_ID)
        }
        return true
    }

    fun resetToBuiltin(): Boolean {
        return setActiveCore(CoreInfo.BUILTIN_ID)
    }

    /**
     * 查询 GitHub Releases 获取最新的 Mirage-rs 内核发布列表
     */
    suspend fun fetchOnlineReleases(): Result<List<OnlineReleaseInfo>> = withContext(Dispatchers.IO) {
        runCatching {
            val url = java.net.URL("https://api.github.com/repos/zdgt0226/Mirage-rs/releases")
            val conn = (url.openConnection() as java.net.HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 10000
                readTimeout = 10000
                setRequestProperty("User-Agent", "Mirage-Android-Client")
                setRequestProperty("Accept", "application/vnd.github.v3+json")
            }
            if (conn.responseCode !in 200..299) {
                throw java.io.IOException("GitHub API 响应失败: HTTP ${conn.responseCode}")
            }
            val responseText = conn.inputStream.bufferedReader().use { it.readText() }
            val jsonArray = JSONArray(responseText)
            val supportedAbis = Build.SUPPORTED_ABIS.toList()

            val list = mutableListOf<OnlineReleaseInfo>()
            for (i in 0 until jsonArray.length()) {
                val rel = jsonArray.getJSONObject(i)
                val tagName = rel.optString("tag_name")
                val releaseName = rel.optString("name", tagName)
                val body = rel.optString("body", "")
                val publishedAt = rel.optString("published_at", "")
                val assets = rel.optJSONArray("assets") ?: continue

                // 查找匹配当前设备 ABI 的 .so 文件资产
                var matchedAsset: JSONObject? = null
                var matchedAbi = "arm64-v8a"

                for (j in 0 until assets.length()) {
                    val asset = assets.getJSONObject(j)
                    val aName = asset.optString("name", "")
                    if (!aName.endsWith(".so")) continue

                    // 优先匹配当前主架构 (如 arm64-v8a / aarch64)
                    for (abi in supportedAbis) {
                        val keyword = when (abi) {
                            "arm64-v8a" -> listOf("arm64", "aarch64")
                            "armeabi-v7a" -> listOf("armv7", "arm32", "armeabi")
                            "x86_64" -> listOf("x86_64", "amd64")
                            "x86" -> listOf("i386", "i686", "x86")
                            else -> listOf(abi)
                        }
                        if (keyword.any { aName.contains(it, ignoreCase = true) } || aName == "libmirage_jni.so") {
                            matchedAsset = asset
                            matchedAbi = abi
                            break
                        }
                    }
                    if (matchedAsset != null) break
                }

                if (matchedAsset != null) {
                    list.add(
                        OnlineReleaseInfo(
                            tagName = tagName,
                            name = releaseName,
                            body = body,
                            publishedAt = publishedAt.take(10),
                            assetName = matchedAsset.getString("name"),
                            downloadUrl = matchedAsset.getString("browser_download_url"),
                            sizeBytes = matchedAsset.optLong("size", 0L),
                            targetAbi = matchedAbi
                        )
                    )
                }
            }
            list
        }
    }

    /**
     * 下载指定的 Release 资产并导入为活跃内核
     */
    suspend fun downloadAndImportRelease(
        release: OnlineReleaseInfo,
        onProgress: (Int) -> Unit
    ): Result<CoreInfo> = withContext(Dispatchers.IO) {
        val tempFile = File(coresDir, "download_${System.currentTimeMillis()}.so")
        try {
            var currentUrl = release.downloadUrl
            var conn: java.net.HttpURLConnection? = null
            var redirectCount = 0
            while (redirectCount < 6) {
                val url = java.net.URL(currentUrl)
                val c = (url.openConnection() as java.net.HttpURLConnection).apply {
                    instanceFollowRedirects = true
                    connectTimeout = 15000
                    readTimeout = 30000
                    setRequestProperty("User-Agent", "Mirage-Android-Client")
                }
                val code = c.responseCode
                if (code in listOf(301, 302, 303, 307, 308)) {
                    val location = c.getHeaderField("Location")
                    if (!location.isNullOrBlank()) {
                        currentUrl = location
                        redirectCount++
                        c.disconnect()
                        continue
                    }
                }
                conn = c
                break
            }

            val finalConn = conn ?: throw java.io.IOException("重定向次数过多")
            if (finalConn.responseCode !in 200..299) {
                throw java.io.IOException("HTTP 下载失败: ${finalConn.responseCode}")
            }

            val totalBytes = finalConn.contentLengthLong.takeIf { it > 0 } ?: release.sizeBytes
            var downloadedBytes = 0L

            finalConn.inputStream.use { input ->
                tempFile.outputStream().use { output ->
                    val buffer = ByteArray(8192)
                    var bytesRead: Int
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        downloadedBytes += bytesRead
                        if (totalBytes > 0) {
                            val percent = ((downloadedBytes * 100) / totalBytes).toInt().coerceIn(0, 100)
                            onProgress(percent)
                        }
                    }
                }
            }

            // 导入下载好的 SO
            val importRes = tempFile.inputStream().use { input ->
                importCore(input, "Mirage-rs ${release.tagName}")
            }
            tempFile.delete()
            importRes
        } catch (e: Exception) {
            tempFile.delete()
            Result.failure(e)
        }
    }

    companion object {
        private const val PREFS_NAME = "mirage_cores"
        private const val KEY_CORES_JSON = "cores_json"
        private const val KEY_ACTIVE_CORE = "active_core_id"

        @Volatile
        private var instance: CoreManager? = null

        fun getInstance(context: Context): CoreManager {
            return instance ?: synchronized(this) {
                instance ?: CoreManager(context.applicationContext).also { instance = it }
            }
        }
    }
}
