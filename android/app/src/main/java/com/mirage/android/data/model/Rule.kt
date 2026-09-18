package com.mirage.android.data.model

import android.content.Context
import androidx.annotation.StringRes
import com.mirage.android.R
import java.util.UUID

/**
 * 单个原子匹配条件模型。
 * type: "domain_suffix" | "domain_exact" | "domain_keyword" | "domain_regex" | "geosite" | "geoip" | "ip_cidr" | "port" | "protocol"
 */
data class RuleCondition(
    val type: String = "domain_suffix",
    val pattern: String = "",
) {
    /**
     * 返回资源 id 而不是字符串: 这个类没有 Context, 拿不到 getString。
     * 调用方 (RuleAdapter) 负责解析。
     */
    @get:StringRes
    val typeDisplayNameRes: Int get() = when (type.lowercase()) {
        "geosite" -> R.string.cond_kind_geosite
        "geoip" -> R.string.cond_kind_geoip
        "exact", "domain_exact" -> R.string.cond_kind_domain_exact
        "keyword", "domain_keyword" -> R.string.cond_kind_domain_keyword
        "regex", "domain_regex" -> R.string.cond_kind_domain_regex
        "cidr", "ip_cidr" -> R.string.cond_kind_ip_cidr
        "port" -> R.string.cond_kind_port
        "protocol" -> R.string.cond_kind_protocol
        else -> R.string.cond_kind_domain_suffix
    }

    fun displayString(ctx: Context): String = "${ctx.getString(typeDisplayNameRes)}: $pattern"
}

/**
 * 复合分流规则模型。
 * 支持单条件简易规则与多条件复合规则 (AND / OR 逻辑算子)。
 * 动作: direct (直连) / proxy (代理) / block (拦截)
 */
data class Rule(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "",
    val enabled: Boolean = true,
    val logic: String = "OR", // "OR" | "AND"
    val conditions: List<RuleCondition> = emptyList(),
    val type: String = "domain", // 兼容旧字段
    val kind: String = "suffix", // 兼容旧字段
    val pattern: String = "",    // 兼容旧字段
    val action: String = "direct", // "direct" | "proxy" | "block"
    val hits: Long = 0,           // 运行时统计
) {
    val isDirect: Boolean get() = action == "direct"
    val isBlock: Boolean get() = action == "block" || action == "reject"
    val isProxy: Boolean get() = !isDirect && !isBlock

    val isComposite: Boolean get() = conditions.size > 1

    val effectiveConditions: List<RuleCondition> get() =
        if (conditions.isNotEmpty()) {
            conditions
        } else if (pattern.isNotBlank()) {
            val ctype = when (kind.lowercase()) {
                "geosite" -> "geosite"
                "geoip" -> "geoip"
                "exact" -> "domain_exact"
                "keyword" -> "domain_keyword"
                "regex" -> "domain_regex"
                "cidr" -> "ip_cidr"
                "port" -> "port"
                "protocol" -> "protocol"
                else -> "domain_suffix"
            }
            listOf(RuleCondition(ctype, pattern))
        } else {
            emptyList()
        }

    val displayName: String get() =
        if (name.isNotBlank()) name
        else if (pattern.isNotBlank()) pattern
        else if (effectiveConditions.isNotEmpty()) effectiveConditions.first().pattern
        // displayName 同时用作 RuleRepository 的去重键与 RuleStore 持久化的 name 字段,
        // 必须与语言无关 —— 一旦随 locale 变化, 切语言就会产生重复规则。
        // i18n-exempt: 见上
        else "未命名规则"

    fun summaryText(ctx: Context): String =
        if (effectiveConditions.size <= 1) {
            val first = effectiveConditions.firstOrNull()
            if (first != null) {
                ctx.getString(R.string.rule_summary_single, ctx.getString(first.typeDisplayNameRes), first.pattern)
            } else {
                displayName
            }
        } else {
            // 空格放在代码里: aapt 会剥掉资源值两端的空白, 资源里只存 "且" / "或"
            val op = " " + ctx.getString(
                if (logic.equals("AND", ignoreCase = true)) R.string.rule_logic_and_sep else R.string.rule_logic_or_sep
            ) + " "
            effectiveConditions.joinToString(op) {
                ctx.getString(R.string.rule_summary_condition, ctx.getString(it.typeDisplayNameRes), it.pattern)
            }
        }

    @get:StringRes
    val actionDisplayNameRes: Int get() = when (action.lowercase()) {
        "direct" -> R.string.action_direct
        "block", "reject" -> R.string.action_block
        else -> R.string.action_proxy
    }
}
