package com.mirage.android.ui.shell

import android.app.AlertDialog
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mirage.android.R
import com.mirage.android.data.model.Node
import com.mirage.android.ui.NodeEditDialog
import com.mirage.android.ui.theme.mirage
import com.mirage.android.data.repository.NodeRepository
import com.mirage.android.ui.viewmodel.NodesViewModel

/** 节点行 RTT 着色的分档阈值（毫秒）。沿用 `NodeAdapter` 的 100 / 250 两档。 */
private const val RTT_GOOD_MS = 100L
private const val RTT_FAIR_MS = 250L

/**
 * 节点管理页（外壳左页）。
 *
 * 行为逐项对齐被替换的 `NodesFragment`：选中即热切换、单节点测速、批量测速后
 * 提示最优并可一键选中、自动选优开关、测速方式、连接池大小、剪贴板导入、
 * 增删改走同一个 [NodeEditDialog]。
 *
 * 对话框仍用 `AlertDialog` / `Toast`：它们是平台弹层，不随本次 Compose 化而
 * 失效，重写成 Compose 对话框属于另一笔改动，混在导航重构里会让回归无法归因。
 */
@Composable
fun NodesPage(
    viewModel: NodesViewModel,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val nodes by viewModel.nodes.collectAsStateWithLifecycle()
    val selectedIndex by viewModel.selectedIndex.collectAsStateWithLifecycle()
    val testingAll by viewModel.isTestingAll.collectAsStateWithLifecycle()
    val autoSelect by viewModel.isAutoSelect.collectAsStateWithLifecycle()
    val testMethod by viewModel.testMethod.collectAsStateWithLifecycle()
    val poolSize by viewModel.poolSize.collectAsStateWithLifecycle()

    Column(
        modifier = modifier
            .fillMaxSize()
            .statusBarsPadding(),
    ) {
        PageHeader(title = stringResource(R.string.nav_nodes_title))

        // 操作条。横向可滚是刻意的: 六个动作在 360dp 宽下放不下,
        // 换成折叠菜单会把高频的「测速」藏进二级。
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ActionChip(
                label = stringResource(R.string.nodes_add),
                modifier = Modifier.weight(1f),
            ) { showNodeDialog(context, viewModel, null) }
            ActionChip(
                label = stringResource(
                    if (testingAll) R.string.nodes_testing else R.string.nodes_test_all,
                ),
                enabled = !testingAll,
                modifier = Modifier.weight(1f),
            ) { testAll(context, viewModel) }
        }
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ActionChip(
                label = stringResource(
                    if (autoSelect) R.string.nodes_auto_on else R.string.nodes_auto_off,
                ),
                modifier = Modifier.weight(1f),
                toggleLike = true,
            ) { viewModel.toggleAutoSelect() }
            ActionChip(
                label = stringResource(R.string.nodes_test_method_fmt, testMethod.uppercase()),
                modifier = Modifier.weight(1f),
            ) { chooseTestMethod(context, viewModel) }
        }
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ActionChip(
                label = stringResource(R.string.nodes_pool_fmt, poolSize),
                modifier = Modifier.weight(1f),
            ) { choosePoolSize(context, viewModel) }
            ActionChip(
                label = stringResource(R.string.nodes_import_clipboard),
                modifier = Modifier.weight(1f),
            ) { importFromClipboard(context, viewModel) }
        }

        if (nodes.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = stringResource(R.string.nodes_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.mirage.inkSecondary,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = 16.dp,
                    end = 16.dp,
                    top = 12.dp,
                    // 底部给页点让位, 否则最后一行被压在页点底下点不到。
                    bottom = 72.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                itemsIndexed(nodes, key = { _, node -> node.uri }) { index, node ->
                    NodeRow(
                        node = node,
                        selected = index == selectedIndex,
                        onSelect = { viewModel.selectNode(index) },
                        onTest = { viewModel.testNode(index) },
                        onEdit = { showNodeDialog(context, viewModel, index) },
                        onCopy = { copyUri(context, node) },
                        onDelete = { confirmDelete(context, viewModel, index, node) },
                    )
                }
            }
        }
    }
}

@Composable
private fun NodeRow(
    node: Node,
    selected: Boolean,
    onSelect: () -> Unit,
    onTest: () -> Unit,
    onEdit: () -> Unit,
    onCopy: () -> Unit,
    onDelete: () -> Unit,
) {
    val colors = MaterialTheme.mirage
    val haptics = rememberShellHaptics()
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.985f else 1f,
        animationSpec = motionAware(ShellMotion.press()),
        label = "nodeRowScale",
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(RoundedCornerShape(14.dp))
            .background(if (selected) colors.nodeRowSelected else colors.nodeRowDefault)
            .border(
                width = if (selected) 1.5.dp else 1.dp,
                color = if (selected) colors.blue else colors.outline,
                shape = RoundedCornerShape(14.dp),
            )
            .clickable(interactionSource = interaction, indication = null) {
                haptics.toggle()
                onSelect()
            }
            .padding(start = 14.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SelectedMark(selected = selected)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = node.displayName,
                style = MaterialTheme.typography.titleMedium,
                color = colors.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                // 只显示 host:port。URI 里 `@` 之前那一段是口令,
                // 整条打出来等于把口令画在屏幕上。
                text = "${node.server}:${node.port}",
                style = MaterialTheme.typography.bodyMedium,
                color = colors.inkSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        LatencyBadge(node = node, onTest = onTest)
        NodeOverflow(
            onTest = onTest,
            onCopy = onCopy,
            onEdit = onEdit,
            onDelete = onDelete,
        )
    }
}

/** 选中标记。取代被替换的 `item_node.xml` 里的 `radioSelected`。 */
@Composable
private fun SelectedMark(selected: Boolean) {
    val colors = MaterialTheme.mirage
    Box(
        Modifier
            .size(14.dp)
            .clip(CircleShape)
            .background(if (selected) colors.blue else Color.Transparent)
            .border(
                width = if (selected) 0.dp else 1.5.dp,
                color = if (selected) colors.blue else colors.outline,
                shape = CircleShape,
            ),
    )
}

/**
 * 节点行的溢出菜单。
 *
 * 四个动作（测试延迟 / 复制链接 / 编辑 / 删除）收进 `⋮`，逐项对齐被替换的
 * `NodeAdapter.showPopupMenu`。**不要把它们摊成并排的文字按钮**：那样「删除」
 * 会紧挨「复制链接」，两者都只有 30dp 上下的热区，误触代价是不可逆的删节点；
 * 而每行四个蓝字也让列表读不出层级。删除项单独标红并与其余拉开一条分隔。
 */
@Composable
private fun NodeOverflow(
    onTest: () -> Unit,
    onCopy: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val colors = MaterialTheme.mirage
    val haptics = rememberShellHaptics()
    var open by remember { mutableStateOf(false) }
    val moreDesc = stringResource(R.string.node_menu_more)

    Box {
        Text(
            text = "⋮",
            style = MaterialTheme.typography.headlineSmall,
            color = colors.inkSecondary,
            modifier = Modifier
                .clip(CircleShape)
                .clickable {
                    haptics.tap()
                    open = true
                }
                // 48dp 方形热区: 这一次给足规范值 —— 它不叠在任何内容之上。
                .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                .padding(horizontal = 18.dp, vertical = 10.dp)
                .semantics { contentDescription = moreDesc },
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            MenuAction(R.string.node_menu_test) { open = false; onTest() }
            MenuAction(R.string.node_menu_copy) { open = false; onCopy() }
            MenuAction(R.string.node_edit_title) { open = false; onEdit() }
            HorizontalDivider()
            MenuAction(R.string.node_delete_title, danger = true) { open = false; onDelete() }
        }
    }
}

@Composable
private fun MenuAction(titleRes: Int, danger: Boolean = false, onClick: () -> Unit) {
    val colors = MaterialTheme.mirage
    val haptics = rememberShellHaptics()
    DropdownMenuItem(
        text = {
            Text(
                text = stringResource(titleRes),
                color = if (danger) colors.error else colors.ink,
            )
        },
        onClick = {
            // 破坏性动作用 reject 而非 tap: 触感本身就该提示"这一下不一样"。
            // 真正的拦截仍在后面的确认对话框。
            if (danger) haptics.reject() else haptics.tap()
            onClick()
        },
    )
}

/**
 * 节点延迟的文案与配色。
 *
 * 抽成一处是因为它有**两个**展示点：节点页的每一行，以及首页节点下拉里的每一项。
 * 阈值分头写会让同一个 120ms 在两个界面显示成不同颜色。
 */
@Composable
internal fun nodeLatencyDisplay(node: Node): Pair<String, Color> {
    val colors = MaterialTheme.mirage
    return when {
        node.isTesting -> stringResource(R.string.node_testing) to colors.inkSecondary
        node.testError != null -> stringResource(R.string.node_unreachable) to colors.error
        node.latencyMs == null -> stringResource(R.string.node_untested) to colors.inkSecondary
        node.latencyMs <= RTT_GOOD_MS -> "${node.latencyMs}ms" to colors.connected
        node.latencyMs <= RTT_FAIR_MS -> "${node.latencyMs}ms" to colors.ginger
        else -> "${node.latencyMs}ms" to colors.error
    }
}

/**
 * 延迟徽章。**整块可点, 点一下就测这一个节点。**
 *
 * 这是被替换的 `NodeAdapter:95` 的交互: 徽章本身就是测速按钮。
 * 显示值的地方同时是刷新这个值的地方 —— 不必先去溢出菜单找「测试延迟」。
 */
@Composable
private fun LatencyBadge(node: Node, onTest: () -> Unit) {
    val haptics = rememberShellHaptics()
    val (text, tint) = nodeLatencyDisplay(node)
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = tint,
        maxLines = 1,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(enabled = !node.isTesting) {
                haptics.tap()
                onTest()
            }
            // 热区撑到 44dp 高: 徽章文字本身只有 18dp 左右, 不撑的话这是个
            // 几乎点不中的按钮。
            .defaultMinSize(minHeight = 44.dp)
            .padding(horizontal = 10.dp, vertical = 12.dp),
    )
}

@Composable
internal fun PageHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.headlineMedium,
        color = MaterialTheme.mirage.ink,
        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 14.dp),
    )
}

/**
 * 操作 chip。
 *
 * @param toggleLike 这个 chip 代表一个开关态（如「自动优选」）而不是一次动作。
 *   触感走 `toggle` 而非 `tap` —— 两者在 API 28 上是不同的常量，用户摸得出
 *   「我切了个状态」和「我按了个按钮」的区别。
 */
@Composable
internal fun ActionChip(
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    toggleLike: Boolean = false,
    onClick: () -> Unit,
) {
    val colors = MaterialTheme.mirage
    val haptics = rememberShellHaptics()
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed && enabled) 0.96f else 1f,
        animationSpec = motionAware(ShellMotion.press()),
        label = "chipScale",
    )
    Box(
        modifier = modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                alpha = if (enabled) 1f else 0.5f
            }
            .clip(RoundedCornerShape(12.dp))
            .background(colors.pillBg)
            .border(1.dp, colors.pillStroke, RoundedCornerShape(12.dp))
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
            ) {
                if (toggleLike) haptics.toggle() else haptics.tap()
                onClick()
            }
            .padding(horizontal = 12.dp, vertical = 11.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = colors.ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

// ---- 平台弹层与剪贴板。逐项对齐 NodesFragment 的原行为。 ----

private fun showNodeDialog(context: Context, viewModel: NodesViewModel, index: Int?) {
    val existing = index?.let { viewModel.nodes.value.getOrNull(it) }
    NodeEditDialog.show(context, existing) { uri, name ->
        if (index == null) {
            val newIdx = viewModel.addNode(uri, name)
            viewModel.selectNode(newIdx)
        } else {
            viewModel.updateNode(index, uri, name)
        }
    }
}

private fun confirmDelete(context: Context, viewModel: NodesViewModel, index: Int, node: Node) {
    AlertDialog.Builder(context)
        .setTitle(R.string.node_delete_title)
        .setMessage(context.getString(R.string.node_delete_message, node.displayName))
        .setPositiveButton(R.string.delete) { _, _ -> viewModel.deleteNode(index) }
        .setNegativeButton(R.string.cancel, null)
        .show()
}

private fun copyUri(context: Context, node: Node) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(android.content.ClipData.newPlainText("mirage-node", node.uri))
    Toast.makeText(context, R.string.node_link_copied, Toast.LENGTH_SHORT).show()
}

private fun importFromClipboard(context: Context, viewModel: NodesViewModel) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    val text = cm.primaryClip?.getItemAt(0)?.text?.toString().orEmpty()
    val count = viewModel.importFromClipboard(text)
    if (count > 0) {
        Toast.makeText(context, context.getString(R.string.nodes_imported, count), Toast.LENGTH_SHORT).show()
    } else {
        Toast.makeText(context, R.string.nodes_import_none, Toast.LENGTH_SHORT).show()
    }
}

private fun testAll(context: Context, viewModel: NodesViewModel) {
    viewModel.testAllWithSort { sorted ->
        if (sorted.isEmpty()) {
            Toast.makeText(context, R.string.nodes_all_unavailable, Toast.LENGTH_LONG).show()
            return@testAllWithSort
        }
        val best = sorted.first()
        // 原实现用 Snackbar 并锚定悬浮底栏。底栏在本次重构里没了, 锚点也就没了;
        // 这里退回 Toast, 并直接选中最优节点 —— 原来那个「选最快」动作按钮
        // 在没有 Snackbar 的情况下无处安放, 而让用户测完速再自己去点一遍
        // 等于把唯一有用的结果丢掉。
        val idx = viewModel.nodes.value.indexOfFirst { it.uri == best.first.uri }
        if (idx >= 0) viewModel.selectNode(idx)
        Toast.makeText(
            context,
            context.getString(
                R.string.nodes_test_summary,
                sorted.size,
                best.first.displayName,
                best.second,
            ),
            Toast.LENGTH_LONG,
        ).show()
    }
}

private fun choosePoolSize(context: Context, viewModel: NodesViewModel) {
    val sizes = intArrayOf(1, 4, 8, 16, 32, 64)
    val items = arrayOf(
        context.getString(R.string.pool_1),
        context.getString(R.string.pool_4),
        context.getString(R.string.pool_8),
        context.getString(R.string.pool_16),
        context.getString(R.string.pool_32),
        context.getString(R.string.pool_64),
    )
    val currentIdx = sizes.indexOf(viewModel.poolSize.value).coerceAtLeast(2)
    AlertDialog.Builder(context)
        .setTitle(R.string.nodes_pool_title)
        .setSingleChoiceItems(items, currentIdx) { dialog, which ->
            val selected = sizes[which]
            viewModel.setPoolSize(selected)
            Toast.makeText(
                context,
                context.getString(R.string.nodes_pool_set, selected),
                Toast.LENGTH_SHORT,
            ).show()
            dialog.dismiss()
        }
        .setNegativeButton(R.string.cancel, null)
        .show()
}

private fun chooseTestMethod(context: Context, viewModel: NodesViewModel) {
    // 只剩两项。原先还有一个「ping」, 但它在 NodeRepository.doTest 里落到和
    // 「tcp」同一个分支 —— 三个选项只有两种行为, 名字与实现不符。
    // 顺序也换了: connect 在前, 因为它是默认, 也是唯一测得出协议故障的那个。
    val items = arrayOf(
        context.getString(R.string.nodes_test_connect),
        context.getString(R.string.nodes_test_tcp),
    )
    val keys = arrayOf(NodeRepository.METHOD_CONNECT, NodeRepository.METHOD_TCP)
    val currentIdx = keys.indexOf(viewModel.testMethod.value).coerceAtLeast(0)
    AlertDialog.Builder(context)
        .setTitle(R.string.nodes_test_method_title)
        .setSingleChoiceItems(items, currentIdx) { dialog, which ->
            viewModel.setTestMethod(keys[which])
            dialog.dismiss()
        }
        .setNegativeButton(R.string.cancel, null)
        .show()
}
