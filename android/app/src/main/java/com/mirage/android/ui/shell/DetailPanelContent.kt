package com.mirage.android.ui.shell

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.AnchoredDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import android.text.format.DateUtils
import com.mirage.android.R
import com.mirage.android.data.model.ConnectionInfo
import com.mirage.android.data.model.RecentRequestInfo
import com.mirage.android.data.repository.VpnRepository
import com.mirage.android.ui.theme.mirage
import com.mirage.android.ui.viewmodel.TrafficViewModel
import kotlinx.coroutines.delay

/**
 * 面板展开期间重读今日 / 本月用量的间隔。
 *
 * 账本每 30 秒才落盘一次 (CoreService 的 trafficJob), 读得比这更勤拿到的也是
 * 同一个数, 只是白做 Binder 往返。10 秒是「落盘后最多再等 10 秒就能看到」。
 */
private const val USAGE_REFRESH_MS = 10_000L

/**
 * 面板与列表之间的嵌套滚动协调器。
 *
 * 为什么必须有这个连接器：
 * 列表滚动 (LazyColumn) 与面板拖拽 (AnchoredDraggable) 共用同一个竖直手势方向。
 * 如果不建立嵌套滚动契约，处于内部的 LazyColumn 会无条件消费所有竖直拖动手势，
 * 导致面板展开后，用户在列表区域向下拉动时无法将面板收起（手势被列表完全吞噬）；
 * 反之向上拖拽时，面板未完全展开前列表也不应提前滚动。
 *
 * 底层手势契约：
 * 1. onPreScroll：手指向天划（delta < 0，即向上展开手势），若面板尚未完全展开 (offset > 0)，
 *    优先把增量喂给 panelState.dispatchRawDelta，消费掉该增量；面板展开到位后才放行给列表滚动。
 * 2. onPostScroll：列表已滚到顶部且用户继续向下拉动（available > 0），列表无法继续消费，
 *    把剩余向下的增量喂给 panelState.dispatchRawDelta，驱动面板折叠收起。
 * 3. onPreFling：若面板处于中间未完全展开状态，甩动手势速度直接交给 panelState.settle() 决出停靠档位并消费。
 * 4. onPostFling：列表到顶后剩余的向下甩动速度，交由 panelState.settle() 触发面板折叠吸附。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun rememberPanelNestedScrollConnection(
    panelState: AnchoredDraggableState<PanelAnchor>,
): NestedScrollConnection {
    return remember(panelState) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                val delta = available.y
                val currentOffset = panelState.offset
                val notFullyExpanded = !currentOffset.isNaN() && currentOffset > 0f
                return if (delta < 0f && notFullyExpanded) {
                    val consumed = panelState.dispatchRawDelta(delta)
                    Offset(0f, consumed)
                } else {
                    Offset.Zero
                }
            }

            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource,
            ): Offset {
                val delta = available.y
                return if (delta > 0f) {
                    val consumedY = panelState.dispatchRawDelta(delta)
                    Offset(0f, consumedY)
                } else {
                    Offset.Zero
                }
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                val currentOffset = panelState.offset
                val notFullyExpanded = !currentOffset.isNaN() && currentOffset > 0f
                return if (notFullyExpanded) {
                    panelState.settle(available.y)
                    available
                } else {
                    Velocity.Zero
                }
            }

            override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
                val vy = available.y
                return if (vy > 0f) {
                    panelState.settle(vy)
                    Velocity(0f, vy)
                } else {
                    Velocity.Zero
                }
            }
        }
    }
}

/**
 * 实时上下行双线折线图 (Sparkline)。
 *
 * 绘制规则：
 * - 采用 Compose Canvas 纯原生绘制，不引入第三方图表依赖；
 * - 上行采用 MaterialTheme.mirage.upload，下行采用 MaterialTheme.mirage.download；
 * - 安全除法：面对空列表或全零数据时，基准除数强制保底 1024f (1 KB/s)，绝不除以零。
 */
@Composable
fun ThroughputSparkline(
    historyUp: List<Float>,
    historyDown: List<Float>,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.mirage
    val uploadColor = colors.upload
    val downloadColor = colors.download
    val outlineColor = colors.outline

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(72.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(colors.pillBg.copy(alpha = 0.5f))
            .border(1.dp, outlineColor.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
            .padding(horizontal = 8.dp, vertical = 6.dp),
    ) {
        val w = size.width
        val h = size.height
        if (w <= 0f || h <= 0f) return@Canvas

        // 绘制底部水平基准线
        val baselineY = h - 1f
        drawLine(
            color = outlineColor.copy(alpha = 0.4f),
            start = Offset(0f, baselineY),
            end = Offset(w, baselineY),
            strokeWidth = 1f,
        )

        // 绘制居中水平参考线
        val midY = h / 2f
        drawLine(
            color = outlineColor.copy(alpha = 0.25f),
            start = Offset(0f, midY),
            end = Offset(w, midY),
            strokeWidth = 1f,
        )

        // 计算最大值：防止空列表与全 0 导致除以零，保底使用 1024f (1 KB/s)
        val maxUp = historyUp.maxOrNull() ?: 0f
        val maxDown = historyDown.maxOrNull() ?: 0f
        val maxVal = maxOf(maxUp, maxDown, 1024f)

        // 绘制下行折线 (download)
        drawPolyline(
            data = historyDown,
            maxVal = maxVal,
            color = downloadColor,
            w = w,
            h = h,
        )

        // 绘制上行折线 (upload)
        drawPolyline(
            data = historyUp,
            maxVal = maxVal,
            color = uploadColor,
            w = w,
            h = h,
        )
    }
}

private fun DrawScope.drawPolyline(
    data: List<Float>,
    maxVal: Float,
    color: Color,
    w: Float,
    h: Float,
) {
    if (data.isEmpty()) return
    val strokeWidth = 2.dp.toPx()
    val verticalPadding = 4.dp.toPx()
    val usableHeight = (h - verticalPadding * 2).coerceAtLeast(1f)
    val bottomY = h - verticalPadding

    if (data.size == 1) {
        val ratio = (data[0] / maxVal).coerceIn(0f, 1f)
        val y = bottomY - usableHeight * ratio
        drawLine(
            color = color,
            start = Offset(0f, y),
            end = Offset(w, y),
            strokeWidth = strokeWidth,
            cap = StrokeCap.Round,
        )
        return
    }

    val stepX = w / (data.size - 1).coerceAtLeast(1)
    val path = Path()
    for (i in data.indices) {
        val x = i * stepX
        val ratio = (data[i] / maxVal).coerceIn(0f, 1f)
        val y = bottomY - usableHeight * ratio
        if (i == 0) {
            path.moveTo(x, y)
        } else {
            path.lineTo(x, y)
        }
    }

    drawPath(
        path = path,
        color = color,
        style = Stroke(
            width = strokeWidth,
            cap = StrokeCap.Round,
            join = StrokeJoin.Round,
        ),
    )
}

private enum class ProtocolOption(val filterValue: String, val labelRes: Int) {
    ALL("ALL", R.string.filter_all),
    TCP("TCP", R.string.traffic_filter_tcp),
    UDP("UDP", R.string.traffic_filter_udp),
}

/**
 * 列表区的视图选择行: 请求流的协议过滤 (ALL / TCP / UDP) + 活跃连接。
 *
 * 四个胶囊是**同一组单选**, 不是「页签 + 过滤」两层: 小屏上列表区只有一百多 dp
 * 高, 再叠一行页签就只剩一行半内容。中间那条竖线是唯一的分组提示 —— 左边三个
 * 过滤请求流, 右边一个换成连接列表; 协议过滤不作用于连接列表, 所以选中「连接」
 * 时左边三个一律不高亮。
 */
@Composable
fun PanelViewRow(
    selectedProtocol: String,
    connectionsSelected: Boolean,
    onSelectProtocol: (String) -> Unit,
    onSelectConnections: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.mirage

    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ProtocolOption.entries.forEach { option ->
            PanelChip(
                label = stringResource(option.labelRes),
                selected = !connectionsSelected &&
                    selectedProtocol.equals(option.filterValue, ignoreCase = true),
                onClick = { onSelectProtocol(option.filterValue) },
            )
        }
        Box(
            Modifier
                .width(1.dp)
                .height(16.dp)
                .background(colors.outline),
        )
        PanelChip(
            label = stringResource(R.string.panel_view_connections),
            selected = connectionsSelected,
            onClick = onSelectConnections,
        )
    }
}

@Composable
private fun PanelChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val colors = MaterialTheme.mirage
    val haptics = rememberShellHaptics()
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.95f else 1f,
        animationSpec = motionAware(ShellMotion.press()),
        label = "panelChipScale",
    )

    Box(
        modifier = Modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(RoundedCornerShape(50))
            .background(if (selected) colors.blue else colors.pillBg)
            .border(
                width = 1.dp,
                color = if (selected) colors.blue else colors.pillStroke,
                shape = RoundedCornerShape(50),
            )
            .clickable(
                interactionSource = interaction,
                indication = null,
            ) {
                haptics.segmentTick()
                onClick()
            }
            .padding(horizontal = 14.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = if (selected) Color.White else colors.inkSecondary,
        )
    }
}

/**
 * 活跃连接单项卡片: 目标、协议、已传字节、存活时长, 右侧一个「断开」。
 *
 * 断开不二次确认: 它只掐掉这一条连接, 应用会自行重连, 不改任何配置 ——
 * 弹确认框的成本高于误触的成本。
 */
@Composable
private fun ConnectionItem(
    item: ConnectionInfo,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.mirage
    val haptics = rememberShellHaptics()
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(colors.card)
            .border(1.dp, colors.outline.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
            .padding(start = 12.dp, top = 8.dp, bottom = 8.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = item.target.ifBlank { "-" },
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Spacer(Modifier.width(8.dp))
                OutboundBadge(outbound = item.outbound)
            }

            Spacer(Modifier.height(4.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                ProtocolBadge(protocol = item.protocol)
                Spacer(Modifier.width(6.dp))
                Text(
                    text = "·",
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.inkSecondary,
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = "↑${item.upFormatted} ↓${item.downFormatted}",
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.inkSecondary,
                    maxLines = 1,
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = "·",
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.inkSecondary,
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = DateUtils.formatElapsedTime(item.durationSecs),
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.inkSecondary,
                    maxLines = 1,
                )
            }
        }

        Spacer(Modifier.width(8.dp))

        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .background(colors.error.copy(alpha = 0.12f))
                .clickable {
                    haptics.tap()
                    onClose()
                }
                .padding(horizontal = 12.dp, vertical = 6.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = stringResource(R.string.conn_close),
                style = MaterialTheme.typography.labelMedium,
                color = colors.error,
            )
        }
    }
}

/**
 * 近期请求单项卡片。
 */
@Composable
private fun RecentRequestItem(
    item: RecentRequestInfo,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.mirage
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(colors.card)
            .border(1.dp, colors.outline.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = item.target.ifBlank { "-" },
                style = MaterialTheme.typography.bodyMedium,
                color = colors.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            Spacer(Modifier.width(8.dp))
            OutboundBadge(outbound = item.outbound)
        }

        Spacer(Modifier.height(4.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ProtocolBadge(protocol = item.protocol)
            if (item.resolvedIp.isNotBlank()) {
                Spacer(Modifier.width(6.dp))
                Text(
                    text = "·",
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.inkSecondary,
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = item.resolvedIp,
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.inkSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun ProtocolBadge(protocol: String) {
    val colors = MaterialTheme.mirage
    val tint = when (protocol.uppercase()) {
        "TCP" -> colors.blue
        "UDP" -> colors.connected
        else -> colors.inkSecondary
    }
    Text(
        text = protocol,
        style = MaterialTheme.typography.labelSmall,
        color = tint,
    )
}

@Composable
private fun OutboundBadge(outbound: String) {
    val colors = MaterialTheme.mirage
    // i18n-exempt: 匹配内核给出的 outbound 取值
    val isProxy = outbound.equals("PROXY", ignoreCase = true) || outbound.contains("隧道代理")
    // i18n-exempt: 匹配内核给出的 outbound 取值
    val isDirect = outbound.equals("DIRECT", ignoreCase = true) || outbound.contains("直连")
    // i18n-exempt: 匹配内核给出的 outbound 取值
    val isBlock = outbound.equals("BLOCK", ignoreCase = true) || outbound.contains("拦截")

    val tint = when {
        isProxy -> colors.blue
        isDirect -> colors.ginger
        isBlock -> colors.error
        else -> colors.connected
    }
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(tint.copy(alpha = 0.12f))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Text(
            text = outbound,
            style = MaterialTheme.typography.labelSmall,
            color = tint,
        )
    }
}

/**
 * 首页上滑详情面板核心内容区 (S2)。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DetailPanelContent(
    panelState: AnchoredDraggableState<PanelAnchor>,
    // **必填, 刻意不给默认值。** 可空带默认值意味着调用方漏传也能编译, 面板静默空白;
    // 而原先的空值回退更糟 —— 它会手工 new 一个 TrafficViewModel, 游离在
    // ViewModelStore 之外、自带协程作用域, 既泄漏又让状态与正牌实例分叉。
    vm: TrafficViewModel,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current

    val historyUp by vm.historyUp.collectAsStateWithLifecycle()
    val historyDown by vm.historyDown.collectAsStateWithLifecycle()
    val requests by vm.filteredRecentRequests.collectAsStateWithLifecycle()
    val protocolFilter by vm.protocolFilter.collectAsStateWithLifecycle()
    val showConnections by vm.showConnections.collectAsStateWithLifecycle()
    val connections by vm.connections.collectAsStateWithLifecycle()

    // 监控活动激活：当面板处于展开状态时，唤醒后台 telemetry 轮询连接与请求流
    val isExpanded = panelState.targetValue == PanelAnchor.Expanded
    // 今日 / 本月用量只在面板展开期间重读。摘要行本身在 HomePage 的 PanelSummary 里,
    // 刷新节拍放这儿是因为「展开与否」的判定在这儿。
    LaunchedEffect(isExpanded) {
        if (isExpanded) {
            while (true) {
                vm.refreshUsage()
                delay(USAGE_REFRESH_MS)
            }
        }
    }
    DisposableEffect(isExpanded) {
        if (isExpanded) {
            VpnRepository.getInstance(context).setMonitorActive(true)
        }
        onDispose {
            if (isExpanded) {
                VpnRepository.getInstance(context).setMonitorActive(false)
            }
        }
    }

    val nestedScrollConnection = rememberPanelNestedScrollConnection(panelState)
    val colors = MaterialTheme.mirage

    Column(
        modifier = modifier.fillMaxWidth(),
    ) {
        // 1. 吞吐量平滑折线图 (Sparkline)
        ThroughputSparkline(
            historyUp = historyUp,
            historyDown = historyDown,
        )

        Spacer(Modifier.height(10.dp))

        // 2. 视图选择行: 请求流协议过滤 (ALL / TCP / UDP) | 活跃连接
        PanelViewRow(
            selectedProtocol = protocolFilter,
            connectionsSelected = showConnections,
            onSelectProtocol = { proto -> vm.setProtocolFilter(proto) },
            onSelectConnections = { vm.selectConnections() },
        )

        Spacer(Modifier.height(8.dp))

        // 3. 列表区: 活跃连接 或 近期请求流, 由上面那行单选决定
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .nestedScroll(nestedScrollConnection),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(vertical = 4.dp),
        ) {
            if (showConnections) {
                if (connections.isEmpty()) {
                    item(key = "connections_empty") {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 24.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = stringResource(R.string.connections_empty),
                                style = MaterialTheme.typography.bodyMedium,
                                color = colors.inkSecondary,
                                textAlign = TextAlign.Center,
                            )
                        }
                    }
                } else {
                    items(
                        items = connections,
                        // 前缀避免与请求流的 key 空间相撞: 两种列表共用一个 LazyColumn,
                        // 切换瞬间新旧项会同时存在于一次组合里。
                        key = { "conn_${it.id}" },
                    ) { conn ->
                        ConnectionItem(
                            item = conn,
                            onClose = { vm.closeConnection(conn.id) },
                        )
                    }
                }
            } else if (requests.isEmpty()) {
                item(key = "empty_state") {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 24.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = stringResource(R.string.traffic_requests_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            color = colors.inkSecondary,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            } else {
                items(
                    items = requests,
                    key = { it.id },
                ) { req ->
                    RecentRequestItem(req)
                }
            }
        }
    }
}
