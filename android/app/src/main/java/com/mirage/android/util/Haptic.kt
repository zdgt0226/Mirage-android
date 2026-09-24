package com.mirage.android.util

import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View

/**
 * 物理级触觉反馈工具类 (遵循 mobile-app-ui-design 微交互规范与 AUDIT_HANDOFF §5.2 最低版本回退契约)
 */
object Haptic {

    /** 轻触 (按钮点击、卡片点击、Tab 切换) - API 1 直接可用 */
    fun tap(view: View?) {
        view?.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
    }

    /** 状态切换 (Switch 开关、CheckBox 勾选、RadioButton 切换) - API 1 直接可用 */
    fun toggle(view: View?) {
        view?.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
    }

    /** 核心确认 (VPN 成功连接、一键断开、保存配置) - API 30 CONFIRM, API 28 回退 VIRTUAL_KEY */
    fun confirm(view: View?) {
        val constant = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            HapticFeedbackConstants.CONFIRM
        } else {
            HapticFeedbackConstants.VIRTUAL_KEY
        }
        view?.performHapticFeedback(constant)
    }

    /** 操作拒绝 / 校验失败 - API 30 REJECT, API 28 回退 LONG_PRESS */
    fun reject(view: View?) {
        val constant = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            HapticFeedbackConstants.REJECT
        } else {
            HapticFeedbackConstants.LONG_PRESS
        }
        view?.performHapticFeedback(constant)
    }

    /** 分段选择 / 齿轮微滴答 - API 34 SEGMENT_TICK, API 28 回退 VIRTUAL_KEY */
    fun segmentTick(view: View?) {
        val constant = if (Build.VERSION.SDK_INT >= 34) {
            HapticFeedbackConstants.SEGMENT_TICK
        } else {
            HapticFeedbackConstants.VIRTUAL_KEY
        }
        view?.performHapticFeedback(constant)
    }

    /** 拖拽排序起止与重要长按 - API 3 直接可用 */
    fun longPress(view: View?) {
        view?.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
    }
}
