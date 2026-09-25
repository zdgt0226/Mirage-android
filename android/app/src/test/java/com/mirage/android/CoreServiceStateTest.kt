package com.mirage.android

import com.mirage.android.CoreService.ServiceState
import com.mirage.android.CoreService.StartVerdict
import com.mirage.android.CoreService.StateMachine
import com.mirage.android.data.model.AppFilterConfig
import com.mirage.android.data.model.AppFilterMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CoreService 状态机回归测试。
 *
 * 断言的是生产裁决对象 [StateMachine] 本身 —— onStartCommand、startInternal、
 * stopInternal 三处守卫都只调用它，所以这里覆盖的就是真实执行路径，
 * 而不是在测试里重写一遍相同的条件判断。
 */
class CoreServiceStateTest {

    // ── startInternal 入口裁决 ──────────────────────────────────────────

    /**
     * 「用户点断开后 VPN 自己回来」的核心回归。
     *
     * stopInternal 在锁内、拆除之前就把状态置为 Stopping。failoverRestartJob 的
     * cancel() 是协作式的，可能已经越过 isActive 检查并在 stateLock 上排队；
     * 它拿到锁时必须看到 Stopping 并放弃，否则会重建刚被用户停掉的 VPN。
     */
    @Test
    fun startIsRejectedWhileStopping() {
        // 原生 isRunning 在停止流程中途会短暂为 false，两种取值都不得放行
        assertSame(
            StartVerdict.RejectStopping,
            StateMachine.verdictForStart(ServiceState.Stopping, nativeRunning = false)
        )
        assertSame(
            StartVerdict.RejectStopping,
            StateMachine.verdictForStart(ServiceState.Stopping, nativeRunning = true)
        )
    }

    @Test
    fun startProceedsFromStopped() {
        assertSame(
            StartVerdict.Proceed,
            StateMachine.verdictForStart(ServiceState.Stopped, nativeRunning = false)
        )
    }

    /**
     * Running 但原生内核已经死了（崩溃 / 被系统回收）时必须允许重启，
     * 否则 failover 永远拉不起来。
     */
    @Test
    fun startProceedsWhenStateSaysRunningButNativeIsDead() {
        assertSame(
            StartVerdict.Proceed,
            StateMachine.verdictForStart(ServiceState.Running, nativeRunning = false)
        )
    }

    @Test
    fun startShortCircuitsWhenAlreadyRunning() {
        assertSame(
            StartVerdict.AlreadyRunning,
            StateMachine.verdictForStart(ServiceState.Running, nativeRunning = true)
        )
    }

    /** Starting 期间再次进入应继续（failover 重启复用同一路径），不得被误判为已运行。 */
    @Test
    fun startProceedsWhileStarting() {
        assertSame(
            StartVerdict.Proceed,
            StateMachine.verdictForStart(ServiceState.Starting, nativeRunning = false)
        )
        assertSame(
            StartVerdict.Proceed,
            StateMachine.verdictForStart(ServiceState.Starting, nativeRunning = true)
        )
    }

    // ── onStartCommand 重入守卫 ────────────────────────────────────────

    @Test
    fun startCommandIsNotReentrantWhileStartingOrRunning() {
        assertFalse(StateMachine.acceptsStartCommand(ServiceState.Starting))
        assertFalse(StateMachine.acceptsStartCommand(ServiceState.Running))
        assertTrue(StateMachine.acceptsStartCommand(ServiceState.Stopped))
        // 停止流程中的启动请求交给 startInternal 的 Stopping 守卫去拒绝，
        // 这里放行是为了不吞掉 startId（否则服务无法正常收尾）
        assertTrue(StateMachine.acceptsStartCommand(ServiceState.Stopping))
    }

    // ── stopInternal 入口守卫 ──────────────────────────────────────────

    @Test
    fun stopIsIdempotent() {
        assertTrue(StateMachine.shouldRunStop(ServiceState.Running))
        assertTrue(StateMachine.shouldRunStop(ServiceState.Starting))
        // 重入与已停止：不得重复执行拆除
        assertFalse(StateMachine.shouldRunStop(ServiceState.Stopping))
        assertFalse(StateMachine.shouldRunStop(ServiceState.Stopped))
    }

    // ── ACTION_STOP 时序防误杀 ──────────────────────────────────────────

    @Test
    fun stopCommandSequenceGuard() {
        // 正常场景: 针对当前 session 的停止指令予以放行
        assertTrue(StateMachine.shouldAcceptStopCommand(stopSeq = 1L, activeSessionSeq = 1L))
        assertTrue(StateMachine.shouldAcceptStopCommand(stopSeq = 2L, activeSessionSeq = 1L))

        // 误杀场景: 上一轮连接排队的 ACTION_STOP 在新一轮连接激活后才到达，必须拒绝
        assertFalse(StateMachine.shouldAcceptStopCommand(stopSeq = 1L, activeSessionSeq = 2L))
        assertFalse(StateMachine.shouldAcceptStopCommand(stopSeq = 5L, activeSessionSeq = 10L))

        // 无差别场景: 未指定序号 (如通知栏/磁贴直接触发)，予以放行
        assertTrue(StateMachine.shouldAcceptStopCommand(stopSeq = 0L, activeSessionSeq = 2L))
        assertTrue(StateMachine.shouldAcceptStopCommand(stopSeq = 0L, activeSessionSeq = 0L))
        assertTrue(StateMachine.shouldAcceptStopCommand(stopSeq = -1L, activeSessionSeq = 2L))
    }

    // ── 启动结果落状态 ────────────────────────────────────────────────

    /**
     * 启动失败必须回到 Stopped。否则服务会带着「已连接」的前台通知和零隧道
     * 继续驻留，用户以为自己受保护 —— rc=-2（VPN 授权被撤销）时流量全明文。
     */
    @Test
    fun failedStartReturnsToStopped() {
        assertEquals(ServiceState.Running, StateMachine.stateAfterStart(0))
        for (rc in listOf(-1, -2, -3, -4, -5, CoreService.RC_REJECTED_WHILE_STOPPING, -7)) {
            assertEquals(
                "rc=$rc 启动失败后必须回到 Stopped",
                ServiceState.Stopped,
                StateMachine.stateAfterStart(rc)
            )
        }
    }

    // ── ServiceConfig ────────────────────────────────────────────────

    /** 默认值是跨进程配置的兜底来源，改动会静默影响 :core 行为，需锁定。 */
    @Test
    fun serviceConfigDefaults() {
        val cfg = CoreService.ServiceConfig()
        assertEquals("", cfg.uri)
        assertEquals(-1, cfg.poolSize)
        assertFalse(cfg.bypassLan)
        assertEquals(1500, cfg.mtu)
        assertEquals("223.5.5.5", cfg.directDns)
        assertEquals("1.1.1.1", cfg.remoteDns)
        assertTrue(cfg.blockQuic)
        assertTrue(cfg.autoReconnect)
        assertEquals(15, cfg.checkIntervalSec)
        assertEquals("best", cfg.failoverMode)
        assertTrue(cfg.nodes.isEmpty())
        assertEquals(0, cfg.outboundMode)
    }

    @Test
    fun serviceConfigCarriesAppFilter() {
        val cfg = CoreService.ServiceConfig(
            uri = "mirage://pass@1.1.1.1:443?sni=sni.test",
            appFilterConfig = AppFilterConfig(
                enabled = true,
                mode = AppFilterMode.DISALLOW,
                selectedPackages = setOf("com.example.app")
            ),
            failoverMode = "next",
            outboundMode = 1
        )
        assertEquals("mirage://pass@1.1.1.1:443?sni=sni.test", cfg.uri)
        assertTrue(cfg.appFilterConfig!!.enabled)
        assertEquals(AppFilterMode.DISALLOW, cfg.appFilterConfig!!.mode)
        assertEquals("next", cfg.failoverMode)
        assertEquals(1, cfg.outboundMode)
    }
}
