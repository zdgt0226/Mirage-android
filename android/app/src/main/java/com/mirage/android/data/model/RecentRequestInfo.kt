package com.mirage.android.data.model

import org.json.JSONObject

/**
 * Surge 风格 Recent Requests 请求流数据模型。
 */
data class RecentRequestInfo(
    val id: Long,
    val protocol: String,       // "TCP", "UDP", "DNS"
    val target: String,         // "api.github.com:443"
    val resolvedIp: String,     // "140.82.112.4" or "198.18.0.2 (Fake-IP)"
    val matchedRule: String,    // "Rule: DOMAIN-SUFFIX (github.com)" or "GEOIP: CN"
    val outbound: String,       // "PROXY" / "DIRECT" / "BLOCK"
    val status: String,         // "Active", "Closed (200 OK)", "Closed (Timeout)"
    val upBytes: Long,
    val downBytes: Long,
    val startTime: Long,
    val durationMs: Long,
    val dnsMs: Long = 0,
    val connectMs: Long = 0,
    val tlsMs: Long = 0,
    val ttfbMs: Long = 0
) {
    val upFormatted: String get() = formatBytes(upBytes)
    val downFormatted: String get() = formatBytes(downBytes)
    val durationFormatted: String get() = if (durationMs >= 1000) "%.1fs".format(durationMs / 1000.0) else "${durationMs}ms"
    val ttfbFormatted: String get() = if (ttfbMs > 0) "${ttfbMs}ms" else "-"
    val connectFormatted: String get() = if (connectMs > 0) "${connectMs}ms" else "-"
    val dnsFormatted: String get() = if (dnsMs > 0) "${dnsMs}ms" else if (resolvedIp.contains("Fake-IP")) "0ms (Fake-IP)" else "-"

    companion object {
        fun fromJson(obj: JSONObject): RecentRequestInfo {
            return RecentRequestInfo(
                id = obj.optLong("id", 0),
                protocol = obj.optString("protocol", "TCP"),
                target = obj.optString("target", ""),
                resolvedIp = obj.optString("resolved_ip", ""),
                matchedRule = obj.optString("matched_rule", ""),
                outbound = obj.optString("outbound", "PROXY"),
                status = obj.optString("status", "Active"),
                upBytes = obj.optLong("up_bytes", 0),
                downBytes = obj.optLong("down_bytes", 0),
                startTime = obj.optLong("start_time", 0),
                durationMs = obj.optLong("duration_ms", 0),
                dnsMs = obj.optLong("dns_ms", 0),
                connectMs = obj.optLong("connect_ms", 0),
                tlsMs = obj.optLong("tls_ms", 0),
                ttfbMs = obj.optLong("ttfb_ms", 0)
            )
        }

        private fun formatBytes(bytes: Long): String {
            val b = bytes.toDouble()
            return when {
                b >= 1 shl 30 -> "%.2f GB".format(b / (1 shl 30))
                b >= 1 shl 20 -> "%.1f MB".format(b / (1 shl 20))
                b >= 1 shl 10 -> "%.0f KB".format(b / (1 shl 10))
                else -> "${bytes} B"
            }
        }
    }
}
