package com.mirage.android.data.model

import java.util.UUID

/**
 * 单个原子匹配条件模型。
 * type: "domain_suffix" | "domain_exact" | "domain_keyword" | "domain_regex" | "geosite" | "geoip" | "ip_cidr" | "port" | "protocol"
 */
data class RuleCondition(
    val type: String = "domain_suffix",
    val pattern: String = "",
) {
    val typeDisplayName: String get() = when (type.lowercase()) {
        "geosite" -> "GEOSITE 规则集"
        "geoip" -> "GEOIP 国家/IP集"
        "exact", "domain_exact" -> "DOMAIN 精确"
        "keyword", "domain_keyword" -> "DOMAIN 关键词"
        "regex", "domain_regex" -> "DOMAIN 正则"
        "cidr", "ip_cidr" -> "IP-CIDR 段"
        "port" -> "目标端口"
        "protocol" -> "传输协议"
        else -> "DOMAIN 后缀"
    }

    val displayString: String get() = "$typeDisplayName: $pattern"
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
        else "未命名规则"

    val summaryText: String get() =
        if (effectiveConditions.size <= 1) {
            val first = effectiveConditions.firstOrNull()
            if (first != null) "${first.typeDisplayName} · ${first.pattern}" else displayName
        } else {
            val op = if (logic.equals("AND", ignoreCase = true)) " 且 " else " 或 "
            effectiveConditions.joinToString(op) { "${it.typeDisplayName}(${it.pattern})" }
        }

    val actionDisplayName: String get() = when (action.lowercase()) {
        "direct" -> "直连"
        "block", "reject" -> "拦截"
        else -> "代理"
    }

    val kindDisplayName: String get() = when (kind.lowercase()) {
        "geosite" -> "GEOSITE 规则集"
        "geoip" -> "GEOIP 国家/IP集"
        "exact" -> "DOMAIN 精确"
        "keyword" -> "DOMAIN 关键词"
        "regex" -> "DOMAIN 正则"
        "cidr" -> "IP-CIDR 段"
        "port" -> "目标端口"
        "protocol" -> "协议"
        else -> "DOMAIN 后缀"
    }
}
