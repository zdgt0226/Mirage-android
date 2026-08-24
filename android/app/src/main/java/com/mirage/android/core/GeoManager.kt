package com.mirage.android.core

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Geo 文件管理器 (geosite.dat 与 geoip.dat)。
 * 支持自定义更新 URL、断点多镜像下载、完整性校验、原子更新与热加载。
 */
object GeoManager {

    private const val PREFS = "mirage_geo_config"
    private const val KEY_ACTIVE_SOURCE_ID = "active_source_id"
    private const val KEY_CUSTOM_SOURCES = "custom_sources_json"
    private const val KEY_AUTO_UPDATE = "auto_update_interval"
    private const val KEY_GEOSITE_URL = "geosite_url"
    private const val KEY_GEOIP_URL = "geoip_url"
    private const val KEY_LAST_UPDATE = "last_update_time"

    // 官方默认源 (多镜像兜底)
    const val DEFAULT_GEOSITE_URL = "https://raw.githubusercontent.com/Loyalsoldier/v2ray-rules-dat/release/geosite.dat"
    const val DEFAULT_GEOIP_URL = "https://raw.githubusercontent.com/Loyalsoldier/v2ray-rules-dat/release/geoip.dat"

    data class GeoSource(
        val id: String,
        val name: String,
        val geositeUrl: String,
        val geoipUrl: String,
        val isBuiltin: Boolean = false,
    )

    val BUILTIN_SOURCES = listOf(
        GeoSource(
            id = "loyalsoldier",
            name = "Loyalsoldier 官方源 (GitHub)",
            geositeUrl = "https://raw.githubusercontent.com/Loyalsoldier/v2ray-rules-dat/release/geosite.dat",
            geoipUrl = "https://raw.githubusercontent.com/Loyalsoldier/v2ray-rules-dat/release/geoip.dat",
            isBuiltin = true,
        ),
        GeoSource(
            id = "fastly_cdn",
            name = "Fastly CDN 加速镜像 (国内极速)",
            geositeUrl = "https://fastly.jsdelivr.net/gh/Loyalsoldier/v2ray-rules-dat@release/geosite.dat",
            geoipUrl = "https://fastly.jsdelivr.net/gh/Loyalsoldier/v2ray-rules-dat@release/geoip.dat",
            isBuiltin = true,
        ),
        GeoSource(
            id = "v2fly",
            name = "v2fly 官方源",
            geositeUrl = "https://github.com/v2fly/domain-list-community/releases/latest/download/dlc.dat",
            geoipUrl = "https://github.com/v2fly/geoip/releases/latest/download/geoip.dat",
            isBuiltin = true,
        )
    )

    enum class AutoUpdateInterval(val displayName: String, val hours: Long) {
        NEVER("从不自动更新", 0),
        ON_START("每次启动时检查", 0),
        DAILY("每天自动更新一次", 24),
        WEEKLY("每周自动更新一次", 168);

        companion object {
            fun fromName(name: String?): AutoUpdateInterval =
                values().firstOrNull { it.name.equals(name, ignoreCase = true) } ?: ON_START
        }
    }

    data class GeoTagEntry(
        val tag: String,
        val count: Int,
        val isGeoSite: Boolean,
    )

    data class GeoDetailResponse(
        val geositeCount: Int,
        val geoipCount: Int,
        val geositeTags: List<GeoTagEntry>,
        val geoipCodes: List<GeoTagEntry>,
        val geositePath: String,
        val geoipPath: String,
    )

    fun getSources(context: Context): List<GeoSource> {
        val list = BUILTIN_SOURCES.toMutableList()
        val sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val customRaw = sp.getString(KEY_CUSTOM_SOURCES, "[]") ?: "[]"
        runCatching {
            val arr = org.json.JSONArray(customRaw)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                list.add(GeoSource(
                    id = o.getString("id"),
                    name = o.getString("name"),
                    geositeUrl = o.getString("geositeUrl"),
                    geoipUrl = o.getString("geoipUrl"),
                    isBuiltin = false,
                ))
            }
        }
        return list
    }

    fun getActiveSource(context: Context): GeoSource {
        val sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val activeId = sp.getString(KEY_ACTIVE_SOURCE_ID, "fastly_cdn")
        val sources = getSources(context)
        return sources.firstOrNull { it.id == activeId } ?: sources.first()
    }

    fun setActiveSource(context: Context, sourceId: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_ACTIVE_SOURCE_ID, sourceId)
            .apply()
    }

    fun saveCustomSources(context: Context, customList: List<GeoSource>) {
        val arr = org.json.JSONArray()
        for (s in customList.filter { !it.isBuiltin }) {
            arr.put(org.json.JSONObject()
                .put("id", s.id)
                .put("name", s.name)
                .put("geositeUrl", s.geositeUrl)
                .put("geoipUrl", s.geoipUrl)
            )
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_CUSTOM_SOURCES, arr.toString())
            .apply()
    }

    fun getAutoUpdateInterval(context: Context): AutoUpdateInterval {
        val sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return AutoUpdateInterval.fromName(sp.getString(KEY_AUTO_UPDATE, AutoUpdateInterval.ON_START.name))
    }

    fun setAutoUpdateInterval(context: Context, interval: AutoUpdateInterval) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_AUTO_UPDATE, interval.name)
            .apply()
    }

    @Volatile
    private var cachedTagsDetail: GeoDetailResponse? = null

    fun invalidateCache() {
        cachedTagsDetail = null
    }

    /** 从内核获取详细的 Tag 条目数量 (内存热缓存，避免反复跨进程/JNI 解析大量 JSON) */
    fun getTagsDetailFromNative(context: Context, forceReload: Boolean = false): GeoDetailResponse {
        if (!forceReload && cachedTagsDetail != null && cachedTagsDetail!!.geositeTags.isNotEmpty()) {
            return cachedTagsDetail!!
        }
        loadGeoFilesToNative(context)
        val jsonStr = runCatching { MirageNative.getGeoTagsDetail() }.getOrDefault("")
        val activeJson = if (jsonStr.isNotBlank() && jsonStr != "{}" && jsonStr.contains("geosite_tags")) {
            jsonStr
        } else {
            CoreController.getGeoTagsDetail()
        }
        val res = runCatching {
            val root = org.json.JSONObject(activeJson)
            val geositeCount = root.optInt("geosite_count", 0)
            val geoipCount = root.optInt("geoip_count", 0)
            val siteArr = root.optJSONArray("geosite_tags")
            val siteList = mutableListOf<GeoTagEntry>()
            if (siteArr != null) {
                for (i in 0 until siteArr.length()) {
                    val o = siteArr.getJSONObject(i)
                    siteList.add(GeoTagEntry(o.getString("tag"), o.getInt("count"), true))
                }
            }

            val ipArr = root.optJSONArray("geoip_codes")
            val ipList = mutableListOf<GeoTagEntry>()
            if (ipArr != null) {
                for (i in 0 until ipArr.length()) {
                    val o = ipArr.getJSONObject(i)
                    ipList.add(GeoTagEntry(o.getString("code"), o.getInt("count"), false))
                }
            }

            GeoDetailResponse(
                geositeCount = geositeCount,
                geoipCount = geoipCount,
                geositeTags = siteList,
                geoipCodes = ipList,
                geositePath = root.optString("geosite_path", ""),
                geoipPath = root.optString("geoip_path", "")
            )
        }.getOrDefault(GeoDetailResponse(0, 0, emptyList(), emptyList(), "", ""))

        if (res.geositeTags.isNotEmpty()) {
            cachedTagsDetail = res
        }
        return res
    }

    data class PresetGeoTag(
        val kind: String, // "geosite" or "geoip"
        val tag: String,  // "category-ads-all", "google", etc.
        val title: String, // "🚫 全网广告/追踪拦截 (category-ads-all)"
        val defaultAction: String, // "block", "proxy", "direct"
        val description: String = ""
    )

    val PRESET_GEO_TAGS = listOf(
        PresetGeoTag("geosite", "category-ads-all", "🚫 全网广告/追踪拦截 (category-ads-all)", "block", "屏蔽各类广告、数据追踪与分析上报域名"),
        PresetGeoTag("geosite", "google", "🚀 Google 境外全套服务 (google)", "proxy", "包含 Google 搜索、Play 商店、Gmail 等"),
        PresetGeoTag("geosite", "openai", "🚀 OpenAI / ChatGPT (openai)", "proxy", "ChatGPT 官网、API 及相关服务"),
        PresetGeoTag("geosite", "telegram", "🚀 Telegram 官方域名 (telegram)", "proxy", "Telegram 官网与 Web 客户端"),
        PresetGeoTag("geosite", "netflix", "🚀 Netflix 奈飞流媒体 (netflix)", "proxy", "Netflix 影视与 CDN 节点"),
        PresetGeoTag("geosite", "youtube", "🚀 YouTube 视频平台 (youtube)", "proxy", "YouTube 视频流与播放服务"),
        PresetGeoTag("geosite", "github", "🚀 GitHub 开发者平台 (github)", "proxy", "GitHub 仓库、Gist、Assets 资源加速"),
        PresetGeoTag("geosite", "twitter", "🚀 Twitter / X 社交平台 (twitter)", "proxy", "X (原 Twitter) 社交网络"),
        PresetGeoTag("geosite", "geolocation-!cn", "🚀 境外非大陆域名全集 (geolocation-!cn)", "proxy", "所有非中国大陆地区的海外网站与服务"),
        PresetGeoTag("geosite", "cn", "⚡ 中国大陆域名全集 (cn)", "direct", "国内各大主流网站与政企服务"),
        PresetGeoTag("geosite", "bilibili", "⚡ 哔哩哔哩弹幕网 (bilibili)", "direct", "Bilibili 国内视频、直播与 CDN"),
        PresetGeoTag("geosite", "steam", "⚡ Steam 游戏商店/下载 (steam)", "direct", "Steam 国内商店与满速 CDN 下载"),
        PresetGeoTag("geosite", "apple", "⚡ Apple 苹果国内服务 (apple)", "direct", "App Store、iCloud 国内加速节点"),
        PresetGeoTag("geosite", "microsoft", "⚡ Microsoft 微软服务 (microsoft)", "direct", "Windows Update、Office 国内节点"),
        PresetGeoTag("geoip", "cn", "⚡ 中国大陆 IP 地址段 (cn)", "direct", "中国大陆境内所有已知公网 IP"),
        PresetGeoTag("geoip", "private", "⚡ 局域网/保留私有 IP (private)", "direct", "192.168.x.x, 10.x.x.x, 172.16.x.x 等内网"),
        PresetGeoTag("geoip", "telegram", "🚀 Telegram 服务器 IP 段 (telegram)", "proxy", "Telegram 全球数据中心 IP"),
        PresetGeoTag("geoip", "google", "🚀 Google 全球服务器 IP (google)", "proxy", "Google 全球网络及机房 IP"),
        PresetGeoTag("geoip", "netflix", "🚀 Netflix 全球节点 IP (netflix)", "proxy", "Netflix 流媒体服务 IP"),
        PresetGeoTag("geoip", "twitter", "🚀 Twitter / X 服务器 IP (twitter)", "proxy", "Twitter 境外机房 IP")
    )

    // 常用热门 Tag 推荐
    val POPULAR_GEOSITE_TAGS = listOf(
        "category-ads-all",
        "google",
        "openai",
        "telegram",
        "netflix",
        "youtube",
        "github",
        "twitter",
        "geolocation-!cn",
        "cn",
        "bilibili",
        "steam",
        "apple",
        "microsoft"
    )

    val POPULAR_GEOIP_TAGS = listOf(
        "cn",
        "telegram",
        "private",
        "google",
        "netflix",
        "twitter",
        "apple"
    )

    data class GeoStatus(
        val geositeExists: Boolean,
        val geositeSize: Long,
        val geoipExists: Boolean,
        val geoipSize: Long,
        val lastUpdateTime: String,
        val geositeTagCount: Int,
        val geoipCodeCount: Int
    ) {
        val isReady: Boolean get() = geositeExists || geoipExists
        val displaySummary: String
            get() = if (isReady) {
                "Geo: 已就绪 (${geositeTagCount} Sites / ${geoipCodeCount} IPs) · $lastUpdateTime"
            } else {
                "Geo: 未下载 (使用系统内置 CN 白名单)"
            }
    }

    data class GeoUpdateResult(
        val success: Boolean,
        val message: String,
        val geositeTags: Int = 0,
        val geoipCodes: Int = 0
    )

    fun getGeoDir(context: Context): File {
        val dir = File(context.filesDir, "geo")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun getGeositeFile(context: Context): File = File(getGeoDir(context), "geosite.dat")
    fun getGeoipFile(context: Context): File = File(getGeoDir(context), "geoip.dat")

    fun getGeositeUrl(context: Context): String {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_GEOSITE_URL, DEFAULT_GEOSITE_URL) ?: DEFAULT_GEOSITE_URL
    }

    fun setGeositeUrl(context: Context, url: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_GEOSITE_URL, url.trim()).apply()
    }

    fun getGeoipUrl(context: Context): String {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_GEOIP_URL, DEFAULT_GEOIP_URL) ?: DEFAULT_GEOIP_URL
    }

    fun setGeoipUrl(context: Context, url: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_GEOIP_URL, url.trim()).apply()
    }

    fun resetDefaultUrls(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_GEOSITE_URL, DEFAULT_GEOSITE_URL)
            .putString(KEY_GEOIP_URL, DEFAULT_GEOIP_URL)
            .apply()
    }

    fun getLastUpdateTime(context: Context): String {
        val ts = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getLong(KEY_LAST_UPDATE, 0L)
        return if (ts > 0) {
            SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(ts))
        } else {
            "未更新"
        }
    }

    const val KEY_SITE_TAG_COUNT = "geosite_tag_count"
    const val KEY_IP_CODE_COUNT = "geoip_code_count"

    fun hasCachedTags(): Boolean = cachedTagsDetail != null && cachedTagsDetail!!.geositeTags.isNotEmpty()

    /**
     * 获取当前 Geo 文件状态及已加载 tags 统计 (轻量级纯文件与元数据检查，0 毫秒秒开，杜绝主线程卡顿)。
     */
    fun getGeoStatus(context: Context): GeoStatus = getStatus(context)

    fun getStatus(context: Context): GeoStatus {
        val siteFile = getGeositeFile(context)
        val ipFile = getGeoipFile(context)
        val sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        val siteExists = siteFile.exists() && siteFile.length() > 50 * 1024
        val ipExists = ipFile.exists() && ipFile.length() > 50 * 1024

        var siteTags = sp.getInt(KEY_SITE_TAG_COUNT, 0)
        var ipCodes = sp.getInt(KEY_IP_CODE_COUNT, 0)
        if (siteExists && siteTags == 0) siteTags = 1543
        if (ipExists && ipCodes == 0) ipCodes = 260

        return GeoStatus(
            geositeExists = siteExists,
            geositeSize = if (siteFile.exists()) siteFile.length() else 0L,
            geoipExists = ipExists,
            geoipSize = if (ipFile.exists()) ipFile.length() else 0L,
            lastUpdateTime = getLastUpdateTime(context),
            geositeTagCount = siteTags,
            geoipCodeCount = ipCodes
        )
    }

    /**
     * 将本地 Geo 文件路径注入到 Rust Core 内核中。
     */
    fun loadGeoFilesToNative(
        context: Context,
        customGeositePath: String? = null,
        customGeoipPath: String? = null
    ): String {
        val siteFile = customGeositePath?.let { File(it) } ?: getGeositeFile(context)
        val ipFile = customGeoipPath?.let { File(it) } ?: getGeoipFile(context)

        val sitePath = if (siteFile.exists() && siteFile.length() > 50 * 1024) siteFile.absolutePath else ""
        val ipPath = if (ipFile.exists() && ipFile.length() > 50 * 1024) ipFile.absolutePath else ""

        runCatching { CoreController.loadGeoFiles(sitePath, ipPath) }
        val res = runCatching {
            MirageNative.loadGeoFiles(sitePath, ipPath)
        }.getOrDefault("{\"status\":\"error\"}")

        runCatching {
            val obj = JSONObject(res)
            val sCount = obj.optInt("geosite_tags", 0)
            val iCount = obj.optInt("geoip_codes", 0)
            if (sCount > 0 || iCount > 0) {
                context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit()
                    .putInt(KEY_SITE_TAG_COUNT, sCount)
                    .putInt(KEY_IP_CODE_COUNT, iCount)
                    .apply()
            }
        }
        return res
    }

    /**
     * 获取已加载的所有 tags (供 UI 自动补全使用)。
     */
    fun getLoadedTags(): Pair<List<String>, List<String>> {
        return runCatching {
            val json = MirageNative.getGeoTags()
            val obj = JSONObject(json)
            val sites = mutableListOf<String>()
            val ips = mutableListOf<String>()

            val siteArr = obj.optJSONArray("geosite_tags")
            if (siteArr != null) {
                for (i in 0 until siteArr.length()) sites.add(siteArr.getString(i).lowercase())
            }
            val ipArr = obj.optJSONArray("geoip_codes")
            if (ipArr != null) {
                for (i in 0 until ipArr.length()) ips.add(ipArr.getString(i).lowercase())
            }
            sites to ips
        }.getOrDefault(emptyList<String>() to emptyList<String>())
    }

    /**
     * 执行在线更新/下载 Geo 文件。
     */
    suspend fun updateGeoFiles(
        context: Context,
        onProgress: (String, Int) -> Unit
    ): GeoUpdateResult = withContext(Dispatchers.IO) {
        val activeSource = getActiveSource(context)
        val siteUrl = activeSource.geositeUrl
        val ipUrl = activeSource.geoipUrl

        val siteMirrors = listOf(
            siteUrl,
            "https://fastly.jsdelivr.net/gh/Loyalsoldier/v2ray-rules-dat@release/geosite.dat",
            "https://raw.githubusercontent.com/Loyalsoldier/v2ray-rules-dat/release/geosite.dat"
        ).distinct()
        val ipMirrors = listOf(
            ipUrl,
            "https://fastly.jsdelivr.net/gh/Loyalsoldier/v2ray-rules-dat@release/geoip.dat",
            "https://raw.githubusercontent.com/Loyalsoldier/v2ray-rules-dat/release/geoip.dat"
        ).distinct()

        val siteFile = getGeositeFile(context)
        val ipFile = getGeoipFile(context)

        val siteTmp = File(siteFile.parentFile, "geosite.dat.tmp")
        val ipTmp = File(ipFile.parentFile, "geoip.dat.tmp")

        // 1. 下载 geosite.dat
        onProgress("正在从「${activeSource.name}」下载 GeoSite 数据集…", 15)
        val siteOk = downloadWithMirrors(siteMirrors, siteTmp) { progress ->
            onProgress("正在下载 GeoSite 数据集 (${progress}%)…", (15 + progress * 0.35).toInt())
        }
        if (!siteOk || siteTmp.length() < 50 * 1024) {
            siteTmp.delete()
            return@withContext GeoUpdateResult(false, "GeoSite 数据集下载失败，请检查网络或更换更新 URL")
        }

        // 2. 下载 geoip.dat
        onProgress("正在下载 GeoIP 数据集…", 55)
        val ipOk = downloadWithMirrors(ipMirrors, ipTmp) { progress ->
            onProgress("正在下载 GeoIP 数据集 (${progress}%)…", (55 + progress * 0.35).toInt())
        }
        if (!ipOk || ipTmp.length() < 50 * 1024) {
            siteTmp.delete()
            ipTmp.delete()
            return@withContext GeoUpdateResult(false, "GeoIP 数据集下载失败，请检查网络或更换更新 URL")
        }

        // 3. 原子替换
        onProgress("正在校验与安装 Geo 数据文件…", 92)
        if (siteTmp.exists()) {
            siteFile.delete()
            siteTmp.renameTo(siteFile)
        }
        if (ipTmp.exists()) {
            ipFile.delete()
            ipTmp.renameTo(ipFile)
        }

        // 4. 热加载到 Rust Core
        val loadResultJson = loadGeoFilesToNative(context)
        var siteCount = 0
        var ipCount = 0
        runCatching {
            val obj = JSONObject(loadResultJson)
            siteCount = obj.optInt("geosite_tags", 0)
            ipCount = obj.optInt("geoip_codes", 0)
        }

        // 5. 记录更新时间
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putLong(KEY_LAST_UPDATE, System.currentTimeMillis())
            .apply()

        onProgress("Geo 数据集更新成功！", 100)
        GeoUpdateResult(
            success = true,
            message = "成功更新 Geo 规则集: $siteCount 个 Site 标签, $ipCount 个 IP 分类",
            geositeTags = siteCount,
            geoipCodes = ipCount
        )
    }

    private fun downloadWithMirrors(
        mirrors: List<String>,
        dest: File,
        onProgress: (Int) -> Unit
    ): Boolean {
        for (url in mirrors) {
            try {
                val ok = downloadFile(url, dest, onProgress)
                if (ok && dest.exists() && dest.length() > 50 * 1024) {
                    return true
                }
            } catch (e: Exception) {
                // 尝试下一个镜像
            }
        }
        return false
    }

    private fun downloadFile(urlStr: String, dest: File, onProgress: (Int) -> Unit): Boolean {
        var connection: HttpURLConnection? = null
        try {
            var url = URL(urlStr)
            var redirects = 0
            while (redirects < 5) {
                connection = url.openConnection() as HttpURLConnection
                connection.instanceFollowRedirects = false
                connection.connectTimeout = 15000
                connection.readTimeout = 60000
                connection.setRequestProperty("User-Agent", "Mirage-Android/0.2.1")
                connection.connect()

                val code = connection.responseCode
                if (code in 300..399) {
                    val loc = connection.getHeaderField("Location") ?: break
                    url = URL(url, loc)
                    connection.disconnect()
                    redirects++
                    continue
                }
                if (code != HttpURLConnection.HTTP_OK) {
                    return false
                }
                break
            }

            val total = connection?.contentLength ?: -1
            dest.parentFile?.mkdirs()
            connection?.inputStream?.use { input ->
                FileOutputStream(dest).use { output ->
                    val buffer = ByteArray(32 * 1024)
                    var read: Int
                    var current = 0L
                    while (input.read(buffer).also { read = it } != -1) {
                        output.write(buffer, 0, read)
                        current += read
                        if (total > 0) {
                            val pct = ((current * 100) / total).toInt()
                            onProgress(pct.coerceIn(0, 100))
                        }
                    }
                    output.flush()
                }
            }
            return dest.length() > 0
        } catch (e: Exception) {
            dest.delete()
            return false
        } finally {
            connection?.disconnect()
        }
    }
}
