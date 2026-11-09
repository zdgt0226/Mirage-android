package com.mirage.android.ui.shell

import android.provider.Settings
import androidx.compose.animation.core.DecayAnimationSpec
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.exponentialDecay
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/**
 * 手势外壳的运动参数。
 *
 * 这里只做两件事: 把 Apple 的 (damping, response) 二参模型换算成 Compose 的
 * (dampingRatio, stiffness), 以及统一减弱动效的退化路径。
 *
 * **刻意不实现动量投射与速度接力。** `AnchoredDraggableState` 的 `decayAnimationSpec`
 * 与 `settle(velocity)` 已经是「按释放速度衰减出落点再吸附」的完整实现
 * (foundation 1.7.8), `HorizontalPager` 的 `PagerDefaults.flingBehavior` 同理。
 * 自己再写一遍等于替换掉框架的手势管线 —— 那正是 `UI_REDESIGN_PROPOSAL.md`
 * §0.2 与 §12.5 第 5 条明令禁止的事, 且 UI-3 已实测证明框架能力足够。
 */
object ShellMotion {

    /**
     * Apple 的 `response`（秒）换算为 Compose `spring()` 的 `stiffness`。
     *
     * Compose 的弹簧质量恒为 1, 故无阻尼自然频率 `ω = sqrt(stiffness)`;
     * Apple 定义 `response = 2π / ω`。两式消去 ω 即得本函数。
     *
     * 为什么必须换算而不是直接挑 `Spring.StiffnessMedium`:
     * 那些常量是 1500 / 400 / 200, 对应 response 分别约 0.16s / 0.31s / 0.44s,
     * 没有一个落在 WWDC 给出的 0.4s（移动）与 0.3s（抽屉）上。直接套常量会让
     * 抽屉比规范快一倍或慢一半, 而这两个值是有实测依据的人机参数, 不是口味。
     *
     * @param responseSec 必须为正。非正值会让 stiffness 发散或为负, 弹簧直接不收敛,
     *   所以在这里钳死而不是交给调用方自觉。
     */
    fun stiffnessForResponse(responseSec: Float): Float {
        val r = responseSec.coerceAtLeast(MIN_RESPONSE_SEC)
        val omega = (2.0 * Math.PI / r).toFloat()
        return omega * omega
    }

    /** `stiffnessForResponse` 的下限。比这更快的弹簧在 60Hz 下单帧位移已超感知阈值。 */
    const val MIN_RESPONSE_SEC: Float = 0.05f

    /** 阻尼比: 临界阻尼, 无过冲。默认值, 用于一切非动量驱动的位移。 */
    const val DAMPING_NO_BOUNCE: Float = 1.0f

    /** 阻尼比: 轻微过冲。只给由用户甩动/拖拽释放驱动的位移 —— 凭空出现的面板不该回弹。 */
    const val DAMPING_GENTLE_BOUNCE: Float = 0.8f

    /** WWDC「移动/重定位」档: damping 1.0 / response 0.4。 */
    fun <T> move(): FiniteAnimationSpec<T> = spring(
        dampingRatio = DAMPING_NO_BOUNCE,
        stiffness = stiffnessForResponse(0.4f),
    )

    /** WWDC「抽屉/面板」档: damping 0.8 / response 0.3。上滑详情面板的吸附用它。 */
    fun <T> drawer(): FiniteAnimationSpec<T> = spring(
        dampingRatio = DAMPING_GENTLE_BOUNCE,
        stiffness = stiffnessForResponse(0.3f),
    )

    /** 按压反馈档: 临界阻尼 + response 0.15。必须起于 pointer-down, 不等抬手。 */
    fun <T> press(): FiniteAnimationSpec<T> = spring(
        dampingRatio = DAMPING_NO_BOUNCE,
        stiffness = stiffnessForResponse(0.15f),
    )

    /**
     * 面板的动量衰减。`AnchoredDraggableState` 用它把释放速度投射成落点。
     *
     * `frictionMultiplier = 1f` 对应 Android 平台默认减速度; Apple 示例代码里的
     * `decelerationRate 0.998` 是同一量级的滚动衰减, 两者不叠加也不互换。
     */
    fun decay(): DecayAnimationSpec<Float> = exponentialDecay(
        frictionMultiplier = 1f,
        absVelocityThreshold = 0.1f,
    )
}

/**
 * 系统是否关闭了动效（开发者选项的动画时长缩放, 或无障碍的减弱动效）。
 *
 * 与 `FluidSpring.isAnimationDisabled` 同一判据, 刻意不复用那个函数:
 * 它接 `Context` 且在 View 体系里按调用点取值, 这里需要的是随 composition
 * 记忆一次的 Compose 侧读取。判据相同, 不会出现两套 UI 对减弱动效态度不一致。
 */
@Composable
fun rememberReducedMotion(): Boolean {
    val context = LocalContext.current
    return remember(context) {
        runCatching {
            Settings.Global.getFloat(
                context.contentResolver,
                Settings.Global.ANIMATOR_DURATION_SCALE,
                1.0f,
            ) == 0f
        }.getOrDefault(false)
    }
}

/** 减弱动效时把弹簧换成瞬时跳变, 保留状态变化本身。 */
@Composable
fun <T> motionAware(spec: FiniteAnimationSpec<T>): FiniteAnimationSpec<T> =
    if (rememberReducedMotion()) snap() else spec
