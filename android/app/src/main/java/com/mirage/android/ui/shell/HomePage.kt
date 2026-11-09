package com.mirage.android.ui.shell

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.AnchoredDraggableState
import androidx.compose.foundation.gestures.DraggableAnchors
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.anchoredDraggable
import androidx.compose.foundation.gestures.animateTo
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.mirage.android.R
import com.mirage.android.data.model.Node
import com.mirage.android.data.model.TrafficStats
import com.mirage.android.data.model.VpnState
import com.mirage.android.ui.theme.mirage
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

/** 上滑详情面板的两个档位。只有两档：没有第三种有意义的中间停留位。 */
enum class PanelAnchor { Collapsed, Expanded }

/** 收起态露出的把手高度。手势起点必须落在这条上，所以它要够厚。 */
private val HANDLE_HEIGHT = 56.dp

/**
 * 页点那一行占掉的高度（28dp 热区 + 上下各 10dp 余量）。
 *
 * **这不是从屏幕底边起算的。** 页点自己带 `navigationBarsPadding()`，所以它离
 * 窗口底边的距离是「导航栏 inset + 本常量」。面板收起位必须按这个和来让位，
 * 只减本常量的话，在有导航栏的设备上页点正好落进把手里 —— SO-02K 的导航栏是
 * 96px/48dp，页点会压在把手中间，点哪个都不确定。
 */
private val DOTS_RESERVE = 48.dp

/**
 * 面板展开后占视口的比例。
 *
 * 刻意不做满屏、也刻意不让高度由内容决定：
 *  - 满屏会让面板读成一次页面跳转，而它是覆盖，顶上那条背景是「还在首页」的凭据；
 *  - 内容决定高度则 anchors 会随内容增减而变，而内容里有实时刷新的连接列表，
 *    高度一变 `updateAnchors` 就重算，面板会在用户手上自己跳。
 */
private const val PANEL_HEIGHT_FRACTION = 0.86f

/**
 * 首页。
 *
 * 静息状态**只有**开关与节点下拉两件东西 —— 这是本次重构的核心取舍：
 * 速率、延迟、连接列表、流量统计全部移到上滑面板里，首页不再是信息看板。
 *
 * @param detail 面板展开后的内容。留成参数是为了让 S2 独立填充，
 *   面板的手势契约与它装什么无关。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HomePage(
    state: VpnState,
    stats: TrafficStats,
    latencyMs: Long,
    /**
     * 上游是否可用。为 false 时「已连接」要显式降级 —— 隧道建好了不等于能用。
     * 详见 `VpnRepository.upstreamHealthy`。
     */
    upstreamHealthy: Boolean,
    nodes: List<Node>,
    selectedNode: Node?,
    onToggle: () -> Unit,
    onSelectNode: (Int) -> Unit,
    modifier: Modifier = Modifier,
    detail: @Composable () -> Unit = {},
) {
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val haptics = rememberShellHaptics()
    // 导航栏 inset: 面板收起位与静息层的底部留白都要把它算进去 (见 DOTS_RESERVE)。
    val navBottomPx = WindowInsets.navigationBars.getBottom(density)
    val snapSpec = motionAware(ShellMotion.drawer<Float>())

    val panelState = remember {
        AnchoredDraggableState(
            initialValue = PanelAnchor.Collapsed,
            // 过半即换档。位置阈值与下面的速度阈值是「或」的关系:
            // 慢慢拖过一半会换, 快速一甩没过一半也会换。
            positionalThreshold = { distance -> distance * 0.5f },
            // 125dp/s: Material 的 ViewDragHelper 用的同一量级。低于此视为「放手」而非「甩」。
            velocityThreshold = { with(density) { 125.dp.toPx() } },
            snapAnimationSpec = snapSpec,
            // 动量投射交给框架。这一个参数就是 Apple 那套 project(velocity) 的等价物。
            decayAnimationSpec = ShellMotion.decay(),
            confirmValueChange = { true },
        )
    }

    // 面板实测高度, 用来算收起态的偏移量。
    var panelHeightPx by remember { mutableStateOf(0) }

    /** 上一次算 anchors 时用的导航栏 inset, 用于判断是否需要重算。 */
    var anchoredNavBottomPx by remember { mutableStateOf(-1) }

    // 连接结果给一次确认/拒绝震动。
    //
    // 按在开关上那一下只是"收到了", 不代表连上 —— 建连是异步的, 真正值得反馈的
    // 是落到 Connected 或 Error 的那一刻。只在状态**类型**变化时响: 直接用
    // VpnState 当 key 会因 Connected(node) 里的节点对象变化而重复触发。
    // 跳过首次组合: 冷启动时 VPN 可能本来就连着, 那不是一次"连上了"。
    var lastStateKey by remember { mutableStateOf<Any?>(null) }
    val stateKey: Any = state::class
    LaunchedEffect(stateKey) {
        val first = lastStateKey == null
        lastStateKey = stateKey
        if (first) return@LaunchedEffect
        when (state) {
            is VpnState.Connected -> haptics.confirm()
            is VpnState.Error -> haptics.reject()
            else -> Unit
        }
    }

    // 面板吸附到档位时一次滴答。用 settledValue 而非 currentValue:
    // 后者在拖过阈值时就变, 来回拖一次会连响两下而面板并没有换档。
    var panelSettleSeen by remember { mutableStateOf(false) }
    LaunchedEffect(panelState.settledValue) {
        if (!panelSettleSeen) {
            panelSettleSeen = true
            return@LaunchedEffect
        }
        haptics.segmentTick()
    }

    // 面板展开时返回键先收面板。不挂这个回调的表现是: 用户上滑看详情,
    // 按返回直接退出应用 —— 面板是覆盖层, 返回理应先撤掉覆盖层。
    // 注册在外壳的回首页回调之后, 因此优先级更高 (OnBackPressedDispatcher 后注册者先得)。
    BackHandler(enabled = panelState.targetValue == PanelAnchor.Expanded) {
        scope.launch { panelState.animateTo(PanelAnchor.Collapsed) }
    }

    Box(modifier = modifier.fillMaxSize()) {

        // ---- 静息层: 开关 + 节点下拉 ----
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                // 整页都是面板的拖拽源, 不只是那条 56dp 的把手。
                //
                // 同一个 AnchoredDraggableState 挂两处是支持的 (Material 的
                // BottomSheetScaffold 对 sheet 与内容就是这么做的), 两处都只是
                // 往同一个 state 喂手势。方向锁住竖直, 所以横滑仍然归 Pager:
                // anchoredDraggable 的竖直识别器不认领横向拖动。
                // 不挂的话用户必须精准摸到屏幕最底下那条才能调出详情, 而用户
                // 给的交互模型是"向上滑动调出"。
                .anchoredDraggable(panelState, Orientation.Vertical)
                .padding(horizontal = 24.dp)
                .padding(bottom = HANDLE_HEIGHT + DOTS_RESERVE),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            HeroToggle(state = state, upstreamHealthy = upstreamHealthy, onToggle = onToggle)
            Spacer(Modifier.height(20.dp))
            Text(
                text = statusLabel(state, upstreamHealthy),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.mirage.inkSecondary,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(28.dp))
            NodeDropdown(
                nodes = nodes,
                selectedNode = selectedNode,
                onSelectNode = { index ->
                    haptics.toggle()
                    onSelectNode(index)
                },
            )
        }

        // ---- 上滑详情面板 ----
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(PANEL_HEIGHT_FRACTION)
                .align(Alignment.BottomStart)
                .onSizeChanged { size ->
                    // inset 变化时高度不变, 所以不能只靠高度判重 —— 否则从三键
                    // 导航切到手势导航后, 收起位仍按旧的 96px 留白算。
                    if (size.height == panelHeightPx && navBottomPx == anchoredNavBottomPx) {
                        return@onSizeChanged
                    }
                    panelHeightPx = size.height
                    anchoredNavBottomPx = navBottomPx
                    // 减掉的是把手高 + 页点行高。只减把手的话, 收起态的把手正好
                    // 落在页点那一行上 —— 两者抢同一片 48dp, 点哪个都不确定。
                    val reserved = with(density) { (HANDLE_HEIGHT + DOTS_RESERVE).toPx() } +
                        navBottomPx
                    val collapsed = (size.height - reserved).coerceAtLeast(0f)
                    panelState.updateAnchors(
                        DraggableAnchors {
                            PanelAnchor.Collapsed at collapsed
                            PanelAnchor.Expanded at 0f
                        },
                        // 保留当前档位: 旋屏或键盘弹出导致重测高度时, 不该把
                        // 用户展开的面板偷偷收回去。
                        panelState.targetValue,
                    )
                }
                .offset {
                    // anchors 落地前 offset 是 NaN, requireOffset() 会抛。
                    // 首帧必须给收起位, 否则面板会在屏幕上闪一下满屏。
                    val o = panelState.offset
                    val y = if (o.isNaN()) panelHeightPx.toFloat() else o
                    IntOffset(0, y.roundToInt())
                }
                .anchoredDraggable(panelState, Orientation.Vertical),
        ) {
            DetailPanelSurface(
                progress = { panelProgress(panelState) },
                onHandleClick = {
                    haptics.tap()
                    scope.launch {
                        val next = if (panelState.targetValue == PanelAnchor.Expanded) {
                            PanelAnchor.Collapsed
                        } else {
                            PanelAnchor.Expanded
                        }
                        panelState.animateTo(next)
                    }
                },
                expanded = panelState.targetValue == PanelAnchor.Expanded,
            ) {
                PanelSummary(
                    state = state,
                    stats = stats,
                    latencyMs = latencyMs,
                    upstreamHealthy = upstreamHealthy,
                )
                detail()
            }
        }
    }
}

/**
 * 面板从收起到展开的进度 0..1。
 *
 * 不直接用 `panelState.progress`: 那个是「到 targetValue 的进度」, 在反向拖动时
 * target 会翻转, 进度跟着从 0 重新开始, 用来驱动视觉会看到跳变。这里要的是
 * 「离展开态有多近」这一单调量, 所以按两个固定档位之间算。
 */
@OptIn(ExperimentalFoundationApi::class)
private fun panelProgress(state: AnchoredDraggableState<PanelAnchor>): Float {
    val collapsed = state.anchors.positionOf(PanelAnchor.Collapsed)
    val expanded = state.anchors.positionOf(PanelAnchor.Expanded)
    val offset = state.offset
    if (collapsed.isNaN() || expanded.isNaN() || offset.isNaN() || collapsed == expanded) return 0f
    return ((collapsed - offset) / (collapsed - expanded)).coerceIn(0f, 1f)
}

/** 面板表面。把手常驻，内容随展开进度淡入。 */
@Composable
private fun DetailPanelSurface(
    progress: () -> Float,
    expanded: Boolean,
    onHandleClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    val colors = MaterialTheme.mirage
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp))
            .background(colors.card)
            .border(
                width = 1.dp,
                color = colors.outline,
                shape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp),
            ),
    ) {
        // 把手。整条可点: 手势之外必须留一条确定能用的路径,
        // 否则 TalkBack 与不习惯滑动的用户打不开这个面板。
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(HANDLE_HEIGHT)
                .clickable(onClick = onHandleClick),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    Modifier
                        .width(36.dp)
                        .height(4.dp)
                        .clip(CircleShape)
                        .background(colors.outline),
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(
                        if (expanded) R.string.home_panel_collapse else R.string.home_panel_handle,
                    ),
                    style = MaterialTheme.typography.labelLarge,
                    color = colors.inkSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.graphicsLayer { alpha = 1f - progress() * 0.6f },
                )
            }
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .navigationBarsPadding()
                .padding(bottom = DOTS_RESERVE)
                // 内容随展开淡入。收起态它在屏幕外, 但仍参与测量 ——
                // 面板高度正是由它决定的, 这也是 anchors 的来源。
                .graphicsLayer { alpha = progress() },
            content = { content() },
        )
    }
}

/** 面板里的速率 / 延迟 / 累计摘要。连接信息列表留给 S2。 */
@Composable
private fun PanelSummary(
    state: VpnState,
    stats: TrafficStats,
    latencyMs: Long,
    upstreamHealthy: Boolean,
) {
    val colors = MaterialTheme.mirage
    Column {
        // 面板里再说一次: 用户上滑来看详情, 正是想知道「为什么没速度」。
        if (state is VpnState.Connected && !upstreamHealthy) {
            Text(
                text = stringResource(R.string.status_upstream_down_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = colors.ginger,
            )
            Spacer(Modifier.height(12.dp))
        }
        Row(Modifier.fillMaxWidth()) {
            RateCell(
                label = stringResource(R.string.rate_up_label),
                value = stats.upRateFormatted,
                tint = colors.upload,
                modifier = Modifier.weight(1f),
            )
            RateCell(
                label = stringResource(R.string.rate_down_label),
                value = stats.downRateFormatted,
                tint = colors.download,
                modifier = Modifier.weight(1f),
            )
        }
        Spacer(Modifier.height(16.dp))
        Text(
            text = if (state.isRunning && latencyMs > 0) {
                stringResource(R.string.home_rtt, latencyMs.toString())
            } else {
                stringResource(R.string.home_latency_none)
            },
            style = MaterialTheme.typography.bodyMedium,
            color = colors.inkSecondary,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = stringResource(
                R.string.home_total_fmt,
                stats.upTotalFormatted,
                stats.downTotalFormatted,
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = colors.inkSecondary,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = stringResource(R.string.home_conns_fmt, stats.tcpConns + stats.udpFlows),
            style = MaterialTheme.typography.bodyMedium,
            color = colors.inkSecondary,
        )
    }
}

@Composable
private fun RateCell(
    label: String,
    value: String,
    tint: Color,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.mirage.inkSecondary,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.headlineMedium,
            color = tint,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * 主开关。
 *
 * 按压反馈起于 pointer-down（`collectIsPressedAsState` 读的是 press 交互，
 * 不是 click），缩放走临界阻尼弹簧 —— 等抬手再反馈会让整个界面「发木」。
 * 震动同样挂在 press 上，理由一样。
 */
@Composable
private fun HeroToggle(state: VpnState, upstreamHealthy: Boolean, onToggle: () -> Unit) {
    val colors = MaterialTheme.mirage
    val haptics = rememberShellHaptics()
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.96f else 1f,
        animationSpec = motionAware(ShellMotion.press()),
        label = "toggleScale",
    )
    LaunchedEffect(pressed) {
        if (pressed) haptics.toggle()
    }

    // Connecting 刻意**不禁用**: 建连可能卡在握手上, 用户得能再点一下中止 ——
    // 被替换的 HomeFragment 就是这个取舍 (Connecting 时 connectBtn.isEnabled = true)。
    // 只有 Syncing (还在查状态) 与 Stopping (正在拆) 这两个瞬时态才禁。
    val busy = state is VpnState.Syncing || state is VpnState.Stopping
    val fill = when {
        state is VpnState.Error -> colors.error
        // 已连接但上游不可用: 用警示色而不是绿色。绿环 + 「加密隧道保护中」
        // 在节点其实握不上手时是**误导**, 用户据此以为流量受保护。
        state is VpnState.Connected && !upstreamHealthy -> colors.ginger
        state is VpnState.Connected -> colors.connected
        state is VpnState.Connecting -> colors.ginger
        else -> colors.disconnected
    }

    // 进行中 = 内核在动而结果未定。此前这三个状态只靠换颜色表达, 而静止的环
    // 读不出"正在进行", 用户唯一的线索是中间那行文字。
    val inProgress = state is VpnState.Connecting ||
        state is VpnState.Stopping ||
        state is VpnState.Syncing

    Box(
        modifier = Modifier
            .size(168.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(CircleShape)
            .background(colors.card)
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = !busy,
                onClick = onToggle,
            ),
        contentAlignment = Alignment.Center,
    ) {
        // 减弱动效时退回静态环: 无限循环动画正是 prefers-reduced-motion 要压的
        // 那一类 (持续运动、无终点)。状态本身仍由颜色与文字表达, 信息不丢。
        if (inProgress && !rememberReducedMotion()) {
            SweepRing(arc = fill, track = colors.outline)
        } else {
            StaticRing(color = fill)
        }
        Text(
            text = stringResource(toggleLabel(state)),
            style = MaterialTheme.typography.headlineMedium,
            color = if (state is VpnState.Connected && upstreamHealthy) colors.connected else colors.ink,
            textAlign = TextAlign.Center,
        )
    }
}

/** 开关外圈：静止态的整圈描边。 */
@Composable
private fun BoxScope.StaticRing(color: Color) {
    Canvas(Modifier.matchParentSize()) {
        val stroke = RING_STROKE_DP.dp.toPx()
        drawCircle(
            color = color,
            radius = (size.minDimension - stroke) / 2f,
            style = Stroke(width = stroke),
        )
    }
}

/**
 * 开关外圈：进行中的扫动弧。
 *
 * 一圈 1100ms、线性缓动、弧长 100°。线性是刻意的：这是进度指示而非位移，
 * 加减速会被读成"快要好了"又"卡住了"。弧长取 100° 是为了在 168dp 的圈上
 * 任何角度都能看见一段，而不至于长到看不出在转。
 */
@Composable
private fun BoxScope.SweepRing(
    arc: Color,
    track: Color,
) {
    val transition = rememberInfiniteTransition(label = "toggleSweep")
    val angle by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1100, easing = LinearEasing),
        ),
        label = "sweepAngle",
    )
    Canvas(Modifier.matchParentSize()) {
        val stroke = RING_STROKE_DP.dp.toPx()
        val inset = stroke / 2f
        val box = Size(size.width - stroke, size.height - stroke)
        drawArc(
            color = track,
            startAngle = 0f,
            sweepAngle = 360f,
            useCenter = false,
            topLeft = Offset(inset, inset),
            size = box,
            style = Stroke(width = stroke),
        )
        drawArc(
            color = arc,
            // -90 让起点落在 12 点方向, 与"从上往下读"的直觉一致。
            startAngle = angle - 90f,
            sweepAngle = SWEEP_DEGREES,
            useCenter = false,
            topLeft = Offset(inset, inset),
            size = box,
            style = Stroke(width = stroke, cap = StrokeCap.Round),
        )
    }
}

/** 开关外圈描边宽度。Canvas 与两个环共用, 不要各写一份。 */
private const val RING_STROKE_DP = 6

/** 扫动弧的弧长（度）。 */
private const val SWEEP_DEGREES = 100f

/**
 * 节点下拉。
 *
 * 从触发行原位展开 —— `DropdownMenu` 自带锚定，不要换成全屏弹层。
 *
 * 每项带**延迟**与**当前选中标记**。只列名字是不够的：首页的下拉是用户换节点的
 * 主路径，而决定换哪一个的信息就是延迟，数据本来就在 `Node.latencyMs` 里。
 * 少了选中标记则在只有两三个同名前缀节点时读不出自己正连着哪一个。
 * 阈值配色与节点页共用 [nodeLatencyDisplay]，避免同一个 120ms 两处显示成不同颜色。
 */
@Composable
private fun NodeDropdown(
    nodes: List<Node>,
    selectedNode: Node?,
    onSelectNode: (Int) -> Unit,
) {
    val colors = MaterialTheme.mirage
    var open by remember { mutableStateOf(false) }
    val label = selectedNode?.displayName ?: stringResource(R.string.home_no_node)

    Box {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .background(colors.pillBg)
                .border(1.dp, colors.pillStroke, RoundedCornerShape(50))
                .clickable(enabled = nodes.isNotEmpty()) { open = true }
                .padding(horizontal = 18.dp, vertical = 12.dp)
                .semantics { contentDescription = label },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.titleMedium,
                color = colors.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.width(200.dp),
            )
            Text(
                text = "▾",
                style = MaterialTheme.typography.titleMedium,
                color = colors.inkSecondary,
            )
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            nodes.forEachIndexed { index, node ->
                val isCurrent = node.uri == selectedNode?.uri
                val (rtt, rttTint) = nodeLatencyDisplay(node)
                DropdownMenuItem(
                    text = {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.width(240.dp),
                        ) {
                            Text(
                                text = node.displayName,
                                color = if (isCurrent) colors.blue else colors.ink,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            Spacer(Modifier.width(12.dp))
                            Text(
                                text = rtt,
                                style = MaterialTheme.typography.labelLarge,
                                color = rttTint,
                                maxLines = 1,
                            )
                        }
                    },
                    leadingIcon = {
                        Box(
                            Modifier
                                .size(10.dp)
                                .clip(CircleShape)
                                .background(if (isCurrent) colors.blue else Color.Transparent)
                                .border(
                                    width = if (isCurrent) 0.dp else 1.5.dp,
                                    color = if (isCurrent) colors.blue else colors.outline,
                                    shape = CircleShape,
                                ),
                        )
                    },
                    onClick = {
                        open = false
                        onSelectNode(index)
                    },
                )
            }
        }
    }
}

@Composable
private fun statusLabel(state: VpnState, upstreamHealthy: Boolean): String = when (state) {
    is VpnState.Syncing -> stringResource(R.string.status_syncing)
    is VpnState.Connected ->
        if (upstreamHealthy) {
            stringResource(R.string.status_connected_detail)
        } else {
            stringResource(R.string.status_upstream_down)
        }
    is VpnState.Connecting -> stringResource(R.string.status_establishing)
    is VpnState.Stopping -> stringResource(R.string.status_disconnecting)
    is VpnState.Error -> stringResource(R.string.status_error, state.message)
    is VpnState.Disconnected -> stringResource(R.string.status_idle)
}

private fun toggleLabel(state: VpnState): Int = when (state) {
    is VpnState.Syncing -> R.string.status_syncing
    is VpnState.Connected -> R.string.disconnect
    is VpnState.Connecting -> R.string.connecting_btn
    is VpnState.Stopping -> R.string.disconnecting_btn
    is VpnState.Error -> R.string.connect
    is VpnState.Disconnected -> R.string.connect
}
