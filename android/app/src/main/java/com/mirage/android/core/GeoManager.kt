package com.mirage.android.core

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Geo 文件管理器 (geosite.dat 与 geoip.dat)。
 * 支持自定义更新 URL、断点多镜像下载、完整性校验、原子更新与热加载。
 */
object GeoManager {

    private const val TAG = "GeoManager"
    private const val PREFS = "mirage_geo_config"
    private const val KEY_ACTIVE_SOURCE_ID = "active_source_id"
    private const val KEY_CUSTOM_SOURCES = "custom_sources_json"
    private const val KEY_AUTO_UPDATE = "auto_update_interval"
    private const val KEY_GEOSITE_URL = "geosite_url"
    private const val KEY_GEOIP_URL = "geoip_url"
    private const val KEY_LAST_UPDATE = "last_update_time"
    private const val KEY_VERIFIED = "last_update_verified"

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
        for (s in customList.filter { !it.isBuiltin && it.geositeUrl.startsWith("https://", ignoreCase = true) && it.geoipUrl.startsWith("https://", ignoreCase = true) }) {
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

    /**
     * 更新互斥。
     *
     * 两次 updateGeoFiles 并发时，它们共用同一组固定的 .tmp 路径：B 校验完
     * geosite.dat.tmp 后 A 把同一文件截断重写，B 的 Files.move 装的是 A 的字节
     * 却报告 verified = true —— 完整性校验被整个架空。
     *
     * 并发是真实可达的：checkGeoInitialization 在 onCreate 里发起且无重入保护，
     * 旋转屏幕即可在首次下载途中再进一次；用户也可以同时在 Geo 资产页点更新。
     */
    private val updateMutex = Mutex()

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
        val geoipCodeCount: Int,
        /** 当前在盘数据是否通过 SHA-256 校验。false 需常驻可见，不能只靠一次性 Toast。 */
        val verified: Boolean = true
    ) {
        /**
         * 两个文件都就绪才算就绪。
         *
         * 用 || 时半装状态 (只有一个文件) 会被判为就绪, 从而抑制
         * checkGeoInitialization 的首启重试, 让用户停在一个残缺的规则集上。
         */
        val isReady: Boolean get() = geositeExists && geoipExists
        val displaySummary: String
            get() = if (isReady) {
                val mark = if (verified) "" else "（未校验）"
                "Geo: 已就绪$mark (${geositeTagCount} Sites / ${geoipCodeCount} IPs) · $lastUpdateTime"
            } else {
                "Geo: 未下载 (使用系统内置 CN 白名单)"
            }
    }

    data class GeoUpdateResult(
        val success: Boolean,
        val message: String,
        val geositeTags: Int = 0,
        val geoipCodes: Int = 0,
        /**
         * 本次产物是否通过 SHA-256 完整性校验。
         *
         * 内置源必定为 true（校验失败或拿不到摘要时整体失败，见 [downloadWithMirrors]）；
         * 用户自定义源在上游未提供 `.sha256sum` 时为 false —— 此时产物未经校验，
         * 调用方必须向用户明示。
         */
        val verified: Boolean = true
    )

    /** 镜像全部失败时的原因，决定给用户的说法。 */
    internal enum class FailureReason {
        /** 下载本身失败 (网络、404、体积不足)。 */
        DOWNLOAD,
        /** 摘要拿到了但对不上 —— 疑似篡改或镜像内容不一致。 */
        MISMATCH,
        /** 上游确实不发布摘要 (404)。 */
        DIGEST_ABSENT,
        /** 摘要重试后仍不可达 —— 网络或镜像问题。 */
        DIGEST_UNAVAILABLE,
    }

    /** 单个镜像的下载结果。[verified] 为 false 表示产物未经 SHA-256 校验。 */
    private data class DownloadOutcome(
        val ok: Boolean,
        val verified: Boolean,
        val failure: FailureReason = FailureReason.DOWNLOAD,
    )

    /** 把失败原因翻译成用户可读的说法。 */
    internal fun describeFailure(what: String, reason: FailureReason): String = when (reason) {
        FailureReason.DOWNLOAD -> "$what 下载失败，请检查网络或更换更新 URL"
        FailureReason.MISMATCH -> "$what 完整性校验不通过（内容与上游摘要不符，疑似被篡改或镜像不一致），已拒绝安装"
        FailureReason.DIGEST_ABSENT -> "$what 的上游未提供 SHA-256 摘要，内置源要求强制校验，已拒绝安装"
        FailureReason.DIGEST_UNAVAILABLE -> "$what 无法获取 SHA-256 摘要（网络或镜像暂时不可用），已拒绝安装；请稍后重试"
    }

    /**
     * 该 URL 是否属于内置源（含内置回退镜像），内置源强制要求 SHA-256 校验。
     *
     * 判定依据是完整 URL 逐字节相等，不做前缀、域名或大小写折叠匹配 —— 自定义源
     * 即使指向同一域名也不会被误认为内置源而继承其信任级别。
     *
     * 刻意不用 ignoreCase: GitHub 与 jsDelivr 的路径是大小写敏感的, 折叠比较会把
     * 拼写变体 (如小写 loyalsoldier) 判成内置源, 进而强制走校验、两个 URL 双双 404,
     * 变成本可避免的硬失败。
     *
     * 方向性说明: 这里 true 是严格分支 (必须校验), false 是宽松分支。因此假阴性
     * 才是危险方向, 而它不可能发生 —— 镜像列表里的内置 URL 与本集合来自同一批
     * 编译期字面量, 必然逐字节相等。大小写变体只可能出现在用户自建的自定义源里,
     * 那本来就属于宽松分支。
     */
    internal fun isBuiltinUrl(url: String): Boolean {
        val u = url.trim()
        return u in BUILTIN_MIRROR_URLS
    }

    /** 内置源与内置回退镜像的全部 URL。 */
    private val BUILTIN_MIRROR_URLS: Set<String> by lazy {
        BUILTIN_SOURCES.flatMap { listOf(it.geositeUrl, it.geoipUrl) }.toSet() + setOf(
            "https://fastly.jsdelivr.net/gh/Loyalsoldier/v2ray-rules-dat@release/geosite.dat",
            "https://fastly.jsdelivr.net/gh/Loyalsoldier/v2ray-rules-dat@release/geoip.dat",
            "https://raw.githubusercontent.com/Loyalsoldier/v2ray-rules-dat/release/geosite.dat",
            "https://raw.githubusercontent.com/Loyalsoldier/v2ray-rules-dat/release/geoip.dat"
        )
    }

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
        val trimmed = url.trim()
        if (!trimmed.startsWith("https://", ignoreCase = true)) {
            Log.w(TAG, "拒绝非 HTTPS 的 GeoSite URL: $trimmed")
            return
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_GEOSITE_URL, trimmed).apply()
    }

    fun getGeoipUrl(context: Context): String {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_GEOIP_URL, DEFAULT_GEOIP_URL) ?: DEFAULT_GEOIP_URL
    }

    fun setGeoipUrl(context: Context, url: String) {
        val trimmed = url.trim()
        if (!trimmed.startsWith("https://", ignoreCase = true)) {
            Log.w(TAG, "拒绝非 HTTPS 的 GeoIP URL: $trimmed")
            return
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_GEOIP_URL, trimmed).apply()
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
            geoipCodeCount = ipCodes,
            // 默认 true: 老版本升级上来时盘上数据来自内置源, 按已校验处理
            verified = sp.getBoolean(KEY_VERIFIED, true)
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
    /**
     * 执行在线更新/下载 Geo 文件。
     *
     * @param allowUnverified 是否允许安装未通过 SHA-256 校验的产物。
     *   默认 **false**：自动/后台路径绝不静默把未校验数据装进持有 TUN 的 :core 进程。
     *   仅当用户在前台明确发起更新、且能看到结果提示时才传 true。
     */
    suspend fun updateGeoFiles(
        context: Context,
        allowUnverified: Boolean = false,
        onProgress: (String, Int) -> Unit
    ): GeoUpdateResult = withContext(Dispatchers.IO) {
        // 已有更新在跑就直接返回, 不排队再下一遍 (旋转屏幕重入 checkGeoInitialization
        // 是最常见的触发方式)。
        if (updateMutex.isLocked) {
            Log.i(TAG, "已有 Geo 更新在进行中，忽略本次请求")
            return@withContext GeoUpdateResult(false, "Geo 更新已在进行中")
        }
        updateMutex.withLock { updateGeoFilesLocked(context, allowUnverified, onProgress) }
    }

    private suspend fun updateGeoFilesLocked(
        context: Context,
        allowUnverified: Boolean,
        onProgress: (String, Int) -> Unit
    ): GeoUpdateResult {
        val activeSource = getActiveSource(context)
        val siteUrl = activeSource.geositeUrl
        val ipUrl = activeSource.geoipUrl

        val siteMirrors = listOf(
            siteUrl,
            "https://fastly.jsdelivr.net/gh/Loyalsoldier/v2ray-rules-dat@release/geosite.dat",
            "https://raw.githubusercontent.com/Loyalsoldier/v2ray-rules-dat/release/geosite.dat"
        ).filter { it.startsWith("https://", ignoreCase = true) }.distinct()
        val ipMirrors = listOf(
            ipUrl,
            "https://fastly.jsdelivr.net/gh/Loyalsoldier/v2ray-rules-dat@release/geoip.dat",
            "https://raw.githubusercontent.com/Loyalsoldier/v2ray-rules-dat/release/geoip.dat"
        ).filter { it.startsWith("https://", ignoreCase = true) }.distinct()

        val siteFile = getGeositeFile(context)
        val ipFile = getGeoipFile(context)

        // 每次运行用独立的临时文件。固定名会让并发的两次运行写同一个 inode ——
        // 即便有互斥, 上次异常退出残留的 .tmp 也可能被误当成本次产物。
        val geoDir = siteFile.parentFile ?: getGeoDir(context)
        val siteTmp = File.createTempFile("geosite", ".dat.tmp", geoDir)
        val ipTmp = File.createTempFile("geoip", ".dat.tmp", geoDir)

        // 1. 下载 geosite.dat
        onProgress("正在从「${activeSource.name}」下载 GeoSite 数据集…", 15)
        val siteOutcome = downloadWithMirrors(siteMirrors, siteTmp) { progress ->
            onProgress("正在下载 GeoSite 数据集 (${progress}%)…", (15 + progress * 0.35).toInt())
        }
        if (!siteOutcome.ok || siteTmp.length() < 50 * 1024) {
            siteTmp.delete()
            return GeoUpdateResult(false, describeFailure("GeoSite 数据集", siteOutcome.failure))
        }

        // 2. 下载 geoip.dat
        onProgress("正在下载 GeoIP 数据集…", 55)
        val ipOutcome = downloadWithMirrors(ipMirrors, ipTmp) { progress ->
            onProgress("正在下载 GeoIP 数据集 (${progress}%)…", (55 + progress * 0.35).toInt())
        }
        if (!ipOutcome.ok || ipTmp.length() < 50 * 1024) {
            siteTmp.delete()
            ipTmp.delete()
            return GeoUpdateResult(false, describeFailure("GeoIP 数据集", ipOutcome.failure))
        }

        // 两个数据集里只要有一个未经校验，整体即视为未校验
        val verified = siteOutcome.verified && ipOutcome.verified

        // 拒绝在自动路径上安装未校验产物。必须在原子替换之前判断 —— 一旦 move 完成
        // 就会被 loadGeoFilesToNative 热加载进 :core，再回滚已无意义。
        if (!verified && !allowUnverified) {
            siteTmp.delete()
            ipTmp.delete()
            Log.w(TAG, "产物未通过 SHA-256 校验且当前路径不允许未校验安装，已丢弃")
            return GeoUpdateResult(
                success = false,
                message = "该数据源未提供可校验的 SHA-256 摘要，已拒绝自动安装。" +
                    "如确认信任该源，请在「Geo 资产」页手动发起更新并确认。",
                verified = false
            )
        }

        // 3. 安装。
        //
        // 单个 Files.move 对目标是原子的, 但这里要装两个文件, 两次 move 之间失败会
        // 留下「新 geosite + 旧 geoip」的错配组合。所以先把旧文件挪到 .bak,
        // 任一步失败就整体回滚, 保证盘上要么全新要么全旧。
        onProgress("正在校验与安装 Geo 数据文件…", 92)
        val siteBak = File(geoDir, "geosite.dat.bak")
        val ipBak = File(geoDir, "geoip.dat.bak")
        try {
            if (siteFile.exists()) Files.move(siteFile.toPath(), siteBak.toPath(), StandardCopyOption.REPLACE_EXISTING)
            if (ipFile.exists()) Files.move(ipFile.toPath(), ipBak.toPath(), StandardCopyOption.REPLACE_EXISTING)

            Files.move(siteTmp.toPath(), siteFile.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            Files.move(ipTmp.toPath(), ipFile.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)

            siteBak.delete()
            ipBak.delete()
        } catch (e: Exception) {
            Log.e(TAG, "Geo 数据文件安装失败, 回滚: ${e.message}", e)
            // 回滚: 把还在 .bak 的旧文件放回去。已成功 move 的新文件会被覆盖。
            runCatching { if (siteBak.exists()) Files.move(siteBak.toPath(), siteFile.toPath(), StandardCopyOption.REPLACE_EXISTING) }
            runCatching { if (ipBak.exists()) Files.move(ipBak.toPath(), ipFile.toPath(), StandardCopyOption.REPLACE_EXISTING) }
            return GeoUpdateResult(false, "安装 Geo 数据文件失败: ${e.message}")
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
            .putBoolean(KEY_VERIFIED, verified)
            .apply()

        onProgress(if (verified) "Geo 数据集更新成功！" else "Geo 数据集已更新（未校验）", 100)
        return GeoUpdateResult(
            success = true,
            message = buildString {
                append("成功更新 Geo 规则集: $siteCount 个 Site 标签, $ipCount 个 IP 分类")
                if (!verified) {
                    append("\n⚠️ 该自定义源未提供 SHA-256 摘要，本次产物未经完整性校验")
                }
            },
            geositeTags = siteCount,
            geoipCodes = ipCount,
            verified = verified
        )
    }

    /**
     * 依次尝试各镜像下载到 [dest]，成功即返回。
     *
     * 完整性策略：内置源（[isBuiltinUrl]）强制 SHA-256 校验，拿不到摘要或校验不过
     * 一律丢弃产物并换下一个镜像；自定义源允许在上游无摘要时放行，但结果标记
     * `verified = false`。
     */
    private fun downloadWithMirrors(
        mirrors: List<String>,
        dest: File,
        onProgress: (Int) -> Unit
    ): DownloadOutcome {
        // 记录最后一次失败原因, 用于给用户一个准确的说法 ——
        // 「疑似篡改」和「拿不到摘要」必须可区分, 否则一次真实攻击与一个糟糕的
        // CDN 日在界面上长得一模一样。
        var lastFailure = FailureReason.DOWNLOAD
        for (url in mirrors) {
            if (!url.startsWith("https://", ignoreCase = true)) continue
            try {
                val ok = downloadFile(url, dest, onProgress)
                if (ok && dest.exists() && dest.length() > 50 * 1024) {
                    val requireSha = isBuiltinUrl(url)
                    when (val digest = fetchSha256("$url.sha256sum")) {
                        is DigestResult.Found -> {
                            val actual = computeSha256(dest)
                            if (!actual.equals(digest.hex, ignoreCase = true)) {
                                Log.w(TAG, "SHA-256 不匹配 ($url): 期望=${digest.hex}, 实际=$actual, 丢弃产物")
                                dest.delete()
                                lastFailure = FailureReason.MISMATCH
                                continue
                            }
                            Log.d(TAG, "SHA-256 校验通过: $actual")
                            return DownloadOutcome(ok = true, verified = true)
                        }
                        DigestResult.Absent -> {
                            // 上游确实不发布摘要。内置源 fail-closed: 能阻断 .dat 的对手
                            // 同样能阻断 .sha256sum, 放行等于把校验降级掉。
                            if (requireSha) {
                                Log.w(TAG, "内置源上游无 SHA-256 摘要 ($url)，拒绝该镜像")
                                dest.delete()
                                lastFailure = FailureReason.DIGEST_ABSENT
                                continue
                            }
                            Log.w(TAG, "自定义源未提供 SHA-256 摘要 ($url)，产物未经校验")
                            return DownloadOutcome(ok = true, verified = false)
                        }
                        DigestResult.Unavailable -> {
                            // 重试后仍拿不到 —— 网络/镜像问题, 不是「上游不提供」。
                            if (requireSha) {
                                Log.w(TAG, "内置源摘要暂不可达 ($url)，拒绝该镜像")
                                dest.delete()
                                lastFailure = FailureReason.DIGEST_UNAVAILABLE
                                continue
                            }
                            Log.w(TAG, "自定义源摘要暂不可达 ($url)，产物未经校验")
                            return DownloadOutcome(ok = true, verified = false)
                        }
                    }
                }
                lastFailure = FailureReason.DOWNLOAD
            } catch (e: Exception) {
                Log.w(TAG, "镜像下载失败 $url: ${e.message}")
                runCatching { dest.delete() }
                lastFailure = FailureReason.DOWNLOAD
            }
        }
        return DownloadOutcome(ok = false, verified = false, failure = lastFailure)
    }

    private fun downloadFile(urlStr: String, dest: File, onProgress: (Int) -> Unit): Boolean {
        if (!urlStr.startsWith("https://", ignoreCase = true)) return false
        var connection: HttpURLConnection? = null
        try {
            var url = URL(urlStr)
            var redirects = 0
            var ok = false
            while (redirects < 5) {
                if (!url.protocol.equals("https", ignoreCase = true)) {
                    Log.w(TAG, "重定向到非 HTTPS 地址，拒绝: $url")
                    return false
                }
                connection = url.openConnection() as HttpURLConnection
                connection.instanceFollowRedirects = false
                connection.connectTimeout = 15000
                connection.readTimeout = 60000
                connection.setRequestProperty("User-Agent", "Mirage-Android/0.2.1")
                connection.connect()

                val code = connection.responseCode
                if (code in 300..399) {
                    // 没有 Location 的 3xx 无处可跳。此前这里 break, 带着活着的 3xx
                    // 连接掉到下面, 把重定向响应体当成产物写进 dest。
                    val loc = connection.getHeaderField("Location") ?: return false
                    url = URL(url, loc)
                    // 重定向目标同样必须是 https, 否则可被降级到明文
                    if (!url.protocol.equals("https", ignoreCase = true)) return false
                    connection.disconnect()
                    connection = null
                    redirects++
                    continue
                }
                if (code != HttpURLConnection.HTTP_OK) {
                    return false
                }
                ok = true
                break
            }
            // 循环因次数耗尽而退出: 此时手上只有一个已 disconnect 的 3xx 连接
            if (!ok) return false

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

    fun computeSha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { fis ->
            val buf = ByteArray(64 * 1024)
            var n: Int
            while (fis.read(buf).also { n = it } != -1) {
                digest.update(buf, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * 摘要获取结果。
     *
     * 必须区分「上游确实不提供摘要」与「这次没拿到」—— 两者在 fail-closed 下
     * 都会拒绝镜像，但含义、重试策略和给用户的说法完全不同。把它们折叠成
     * `String?` 会让一次瞬时 5xx 被当成「该源不发布摘要」，进而静默换源。
     */
    internal sealed interface DigestResult {
        data class Found(val hex: String) : DigestResult
        /** 上游明确没有这个文件 (404/410)。 */
        object Absent : DigestResult
        /** 暂时拿不到: 5xx、超时、连接失败、响应不含合法摘要。 */
        object Unavailable : DigestResult
    }

    /** 摘要获取的重试次数。GitHub release 资产 CDN 实测会出现连续数次 5xx 后自愈。 */
    private const val SHA_FETCH_ATTEMPTS = 3
    /** 摘要响应读取上限 (字符)。合法内容是 64 hex + 文件名, 远小于此。 */
    private const val MAX_DIGEST_CHARS = 4096

    /**
     * 获取 `<artifact>.sha256sum`，瞬时失败自动重试。
     *
     * **能力边界（重要，勿误读为真实性保证）**：摘要与产物来自同一来源，
     * 因此本校验只能防住「产物被截断 / 下载损坏 / 镜像内容与上游不一致 / 单侧被阻断」，
     * **防不住控制了该来源的攻击者** —— 他可以同时提供伪造的产物与匹配的摘要。
     *
     * 曾考虑跨源取摘要（产物走 fastly、摘要走 raw.githubusercontent）来获得两条
     * 独立信任路径，但 `raw.githubusercontent.com` 正是目标网络中被封锁的 host：
     * 在墙内会让 fail-closed 必然触发，把可用性问题重新制造出来。故不采用。
     *
     * 要获得真实性，正确做法是内置签名公钥（Ed25519/minisign）并校验摘要的签名，
     * 那需要上游配合发布签名文件。记录在 docs/AUDIT_HANDOFF.md 的后续项里。
     *
     * 404/410 直接判定 [DigestResult.Absent] 且不重试 —— 上游不提供，重试无意义。
     */
    private fun fetchSha256(shaUrl: String): DigestResult {
        if (!shaUrl.startsWith("https://", ignoreCase = true)) return DigestResult.Unavailable
        var last: DigestResult = DigestResult.Unavailable
        for (attempt in 1..SHA_FETCH_ATTEMPTS) {
            last = fetchSha256Once(shaUrl)
            if (last is DigestResult.Found || last is DigestResult.Absent) return last
            if (attempt < SHA_FETCH_ATTEMPTS) {
                Log.w(TAG, "摘要暂时不可达 ($shaUrl)，第 $attempt 次重试")
                runCatching { Thread.sleep(600L * attempt) }
            }
        }
        return last
    }

    private fun fetchSha256Once(shaUrl: String): DigestResult {
        var conn: HttpURLConnection? = null
        return try {
            var url = URL(shaUrl)
            var redirects = 0
            while (redirects < 5) {
                if (!url.protocol.equals("https", ignoreCase = true)) return DigestResult.Unavailable
                conn = url.openConnection() as HttpURLConnection
                conn.instanceFollowRedirects = false
                conn.connectTimeout = 10000
                conn.readTimeout = 15000
                conn.setRequestProperty("User-Agent", "Mirage-Android/0.2.1")
                conn.connect()
                val code = conn.responseCode
                if (code in 300..399) {
                    val loc = conn.getHeaderField("Location") ?: return DigestResult.Unavailable
                    url = URL(url, loc)
                    conn.disconnect()
                    conn = null
                    redirects++
                    continue
                }
                if (code == HttpURLConnection.HTTP_NOT_FOUND || code == HttpURLConnection.HTTP_GONE) {
                    return DigestResult.Absent
                }
                if (code == HttpURLConnection.HTTP_OK) {
                    // 摘要文件只有几十字节。设上限, 避免恶意镜像用超大响应体撑爆内存。
                    val buf = CharArray(MAX_DIGEST_CHARS)
                    val n = conn.inputStream.bufferedReader().use { it.read(buf, 0, MAX_DIGEST_CHARS) }
                    if (n <= 0) return DigestResult.Unavailable
                    val text = String(buf, 0, n).trim()
                    val match = Regex("^[a-fA-F0-9]{64}").find(text)
                    return match?.value?.lowercase()?.let { DigestResult.Found(it) }
                        ?: DigestResult.Unavailable
                }
                return DigestResult.Unavailable
            }
            DigestResult.Unavailable
        } catch (e: Exception) {
            Log.w(TAG, "摘要请求失败 ($shaUrl): ${e.message}")
            DigestResult.Unavailable
        } finally {
            runCatching { conn?.disconnect() }
        }
    }
}
