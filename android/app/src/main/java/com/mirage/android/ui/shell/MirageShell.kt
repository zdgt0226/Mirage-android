package com.mirage.android.ui.shell

import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.interaction.MutableInteractionSource
import com.mirage.android.R
import com.mirage.android.ui.theme.mirage
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.absoluteValue
import kotlinx.coroutines.launch

/**
 * 三页手势外壳的页序。
 *
 * 方向按**手指方向**定义（2026-11-06 用户裁定）：手指向右滑露出左边那页。
 * 因此「首页往右滑进节点」= 节点在首页左侧，「往左滑进设置」= 设置在首页右侧。
 * 枚举顺序即视觉顺序，`ordinal` 直接当页下标用，不要再维护第二份映射。
 */
enum class ShellPage {
    Nodes,
    Home,
    Settings,
    ;

    companion object {
        val COUNT: Int = entries.size

        fun of(index: Int): ShellPage = entries.getOrElse(index) { Home }
    }
}

/** 相邻页在屏幕两侧露出的宽度。可发现性兜底之一，不是装饰。 */
private val PEEK = 8.dp

/** 页与页之间的空隙。与 [PEEK] 分开写，因为 Pager 的 contentPadding 必须是两者之和。 */
private val PAGE_GAP = 2.dp

/**
 * 手势外壳根容器。
 *
 * 导航模型完全由横向翻页承担，没有底栏。可发现性与无障碍靠三件事兜底：
 *  1. 两侧 [PEEK] 宽的相邻页窥视条；
 *  2. 底部页点，**可点击**且带 `Role.Tab` 语义 —— TalkBack 无法发现滑动手势，
 *     页点是它唯一的导航入口，所以这不是装饰性指示器；
 *  3. 返回键回首页（见下方 [PredictiveBackHandler]）。
 *
 * @param nodesPage 左页内容
 * @param homePage 中页内容
 * @param settingsPage 右页内容
 */
@Composable
fun MirageShell(
    nodesPage: @Composable () -> Unit,
    homePage: @Composable () -> Unit,
    settingsPage: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    val pagerState = rememberPagerState(initialPage = ShellPage.Home.ordinal) { ShellPage.COUNT }
    val scope = rememberCoroutineScope()

    // 预测式返回的配合形变。§0.2「拒绝 1」的正确做法是把返回交给系统管线驱动，
    // 应用层只提供缩放 —— 所以这里**不去动 pager 的滚动**，只随系统给的 progress
    // 缩放当前页。自己驱动翻页会和系统手势抢同一条输入。
    val backScale = remember { Animatable(1f) }
    val notHome = pagerState.currentPage != ShellPage.Home.ordinal
    PredictiveBackHandler(enabled = notHome) { progress ->
        try {
            progress.collect { event ->
                // 0..1 映射到 1.0..0.92: 幅度刻意小, 这是「要走了」的暗示而非一次转场。
                backScale.snapTo(1f - 0.08f * event.progress)
            }
            // 手势完成 = 返回被确认。回首页, 并把缩放弹回。
            scope.launch { pagerState.animateScrollToPage(ShellPage.Home.ordinal) }
            backScale.animateTo(1f, ShellMotion.move())
        } catch (_: CancellationException) {
            // 用户中途反向滑回取消了返回。必须从当前呈现值弹回, 不能跳。
            backScale.animateTo(1f, ShellMotion.move())
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.mirage.canvas),
    ) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .fillMaxSize()
                .scale(backScale.value),
            // contentPadding 必须是窥视宽 + 页间隙: Pager 的页宽 = 视口宽 - 横向
            // contentPadding, 相邻页右缘落在 contentPadding.start - pageSpacing 处。
            // 只写 PEEK 的话相邻页会正好贴着屏幕边缘, 一点都露不出来。
            contentPadding = PaddingValues(horizontal = PEEK + PAGE_GAP),
            pageSpacing = PAGE_GAP,
            // 三页常驻。左右页是真实业务页（节点列表的测速中状态、设置页的展开分块），
            // 被回收重建会丢掉这些瞬时状态, 而三页的驻留成本是确定且有限的。
            beyondViewportPageCount = 1,
            key = { it },
        ) { index ->
            Box(Modifier.fillMaxSize()) {
                when (ShellPage.of(index)) {
                    ShellPage.Nodes -> nodesPage()
                    ShellPage.Home -> homePage()
                    ShellPage.Settings -> settingsPage()
                }
            }
        }

        PageDots(
            pagerState = pagerState,
            onSelect = { page -> scope.launch { pagerState.animateScrollToPage(page.ordinal) } },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 10.dp),
        )
    }
}

/**
 * 底部页点。
 *
 * 用 `currentPageOffsetFraction` 连续插值而不是按 `currentPage` 离散跳变 ——
 * 拖到一半时中间那点已经在缩、目标点已经在胀，这就是「中间帧指向结果」。
 * 离散跳变会让页点在翻页落定那一刻才突变，反而把连续手势切成两段。
 */
@Composable
private fun PageDots(
    pagerState: PagerState,
    onSelect: (ShellPage) -> Unit,
    modifier: Modifier = Modifier,
) {
    // 复用既有的导航标题 key: 这三个词和被删掉的底栏是同一组文案,
    // 另起 shell_page_* 会让同一个概念在 strings.xml 里有两份译文。
    val labels = listOf(
        stringResource(R.string.nav_nodes_title),
        stringResource(R.string.nav_home_title),
        stringResource(R.string.settings_title),
    )
    val position = pagerState.currentPage + pagerState.currentPageOffsetFraction
    val colors = MaterialTheme.mirage

    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        ShellPage.entries.forEachIndexed { index, page ->
            if (index > 0) Spacer(Modifier.width(4.dp))
            // 1.0 = 正对该点, 0.0 = 相邻或更远。线性而非阶跃, 才能跟手。
            val proximity = (1f - (position - index).absoluteValue).coerceIn(0f, 1f)
            val label = labels[index]
            Box(
                modifier = Modifier
                    // 触控热区 28dp, 视觉只有 7dp。热区仍小于 48dp 规范值是刻意的:
                    // 页点浮在内容之上, 48dp 的透明热区会吃掉底部内容的点击。
                    // 代价由 Role.Tab + contentDescription 的无障碍路径补偿, 那条路径
                    // 不受视觉尺寸限制。页面内容须在底部预留 48dp 避开这一行。
                    .requiredSize(28.dp)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        role = Role.Tab,
                        onClickLabel = label,
                    ) { onSelect(page) }
                    .semantics { contentDescription = label },
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier
                        .size(7.dp)
                        // 选中态 7dp, 非选中 4.9dp。缩放而非改 size: 改 size 每帧
                        // 触发一次重新布局, graphicsLayer 只走合成。
                        .graphicsLayer {
                            val s = 0.7f + 0.3f * proximity
                            scaleX = s
                            scaleY = s
                            alpha = 0.35f + 0.65f * proximity
                        }
                        .clip(CircleShape)
                        .background(colors.blue),
                )
            }
        }
    }
}
