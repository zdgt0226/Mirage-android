package com.mirage.android.data.repository

import android.content.Context
import com.mirage.android.core.CoreController
import com.mirage.android.data.model.Node
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.net.InetSocketAddress
import java.net.Socket
import com.mirage.android.R

/**
 * 节点数据仓库 (单例 / 统一管理持久化、测活与并发测速)。
 */
class NodeRepository(private val context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _nodes = MutableStateFlow<List<Node>>(emptyList())
    val nodes: StateFlow<List<Node>> = _nodes.asStateFlow()

    private val _selectedIndex = MutableStateFlow(-1)
    val selectedIndex: StateFlow<Int> = _selectedIndex.asStateFlow()

    /**
     * 测速方式。默认 `connect` —— **不要改回 `tcp`**。
     *
     * `tcp` 只做一次 `Socket.connect()`，即 TCP 三次握手，完全不碰 Mirage 协议。
     * 节点 TCP 可达但协议握手失败时（2026-11-09 真机: 生产节点暖池全量
     * `Timeout before CCS`），`tcp` 照样报出 4ms 的绿色健康值，把一个不可用的
     * 节点显示成最优。测速的意义是回答「这个节点现在能不能用」，而 `tcp`
     * 回答不了。`connect` 走 `CoreController.testNode`，和真实建连同一条路径。
     */
    private val _testMethod = MutableStateFlow("connect")
    val testMethod: StateFlow<String> = _testMethod.asStateFlow()

    private val _isAutoSelect = MutableStateFlow(false)
    val isAutoSelect: StateFlow<Boolean> = _isAutoSelect.asStateFlow()

    private val _isTestingAll = MutableStateFlow(false)
    val isTestingAll: StateFlow<Boolean> = _isTestingAll.asStateFlow()

    private val _poolSize = MutableStateFlow(16)
    val poolSize: StateFlow<Int> = _poolSize.asStateFlow()

    private val coroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    init {
        loadData()
    }

    fun reload() {
        loadData()
    }

    private fun loadData() {
        val raw = prefs.getString(KEY_NODES, "[]") ?: "[]"
        val list = runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                Node(uri = o.getString("uri"), name = o.optString("name", ""))
            }
        }.getOrDefault(emptyList())

        // 迁移清理：过滤掉旧版本内置的泄露测试节点并回写持久化。
        // 此处按特征匹配（旧预置节点特征：name 以 "Speedtest-HK" 开头且 sni 为 speedtest.net），
        // 彻底杜绝在源码与配置中残留任何真实泄露节点的公网 IP 与口令。
        val finalNodes = list.filterNot {
            it.name.startsWith("Speedtest-HK") && it.sni == "speedtest.net"
        }
        val hadLeakedNode = finalNodes.size != list.size

        _nodes.value = finalNodes
        val savedIdx = prefs.getInt(KEY_SELECTED, 0)
        val targetIdx = if (finalNodes.isEmpty()) {
            -1
        } else if (savedIdx in finalNodes.indices) {
            savedIdx
        } else {
            0
        }
        _selectedIndex.value = targetIdx
        prefs.edit().putInt(KEY_SELECTED, targetIdx).apply()
        _testMethod.value = normalizeTestMethod(prefs.getString(KEY_TEST_METHOD, DEFAULT_TEST_METHOD))
        _isAutoSelect.value = prefs.getBoolean(KEY_AUTO_SELECT, false)
        val pool = prefs.getInt(KEY_POOL, 16)
        _poolSize.value = pool
        com.mirage.android.core.NodeStore.setPoolSize(context, pool)
        if (hadLeakedNode) {
            saveNodes(finalNodes)
        }
    }

    private fun saveNodes(list: List<Node>) {
        val arr = JSONArray()
        for (n in list) {
            arr.put(JSONObject().put("uri", n.uri).put("name", n.name))
        }
        prefs.edit().putString(KEY_NODES, arr.toString()).apply()
    }

    fun getSelectedNode(): Node? {
        val idx = _selectedIndex.value
        val list = _nodes.value
        return if (idx in list.indices) list[idx] else null
    }

    fun getSelectedUri(): String {
        return getSelectedNode()?.uri ?: ""
    }

    fun setSelected(index: Int) {
        val list = _nodes.value
        if (index in list.indices || index == -1) {
            _selectedIndex.value = index
            prefs.edit().putInt(KEY_SELECTED, index).apply()
        }
    }

    fun addNode(node: Node): Int {
        val current = _nodes.value.toMutableList()
        current.add(node)
        _nodes.value = current
        saveNodes(current)
        val newIndex = current.size - 1
        if (_selectedIndex.value == -1) {
            setSelected(newIndex)
        }
        return newIndex
    }

    fun updateNode(index: Int, node: Node) {
        val current = _nodes.value.toMutableList()
        if (index in current.indices) {
            current[index] = node
            _nodes.value = current
            saveNodes(current)
        }
    }

    fun removeNode(index: Int) {
        val current = _nodes.value.toMutableList()
        if (index in current.indices) {
            current.removeAt(index)
            _nodes.value = current
            saveNodes(current)
            val sel = _selectedIndex.value
            if (current.isEmpty()) {
                setSelected(-1)
            } else if (sel >= current.size) {
                setSelected(current.size - 1)
            }
        }
    }

    fun setAutoSelect(enabled: Boolean) {
        _isAutoSelect.value = enabled
        prefs.edit().putBoolean(KEY_AUTO_SELECT, enabled).apply()
    }

    fun setTestMethod(method: String) {
        _testMethod.value = normalizeTestMethod(method)
        prefs.edit().putString(KEY_TEST_METHOD, method).apply()
    }

    fun getPoolSize(): Int = _poolSize.value

    fun setPoolSize(size: Int) {
        val safeSize = size.coerceIn(1, 64)
        _poolSize.value = safeSize
        prefs.edit().putInt(KEY_POOL, safeSize).apply()
        com.mirage.android.core.NodeStore.setPoolSize(context, safeSize)
        CoreController.setPoolSize(safeSize)
    }

    /**
     * 单节点测活。
     */
    suspend fun testNode(index: Int): Long = withContext(Dispatchers.IO) {
        val current = _nodes.value
        if (index !in current.indices) return@withContext -1L

        val node = current[index]
        updateNodeTesting(index, isTesting = true)

        val rtt = doTest(node, _testMethod.value)
        updateNodeLatency(index, rtt)
        rtt
    }

    /**
     * 批量并发测活所有节点。
     */
    suspend fun testAllNodes(): Int? = withContext(Dispatchers.IO) {
        val list = _nodes.value
        if (list.isEmpty()) return@withContext null

        _isTestingAll.value = true
        // 全部标记为测试中
        _nodes.value = list.map { it.copy(isTesting = true, testError = null) }

        val method = _testMethod.value
        val gate = kotlinx.coroutines.sync.Semaphore(MAX_PARALLEL_TESTS)
        val deferredList = list.mapIndexed { index, node ->
            async {
                val rtt = gate.withPermit { doTest(node, method) }
                updateNodeLatency(index, rtt)
                Pair(index, rtt)
            }
        }

        val results = deferredList.awaitAll()
        _isTestingAll.value = false

        // 选出最低延迟的可用节点下标
        results.filter { it.second >= 0 }.minByOrNull { it.second }?.first
    }

    private val updateMutex = kotlinx.coroutines.sync.Mutex()

    /** 批量测速并返回全部结果 [(index, rtt)] (rtt>=0 可用)。 */
    suspend fun testAllNodesDetailed(): List<Pair<Int, Long>> = withContext(Dispatchers.IO) {
        val list = _nodes.value
        if (list.isEmpty()) return@withContext emptyList()
        _isTestingAll.value = true
        _nodes.value = list.map { it.copy(isTesting = true, testError = null) }
        val method = _testMethod.value
        val gate = kotlinx.coroutines.sync.Semaphore(MAX_PARALLEL_TESTS)
        val results = list.mapIndexed { index, node ->
            async {
                val rtt = gate.withPermit { doTest(node, method) }
                updateNodeLatencySafe(index, rtt)
                Pair(index, rtt)
            }
        }.awaitAll()
        _isTestingAll.value = false
        results
    }

    /**
     * 执行底层连接测试。
     */
    private fun doTest(node: Node, method: String): Long {
        return try {
            when (method) {
                // 真实路径: 走内核, 完整跑一遍伪装 TLS 握手 + 认证 + TIME_SYNC。
                // 这是唯一能回答「这个节点现在能不能用」的测法。
                METHOD_CONNECT -> {
                    CoreController.testNode(node.uri, 5000)
                }
                // 只做 TCP 三次握手。保留它是因为它快且不消耗服务端握手配额,
                // 适合在几十个节点里先粗筛掉完全不可达的; 但它**测不出协议故障**。
                else -> {
                    val sock = Socket()
                    val t0 = System.currentTimeMillis()
                    val port = node.port.toIntOrNull() ?: 443
                    sock.connect(InetSocketAddress(node.server, port), 4000)
                    val rtt = System.currentTimeMillis() - t0
                    sock.close()
                    rtt
                }
            }
        } catch (e: Exception) {
            -1L
        }
    }

    private fun updateNodeTesting(index: Int, isTesting: Boolean) {
        val list = _nodes.value.toMutableList()
        if (index in list.indices) {
            list[index] = list[index].copy(isTesting = isTesting)
            _nodes.value = list
        }
    }

    private suspend fun updateNodeLatencySafe(index: Int, latency: Long) {
        updateMutex.withLock {
            val list = _nodes.value.toMutableList()
            if (index in list.indices) {
                list[index] = list[index].copy(
                    latencyMs = if (latency >= 0) latency else null,
                    isTesting = false,
                    testError = if (latency < 0) context.getString(R.string.node_unreachable) else null
                )
                _nodes.value = list
            }
        }
    }

    private fun updateNodeLatency(index: Int, latency: Long) {
        val list = _nodes.value.toMutableList()
        if (index in list.indices) {
            list[index] = list[index].copy(
                latencyMs = if (latency >= 0) latency else null,
                isTesting = false,
                testError = if (latency < 0) context.getString(R.string.node_unreachable) else null
            )
            _nodes.value = list
        }
    }

    /**
     * 从剪贴板文本中提取并解析 mirage:// 链接。
     */
    fun extractNodesFromText(text: String): List<Node> {
        val regex = Regex("""mirage://[^\s<>"]+""")
        return regex.findAll(text).map { match ->
            val uri = match.value.trim()
            Node(uri = uri, name = Node.defaultName(uri))
        }.toList()
    }

    companion object {
        private const val PREFS_NAME = "mirage_nodes"
        private const val KEY_NODES = "nodes_json"
        private const val KEY_SELECTED = "selected_index"
        private const val KEY_POOL = "pool_size"
        private const val KEY_AUTO_SELECT = "auto_select"
        private const val KEY_TEST_METHOD = "test_method"

        /** 走内核完整握手。默认值。 */
        const val METHOD_CONNECT = "connect"

        /** 只做 TCP 三次握手。快, 但测不出协议故障。 */
        const val METHOD_TCP = "tcp"

        const val DEFAULT_TEST_METHOD = METHOD_CONNECT

        /**
         * 批量测速的并发上限。
         *
         * 默认测法从 `tcp` 改成 `connect` 之后, 每个节点都是一次完整的协议握手,
         * 而批量测速原先是对全表无上限 `async`。几十个节点同时握手既打自己也打
         * 服务端 (上游 v0.16.1 的 CHANGELOG 记过: 两个错口令客户端几分钟内让
         * 服务端记录 662 条 auth failed)。4 是被删掉的外壳看门狗 `doFailover`
         * 里用的同一个值。
         */
        private const val MAX_PARALLEL_TESTS = 4

        /**
         * 历史上存过的 `ping` 其实走的是 TCP 分支 —— 选项名与行为不符。
         * 选项已从界面移除, 这里把旧值归一到它真正的行为上, 避免用户看到一个
         * 界面里不存在的测法。
         */
        fun normalizeTestMethod(raw: String?): String = when (raw) {
            METHOD_CONNECT -> METHOD_CONNECT
            METHOD_TCP -> METHOD_TCP
            else -> if (raw == null) DEFAULT_TEST_METHOD else METHOD_TCP
        }

        @Volatile
        private var instance: NodeRepository? = null

        fun getInstance(context: Context): NodeRepository {
            return instance ?: synchronized(this) {
                instance ?: NodeRepository(context.applicationContext).also { instance = it }
            }
        }
    }
}
