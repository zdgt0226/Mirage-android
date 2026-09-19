package com.mirage.android

import com.mirage.android.data.model.RecentRequestInfo
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class CommandBusPayloadTest {

    @Test
    fun testParseDirectJsonArrayPayload() {
        val payload = """
            {
                "event": "recent_requests",
                "data": [
                    {
                        "id": 101,
                        "protocol": "TCP",
                        "target": "example.com:443",
                        "resolved_ip": "93.184.216.34",
                        "outbound": "PROXY",
                        "status": "Active"
                    },
                    {
                        "id": 102,
                        "protocol": "UDP",
                        "target": "dns.google:53",
                        "resolved_ip": "8.8.8.8",
                        "outbound": "DIRECT",
                        "status": "Closed"
                    }
                ]
            }
        """.trimIndent()

        val obj = JSONObject(payload)
        val arr = obj.optJSONArray("data")
        assertNotNull(arr)
        assertEquals(2, arr!!.length())

        val item1 = RecentRequestInfo.fromJson(arr.getJSONObject(0))
        assertEquals(101L, item1.id)
        assertEquals("example.com:443", item1.target)
        assertEquals("PROXY", item1.outbound)

        val item2 = RecentRequestInfo.fromJson(arr.getJSONObject(1))
        assertEquals(102L, item2.id)
        assertEquals("UDP", item2.protocol)
    }

    @Test
    fun testParseNestedJsonStringPayloadFallback() {
        // 验证兼容旧格式 (data 为转义字符串的 JSON)
        val innerJson = """[{"id":201,"protocol":"TCP","target":"legacy.com:443"}]"""
        val outerObj = JSONObject()
        outerObj.put("event", "recent_requests")
        outerObj.put("data", innerJson)

        val arr = outerObj.optJSONArray("data") ?: run {
            val dataStr = outerObj.optString("data")
            if (dataStr.isNotBlank() && dataStr != "[]") {
                runCatching { JSONArray(dataStr) }.getOrNull()
            } else null
        }
        assertNotNull(arr)
        assertEquals(1, arr!!.length())
        val item = RecentRequestInfo.fromJson(arr.getJSONObject(0))
        assertEquals(201L, item.id)
        assertEquals("legacy.com:443", item.target)
    }
}
