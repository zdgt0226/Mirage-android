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

    fun createDefaultRules(): List<Rule> {
        return listOf(
            Rule(
                id = java.util.UUID.randomUUID().toString(),
                name = "国内域名直连 (GeoSite)",
                enabled = true,
                action = "direct",
                conditions = listOf(RuleCondition("geosite", "cn"))
            ),
            Rule(
                id = java.util.UUID.randomUUID().toString(),
                name = "国内 IP 直连 (GeoIP)",
                enabled = true,
                action = "direct",
                conditions = listOf(RuleCondition("geoip", "cn"))
            ),
            Rule(
                id = java.util.UUID.randomUUID().toString(),
                name = "广告拦截 (GeoSite Ads)",
                enabled = true,
                action = "block",
                conditions = listOf(RuleCondition("geosite", "category-ads-all"))
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
