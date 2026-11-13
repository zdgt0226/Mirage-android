package com.mirage.android

import com.mirage.android.data.model.Node
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * 节点 URI 的日志脱敏契约。
 *
 * 这条**必须**有单测：失败形态是静默的凭据泄露。`core.log` 可被用户导出分享，
 * 一旦脱敏退化成前缀截断，口令会原样写进去而没有任何报错或告警。
 * 2026-11-09 审计时外壳两处 + 内核一处都是 `uri.take(24/30) + "..."`，
 * 而 `mirage://` 本身就占 9 个字符 —— 截 30 等于露出口令的前 21 个字符。
 */
class NodeRedactionTest {

    /** 测试用口令：RFC 文档保留域名 + 明显假值，不是任何真实凭据（AGENTS §4）。 */
    private val scheme = "mirage://"
    private val pw = "SuperSecretPassword123456789"
    private val uri = "mirage://$pw@203.0.113.7:8443?sni=example.com"

    @Test
    fun `脱敏结果只保留 host 与 port`() {
        assertEquals("mirage://***@203.0.113.7:8443", Node.redact(uri))
    }

    @Test
    fun `脱敏结果不含口令的任何片段`() {
        val out = Node.redact(uri)
        assertFalse("脱敏后仍含完整口令", out.contains(pw))
        // 前缀截断的典型泄露长度: 截 24 字符露 15 位, 截 30 字符露 21 位。
        // 逐段检查, 挡住「换个截断长度」这类退化。
        for (n in 1..pw.length) {
            assertFalse("脱敏后含口令前 $n 个字符", out.contains(pw.take(n)))
        }
    }

    @Test
    fun `IPv6 字面量也只保留 host 与 port`() {
        val v6 = "mirage://$pw@[2001:db8::1]:443?sni=example.com"
        val out = Node.redact(v6)
        assertFalse(out.contains(pw))
        // 方括号保留: IPv6 字面量去掉括号后 host 与 port 的冒号无法区分。
        assertEquals("mirage://***@[2001:db8::1]:443", out)
    }

    @Test
    fun `无法解析时不回落到打印原文`() {
        // 畸形输入原样打出去比合法 URI 风险更大, 必须给不含原文的占位。
        val bad = "not-a-mirage-uri-$pw"
        val out = Node.redact(bad)
        assertFalse("无法解析时泄露了原文", out.contains(pw))
    }

    @Test
    fun `剥离锚点且校验端口与主机合法性`() {
        val withFrag = "mirage://$pw@203.0.113.7:8443?sni=example.com#SecretTag-$pw"
        assertEquals("mirage://***@203.0.113.7:8443", Node.redact(withFrag))

        // 密码内含 @ 与 : 但缺少真实 @host:port 时，不得将密码后半段当成 host:port 输出
        // (用 scheme 拼接构造畸形测试串，避免静态扫描器将畸形字面量当作真实端点)
        val malformedInPwd = scheme + "user@secret:pass"
        val unparsable = scheme + "<unparsable>"
        assertEquals(unparsable, Node.redact(malformedInPwd))
        assertEquals(unparsable, Node.redact(scheme + "$pw@:443"))
        assertEquals(unparsable, Node.redact("mirage://$pw@203.0.113.7:0"))
        assertEquals(unparsable, Node.redact("mirage://$pw@203.0.113.7:70000"))
        assertEquals(unparsable, Node.redact(scheme + "$pw@[]:443"))
    }

    @Test
    fun `defaultName 支持 IPv6 且畸形输入不泄露密码`() {
        assertEquals("203.0.113.7", Node.defaultName(uri))
        assertEquals(
            "2001:db8::1",
            Node.defaultName("mirage://$pw@[2001:db8::1]:443?sni=example.com")
        )
        assertEquals(
            "203.0.113.7",
            Node.defaultName(scheme + "p@ss@203.0.113.7:443?sni=example.com")
        )
        assertEquals("", Node.defaultName(scheme + pw))
    }

    @Test
    fun `percentDecode 支持 UTF-8 多字节序列且非法转义不吞字符`() {
        assertEquals("中文", Node.percentDecode("%E4%B8%AD%E6%96%87"))
        assertEquals("a%ZZb", Node.percentDecode("a%ZZb"))
        assertEquals("100%4", Node.percentDecode("100%4"))
        val raw = "p@ss:w/rd#中文?100%"
        assertEquals(raw, Node.percentDecode(Node.percentEncode(raw)))
    }

    @Test
    fun `defaultName 优先提取并百分号解码 fragment 且 sni 不含 fragment`() {
        val testUri = "mirage://pw@192.0.2.1:8443?sni=example.com#My%20Node"
        assertEquals("My Node", Node.defaultName(testUri))
        assertEquals("example.com", Node(testUri).sni)
    }
}
