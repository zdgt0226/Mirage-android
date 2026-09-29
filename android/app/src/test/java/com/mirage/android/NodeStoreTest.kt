package com.mirage.android

import com.mirage.android.core.NodeStore
import org.junit.Assert.*
import org.junit.Test

class NodeStoreTest {

    @Test
    fun testNodeUriParsing_Standard() {
        val uri = "mirage://mypassword@example.com:8443?sni=sni.example.com"
        val node = NodeStore.Node(uri, "HK Node")

        assertEquals("HK Node", node.name)
        assertEquals("mypassword", node.password)
        assertEquals("example.com", node.server)
        assertEquals("8443", node.port)
        assertEquals("sni.example.com", node.sni)
        assertEquals("HK Node", node.displayName)
    }

    @Test
    fun testNodeUriParsing_PercentEncodedPassword() {
        val uri = "mirage://pass%40word%23123@1.2.3.4:443?sni=test.com"
        val node = NodeStore.Node(uri, "")

        assertEquals("pass@word#123", node.password)
        assertEquals("1.2.3.4", node.server)
        assertEquals("443", node.port)
        assertEquals("test.com", node.sni)
        assertEquals("1.2.3.4:443", node.displayName)
    }

    @Test
    fun testNodeUriParsing_Ipv6Host() {
        val uri = "mirage://secret@[2001:db8::1]:8080?sni=ipv6.test"
        val node = NodeStore.Node(uri, "IPv6 Node")

        assertEquals("secret", node.password)
        assertEquals("2001:db8::1", node.server)
        assertEquals("8080", node.port)
        assertEquals("ipv6.test", node.sni)
    }

    @Test
    fun testPercentEncodeDecodeRoundtrip() {
        val original = "abc!@#$%^&*()_+~`{}|[]\\:\";'<>?,/"
        val encoded = NodeStore.Node.percentEncode(original)
        val decoded = NodeStore.Node.percentDecode(encoded)
        assertEquals(original, decoded)
    }

    @Test
    fun testParseNodesJson() {
        val json = """
            [
                {"uri": "mirage://p1@203.0.113.1:443?sni=s1", "name": "Node 1"},
                {"uri": "mirage://p2@203.0.113.2:8443?sni=s2", "name": "Node 2"}
            ]
        """.trimIndent()

        val nodes = NodeStore.parseNodesJson(json)
        assertEquals(2, nodes.size)
        assertEquals("Node 1", nodes[0].name)
        assertEquals("203.0.113.1", nodes[0].server)
        assertEquals("Node 2", nodes[1].name)
        assertEquals("203.0.113.2", nodes[1].server)
    }

    @Test
    fun testParseNodesJson_InvalidFallback() {
        val empty = NodeStore.parseNodesJson("")
        assertTrue(empty.isEmpty())

        val invalid = NodeStore.parseNodesJson("not a valid json")
        assertTrue(invalid.isEmpty())
    }

    @Test
    fun testMigrateLeakedPresetNodeFiltering() {
        val json = """
            [
                {"uri": "mirage://dummy@203.0.113.10:8443?sni=speedtest.net", "name": "Speedtest-HK (Test)"},
                {"uri": "mirage://user@203.0.113.20:8443?sni=example.com", "name": "Custom User Node"}
            ]
        """.trimIndent()
        val nodes = NodeStore.parseNodesJson(json)
        assertEquals(2, nodes.size)
        val filtered = nodes.filterNot { it.name.startsWith("Speedtest-HK") && it.sni == "speedtest.net" }
        assertEquals(1, filtered.size)
        assertEquals("Custom User Node", filtered[0].name)
        assertEquals("203.0.113.20", filtered[0].server)
    }
}
