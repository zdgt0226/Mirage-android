package com.mirage.android

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「绕过局域网」路由表的覆盖契约。
 *
 * 这张表是纯数据，失败形态完全无声：少排除一个网段，那一段流量就被静默送进隧道，
 * 而用户以为自己已经绕过了局域网。2026-11-10 审计就是这么发现 `168.0.0.0/6`
 * 把 `169.254.0.0/16`（链路本地 / APIPA）整段吞掉的 —— 表里写的和注释声明的不一致，
 * 没有任何测试能发现。
 *
 * 这里不依赖 Android 框架：直接对 `NON_LAN_IPV4_ROUTES` 做 CIDR 包含判断。
 */
class BypassLanRoutesTest {

    private fun ipToLong(ip: String): Long =
        ip.split(".").fold(0L) { acc, part -> (acc shl 8) or part.toLong() }

    /** 该地址是否被路由表的某一条覆盖（即会被送进隧道）。 */
    private fun covered(ip: String): Boolean {
        val v = ipToLong(ip)
        return CoreService.NON_LAN_IPV4_ROUTES.any { (net, prefix) ->
            val mask = if (prefix == 0) 0L else (-1L shl (32 - prefix)) and 0xFFFFFFFFL
            (v and mask) == (ipToLong(net) and mask)
        }
    }

    @Test
    fun `注释声明排除的网段一律不得进隧道`() {
        // 逐条对应 NON_LAN_IPV4_ROUTES 的 KDoc 列出的排除项。
        val mustBypass = listOf(
            "0.0.0.1" to "本网络 0.0.0.0/8",
            "10.1.2.3" to "RFC 1918 A 类",
            "100.64.0.1" to "RFC 6598 CGNAT",
            "127.0.0.1" to "环回",
            "169.254.1.1" to "链路本地 / APIPA",
            "169.254.255.254" to "链路本地上边界",
            "172.16.0.1" to "RFC 1918 B 类",
            "172.31.255.254" to "RFC 1918 B 类上边界",
            "192.168.1.1" to "RFC 1918 C 类",
            "224.0.0.251" to "mDNS 组播",
            "239.255.255.250" to "SSDP 组播",
            "255.255.255.255" to "广播",
        )
        for ((ip, why) in mustBypass) {
            assertFalse("$ip ($why) 不该被路由进隧道", covered(ip))
        }
    }

    @Test
    fun `链路本地两侧的相邻网段仍须进隧道`() {
        // 挖掉 169.254/16 时最容易连带挖多或挖少。钉住紧邻的两侧。
        assertTrue("169.253.255.255 应进隧道", covered("169.253.255.255"))
        assertTrue("169.255.0.0 应进隧道", covered("169.255.0.0"))
        assertTrue("168.0.0.1 应进隧道", covered("168.0.0.1"))
        assertTrue("170.0.0.1 应进隧道", covered("170.0.0.1"))
        assertTrue("171.255.255.255 应进隧道", covered("171.255.255.255"))
    }

    @Test
    fun `常见公网地址必须进隧道`() {
        // 用 RFC 文档保留地址作样本（AGENTS §4），它们在路由意义上就是普通公网地址。
        for (ip in listOf("1.1.1.1", "8.8.8.8", "203.0.113.7", "198.51.100.1", "192.0.2.1")) {
            assertTrue("$ip 应进隧道", covered(ip))
        }
    }

    @Test
    fun `路由表内不存在重叠或重复的冗余子网段`() {
        val ranges = CoreService.NON_LAN_IPV4_ROUTES.map { (net, prefix) ->
            val mask = if (prefix == 0) 0L else (-1L shl (32 - prefix)) and 0xFFFFFFFFL
            val start = ipToLong(net) and mask
            val size = 1L shl (32 - prefix)
            val end = start + size - 1L
            Triple("$net/$prefix", start, end)
        }
        for (i in ranges.indices) {
            for (j in i + 1 until ranges.size) {
                val a = ranges[i]
                val b = ranges[j]
                val overlaps = a.second <= b.third && b.second <= a.third
                assertFalse("路由网段存在重叠冗余: ${a.first} 与 ${b.first}", overlaps)
            }
        }
    }
}
