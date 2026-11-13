package com.mirage.android.core

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * 多节点存储 (SharedPreferences + JSON)。
 * 支持: 添加 / 编辑 / 删除 / 选择当前节点。
 */
object NodeStore {

    data class Node(
        val uri: String,      // mirage:// 完整串
        val name: String,     // 显示名 (默认取 host:port)
    ) {
        /** 密码 (mirage://密码@host:port?sni=...) 含百分号解码。 */
        val password: String get() {
            val raw = uri.substringAfter("mirage://").substringBefore("#").substringBefore("?").substringBeforeLast("@")
            return percentDecode(raw)
        }
        val server: String get() {
            val hp = uri.substringAfter("mirage://").substringBefore("#").substringAfterLast("@").substringBefore("?")
            return hp.substringBeforeLast(":").removePrefix("[").removeSuffix("]")
        }
        val port: String get() {
            val hp = uri.substringAfter("mirage://").substringBefore("#").substringAfterLast("@").substringBefore("?")
            return hp.substringAfterLast(":").trimEnd('/')
        }
        /** SNI (伪装域名)。 */
        val sni: String get() {
            val q = uri.substringBefore("#").substringAfter("?", "")
            return q.split("&").firstOrNull { it.startsWith("sni=") }
                ?.substringAfter("=")?.let { percentDecode(it) } ?: ""
        }
        val displayName: String get() = if (name.isNotBlank()) name else "$server:$port"

        companion object {
            /** 百分号解码 (URI 里密码/SNI 的转义)。 */
            fun percentDecode(s: String): String {
                if (!s.contains('%')) return s
                val sb = StringBuilder(s.length)
                val byteBuf = java.io.ByteArrayOutputStream()
                var i = 0
                while (i < s.length) {
                    if (s[i] == '%' && i + 2 < s.length) {
                        val d1 = Character.digit(s[i + 1], 16)
                        val d2 = Character.digit(s[i + 2], 16)
                        if (d1 >= 0 && d2 >= 0) {
                            byteBuf.write((d1 shl 4) or d2)
                            i += 3
                            continue
                        }
                    }
                    if (byteBuf.size() > 0) {
                        sb.append(byteBuf.toByteArray().toString(Charsets.UTF_8))
                        byteBuf.reset()
                    }
                    sb.append(s[i])
                    i += 1
                }
                if (byteBuf.size() > 0) {
                    sb.append(byteBuf.toByteArray().toString(Charsets.UTF_8))
                }
                return sb.toString()
            }

            /** 百分号编码 (密码/SNI 特殊字符)。 */
            fun percentEncode(s: String): String {
                val sb = StringBuilder()
                for (b in s.toByteArray(Charsets.UTF_8)) {
                    val v = b.toInt() and 0xFF
                    val c = v.toChar()
                    if ((c in 'a'..'z') || (c in 'A'..'Z') || (c in '0'..'9') || c in "-_.~") {
                        sb.append(c)
                    } else {
                        sb.append('%').append(String.format("%02X", v))
                    }
                }
                return sb.toString()
            }

            /** 从拆分字段构造 URI。 */
            fun uriOf(server: String, port: String, password: String, sni: String): String {
                val host = if (server.contains(":")) "[$server]" else server
                val p = port.trim().ifEmpty { "443" }
                return "mirage://${percentEncode(password)}@$host:$p?sni=${percentEncode(sni)}"
            }
        }
    }

    private const val PREFS = "mirage_nodes"
    private const val KEY_NODES = "nodes_json"
    private const val KEY_SELECTED = "selected_index"
    private const val KEY_POOL = "pool_size"

    fun getNodesJson(ctx: Context): String =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_NODES, "[]") ?: "[]"

    fun parseNodesJson(raw: String): List<Node> {
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                Node(o.getString("uri"), o.optString("name", ""))
            }
        }.getOrDefault(emptyList())
    }

    fun toJson(list: List<Node>): String {
        val arr = JSONArray()
        for (n in list) {
            arr.put(JSONObject().put("uri", n.uri).put("name", n.name))
        }
        return arr.toString()
    }

    fun getNodes(ctx: Context): List<Node> =
        parseNodesJson(getNodesJson(ctx))

    fun addNode(ctx: Context, node: Node): Int {
        val list = getNodes(ctx).toMutableList()
        val existingIdx = list.indexOfFirst { it.uri == node.uri }
        if (existingIdx >= 0) {
            val updatedName = node.name.ifBlank { list[existingIdx].name }
            list[existingIdx] = list[existingIdx].copy(name = updatedName)
            saveNodes(ctx, list)
            return existingIdx
        }
        list.add(node)
        saveNodes(ctx, list)
        return list.size - 1
    }

    fun updateNode(ctx: Context, index: Int, node: Node) {
        val list = getNodes(ctx).toMutableList()
        if (index in list.indices) {
            list[index] = node
            saveNodes(ctx, list)
        }
    }

    fun removeNode(ctx: Context, index: Int) {
        val list = getNodes(ctx).toMutableList()
        if (index in list.indices) {
            list.removeAt(index)
            saveNodes(ctx, list)
            val sel = getSelected(ctx)
            if (sel >= list.size) setSelected(ctx, (list.size - 1).coerceAtLeast(0))
        }
    }

    fun saveNodes(ctx: Context, list: List<Node>) {
        val json = toJson(list)
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_NODES, json).apply()
        CoreController.updateNodes(json)
    }

    fun getSelected(ctx: Context): Int =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_SELECTED, -1)

    fun setSelected(ctx: Context, index: Int) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putInt(KEY_SELECTED, index).apply()
    }

    /** 当前选中节点的 URI (无节点返回空串)。 */
    fun getSelectedUri(ctx: Context): String {
        val idx = getSelected(ctx)
        val nodes = getNodes(ctx)
        return if (idx in nodes.indices) nodes[idx].uri else ""
    }

    // ── 自动节点选择 ────────────────────────────────────────────────────
    private const val KEY_AUTO_SELECT = "auto_select"
    private const val KEY_TEST_METHOD = "test_method"  // "tcp" | "ping" | "connect"

    fun isAutoSelect(ctx: Context): Boolean =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_AUTO_SELECT, false)

    fun setAutoSelect(ctx: Context, enabled: Boolean) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_AUTO_SELECT, enabled).apply()
    }

    fun getTestMethod(ctx: Context): String =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_TEST_METHOD, com.mirage.android.data.repository.NodeRepository.DEFAULT_TEST_METHOD)
            ?: com.mirage.android.data.repository.NodeRepository.DEFAULT_TEST_METHOD

    fun setTestMethod(ctx: Context, method: String) {
        val norm = com.mirage.android.data.repository.NodeRepository.normalizeTestMethod(method)
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_TEST_METHOD, norm).apply()
    }

    fun getPoolSize(ctx: Context): Int =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_POOL, 16)

    fun setPoolSize(ctx: Context, size: Int) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt(KEY_POOL, size).apply()
    }

    /** 从 mirage:// 串生成显示名。 */
    fun defaultName(uri: String): String {
        val body = uri.removePrefix("mirage://").substringBefore("#").substringBefore("?")
        if (!body.contains("@")) return ""
        val hostPort = body.substringAfterLast("@")
        val host = if (hostPort.contains(":")) hostPort.substringBeforeLast(":") else hostPort
        return host.removePrefix("[").removeSuffix("]")
    }
}
