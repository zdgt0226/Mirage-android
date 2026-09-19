package com.mirage.android.core

import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.util.Log
import com.mirage.android.data.model.RecentRequestInfo
import com.mirage.android.data.model.TrafficStats
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter

/**
 * 统一命令总线客户端 (Command Bus Client)。
 *
 * 通过 Linux 抽象命名空间 Unix 域套接字 (`@mirage_cmd.sock`) 直连原生内核：
 * 1. 规避 Android Binder 1MB 事务上限 (`TransactionTooLargeException`)；
 * 2. 毫秒级流式消费实时速率 (1s 周期) 与 Recent Requests 请求快照 (2s 周期)；
 * 3. 允许零轮询向内核发送控制指令 (如 `close_connection`)。
 */
object CommandBusClient {
    private const val TAG = "CommandBusClient"
    private const val SOCKET_ABSTRACT_NAME = "mirage_cmd.sock"

    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    private val _statsFlow = MutableSharedFlow<TrafficStats>(
        replay = 1,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val statsFlow: SharedFlow<TrafficStats> = _statsFlow.asSharedFlow()

    private val _recentRequestsFlow = MutableSharedFlow<List<RecentRequestInfo>>(
        replay = 1,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val recentRequestsFlow: SharedFlow<List<RecentRequestInfo>> = _recentRequestsFlow.asSharedFlow()

    private var clientJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    @Volatile
    private var currentSocket: LocalSocket? = null
    @Volatile
    private var currentWriter: BufferedWriter? = null

    @Synchronized
    fun start() {
        if (clientJob?.isActive == true) return
        clientJob = scope.launch {
            while (isActive) {
                var socket: LocalSocket? = null
                try {
                    socket = LocalSocket()
                    val address = LocalSocketAddress(SOCKET_ABSTRACT_NAME, LocalSocketAddress.Namespace.ABSTRACT)
                    socket.connect(address)
                    currentSocket = socket

                    val reader = BufferedReader(InputStreamReader(socket.inputStream, Charsets.UTF_8))
                    val writer = BufferedWriter(OutputStreamWriter(socket.outputStream, Charsets.UTF_8))
                    currentWriter = writer

                    _isConnected.value = true
                    Log.i(TAG, "已连接到内核命令总线: @$SOCKET_ABSTRACT_NAME")

                    while (isActive) {
                        val line = reader.readLine() ?: break
                        if (line.isBlank()) continue
                        handleMessage(line)
                    }
                } catch (e: Exception) {
                    if (isActive) {
                        Log.d(TAG, "命令总线连接断开或待就绪: ${e.message}")
                    }
                } finally {
                    _isConnected.value = false
                    currentWriter = null
                    runCatching { currentSocket?.close() }
                    currentSocket = null
                    delay(1000)
                }
            }
        }
    }

    @Synchronized
    fun stop() {
        clientJob?.cancel()
        clientJob = null
        _isConnected.value = false
        currentWriter = null
        runCatching { currentSocket?.close() }
        currentSocket = null
    }

    /**
     * 向内核发送关闭指定连接指令
     */
    fun sendCloseConnection(id: Long) {
        scope.launch {
            try {
                val writer = currentWriter ?: return@launch
                val payload = JSONObject().apply {
                    put("action", "close_connection")
                    put("id", id)
                }.toString() + "\n"
                synchronized(writer) {
                    writer.write(payload)
                    writer.flush()
                }
            } catch (e: Exception) {
                Log.w(TAG, "发送 close_connection 指令失败: ${e.message}")
            }
        }
    }

    private fun handleMessage(jsonStr: String) {
        runCatching {
            val obj = JSONObject(jsonStr)
            when (obj.optString("event")) {
                "stats" -> {
                    val stats = TrafficStats(
                        upTotal = obj.optDouble("up", 0.0),
                        downTotal = obj.optDouble("down", 0.0),
                        upRate = obj.optDouble("up_rate", 0.0),
                        downRate = obj.optDouble("down_rate", 0.0),
                        tcpConns = obj.optInt("tcp", 0),
                        udpFlows = obj.optInt("udp", 0),
                        dnsQueries = obj.optLong("dns", 0L)
                    )
                    _statsFlow.tryEmit(stats)
                }
                "recent_requests" -> {
                    val arr = obj.optJSONArray("data") ?: run {
                        val dataStr = obj.optString("data")
                        if (dataStr.isNotBlank() && dataStr != "[]") {
                            runCatching { JSONArray(dataStr) }.getOrNull()
                        } else null
                    }
                    if (arr != null && arr.length() > 0) {
                        val list = ArrayList<RecentRequestInfo>(arr.length())
                        for (i in 0 until arr.length()) {
                            list.add(RecentRequestInfo.fromJson(arr.getJSONObject(i)))
                        }
                        _recentRequestsFlow.tryEmit(list)
                    } else {
                        _recentRequestsFlow.tryEmit(emptyList())
                    }
                }
                "pong" -> {
                    Log.d(TAG, "命令总线心跳响应: pong")
                }
            }
        }.onFailure {
            Log.w(TAG, "解析总线推送消息异常: ${it.message}")
        }
    }
}
