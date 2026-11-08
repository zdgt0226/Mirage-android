package com.mirage.android.ui.shell

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import com.mirage.android.R
import com.mirage.android.ui.theme.mirage

/** 设置页的分块。顺序即屏幕顺序，`ordinal` 当作展开态的键。 */
enum class SettingsBlock {
    /** 路由与分流: 规则列表、Geo 规则集、分应用代理、出站模式。 */
    Routing,

    /** 域名解析: DNS 分流。 */
    Dns,

    /** 连接: 预热池、TUN 参数。 */
    Connection,

    /** 内核: 运行时换核、版本。 */
    Core,

    /** 配置: 备份与还原。 */
    Config,
}

/**
 * 设置页上一个可点的条目。
 *
 * 做成数据而不是直接摊成 composable，是为了让「哪些条目属于哪一块」这件事
 * 可以被读出来、也可以被单测钉住 —— 分块归属改了却没改文案是这类页面最常见的回归。
 */
internal data class SettingsEntry(
    val titleRes: Int,
    val summary: String?,
    val onClick: () -> Unit,
)

/**
 * 设置页（外壳右页）。
 *
 * 全部交互收在本页之内：平台弹层（DNS / TUN / 内核）由宿主传入的回调拉起，
 * 外挂 Activity（Geo 规则集、分应用代理）同理。本 composable 不认识 Activity。
 */
@Composable
fun SettingsPage(
    outboundMode: Int,
    dnsSummary: String,
    tunSummary: String,
    poolSize: Int,
    onOpenRules: () -> Unit,
    onOpenGeo: () -> Unit,
    onOpenPerApp: () -> Unit,
    onPickOutboundMode: () -> Unit,
    onOpenDns: () -> Unit,
    onOpenPool: () -> Unit,
    onOpenTun: () -> Unit,
    onOpenCore: () -> Unit,
    onOpenVersion: () -> Unit,
    onBackup: () -> Unit,
    onRestore: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val blocks = settingsBlocks(
        outboundMode = outboundMode,
        dnsSummary = dnsSummary,
        tunSummary = tunSummary,
        poolSize = poolSize,
        onOpenRules = onOpenRules,
        onOpenGeo = onOpenGeo,
        onOpenPerApp = onOpenPerApp,
        onPickOutboundMode = onPickOutboundMode,
        onOpenDns = onOpenDns,
        onOpenPool = onOpenPool,
        onOpenTun = onOpenTun,
        onOpenCore = onOpenCore,
        onOpenVersion = onOpenVersion,
        onBackup = onBackup,
        onRestore = onRestore,
    )

    // 展开态过 rememberSaveable: 翻到别的页再翻回来, 用户展开的分块必须还在。
    // Pager 的 beyondViewportPageCount=1 已经保证本页不被回收, 这里防的是进程重建。
    var expanded by rememberSaveable { mutableStateOf(SettingsBlock.Routing.ordinal) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val haptics = rememberShellHaptics()

    Column(
        modifier = modifier
            .fillMaxSize()
            .statusBarsPadding(),
    ) {
        PageHeader(title = stringResource(R.string.settings_title))
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 72.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(blocks.size, key = { blocks[it].first.name }) { i ->
                val (block, entries) = blocks[i]
                SettingsBlockCard(
                    titleRes = blockTitleRes(block),
                    entries = entries,
                    expanded = expanded == block.ordinal,
                    // 手风琴: 同时只开一块。全开会让这一页退化成一条长列表,
                    // 而「分块」的意义正是一次只看一组。
                    onToggle = {
                        haptics.tap()
                        val opening = expanded != block.ordinal
                        expanded = if (opening) block.ordinal else NONE_EXPANDED
                        if (opening) {
                            // 展开后把这一块滚到顶。不滚的话点开靠底部的分块,
                            // 内容整段落在屏幕外 —— 用户按了一下, 看起来什么都没发生,
                            // 还得自己再滑一次才看见。
                            scope.launch { listState.animateScrollToItem(i) }
                        }
                    },
                )
            }
        }
    }
}

private const val NONE_EXPANDED = -1

@Composable
private fun SettingsBlockCard(
    titleRes: Int,
    entries: List<SettingsEntry>,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    val colors = MaterialTheme.mirage
    val chevronRotation by animateFloatAsState(
        targetValue = if (expanded) 90f else 0f,
        animationSpec = motionAware(ShellMotion.move()),
        label = "blockChevron",
    )
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(colors.card)
            .border(1.dp, colors.outline, RoundedCornerShape(16.dp)),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggle)
                .padding(horizontal = 16.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(titleRes),
                style = MaterialTheme.typography.headlineSmall,
                color = colors.ink,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "›",
                style = MaterialTheme.typography.headlineSmall,
                color = colors.inkSecondary,
                modifier = Modifier.graphicsLayer { rotationZ = chevronRotation },
            )
        }
        AnimatedVisibility(
            visible = expanded,
            // 进出同一条路径: 从顶边往下展开, 就从顶边往上收回。
            // expandFrom / shrinkTowards 的默认值都是 Bottom —— 那会让分块
            // 从底边长出来, 读起来像内容往上顶而不是标题下面展开。
            enter = expandVertically(
                animationSpec = ShellMotion.move(),
                expandFrom = Alignment.Top,
            ),
            exit = shrinkVertically(
                animationSpec = ShellMotion.move(),
                shrinkTowards = Alignment.Top,
            ),
        ) {
            Column {
                entries.forEach { entry ->
                    SettingsRow(entry)
                }
                Spacer(Modifier.height(6.dp))
            }
        }
    }
}

@Composable
private fun SettingsRow(entry: SettingsEntry) {
    val colors = MaterialTheme.mirage
    val haptics = rememberShellHaptics()
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.99f else 1f,
        animationSpec = motionAware(ShellMotion.press()),
        label = "settingsRowScale",
    )
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clickable(
                interactionSource = interaction,
                indication = null,
            ) {
                haptics.tap()
                entry.onClick()
            }
            // 44dp 下限: 两行文字的行本来就够高, 但只有标题没有副标题的行
            // (将来可能出现) 会塌到 40dp 以下。
            .defaultMinSize(minHeight = 44.dp)
            .padding(horizontal = 16.dp, vertical = 13.dp),
    ) {
        Text(
            text = stringResource(entry.titleRes),
            style = MaterialTheme.typography.titleMedium,
            color = colors.ink,
        )
        if (entry.summary != null) {
            Spacer(Modifier.height(3.dp))
            Text(
                text = entry.summary,
                style = MaterialTheme.typography.bodyMedium,
                color = colors.inkSecondary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** 分块标题。独立成函数, 让分块与标题的对应关系只有一处。 */
internal fun blockTitleRes(block: SettingsBlock): Int = when (block) {
    SettingsBlock.Routing -> R.string.settings_section_routing
    SettingsBlock.Dns -> R.string.settings_section_dns
    SettingsBlock.Connection -> R.string.settings_section_conn
    SettingsBlock.Core -> R.string.settings_section_core
    SettingsBlock.Config -> R.string.settings_section_config
}

/** 出站模式的下标 → 文案。下标语义与 `VpnRepository.outboundMode` 一致。 */
internal fun outboundModeTitleRes(mode: Int): Int = when (mode) {
    1 -> R.string.mode_global_full
    2 -> R.string.mode_direct_full
    else -> R.string.mode_rule_full
}

@Composable
private fun settingsBlocks(
    outboundMode: Int,
    dnsSummary: String,
    tunSummary: String,
    poolSize: Int,
    onOpenRules: () -> Unit,
    onOpenGeo: () -> Unit,
    onOpenPerApp: () -> Unit,
    onPickOutboundMode: () -> Unit,
    onOpenDns: () -> Unit,
    onOpenPool: () -> Unit,
    onOpenTun: () -> Unit,
    onOpenCore: () -> Unit,
    onOpenVersion: () -> Unit,
    onBackup: () -> Unit,
    onRestore: () -> Unit,
): List<Pair<SettingsBlock, List<SettingsEntry>>> = listOf(
    SettingsBlock.Routing to listOf(
        SettingsEntry(
            titleRes = R.string.settings_row_outbound,
            summary = stringResource(outboundModeTitleRes(outboundMode)),
            onClick = onPickOutboundMode,
        ),
        SettingsEntry(
            titleRes = R.string.rules_title,
            summary = stringResource(R.string.rules_drag_hint),
            onClick = onOpenRules,
        ),
        SettingsEntry(
            titleRes = R.string.rules_geo_entry,
            summary = stringResource(R.string.rules_geo_entry_desc),
            onClick = onOpenGeo,
        ),
        SettingsEntry(
            titleRes = R.string.rules_perapp_entry,
            summary = stringResource(R.string.rules_perapp_entry_desc),
            onClick = onOpenPerApp,
        ),
    ),
    SettingsBlock.Dns to listOf(
        SettingsEntry(
            titleRes = R.string.home_dns_card,
            summary = dnsSummary,
            onClick = onOpenDns,
        ),
    ),
    SettingsBlock.Connection to listOf(
        SettingsEntry(
            titleRes = R.string.settings_row_pool,
            summary = stringResource(R.string.nodes_pool_fmt, poolSize),
            onClick = onOpenPool,
        ),
        SettingsEntry(
            titleRes = R.string.tun_title,
            summary = tunSummary,
            onClick = onOpenTun,
        ),
    ),
    SettingsBlock.Core to listOf(
        SettingsEntry(
            titleRes = R.string.core_mgr_title,
            summary = stringResource(R.string.core_mgr_summary),
            onClick = onOpenCore,
        ),
        SettingsEntry(
            titleRes = R.string.version_dialog_title,
            summary = stringResource(R.string.version_summary),
            onClick = onOpenVersion,
        ),
    ),
    SettingsBlock.Config to listOf(
        SettingsEntry(
            titleRes = R.string.backup_config,
            summary = stringResource(R.string.backup_summary),
            onClick = onBackup,
        ),
        SettingsEntry(
            titleRes = R.string.restore_config,
            summary = stringResource(R.string.restore_summary),
            onClick = onRestore,
        ),
    ),
)
