package com.mirage.android.ui.common

import android.content.Context
import android.provider.Settings
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import androidx.dynamicanimation.animation.DynamicAnimation
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import java.util.WeakHashMap

/**
 * 流体交互与物理弹簧基础工具 (对齐 WWDC Fluid Interfaces 交互模型)
 *
 * 核心设计契约:
 * 1. 即时响应 (Responsiveness): 交互反馈始于 ACTION_DOWN, 绝不拖延到抬手
 * 2. 连续可中断 (Interruptibility): 动画通过 SpringAnimation.animateToFinalPosition 驱动,
 *    天然从当前呈现值 (Current Presentation Value) 起算并继承瞬时速度, 彻底废除固定时长补间
 * 3. 减弱动效 (Reduce Motion): 严格检测系统 ANIMATOR_DURATION_SCALE, 为 0 时退化为即时状态切换
 * 4. 物理参数规范: 默认采用严格临界阻尼 (DAMPING_RATIO_NO_BOUNCY = 1.0f, 无多余过冲震荡)
 *    与中等刚度 (STIFFNESS_MEDIUM = 1500f)
 */
object FluidSpring {

    const val DEFAULT_DAMPING: Float = SpringForce.DAMPING_RATIO_NO_BOUNCY
    const val DEFAULT_STIFFNESS: Float = SpringForce.STIFFNESS_MEDIUM

    private class SpringState(val anim: SpringAnimation) {
        var activeEndListener: DynamicAnimation.OnAnimationEndListener? = null
    }

    // 针对每个 View 的每个属性缓存 SpringAnimation 实例,
    // 保证连续触发与中途反向时复用同一弹簧对象, 从当前瞬时呈现值平滑渡向新目标, 杜绝动画互搏
    private val springCache = WeakHashMap<View, MutableMap<DynamicAnimation.ViewProperty, SpringState>>()

    /**
     * 判断系统是否关闭了动效 (开发者选项或无障碍中的减弱动效)
     */
    fun isAnimationDisabled(context: Context): Boolean {
        return try {
            val scale = Settings.Global.getFloat(
                context.contentResolver,
                Settings.Global.ANIMATOR_DURATION_SCALE,
                1.0f
            )
            scale == 0f
        } catch (_: Exception) {
            false
        }
    }

    /**
     * 通用可中断弹簧动画驱动
     *
     * @param view 目标视图
     * @param property 要驱动的 ViewProperty (如 SCALE_X, SCALE_Y, ALPHA, TRANSLATION_Y 等)
     * @param target 目标最终值
     * @param dampingRatio 阻尼比 (默认 1.0f 临界阻尼)
     * @param stiffness 刚度 (默认 1500f)
     * @param endListener 动画完成或中断结束回调
     * @return 运行中的 SpringAnimation 实例; 若系统动效关闭则返回 null (已同步置入终值)
     */
    fun animateTo(
        view: View,
        property: DynamicAnimation.ViewProperty,
        target: Float,
        dampingRatio: Float = DEFAULT_DAMPING,
        stiffness: Float = DEFAULT_STIFFNESS,
        endListener: ((canceled: Boolean, value: Float) -> Unit)? = null
    ): SpringAnimation? {
        if (isAnimationDisabled(view.context)) {
            // 减弱动效开启: 取消正在进行的弹簧并直接置入终值
            val state = springCache[view]?.get(property)
            if (state != null && state.anim.isRunning) {
                state.activeEndListener?.let { state.anim.removeEndListener(it) }
                state.activeEndListener = null
                state.anim.cancel()
            }
            property.setValue(view, target)
            endListener?.invoke(false, target)
            return null
        }

        val propMap = springCache.getOrPut(view) { mutableMapOf() }
        val state = propMap.getOrPut(property) {
            val anim = SpringAnimation(view, property).apply {
                spring = SpringForce().apply {
                    this.dampingRatio = dampingRatio
                    this.stiffness = stiffness
                }
            }
            SpringState(anim)
        }

        val anim = state.anim
        if (anim.spring == null) {
            anim.spring = SpringForce()
        }
        anim.spring.dampingRatio = dampingRatio
        anim.spring.stiffness = stiffness

        // 移除上一轮尚未触发的旧回调, 避免反向或新目标时触发陈旧回调
        state.activeEndListener?.let {
            anim.removeEndListener(it)
            state.activeEndListener = null
        }

        if (endListener != null) {
            val listener = object : DynamicAnimation.OnAnimationEndListener {
                override fun onAnimationEnd(
                    animation: DynamicAnimation<out DynamicAnimation<*>>?,
                    canceled: Boolean,
                    value: Float,
                    velocity: Float
                ) {
                    anim.removeEndListener(this)
                    if (state.activeEndListener === this) {
                        state.activeEndListener = null
                    }
                    endListener(canceled, value)
                }
            }
            state.activeEndListener = listener
            anim.addEndListener(listener)
        }

        anim.animateToFinalPosition(target)
        return anim
    }

    /**
     * 绑定按压弹性形变 (ACTION_DOWN 压迫至 pressedScale, 抬手/取消弹性恢复 1.0x)
     *
     * @param touchTarget 接收触控事件的 View
     * @param visualTarget 实际执行缩放形变的 View (默认等于 touchTarget)
     * @param pressedScale 按下时的缩放系数 (默认 0.96x)
     * @param triggerHapticOnDown 按下瞬间是否触发即时触感反馈 (默认 true)
     */
    fun attachPressScale(
        touchTarget: View,
        visualTarget: View = touchTarget,
        pressedScale: Float = 0.96f,
        triggerHapticOnDown: Boolean = true
    ) {
        touchTarget.setOnTouchListener(
            FluidPressTouchListener(visualTarget, pressedScale, triggerHapticOnDown)
        )
    }

    class FluidPressTouchListener(
        private val targetView: View,
        private val pressedScale: Float = 0.96f,
        private val triggerHapticOnDown: Boolean = true
    ) : View.OnTouchListener {

        private var isPressedDown = false

        override fun onTouch(v: View, event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    isPressedDown = true
                    if (triggerHapticOnDown) {
                        com.mirage.android.util.Haptic.tap(v)
                    }
                    animateTo(targetView, DynamicAnimation.SCALE_X, pressedScale)
                    animateTo(targetView, DynamicAnimation.SCALE_Y, pressedScale)
                }
                MotionEvent.ACTION_MOVE -> {
                    if (isPressedDown) {
                        val slop = ViewConfiguration.get(v.context).scaledTouchSlop
                        if (event.x < -slop || event.x > v.width + slop ||
                            event.y < -slop || event.y > v.height + slop
                        ) {
                            isPressedDown = false
                            animateTo(targetView, DynamicAnimation.SCALE_X, 1.0f)
                            animateTo(targetView, DynamicAnimation.SCALE_Y, 1.0f)
                        }
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (isPressedDown) {
                        isPressedDown = false
                        animateTo(targetView, DynamicAnimation.SCALE_X, 1.0f)
                        animateTo(targetView, DynamicAnimation.SCALE_Y, 1.0f)
                    }
                }
            }
            return false // 不拦截事件, 允许原有 OnClickListener / OnLongClickListener 正常处理
        }
    }
}
