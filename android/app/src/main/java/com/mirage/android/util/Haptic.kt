package com.mirage.android.util

import android.view.HapticFeedbackConstants
import android.view.View

/**
 * 物理级触觉反馈工具类 (遵循 mobile-app-ui-design 微交互规范)
 */
object Haptic {

    /** 轻触 (按钮点击、卡片点击、Tab 切换) */
    fun tap(view: View?) {
        view?.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
    }

    /** 状态切换 (Switch 开关、CheckBox 勾选、RadioButton 切换) */
    fun toggle(view: View?) {
        view?.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
    }

    /** 核心确认 (VPN 成功连接、一键断开、保存配置) */
    fun confirm(view: View?) {
        view?.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
    }

    /** 拖拽排序起止与重要长按 */
    fun longPress(view: View?) {
        view?.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
    }
}
