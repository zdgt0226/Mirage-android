package com.mirage.android.core

import android.content.Context
import com.mirage.android.data.model.Rule
import com.mirage.android.data.model.RuleCondition
import org.json.JSONArray
import org.json.JSONObject

/**
 * 自定义分流规则存储。
 * 支持单条件规则与复合多条件规则 (AND / OR 逻辑算子)。
 */
object RuleStore {

    private const val PREFS = "mirage_rules"
    private const val KEY_RULES = "rules_json"
    private const val KEY_DEFAULT = "default_action"  // "proxy" | "direct" | "block"

    /** 模板 1: 🌟 经典国内外分流 + 去广告 (推荐) */
    fun createDefaultRules(): List<Rule> {
        return listOf(
            Rule(
                id = "builtin_lan_captive",
                name = "局域网与连网认证直连 (LAN & Captive Portal)",
                enabled = true,
                action = "direct",
                logic = "OR",
                conditions = listOf(
                    RuleCondition("geoip", "private"),
                    RuleCondition("domain_suffix", "local"),
                    RuleCondition("domain_suffix", "lan"),
                    RuleCondition("domain_suffix", "internal"),
                    RuleCondition("domain_keyword", "connectivitycheck"),
                    RuleCondition("domain_exact", "captive.apple.com")
                )
            ),
            Rule(
                id = "builtin_adblock",
                name = "广告与隐私追踪拦截 (AdBlock & Analytics)",
                enabled = true,
                action = "block",
                logic = "OR",
                conditions = listOf(
                    RuleCondition("geosite", "category-ads-all"),
                    RuleCondition("domain_suffix", "doubleclick.net"),
                    RuleCondition("domain_suffix", "googleadservices.com"),
                    RuleCondition("domain_suffix", "google-analytics.com"),
                    RuleCondition("domain_suffix", "pglstatp-toutiao.com"),
                    RuleCondition("domain_suffix", "gdt.qq.com"),
                    RuleCondition("domain_suffix", "mobads.baidu.com"),
                    RuleCondition("domain_suffix", "adukwai.com")
                )
            ),
            Rule(
                id = "builtin_ai_proxy",
                name = "海外 AI 专区加速 (OpenAI / Claude / Gemini / Copilot / Grok)",
                enabled = true,
                action = "proxy",
                logic = "OR",
                conditions = listOf(
                    RuleCondition("geosite", "openai"),
                    RuleCondition("geosite", "anthropic"),
                    RuleCondition("geosite", "google-gemini"),
                    RuleCondition("domain_suffix", "openai.com"),
                    RuleCondition("domain_suffix", "chatgpt.com"),
                    RuleCondition("domain_suffix", "oaistatic.com"),
                    RuleCondition("domain_suffix", "claude.ai"),
                    RuleCondition("domain_suffix", "anthropic.com"),
                    RuleCondition("domain_suffix", "gemini.google.com"),
                    RuleCondition("domain_suffix", "x.ai"),
                    RuleCondition("domain_suffix", "grok.com")
                )
            ),
            Rule(
                id = "builtin_streaming_social",
                name = "海外流媒体与社交平台 (YouTube / Netflix / Telegram / X / TikTok / Spotify)",
                enabled = true,
                action = "proxy",
                logic = "OR",
                conditions = listOf(
                    RuleCondition("geosite", "youtube"),
                    RuleCondition("geosite", "netflix"),
                    RuleCondition("geosite", "telegram"),
                    RuleCondition("geosite", "twitter"),
                    RuleCondition("geosite", "tiktok"),
                    RuleCondition("geosite", "spotify"),
                    RuleCondition("geosite", "github"),
                    RuleCondition("domain_suffix", "youtube.com"),
                    RuleCondition("domain_suffix", "googlevideo.com"),
                    RuleCondition("domain_suffix", "ytimg.com"),
                    RuleCondition("domain_suffix", "netflix.com"),
                    RuleCondition("domain_suffix", "telegram.org"),
                    RuleCondition("domain_suffix", "t.me"),
                    RuleCondition("domain_suffix", "twitter.com"),
                    RuleCondition("domain_suffix", "x.com"),
                    RuleCondition("domain_suffix", "tiktok.com"),
                    RuleCondition("domain_suffix", "github.com")
                )
            ),
            Rule(
                id = "builtin_cn_geosite",
                name = "国内域名直连 (GeoSite CN)",
                enabled = true,
                action = "direct",
                conditions = listOf(RuleCondition("geosite", "cn"))
            ),
            Rule(
                id = "builtin_cn_geoip",
                name = "国内 IP 直连 (GeoIP CN)",
                enabled = true,
                action = "direct",
                conditions = listOf(RuleCondition("geoip", "cn"))
            )
        )
    }

    /** 模板 2: 🇨🇳 白名单模式 (极速省流: 国内直连，其余所有未知站点默认走代理) */
    fun createWhitelistRules(): List<Rule> {
        return listOf(
            Rule(
                id = "wl_lan",
                name = "局域网私有 IP 直连 (GeoIP Private)",
                enabled = true,
                action = "direct",
                conditions = listOf(RuleCondition("geoip", "private"))
            ),
            Rule(
                id = "wl_adblock",
                name = "广告拦截 (GeoSite Ads)",
                enabled = true,
                action = "block",
                conditions = listOf(RuleCondition("geosite", "category-ads-all"))
            ),
            Rule(
                id = "wl_cn_domains",
                name = "国内域名直连 (GeoSite CN)",
                enabled = true,
                action = "direct",
                conditions = listOf(RuleCondition("geosite", "cn"))
            ),
            Rule(
                id = "wl_cn_ips",
                name = "国内 IP 直连 (GeoIP CN)",
                enabled = true,
                action = "direct",
                conditions = listOf(RuleCondition("geoip", "cn"))
            )
        )
    }

    /** 模板 3: 🛡️ 黑名单模式 (GFWList 模式: 被阻断域名走代理，其余所有站点默认直连) */
    fun createBlacklistRules(): List<Rule> {
        return listOf(
            Rule(
                id = "bl_adblock",
                name = "广告拦截 (GeoSite Ads)",
                enabled = true,
                action = "block",
                conditions = listOf(RuleCondition("geosite", "category-ads-all"))
            ),
            Rule(
                id = "bl_ai",
                name = "AI 服务专区代理 (OpenAI / Claude / Gemini)",
                enabled = true,
                action = "proxy",
                logic = "OR",
                conditions = listOf(
                    RuleCondition("geosite", "openai"),
                    RuleCondition("geosite", "anthropic"),
                    RuleCondition("domain_suffix", "chatgpt.com"),
                    RuleCondition("domain_suffix", "claude.ai")
                )
            ),
            Rule(
                id = "bl_gfw",
                name = "GFW 境外被阻断域名代理 (GeoSite GFW)",
                enabled = true,
                action = "proxy",
                logic = "OR",
                conditions = listOf(
                    RuleCondition("geosite", "gfw"),
                    RuleCondition("geosite", "youtube"),
                    RuleCondition("geosite", "telegram"),
                    RuleCondition("geosite", "twitter")
                )
            )
        )
    }

    /** 模板 4: 🚫 仅去广告直连模式 (全系统直连 + 强效去广告) */
    fun createAdBlockOnlyRules(): List<Rule> {
        return listOf(
            Rule(
                id = "ad_only_all",
                name = "全量广告与隐私追踪拦截 (AdBlock & Analytics)",
                enabled = true,
                action = "block",
                logic = "OR",
                conditions = listOf(
                    RuleCondition("geosite", "category-ads-all"),
                    RuleCondition("domain_suffix", "doubleclick.net"),
                    RuleCondition("domain_suffix", "googleadservices.com"),
                    RuleCondition("domain_suffix", "google-analytics.com"),
                    RuleCondition("domain_suffix", "pglstatp-toutiao.com"),
                    RuleCondition("domain_suffix", "gdt.qq.com"),
                    RuleCondition("domain_suffix", "mobads.baidu.com"),
                    RuleCondition("domain_suffix", "adukwai.com")
                )
            )
        )
    }

    @Synchronized
    fun getRules(ctx: Context): List<Rule> {
        val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!sp.contains(KEY_RULES)) {
            val defaults = createDefaultRules()
            saveRules(ctx, defaults)
            return defaults
        }
        val raw = sp.getString(KEY_RULES, "[]") ?: "[]"
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val id = o.optString("id", java.util.UUID.randomUUID().toString())
                val name = o.optString("name", "")
                val enabled = o.optBoolean("enabled", true)
                val logic = o.optString("logic", "OR")
                val action = o.optString("action", "direct")
                val hits = o.optLong("hits", 0L)

                val conditions = mutableListOf<RuleCondition>()
                val condArr = o.optJSONArray("conditions")
                if (condArr != null && condArr.length() > 0) {
                    for (ci in 0 until condArr.length()) {
                        val co = condArr.optJSONObject(ci) ?: continue
                        conditions.add(RuleCondition(
                            co.optString("type", "domain_suffix"),
                            co.optString("pattern", "")
                        ))
                    }
                }

                val type = o.optString("type", "domain")
                val kind = o.optString("kind", "suffix")
                val pattern = o.optString("pattern", "")

                Rule(
                    id = id,
                    name = name,
                    enabled = enabled,
                    logic = logic,
                    conditions = conditions,
                    type = type,
                    kind = kind,
                    pattern = pattern,
                    action = action,
                    hits = hits,
                )
            }
        }.getOrElse {
            LogStore.append("[store] 规则 JSON 解析异常, 启用容灾默认规则: ${it.message}")
            createDefaultRules()
        }
    }

    @Synchronized
    fun addRule(ctx: Context, rule: Rule) {
        val list = getRules(ctx).toMutableList()
        list.add(rule)
        saveRules(ctx, list)
    }

    @Synchronized
    fun updateRule(ctx: Context, index: Int, rule: Rule) {
        val list = getRules(ctx).toMutableList()
        if (index in list.indices) {
            list[index] = rule
            saveRules(ctx, list)
        }
    }

    /** 切换某条规则的启用状态 */
    @Synchronized
    fun toggleRuleEnabled(ctx: Context, index: Int): Boolean {
        val list = getRules(ctx).toMutableList()
        if (index in list.indices) {
            val old = list[index]
            val updated = old.copy(enabled = !old.enabled)
            list[index] = updated
            saveRules(ctx, list)
            return updated.enabled
        }
        return false
    }

    /** 拖动排序规则 (from -> to) */
    @Synchronized
    fun moveRule(ctx: Context, from: Int, to: Int) {
        val list = getRules(ctx).toMutableList()
        if (from in list.indices && to in list.indices) {
            val r = list.removeAt(from)
            list.add(to, r)
            saveRules(ctx, list)
        }
    }

    @Synchronized
    fun removeRule(ctx: Context, index: Int) {
        val list = getRules(ctx).toMutableList()
        if (index in list.indices) {
            list.removeAt(index)
            saveRules(ctx, list)
        }
    }

    @Synchronized
    fun saveRules(ctx: Context, list: List<Rule>) {
        val arr = JSONArray()
        for (r in list) {
            val obj = JSONObject()
                .put("id", r.id)
                .put("name", r.name)
                .put("enabled", r.enabled)
                .put("logic", r.logic)
                .put("action", r.action)
                .put("type", r.type)
                .put("kind", r.kind)
                .put("pattern", r.pattern)

            val condArr = JSONArray()
            for (c in r.effectiveConditions) {
                condArr.put(JSONObject().put("type", c.type).put("pattern", c.pattern))
            }
            obj.put("conditions", condArr)
            arr.put(obj)
        }
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_RULES, arr.toString()).apply()
    }

    fun getDefaultAction(ctx: Context): String =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_DEFAULT, "proxy") ?: "proxy"

    fun setDefaultAction(ctx: Context, action: String) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_DEFAULT, action).apply()
    }

    /** 生成传给 Rust setCustomRules 的 JSON */
    fun toJson(ctx: Context): String {
        val root = JSONObject()
        root.put("default_action", getDefaultAction(ctx))

        val arr = JSONArray()
        for (r in getRules(ctx)) {
            val obj = JSONObject()
                .put("id", r.id)
                .put("name", r.displayName)
                .put("enabled", r.enabled)
                .put("logic", r.logic)
                .put("action", r.action.trim().lowercase())

            val condArr = JSONArray()
            for (c in r.effectiveConditions) {
                condArr.put(JSONObject().put("type", c.type).put("pattern", c.pattern))
            }
            obj.put("conditions", condArr)
            arr.put(obj)
        }
        root.put("rules", arr)
        return root.toString()
    }
}
