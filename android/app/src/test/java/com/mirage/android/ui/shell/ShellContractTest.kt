package com.mirage.android.ui.shell

import com.mirage.android.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 手势外壳里几处纯逻辑的回归护栏。
 *
 * 这些函数之所以值得单测，是因为它们的失败形态都不报错：页序错了只是页面顺序
 * 反过来，刚度算错了只是弹簧手感不对，分块标题漏了一个分支只是显示成另一块的
 * 标题。全都要靠肉眼在真机上发现，而真机回归一轮几十分钟。
 */
class ShellContractTest {

    @Test
    fun `页序即视觉顺序 节点在左 设置在右`() {
        // 方向按手指方向定义: 手指向右滑露出左边那页。这个顺序一旦被改成
        // [设置][首页][节点], 两条滑动手势的语义同时反过来, 而编译照样通过。
        assertEquals(0, ShellPage.Nodes.ordinal)
        assertEquals(1, ShellPage.Home.ordinal)
        assertEquals(2, ShellPage.Settings.ordinal)
        assertEquals(3, ShellPage.COUNT)
    }

    @Test
    fun `越界页下标回落首页而不是抛异常`() {
        // Pager 在恢复状态时可能送来超出范围的下标 (页数变少的那次升级)。
        // 抛异常会让整个外壳组合失败, 表现为白屏。
        assertEquals(ShellPage.Home, ShellPage.of(-1))
        assertEquals(ShellPage.Home, ShellPage.of(99))
        assertEquals(ShellPage.Nodes, ShellPage.of(0))
        assertEquals(ShellPage.Settings, ShellPage.of(2))
    }

    @Test
    fun `response 换算刚度符合 WWDC 的两个档位`() {
        // k = (2π / response)^2。WWDC「移动」档 response 0.4s、「抽屉」档 0.3s。
        // 容差 1.0 是为了不把浮点实现细节钉死, 但足以挡住「直接套
        // Spring.StiffnessMedium=1500」这种偏差一个量级的写法。
        assertEquals(246.7f, ShellMotion.stiffnessForResponse(0.4f), 1.0f)
        assertEquals(438.6f, ShellMotion.stiffnessForResponse(0.3f), 1.0f)
        // 单调: response 越小越硬。
        assertTrue(
            ShellMotion.stiffnessForResponse(0.2f) > ShellMotion.stiffnessForResponse(0.5f),
        )
    }

    @Test
    fun `非正 response 被钳死而不产生发散或负刚度`() {
        // 负刚度的弹簧不收敛, 0 刚度的弹簧永远不动。调用方传 0 是很容易写出来的
        // (比如从一个未初始化的配置里读), 所以在函数里钳而不是靠调用方自觉。
        val floor = ShellMotion.stiffnessForResponse(ShellMotion.MIN_RESPONSE_SEC)
        assertEquals(floor, ShellMotion.stiffnessForResponse(0f), 0.01f)
        assertEquals(floor, ShellMotion.stiffnessForResponse(-1f), 0.01f)
        assertTrue(ShellMotion.stiffnessForResponse(0f) > 0f)
    }

    @Test
    fun `每个设置分块都有自己的标题`() {
        // when 分支漏一个会被编译器拦住 (枚举穷尽), 但两个分块指向同一个 key
        // 编译器管不了 —— 那会让用户看到两块叫同一个名字。
        val titles = SettingsBlock.entries.map { blockTitleRes(it) }
        assertEquals(SettingsBlock.entries.size, titles.distinct().size)
        titles.forEach { assertNotEquals(0, it) }
    }

    @Test
    fun `出站模式下标映射到各自的文案`() {
        // 下标语义来自 VpnRepository.outboundMode: 0 规则 / 1 全局 / 2 直连。
        assertEquals(R.string.mode_rule_full, outboundModeTitleRes(0))
        assertEquals(R.string.mode_global_full, outboundModeTitleRes(1))
        assertEquals(R.string.mode_direct_full, outboundModeTitleRes(2))
        // 未知值必须落回「规则」而不是崩或显示空白: 内核换代加了新模式时,
        // 旧外壳读到 3 应当退化到最保守的那一档。
        assertEquals(R.string.mode_rule_full, outboundModeTitleRes(7))
        assertEquals(R.string.mode_rule_full, outboundModeTitleRes(-1))
    }
}
