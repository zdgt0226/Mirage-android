package com.mirage.android

import com.mirage.android.CoreService.FailoverControl
import com.mirage.android.CoreService.ServiceState
import com.mirage.android.CoreService.StartVerdict
import com.mirage.android.CoreService.StateMachine
import com.mirage.android.data.model.AppFilterConfig
import com.mirage.android.data.model.AppFilterMode
import org.json.JSONObject
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
     * stopInternal 在锁内、拆除之前就把状态置为 Stopping。内核「请求重启」事件
     * 是异步落地的，它可能已在 stateLock 上排队；拿到锁时必须看到 Stopping
     * 并放弃，否则会重建刚被用户停掉的 VPN。
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
        assertEquals("", cfg.rulesJson)
    }

    @Test
    fun serviceConfigCarriesAppFilter() {
        val cfg = CoreService.ServiceConfig(
            uri = "mirage://pass@203.0.113.1:443?sni=sni.test",
            appFilterConfig = AppFilterConfig(
                enabled = true,
                mode = AppFilterMode.DISALLOW,
                selectedPackages = setOf("com.example.app")
            ),
            failoverMode = "next",
            outboundMode = 1,
            rulesJson = """{"default":"proxy","rules":[]}"""
        )
        assertEquals("mirage://pass@203.0.113.1:443?sni=sni.test", cfg.uri)
        assertTrue(cfg.appFilterConfig!!.enabled)
        assertEquals(AppFilterMode.DISALLOW, cfg.appFilterConfig!!.mode)
        assertEquals("next", cfg.failoverMode)
        assertEquals(1, cfg.outboundMode)
        assertEquals("""{"default":"proxy","rules":[]}""", cfg.rulesJson)
    }

    // ── 下沉方案 P3: 故障切换控制面 ────────────────────────────────────

    /**
     * P3 的无声失败防线。
     *
     * 外壳看门狗已删除，内核看门狗是否干活完全取决于这份 JSON 的 `enabled`。
     * Rust 侧 `FailoverPolicy` 每个字段都带 `#[serde(default)]`，键名拼错会被
     * 静默丢弃并回落到默认值 `enabled = false` —— 故障切换整条链路失效，
     * 而日志里只有一行 `切换策略下发内核: ok=true`。所以这里逐字断言键名。
     */
    @Test
    fun policyJsonUsesExactRustFieldNames() {
        val json = JSONObject(
            FailoverControl.policyJson(mode = "best", intervalSec = 15, autoReconnect = true)
        )
        assertTrue("mode", json.has("mode"))
        assertTrue("interval_sec", json.has("interval_sec"))
        assertTrue("backoff_sec", json.has("backoff_sec"))
        assertTrue("restart_delay_ms", json.has("restart_delay_ms"))
        assertTrue("enabled", json.has("enabled"))
        assertEquals("best", json.getString("mode"))
        assertEquals(15, json.getInt("interval_sec"))
        assertEquals(3000, json.getInt("restart_delay_ms"))
    }

    /**
     * `enabled` 必须跟随「断线自动重连」开关。
     *
     * 外壳原看门狗在 autoReconnect 为 false 时只记日志不动手；内核整轮跳过与之
     * 等价。若这里恒为 true，用户关掉自动重连后内核仍会自己换节点。
     */
    @Test
    fun enabledTracksAutoReconnect() {
        val on = JSONObject(FailoverControl.policyJson("best", 15, autoReconnect = true))
        assertTrue(on.getBoolean("enabled"))
        val off = JSONObject(FailoverControl.policyJson("best", 15, autoReconnect = false))
        assertFalse(off.getBoolean("enabled"))
    }

    /**
     * 静态基线必须是 0。
     *
     * 真正的退避递增由内核自己累加（`failover::escalate_backoff`，+5s 上限 30s，
     * 照抄被删除的 failoverBackoffSec）。这里填非 0 会与内核的动态退避相加，
     * 两处都变慢且无人察觉。
     */
    @Test
    fun staticBackoffBaselineIsZero() {
        val json = JSONObject(FailoverControl.policyJson("next", 30, autoReconnect = true))
        assertEquals(0, json.getInt("backoff_sec"))
    }

    /**
     * 内核事件只在 Running 下执行。
     *
     * 事件在内核 runtime 线程上产生、异步落到外壳，与用户点断开同时发生是常态。
     * 「请求重启」在 Stopping 下放行会复活刚停掉的 VPN，在 Stopped 下放行等于凭一条
     * 迟到的事件把服务重新拉起来；「已热切换」在非 Running 下放行会把下标回流落盘到
     * UI 的 selected_index，表现为用户停掉 VPN 后选中的节点被悄悄换了。
     */
    @Test
    fun kernelEventsOnlyHonoredWhileRunning() {
        assertTrue(FailoverControl.shouldHonorEvent(ServiceState.Running))
        assertFalse(FailoverControl.shouldHonorEvent(ServiceState.Stopping))
        assertFalse(FailoverControl.shouldHonorEvent(ServiceState.Stopped))
        assertFalse(FailoverControl.shouldHonorEvent(ServiceState.Starting))
    }
}
