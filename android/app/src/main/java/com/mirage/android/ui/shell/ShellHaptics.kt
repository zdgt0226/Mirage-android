package com.mirage.android.ui.shell

import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalView
import com.mirage.android.util.Haptic

/**
 * 手势外壳的触觉反馈入口。
 *
 * **为什么不用 Compose 自带的 `LocalHapticFeedback`**：它只暴露
 * `HapticFeedbackType.LongPress` 与 `TextHandleMove` 两种，而本仓库的 [Haptic]
 * 覆盖 tap / toggle / confirm / reject / segmentTick / longPress 六档，且各自带了
 * API 30 / API 34 的回退（见 `Haptic.kt`，下限机 SO-02K 是 API 28）。
 * 换成 Compose 那两档等于把这些语义压成一种。
 *
 * **为什么要有这个包装**：[Haptic] 收 `View?`，Compose 侧每个调用点都写
 * `Haptic.tap(LocalView.current)` 会把 `LocalView` 读进每个 composable 的作用域。
 * 这里按 view 记忆一次，调用点只剩语义。
 */
class ShellHaptics internal constructor(private val view: View?) {

    /** 轻触：按钮、chip、列表项、分块展开。 */
    fun tap() = Haptic.tap(view)

    /** 状态切换：主开关按下、节点选中、开关型 chip。 */
    fun toggle() = Haptic.toggle(view)

    /** 核心确认：VPN 连上、热切换成功。 */
    fun confirm() = Haptic.confirm(view)

    /** 操作拒绝：无节点可连、校验失败、进入错误态。 */
    fun reject() = Haptic.reject(view)

    /** 分段滴答：翻页落定、面板吸附到档位。 */
    fun segmentTick() = Haptic.segmentTick(view)

    /** 长按：溢出菜单由长按唤出时。 */
    fun longPress() = Haptic.longPress(view)
}

@Composable
fun rememberShellHaptics(): ShellHaptics {
    val view = LocalView.current
    return remember(view) { ShellHaptics(view) }
}
