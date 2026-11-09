package com.mirage.android.data.model

/**
 * Mirage 节点模型。
 */
data class Node(
    val uri: String,
    val name: String = "",
    val latencyMs: Long? = null,
    val isTesting: Boolean = false,
    val testError: String? = null,
    /**
     * 本次 [latencyMs] 是否只量了 TCP 连通性（没做 Mirage 协议握手）。
     *
     * 必须跟着延迟值一起存：TCP 测法量不出协议故障，节点 TCP 可达而握手失败时
     * 它照样给出一个漂亮的小数字。界面必须能把这种数字和完整握手测出来的数字
     * 区分开，否则又回到「显示绿色但其实不可用」。
     */
    val latencyTcpOnly: Boolean = false,
) {
    val server: String get() {
        val hp = uri.substringAfter("mirage://").substringAfterLast("@").substringBefore("?")
        return hp.substringBeforeLast(":").removePrefix("[").removeSuffix("]")
    }

    val port: String get() {
        val hp = uri.substringAfter("mirage://").substringAfterLast("@").substringBefore("?")
        return hp.substringAfterLast(":").trimEnd('/')
    }

    val password: String get() {
        val raw = uri.substringAfter("mirage://").substringBeforeLast("@")
        return percentDecode(raw)
    }

    val sni: String get() {
        val q = uri.substringAfter("?", "")
        return q.split("&").firstOrNull { it.startsWith("sni=") }
            ?.substringAfter("=")?.let { percentDecode(it) } ?: ""
    }

    val displayName: String get() = if (name.isNotBlank()) name else "$server:$port"

    companion object {
        fun percentDecode(s: String): String {
            if (!s.contains('%')) return s
            val sb = StringBuilder()
            var i = 0
            while (i < s.length) {
                val c = s[i]
                if (c == '%' && i + 2 < s.length) {
                    val hex = s.substring(i + 1, i + 3)
                    sb.append(hex.toIntOrNull(16)?.toChar() ?: c)
                    i += 3
                } else {
                    sb.append(c)
                    i++
                }
            }
            return sb.toString()
        }

        fun percentEncode(s: String): String {
            val sb = StringBuilder()
            for (c in s) {
                when {
                    c.isLetterOrDigit() || c in "-_.~" -> sb.append(c)
                    else -> sb.append('%').append(String.format("%02X", c.code))
                }
            }
            return sb.toString()
        }

        fun uriOf(server: String, port: String, password: String, sni: String): String {
            val host = if (server.contains(":")) "[$server]" else server
            val p = port.trim().ifEmpty { "443" }
            val s = sni.trim().ifEmpty { "www.apple.com" }
            return "mirage://${percentEncode(password)}@$host:$p?sni=${percentEncode(s)}"
        }

        /**
         * 节点 URI 的日志脱敏: 只保留 scheme 与 host:port, 口令位置写成三个星号。
         *
         * **前缀截断不是脱敏。** scheme 本身就占 9 个字符, 紧随其后的就是口令,
         * 所以 `uri.take(30)` 会把口令的前 21 个字符原样写进 `core.log` —— 而那个
         * 文件用户可以导出分享。2026-11-09 审计发现外壳两处 + 内核一处都是这个形状。
         *
         * 注意本段**刻意不写出** scheme 后面紧跟星号的字面形式: Kotlin 块注释可嵌套,
         * 斜杠紧跟星号会开一层嵌套注释, 把后面的代码整段吃掉 (编译器报的错会出现在
         * 几十行之外)。这正是 `LocalizationTest` 的注释里记过的同一个坑。
         *
         * 解析不出 host/port 时不回落到打印原文或前缀, 给不含原文的占位。
         * 与内核侧 `mirage-jni` 的 `redact_uri` 输出同一形状, 便于两边日志对照。
         */
        fun redact(uri: String): String {
            // 刻意不复用 [server] / [port] 这两个 getter: 它们很宽松, 对畸形输入会
            // 原样返回整串 (substringBeforeLast 找不到分隔符时返回全文), 结果是把
            // 口令当成 host 打进日志 —— 单测 `无法解析时不回落到打印原文` 钉的就是这个。
            if (!uri.startsWith(SCHEME)) return REDACT_UNPARSABLE
            val afterScheme = uri.removePrefix(SCHEME).substringBefore("?")
            val at = afterScheme.lastIndexOf('@')
            if (at < 0) return REDACT_UNPARSABLE
            val hostPort = afterScheme.substring(at + 1)
            if (hostPort.isBlank() || !hostPort.contains(':')) return REDACT_UNPARSABLE
            return SCHEME + "***@" + hostPort
        }

        private const val SCHEME = "mirage://"

        /** 解析失败时的占位。不含输入的任何片段。 */
        private const val REDACT_UNPARSABLE = "mirage://<unparsable>"

        fun defaultName(uri: String): String {
            val core = uri.substringAfter("mirage://").substringAfter("@").substringBefore("?")
            return core.substringBefore(":").removePrefix("[").removeSuffix("]")
        }
    }
}
