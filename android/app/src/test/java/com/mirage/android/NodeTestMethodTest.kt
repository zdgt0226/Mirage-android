package com.mirage.android

import com.mirage.android.data.repository.NodeRepository
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 测速方式的取值契约。
 *
 * 为什么值得单测：这三条的失败形态都**不报错**。
 *  1. 默认值退回 `tcp` —— `tcp` 只做 `Socket.connect()`，节点 TCP 可达但协议握手
 *     失败时照样显示绿色健康值（2026-11-09 真机：生产节点暖池 100%
 *     `Timeout before CCS`，测速仍报 4ms）。测速从此测不出节点可用性。
 *  2. 旧的 `ping` 值在 `doTest` 里落到 TCP 分支，而选项已从界面移除。归一化
 *     漏掉它，用户设置页会显示一个界面里不存在的测法。
 *  3. 未知值落到哪一档：必须是 `tcp`（保守、快），不能是 `connect`（会对每个
 *     节点发起完整握手）。
 */
class NodeTestMethodTest {

    @Test
    fun `默认测速方式是走内核的 connect 而不是 tcp`() {
        assertEquals(NodeRepository.METHOD_CONNECT, NodeRepository.DEFAULT_TEST_METHOD)
        // 缺省读取（prefs 里没存过）必须落到默认值。
        assertEquals(NodeRepository.METHOD_CONNECT, NodeRepository.normalizeTestMethod(null))
    }

    @Test
    fun `两个有效值原样保留`() {
        assertEquals(NodeRepository.METHOD_CONNECT, NodeRepository.normalizeTestMethod("connect"))
        assertEquals(NodeRepository.METHOD_TCP, NodeRepository.normalizeTestMethod("tcp"))
    }

    @Test
    fun `历史 ping 值归一到它真正的行为 tcp`() {
        // ping 从来没有独立实现, doTest 里它走的就是 TCP 分支。
        assertEquals(NodeRepository.METHOD_TCP, NodeRepository.normalizeTestMethod("ping"))
    }

    @Test
    fun `未知值落到 tcp 而不是 connect`() {
        // 保守侧: 未知值不该触发对每个节点的完整协议握手。
        assertEquals(NodeRepository.METHOD_TCP, NodeRepository.normalizeTestMethod(""))
        assertEquals(NodeRepository.METHOD_TCP, NodeRepository.normalizeTestMethod("icmp"))
    }
}
