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
    fun `内核未运行时 connect 降级为 tcp`() {
        // CoreService 的原生守卫只在启动路径调 NativeLoader.load (守卫原则第 3 条:
        // 查询路径不得隐式装载, 内核选择必须只发生在受控启动流程里)。于是 :core
        // 只被 bind 拉起、从未 start() 过时, MirageNative.testNode 必返回 -1,
        // 每个节点都显示「不可用」—— 而未连接正是最常测速的时机。
        // 真机 2026-11-09 (SO-02K + 本地 v0.16.2): 未连接时两节点全不可用、服务端
        // 零握手; 连接后同样操作得 94ms/92ms 且服务端新增 17 次成功握手。
        assertEquals(
            NodeRepository.METHOD_TCP,
            NodeRepository.effectiveMethod(NodeRepository.METHOD_CONNECT, coreRunning = false),
        )
    }

    @Test
    fun `内核运行时 connect 保持 connect`() {
        assertEquals(
            NodeRepository.METHOD_CONNECT,
            NodeRepository.effectiveMethod(NodeRepository.METHOD_CONNECT, coreRunning = true),
        )
    }

    @Test
    fun `显式选 tcp 时不受内核运行状态影响`() {
        // 降级只针对 connect。用户显式选了 tcp 就是 tcp, 不该因为连上了就偷偷升级 ——
        // 那会让同一个设置在不同时刻量出不同含义的数字。
        assertEquals(
            NodeRepository.METHOD_TCP,
            NodeRepository.effectiveMethod(NodeRepository.METHOD_TCP, coreRunning = true),
        )
        assertEquals(
            NodeRepository.METHOD_TCP,
            NodeRepository.effectiveMethod(NodeRepository.METHOD_TCP, coreRunning = false),
        )
    }

    @Test
    fun `未知值落到 tcp 而不是 connect`() {
        // 保守侧: 未知值不该触发对每个节点的完整协议握手。
        assertEquals(NodeRepository.METHOD_TCP, NodeRepository.normalizeTestMethod(""))
        assertEquals(NodeRepository.METHOD_TCP, NodeRepository.normalizeTestMethod("icmp"))
    }
}
