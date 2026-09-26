package com.mirage.android

import com.mirage.android.CoreService
import com.mirage.android.data.repository.VpnRepository.BroadcastArbiter
import com.mirage.android.data.repository.VpnRepository.BroadcastArbiter.TargetState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [BroadcastArbiter] 广播状态裁决纯 JVM 单元测试。
 *
 * 测试目标：
 * 验证对 API < 33 动态 receiver 伪造广播的纵深防御逻辑，
 * 确保外部应用发送仿冒广播时无法伪造连接状态，也无法冒充停止确认。
 */
class BroadcastArbiterTest {

    // ── 场景 1: 已绑定状态下以 AIDL 权威为准 (免疫外部伪造广播) ────────────────

    /**
     * 核心防御测试：VPN 运行中收到外部仿冒的 ACTION_VPN_STOPPED 广播。
     *
     * 此时由于已绑定且 Core 正在运行 (isCoreRunning = true)，
     * 裁决必须强制维持 CONNECTED，且绝不可冒充停止确认 (completeStopConfirmation = false)。
     */
    @Test
    fun boundAndRunning_fakeStoppedBroadcastIgnored() {
        val verdict = BroadcastArbiter.judge(
            action = CoreService.ACTION_VPN_STOPPED,
            isBound = true,
            isCoreRunning = true
        )
        assertEquals(TargetState.CONNECTED, verdict.targetState)
        assertFalse("运行中收到 STOP 广播不得完成停止确认", verdict.completeStopConfirmation)
    }

    /**
     * 已绑定且运行中收到合法的 ACTION_VPN_STARTED 广播，保持 CONNECTED。
     */
    @Test
    fun boundAndRunning_startedBroadcastConfirmed() {
        val verdict = BroadcastArbiter.judge(
            action = CoreService.ACTION_VPN_STARTED,
            isBound = true,
            isCoreRunning = true
        )
        assertEquals(TargetState.CONNECTED, verdict.targetState)
        assertFalse(verdict.completeStopConfirmation)
    }

    /**
     * 已绑定且 AIDL 核实已停止 (isCoreRunning = false)，收到 ACTION_VPN_STOPPED 广播。
     *
     * 属于经 AIDL 权威核实的合法停止，允许置 DISCONNECTED 并完成停止确认。
     */
    @Test
    fun boundAndStopped_verifiedStoppedBroadcastConfirmed() {
        val verdict = BroadcastArbiter.judge(
            action = CoreService.ACTION_VPN_STOPPED,
            isBound = true,
            isCoreRunning = false
        )
        assertEquals(TargetState.DISCONNECTED, verdict.targetState)
        assertTrue("经 AIDL 核实已停止的广播允许确认 stopConfirmation", verdict.completeStopConfirmation)
    }

    /**
     * 服务已停止但外部发来伪造的 ACTION_VPN_STARTED 广播。
     *
     * 此时 AIDL 状态显示未运行 (isCoreRunning = false)，裁决必须拒绝置为 CONNECTED。
     */
    @Test
    fun boundAndStopped_fakeStartedBroadcastRejected() {
        val verdict = BroadcastArbiter.judge(
            action = CoreService.ACTION_VPN_STARTED,
            isBound = true,
            isCoreRunning = false
        )
        assertEquals(TargetState.DISCONNECTED, verdict.targetState)
        assertFalse("伪造的 STARTED 广播绝不可触发停止确认", verdict.completeStopConfirmation)
    }

    // ── 场景 2: 未绑定状态下仅采信为提示，绝不赋予权威停止确认 ──────────────────

    /**
     * 未绑定时收到 ACTION_VPN_STARTED 广播，采信提示置为 CONNECTED。
     */
    @Test
    fun unbound_startedBroadcastAcceptedAsHint() {
        val verdict = BroadcastArbiter.judge(
            action = CoreService.ACTION_VPN_STARTED,
            isBound = false,
            isCoreRunning = null
        )
        assertEquals(TargetState.CONNECTED, verdict.targetState)
        assertFalse(verdict.completeStopConfirmation)
    }

    /**
     * 未绑定时收到 ACTION_VPN_STOPPED 广播：
     *
     * 可以将 UI 提示置为 DISCONNECTED，但由于缺乏 AIDL 权威凭据，
     * 绝不可完成停止确认 (completeStopConfirmation 必须为 false)，
     * 防止外部仿冒广播篡改 stopVpn() 的停止确认时序。
     */
    @Test
    fun unbound_stoppedBroadcastAcceptedAsHintOnly_neverConfirmsStop() {
        val verdict = BroadcastArbiter.judge(
            action = CoreService.ACTION_VPN_STOPPED,
            isBound = false,
            isCoreRunning = null
        )
        assertEquals(TargetState.DISCONNECTED, verdict.targetState)
        assertFalse("未绑定时收到的 STOP 广播无 AIDL 凭据，绝对不能 completeStopConfirmation", verdict.completeStopConfirmation)
    }

    // ── 场景 3: 未知或空广播过滤 ────────────────────────────────────────

    @Test
    fun unknownOrNullAction_ignored() {
        val nullVerdict = BroadcastArbiter.judge(
            action = null,
            isBound = true,
            isCoreRunning = true
        )
        assertEquals(TargetState.NO_CHANGE, nullVerdict.targetState)
        assertFalse(nullVerdict.completeStopConfirmation)

        val unknownVerdict = BroadcastArbiter.judge(
            action = "com.example.FAKE_ACTION",
            isBound = false
        )
        assertEquals(TargetState.NO_CHANGE, unknownVerdict.targetState)
        assertFalse(unknownVerdict.completeStopConfirmation)
    }
}
