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
        val uri = "mirage://secret@[2400:3200::1]:8080?sni=ipv6.test"
        val node = NodeStore.Node(uri, "IPv6 Node")

        assertEquals("secret", node.password)
        assertEquals("2400:3200::1", node.server)
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
                {"uri": "mirage://p1@1.1.1.1:443?sni=s1", "name": "Node 1"},
                {"uri": "mirage://p2@2.2.2.2:8443?sni=s2", "name": "Node 2"}
            ]
        """.trimIndent()

        val nodes = NodeStore.parseNodesJson(json)
        assertEquals(2, nodes.size)
        assertEquals("Node 1", nodes[0].name)
        assertEquals("1.1.1.1", nodes[0].server)
        assertEquals("Node 2", nodes[1].name)
        assertEquals("2.2.2.2", nodes[1].server)
    }

    @Test
    fun testParseNodesJson_InvalidFallback() {
        val empty = NodeStore.parseNodesJson("")
        assertTrue(empty.isEmpty())

        val invalid = NodeStore.parseNodesJson("not a valid json")
        assertTrue(invalid.isEmpty())
    }
}
