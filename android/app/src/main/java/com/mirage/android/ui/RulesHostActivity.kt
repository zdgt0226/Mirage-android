package com.mirage.android.ui

import android.os.Bundle
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat

/**
 * 分流规则页的宿主。
 *
 * **这是过渡件。** 规则页（[RulesFragment]，569 行，含拖拽重排）在导航重构的这一段
 * 里还没有 Compose 版本，而手势外壳的设置页需要一个能跳进去的目标。把它塞进
 * `AndroidView` 内嵌到 Pager 页里是更坏的选择：Pager 会按需组合/销毁页面，
 * Fragment 的生命周期跟着反复重建，拖拽状态与滚动位置都保不住。
 *
 * 因此这里用一个独立 Activity 承载它 —— 外壳不碰 Fragment，Fragment 不碰外壳。
 * 规则页改写为 Compose 之后，本文件与 [RulesFragment] 一并删除。
 */
class RulesHostActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        val container = FrameLayout(this).apply {
            id = CONTAINER_ID
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
        }
        setContentView(container)

        // savedInstanceState != null 时 FragmentManager 已经自己恢复了实例,
        // 再 add 一次会叠两份规则列表。
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(CONTAINER_ID, RulesFragment())
                .commit()
        }
    }

    private companion object {
        /** 代码里建的容器没有资源 id, 必须给一个稳定值供 FragmentManager 恢复时寻址。 */
        const val CONTAINER_ID = 0x7E000001
    }
}
