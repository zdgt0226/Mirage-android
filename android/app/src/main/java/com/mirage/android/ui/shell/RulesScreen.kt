package com.mirage.android.ui.shell

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.mirage.android.R
import com.mirage.android.core.GeoManager
import com.mirage.android.data.model.Rule
import com.mirage.android.data.model.RuleCondition
import com.mirage.android.ui.theme.mirage
import com.mirage.android.ui.viewmodel.RulesViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

// 纯 Compose 分流规则全屏页。
// 支持长按拖拽重排、条件弹窗编辑、默认出站策略、预设模板切换、命中统计重置与热生效应用。
@Composable
fun RulesScreen(
    viewModel: RulesViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // 拦截返回键回退至设置页，绝不退出应用
    BackHandler(onBack = onBack)

    val colors = MaterialTheme.mirage
    val haptics = rememberShellHaptics()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()

    val rules by viewModel.rules.collectAsStateWithLifecycle()
    val defaultAction by viewModel.defaultAction.collectAsStateWithLifecycle()
    val hasUnappliedChanges by viewModel.hasUnappliedChanges.collectAsStateWithLifecycle()

    // 周期性拉取内核规则命中统计（跟随 STARTED 生命周期，后台自动挂起）
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (isActive) {
                viewModel.refreshRuleHits()
                delay(2000L)
            }
        }
    }

    // 弹窗状态管理（rememberSaveable 保证旋屏不丢弹窗）
    var showEditDialog by rememberSaveable { mutableStateOf(false) }
    var editIndex by rememberSaveable { mutableStateOf<Int?>(null) }
    var editingRuleId by rememberSaveable { mutableStateOf<String?>(null) }

    var showDeleteDialog by rememberSaveable { mutableStateOf(false) }
    var deleteIndex by rememberSaveable { mutableStateOf<Int?>(null) }
    var deletingRuleId by rememberSaveable { mutableStateOf<String?>(null) }

    var showDefaultActionDialog by rememberSaveable { mutableStateOf(false) }
    var showPresetsDialog by rememberSaveable { mutableStateOf(false) }

    fun resolveRuleIndex(ruleId: String?, fallbackIndex: Int?): Int? {
        if (!ruleId.isNullOrBlank()) {
            val byId = rules.indexOfFirst { it.id == ruleId }
            if (byId >= 0) return byId
        }
        return fallbackIndex?.takeIf { it in rules.indices }
    }

    // 拖拽重排状态
    val lazyListState = rememberLazyListState()
    var draggingIndex by remember { mutableStateOf<Int?>(null) }
    var draggedItemKey by remember { mutableStateOf<Any?>(null) }
    var dragOffsetY by remember { mutableFloatStateOf(0f) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.canvas)
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        // 顶部导航栏
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val backDesc = stringResource(R.string.action_back)
            IconButton(
                onClick = {
                    haptics.tap()
                    onBack()
                },
                modifier = Modifier.semantics { contentDescription = backDesc },
            ) {
                Text(
                    text = "‹",
                    style = MaterialTheme.typography.headlineLarge,
                    color = colors.ink,
                )
            }
            Text(
                text = stringResource(R.string.rules_title),
                style = MaterialTheme.typography.headlineMedium,
                color = colors.ink,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )
            TextButton(
                onClick = {
                    haptics.tap()
                    viewModel.resetRuleHits()
                    Toast.makeText(context, R.string.rules_stats_cleared, Toast.LENGTH_SHORT).show()
                },
            ) {
                Text(
                    text = stringResource(R.string.rules_clear_stats),
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.inkSecondary,
                )
            }
        }

        // 快捷操作条（默认动作、预设模板、添加规则）
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val defaultActionLabel = stringResource(
                if (defaultAction == "direct") R.string.action_direct else R.string.action_proxy,
            )
            ActionChip(
                label = stringResource(R.string.rules_default_fmt, defaultActionLabel),
                modifier = Modifier.weight(1f),
                onClick = { showDefaultActionDialog = true },
            )
            ActionChip(
                label = stringResource(R.string.rules_presets),
                modifier = Modifier.weight(1f),
                onClick = { showPresetsDialog = true },
            )
            ActionChip(
                label = stringResource(R.string.rules_add),
                modifier = Modifier.weight(1f),
                onClick = {
                    editingRuleId = null
                    editIndex = null
                    showEditDialog = true
                },
            )
        }

        // 规则列表标题栏与手势提示
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 20.dp, top = 10.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.rules_list_label),
                style = MaterialTheme.typography.titleMedium,
                color = colors.ink,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = stringResource(R.string.rules_drag_hint),
                style = MaterialTheme.typography.bodySmall,
                color = colors.inkSecondary,
            )
        }

        // 规则列表与空态展示
        if (rules.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(32.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(R.string.rules_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.inkSecondary,
                )
            }
        } else {
            LazyColumn(
                state = lazyListState,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 100.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                itemsIndexed(
                    items = rules,
                    key = { index, rule ->
                        if (rule.id.isNotBlank() && rules.indexOfFirst { it.id == rule.id } == index) {
                            "id_${rule.id}"
                        } else {
                            "pos_${index}_${rule.id}"
                        }
                    },
                ) { index, rule ->
                    val itemKey = if (rule.id.isNotBlank() && rules.indexOfFirst { it.id == rule.id } == index) {
                        "id_${rule.id}"
                    } else {
                        "pos_${index}_${rule.id}"
                    }
                    RuleRow(
                        index = index,
                        rule = rule,
                        isDragging = draggingIndex == index,
                        dragOffsetY = if (draggingIndex == index) dragOffsetY else 0f,
                        onToggleEnabled = {
                            val idx = resolveRuleIndex(rule.id, index) ?: return@RuleRow
                            viewModel.toggleRuleEnabled(idx)
                        },
                        onEdit = {
                            editingRuleId = rule.id
                            editIndex = resolveRuleIndex(rule.id, index)
                            showEditDialog = true
                        },
                        onDelete = {
                            deletingRuleId = rule.id
                            deleteIndex = resolveRuleIndex(rule.id, index)
                            showDeleteDialog = true
                        },
                        onMoveTop = if (index > 0) {
                            {
                                val idx = resolveRuleIndex(rule.id, index)
                                if (idx != null && idx > 0) viewModel.moveRule(idx, 0)
                            }
                        } else null,
                        onMoveUp = if (index > 0) {
                            {
                                val idx = resolveRuleIndex(rule.id, index)
                                if (idx != null && idx > 0) viewModel.moveRule(idx, idx - 1)
                            }
                        } else null,
                        onMoveDown = if (index < rules.size - 1) {
                            {
                                val idx = resolveRuleIndex(rule.id, index)
                                if (idx != null && idx < rules.size - 1) viewModel.moveRule(idx, idx + 1)
                            }
                        } else null,
                        onMoveBottom = if (index < rules.size - 1) {
                            {
                                val idx = resolveRuleIndex(rule.id, index)
                                if (idx != null && idx < rules.size - 1) viewModel.moveRule(idx, rules.size - 1)
                            }
                        } else null,
                        onDragStart = {
                            val idx = resolveRuleIndex(rule.id, index)
                            if (idx != null) {
                                draggingIndex = idx
                                draggedItemKey = itemKey
                                dragOffsetY = 0f
                                haptics.longPress()
                            }
                        },
                        onDragDelta = { dy ->
                            val currentIdx = draggingIndex ?: return@RuleRow
                            dragOffsetY += dy
                            val visibleItems = lazyListState.layoutInfo.visibleItemsInfo
                            val currentItem = visibleItems.find { it.index == currentIdx } ?: return@RuleRow
                            // 等待上一次交换后的布局刷新落地，防止同一帧内用旧 layoutInfo 反复交换
                            if (draggedItemKey != null && currentItem.key != draggedItemKey) {
                                return@RuleRow
                            }
                            val currentCenter = currentItem.offset + currentItem.size / 2 + dragOffsetY

                            // 视口边缘自动平滑滚动
                            val viewportStart = lazyListState.layoutInfo.viewportStartOffset
                            val viewportEnd = lazyListState.layoutInfo.viewportEndOffset
                            val edgeThreshold = 80
                            if (currentCenter < viewportStart + edgeThreshold) {
                                scope.launch { lazyListState.scrollBy(-20f) }
                            } else if (currentCenter > viewportEnd - edgeThreshold) {
                                scope.launch { lazyListState.scrollBy(20f) }
                            }

                            // 命中测试：中点穿过目标项中轴即判定触发位移重排
                            val targetItem = visibleItems.find { item ->
                                item.index != currentIdx &&
                                    if (item.index > currentIdx) {
                                        currentCenter > (item.offset + item.size / 2)
                                    } else {
                                        currentCenter < (item.offset + item.size / 2)
                                    }
                            }

                            if (targetItem != null) {
                                val targetIdx = targetItem.index
                                viewModel.moveRule(currentIdx, targetIdx)
                                haptics.segmentTick()
                                dragOffsetY += (currentItem.offset - targetItem.offset)
                                draggingIndex = targetIdx
                            }
                        },
                        onDragEnd = {
                            draggingIndex = null
                            draggedItemKey = null
                            dragOffsetY = 0f
                        },
                    )
                }
            }
        }

        // 底部未生效改动应用提示条
        AnimatedVisibility(
            visible = hasUnappliedChanges,
            enter = expandVertically(expandFrom = Alignment.Bottom) + fadeIn(),
            exit = shrinkVertically(shrinkTowards = Alignment.Bottom) + fadeOut(),
            modifier = Modifier.padding(bottom = 56.dp),
        ) {
            ApplyBanner(
                onApply = {
                    haptics.confirm()
                    val ok = viewModel.applyRules()
                    Toast.makeText(
                        context,
                        if (ok) R.string.rules_applied else R.string.rules_apply_failed,
                        Toast.LENGTH_SHORT,
                    ).show()
                },
            )
        }
    }

    // 默认出站策略选择弹窗
    if (showDefaultActionDialog) {
        DefaultActionDialog(
            currentAction = defaultAction,
            onSelect = { actionKey ->
                viewModel.setDefaultAction(actionKey)
                showDefaultActionDialog = false
            },
            onDismiss = { showDefaultActionDialog = false },
        )
    }

    // 常用分流预设模板弹窗
    if (showPresetsDialog) {
        PresetsDialog(
            onAddPresetRule = { presetRule ->
                viewModel.saveRule(null, presetRule)
                showPresetsDialog = false
            },
            onApplyTemplate = { templateId ->
                viewModel.applyPresetTemplate(templateId)
                showPresetsDialog = false
            },
            onDismiss = { showPresetsDialog = false },
        )
    }

    // 单条规则添加与编辑弹窗
    if (showEditDialog) {
        val resolvedEditIdx = if (editingRuleId == null && editIndex == null) {
            null
        } else {
            resolveRuleIndex(editingRuleId, editIndex)
        }
        val currentEditingRule = resolvedEditIdx?.let { rules.getOrNull(it) }
        EditRuleDialog(
            existing = currentEditingRule,
            editIndex = resolvedEditIdx,
            onDismiss = { showEditDialog = false },
            onSave = { ruleToSave ->
                val targetIdx = if (editingRuleId == null && editIndex == null) {
                    null
                } else {
                    resolveRuleIndex(editingRuleId, editIndex)
                }
                viewModel.saveRule(targetIdx, ruleToSave)
                Toast.makeText(context, R.string.rules_updated, Toast.LENGTH_SHORT).show()
                showEditDialog = false
            },
        )
    }

    // 删除规则确认弹窗
    if (showDeleteDialog) {
        val resolvedDeleteIdx = resolveRuleIndex(deletingRuleId, deleteIndex)
        val rule = resolvedDeleteIdx?.let { rules.getOrNull(it) }
        if (rule == null || resolvedDeleteIdx == null) {
            showDeleteDialog = false
        } else {
            AlertDialog(
                onDismissRequest = { showDeleteDialog = false },
                title = {
                    Text(
                        text = stringResource(R.string.rule_delete_title),
                        color = colors.ink,
                        fontWeight = FontWeight.Bold,
                    )
                },
                text = {
                    Text(
                        text = stringResource(R.string.rule_delete_message, rule.displayName),
                        color = colors.inkSecondary,
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            val targetIdx = resolveRuleIndex(deletingRuleId, deleteIndex)
                            showDeleteDialog = false
                            if (targetIdx != null) {
                                haptics.reject()
                                viewModel.deleteRule(targetIdx)
                            }
                        },
                    ) {
                        Text(
                            text = stringResource(R.string.delete),
                            color = colors.error,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showDeleteDialog = false }) {
                        Text(stringResource(R.string.cancel), color = colors.inkSecondary)
                    }
                },
                containerColor = colors.card,
            )
        }
    }
}

// 单条分流规则卡片
@Composable
private fun RuleRow(
    index: Int,
    rule: Rule,
    isDragging: Boolean,
    dragOffsetY: Float,
    onToggleEnabled: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onMoveTop: (() -> Unit)?,
    onMoveUp: (() -> Unit)?,
    onMoveDown: (() -> Unit)?,
    onMoveBottom: (() -> Unit)?,
    onDragStart: () -> Unit,
    onDragDelta: (Float) -> Unit,
    onDragEnd: () -> Unit,
) {
    val colors = MaterialTheme.mirage
    val haptics = rememberShellHaptics()
    val context = LocalContext.current

    val currentOnDragStart by rememberUpdatedState(onDragStart)
    val currentOnDragDelta by rememberUpdatedState(onDragDelta)
    val currentOnDragEnd by rememberUpdatedState(onDragEnd)

    val scale by animateFloatAsState(
        targetValue = if (isDragging) 1.03f else 1f,
        animationSpec = motionAware(ShellMotion.press()),
        label = "ruleDragScale",
    )
    val elevation by animateDpAsState(
        targetValue = if (isDragging) 8.dp else 0.dp,
        animationSpec = motionAware(ShellMotion.press()),
        label = "ruleDragElevation",
    )
    val dragHandleDesc = stringResource(R.string.rule_drag_handle)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .zIndex(if (isDragging) 1f else 0f)
            .graphicsLayer {
                translationY = if (isDragging) dragOffsetY else 0f
                scaleX = scale
                scaleY = scale
                shadowElevation = elevation.toPx()
            }
            .clip(RoundedCornerShape(14.dp))
            .background(colors.card)
            .border(
                width = if (isDragging) 1.5.dp else 1.dp,
                color = if (isDragging) colors.blue else colors.outline,
                shape = RoundedCornerShape(14.dp),
            )
            .pointerInput(rule.id.ifBlank { index }) {
                detectDragGesturesAfterLongPress(
                    onDragStart = { currentOnDragStart() },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        currentOnDragDelta(dragAmount.y)
                    },
                    onDragEnd = { currentOnDragEnd() },
                    onDragCancel = { currentOnDragEnd() },
                )
            }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) {
                haptics.tap()
                onEdit()
            }
            .padding(horizontal = 12.dp, vertical = 10.dp)
            .graphicsLayer {
                alpha = if (rule.enabled) 1f else 0.5f
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 左侧把手与优先级序号
        Column(
            modifier = Modifier
                .defaultMinSize(minWidth = 36.dp)
                .padding(end = 8.dp)
                .semantics { contentDescription = dragHandleDesc },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = "≡",
                style = MaterialTheme.typography.titleMedium,
                color = colors.inkSecondary,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = "#${index + 1}",
                style = MaterialTheme.typography.labelSmall,
                color = colors.inkSecondary,
                fontSize = 10.sp,
            )
        }

        // 中间规则信息
        Column(modifier = Modifier.weight(1f)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = rule.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    color = colors.ink,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (rule.isComposite) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(colors.accentTint)
                            .padding(horizontal = 4.dp, vertical = 1.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.rule_logic_badge, rule.logic.uppercase()),
                            style = MaterialTheme.typography.labelSmall,
                            color = colors.blue,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                // 动作胶囊徽章
                val (actionColor, actionBg) = when {
                    rule.isDirect -> colors.connected to colors.connected.copy(alpha = 0.12f)
                    rule.isBlock -> colors.error to colors.error.copy(alpha = 0.12f)
                    else -> colors.blue to colors.accentTint
                }
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(actionBg)
                        .padding(horizontal = 5.dp, vertical = 1.dp),
                ) {
                    Text(
                        text = stringResource(rule.actionDisplayNameRes),
                        style = MaterialTheme.typography.labelSmall,
                        color = actionColor,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }

                // 规则类型与匹配内容摘要
                Text(
                    text = rule.summaryText(context),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.inkSecondary,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )

                // 命中统计徽章
                if (rule.hits > 0) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(colors.pillBg)
                            .padding(horizontal = 4.dp, vertical = 1.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.rule_hits_fmt, rule.hits),
                            style = MaterialTheme.typography.labelSmall,
                            color = colors.inkSecondary,
                            fontSize = 9.sp,
                        )
                    }
                }
            }
        }

        Spacer(Modifier.width(8.dp))

        // 右侧启闭 Switch
        Switch(
            checked = rule.enabled,
            onCheckedChange = {
                haptics.toggle()
                onToggleEnabled()
            },
            colors = SwitchDefaults.colors(
                checkedThumbColor = colors.card,
                checkedTrackColor = colors.blue,
                uncheckedThumbColor = colors.card,
                uncheckedTrackColor = colors.outline,
            ),
        )

        // 溢出操作菜单
        RuleOverflowMenu(
            onEdit = onEdit,
            onDelete = onDelete,
            onMoveTop = onMoveTop,
            onMoveUp = onMoveUp,
            onMoveDown = onMoveDown,
            onMoveBottom = onMoveBottom,
        )
    }
}

// 规则行溢出菜单
@Composable
private fun RuleOverflowMenu(
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onMoveTop: (() -> Unit)?,
    onMoveUp: (() -> Unit)?,
    onMoveDown: (() -> Unit)?,
    onMoveBottom: (() -> Unit)?,
) {
    val colors = MaterialTheme.mirage
    val haptics = rememberShellHaptics()
    var open by remember { mutableStateOf(false) }
    val moreDesc = stringResource(R.string.more_actions)

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
                .padding(horizontal = 8.dp, vertical = 4.dp)
                .semantics { contentDescription = moreDesc },
        )
        DropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
        ) {
            if (onMoveTop != null) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.rule_menu_top), color = colors.ink) },
                    onClick = {
                        open = false
                        haptics.tap()
                        onMoveTop()
                    },
                )
            }
            if (onMoveUp != null) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.rule_menu_up), color = colors.ink) },
                    onClick = {
                        open = false
                        haptics.tap()
                        onMoveUp()
                    },
                )
            }
            if (onMoveDown != null) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.rule_menu_down), color = colors.ink) },
                    onClick = {
                        open = false
                        haptics.tap()
                        onMoveDown()
                    },
                )
            }
            if (onMoveBottom != null) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.rule_menu_bottom), color = colors.ink) },
                    onClick = {
                        open = false
                        haptics.tap()
                        onMoveBottom()
                    },
                )
            }
            DropdownMenuItem(
                text = { Text(stringResource(R.string.rule_menu_edit), color = colors.ink) },
                onClick = {
                    open = false
                    haptics.tap()
                    onEdit()
                },
            )
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text(stringResource(R.string.rule_menu_delete), color = colors.error) },
                onClick = {
                    open = false
                    haptics.reject()
                    onDelete()
                },
            )
        }
    }
}

// 底部未生效变更应用横幅
@Composable
private fun ApplyBanner(onApply: () -> Unit) {
    val colors = MaterialTheme.mirage
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(colors.card)
            .border(1.dp, colors.outline, RoundedCornerShape(14.dp))
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.rules_unapplied_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = colors.ink,
            fontSize = 13.sp,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(18.dp))
                .background(colors.blue)
                .clickable(onClick = onApply)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = stringResource(R.string.rules_apply),
                style = MaterialTheme.typography.labelLarge,
                color = colors.card,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

// 默认出站策略选择弹窗
@Composable
private fun DefaultActionDialog(
    currentAction: String,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = MaterialTheme.mirage
    val haptics = rememberShellHaptics()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = stringResource(R.string.rules_default_title),
                color = colors.ink,
                fontWeight = FontWeight.Bold,
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val options = listOf(
                    "proxy" to R.string.rules_default_proxy_item,
                    "direct" to R.string.rules_default_direct_item,
                )
                options.forEach { (actionKey, labelRes) ->
                    val selected = currentAction == actionKey
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (selected) colors.accentTint else Color.Transparent)
                            .clickable {
                                haptics.tap()
                                onSelect(actionKey)
                            }
                            .padding(horizontal = 12.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = stringResource(labelRes),
                            style = MaterialTheme.typography.bodyLarge,
                            color = if (selected) colors.blue else colors.ink,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                            modifier = Modifier.weight(1f),
                        )
                        if (selected) {
                            Text(text = "✓", color = colors.blue, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel), color = colors.inkSecondary)
            }
        },
        containerColor = colors.card,
    )
}

// 常用分流预设与单条 Geo 标签添加弹窗
@Composable
private fun PresetsDialog(
    onAddPresetRule: (Rule) -> Unit,
    onApplyTemplate: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = MaterialTheme.mirage
    val haptics = rememberShellHaptics()
    val context = LocalContext.current

    val presetTags = GeoManager.PRESET_GEO_TAGS
    var selectedTagIdx by rememberSaveable { mutableIntStateOf(0) }
    val safeTagIdx = selectedTagIdx.coerceIn(0, (presetTags.size - 1).coerceAtLeast(0))
    val currentPreset = presetTags.getOrNull(safeTagIdx)
    var selectedAction by rememberSaveable {
        mutableStateOf(currentPreset?.defaultAction ?: "proxy")
    }
    var tagDropdownExpanded by remember { mutableStateOf(false) }

    val actions = listOf(
        "proxy" to R.string.rule_action_proxy,
        "direct" to R.string.rule_action_direct,
        "block" to R.string.rule_action_block,
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = stringResource(R.string.rules_preset_title),
                color = colors.ink,
                fontWeight = FontWeight.Bold,
            )
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = stringResource(R.string.rules_preset_intro),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.inkSecondary,
                )

                // 单条 Geo 标签预设快速添加
                if (currentPreset != null) {
                    Text(
                        text = stringResource(R.string.rules_preset_pick_tag),
                        style = MaterialTheme.typography.labelMedium,
                        color = colors.inkSecondary,
                        fontWeight = FontWeight.Bold,
                    )
                    Box(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(colors.pillBg)
                                .border(1.dp, colors.pillStroke, RoundedCornerShape(8.dp))
                                .clickable { tagDropdownExpanded = true }
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = currentPreset.title,
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.ink,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(text = "▼", fontSize = 10.sp, color = colors.inkSecondary)
                        }
                        DropdownMenu(
                            expanded = tagDropdownExpanded,
                            onDismissRequest = { tagDropdownExpanded = false },
                        ) {
                            presetTags.forEachIndexed { idx, tagItem ->
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            text = tagItem.title,
                                            color = if (idx == safeTagIdx) colors.blue else colors.ink,
                                            fontWeight = if (idx == safeTagIdx) FontWeight.Bold else FontWeight.Normal,
                                        )
                                    },
                                    onClick = {
                                        selectedTagIdx = idx
                                        selectedAction = tagItem.defaultAction
                                        tagDropdownExpanded = false
                                    },
                                )
                            }
                        }
                    }

                    Text(
                        text = stringResource(R.string.rules_route_action),
                        style = MaterialTheme.typography.labelMedium,
                        color = colors.inkSecondary,
                        fontWeight = FontWeight.Bold,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        actions.forEach { (actionKey, labelRes) ->
                            val selected = selectedAction == actionKey
                            val (selColor, selBg) = when (actionKey) {
                                "direct" -> colors.connected to colors.connected.copy(alpha = 0.15f)
                                "block" -> colors.error to colors.error.copy(alpha = 0.15f)
                                else -> colors.blue to colors.accentTint
                            }
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (selected) selBg else colors.pillBg)
                                    .border(
                                        1.dp,
                                        if (selected) selColor else colors.pillStroke,
                                        RoundedCornerShape(8.dp),
                                    )
                                    .clickable {
                                        haptics.tap()
                                        selectedAction = actionKey
                                    }
                                    .padding(vertical = 8.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text = stringResource(labelRes),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = if (selected) selColor else colors.ink,
                                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                                    maxLines = 1,
                                )
                            }
                        }
                    }

                    ActionChip(
                        label = stringResource(R.string.rules_preset_add_one),
                        modifier = Modifier.fillMaxWidth(),
                        onClick = {
                            haptics.confirm()
                            val newRule = Rule(
                                name = currentPreset.title,
                                type = currentPreset.kind,
                                kind = currentPreset.kind,
                                pattern = currentPreset.tag,
                                action = selectedAction,
                                conditions = listOf(RuleCondition(currentPreset.kind, currentPreset.tag)),
                            )
                            val actionDisplay = context.getString(newRule.actionDisplayNameRes)
                            onAddPresetRule(newRule)
                            Toast.makeText(
                                context,
                                context.getString(
                                    R.string.rules_preset_added,
                                    "${currentPreset.kind}:${currentPreset.tag}",
                                    actionDisplay,
                                ),
                                Toast.LENGTH_SHORT,
                            ).show()
                        },
                    )

                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                }

                Text(
                    text = stringResource(R.string.rules_template_switch),
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.inkSecondary,
                    fontWeight = FontWeight.Bold,
                )
                val templates = listOf(
                    Triple(0, R.string.tpl_classic, R.string.tpl_classic_desc),
                    Triple(1, R.string.tpl_whitelist, R.string.tpl_whitelist_desc),
                    Triple(2, R.string.tpl_blacklist, R.string.tpl_blacklist_desc),
                    Triple(3, R.string.tpl_adsonly, R.string.tpl_adsonly_desc),
                )
                templates.forEach { (id, titleRes, descRes) ->
                    val titleText = stringResource(titleRes)
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(colors.pillBg)
                            .border(1.dp, colors.pillStroke, RoundedCornerShape(10.dp))
                            .clickable {
                                haptics.confirm()
                                onApplyTemplate(id)
                                Toast.makeText(
                                    context,
                                    context.getString(R.string.rules_template_applied, titleText),
                                    Toast.LENGTH_LONG,
                                ).show()
                            }
                            .padding(12.dp),
                    ) {
                        Text(
                            text = titleText,
                            style = MaterialTheme.typography.titleSmall,
                            color = colors.ink,
                            fontWeight = FontWeight.Bold,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = stringResource(descRes),
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.inkSecondary,
                            fontSize = 11.sp,
                        )
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel), color = colors.inkSecondary)
            }
        },
        containerColor = colors.card,
    )
}

private fun normalizeConditionType(raw: String): String = when (raw.lowercase()) {
    "cidr", "ip_cidr" -> "ip_cidr"
    "exact", "domain_exact" -> "domain_exact"
    "keyword", "domain_keyword" -> "domain_keyword"
    "regex", "domain_regex" -> "domain_regex"
    "geosite" -> "geosite"
    "geoip" -> "geoip"
    "port" -> "port"
    "protocol" -> "protocol"
    else -> "domain_suffix"
}

// 单条/多条件复合规则添加与编辑弹窗
@Composable
private fun EditRuleDialog(
    existing: Rule?,
    editIndex: Int?,
    onDismiss: () -> Unit,
    onSave: (Rule) -> Unit,
) {
    val colors = MaterialTheme.mirage
    val haptics = rememberShellHaptics()
    val context = LocalContext.current

    var name by rememberSaveable(existing?.id) { mutableStateOf(existing?.name.orEmpty()) }
    var action by rememberSaveable(existing?.id) { mutableStateOf(existing?.action ?: "proxy") }
    var logic by rememberSaveable(existing?.id) {
        mutableStateOf(
            if (existing?.logic?.equals("AND", ignoreCase = true) == true) "AND" else "OR",
        )
    }

    val conditions = rememberSaveable(
        existing?.id,
        saver = listSaver(
            save = { list -> list.flatMap { listOf(it.type, it.pattern) } },
            restore = { flat ->
                mutableStateListOf<RuleCondition>().apply {
                    for (i in 0 until flat.size step 2) {
                        add(
                            RuleCondition(
                                normalizeConditionType(flat[i]),
                                flat.getOrElse(i + 1) { "" },
                            ),
                        )
                    }
                    if (isEmpty()) {
                        add(RuleCondition("domain_suffix", ""))
                    }
                }
            },
        ),
    ) {
        val initial = existing?.effectiveConditions
            ?.takeIf { it.isNotEmpty() }
            ?.map { RuleCondition(normalizeConditionType(it.type), it.pattern) }
            ?: listOf(RuleCondition("domain_suffix", ""))
        mutableStateListOf<RuleCondition>().apply { addAll(initial) }
    }

    val kinds = listOf(
        "domain_suffix" to R.string.cond_kind_domain_suffix,
        "domain_exact" to R.string.cond_kind_domain_exact,
        "domain_keyword" to R.string.cond_kind_domain_keyword,
        "domain_regex" to R.string.cond_kind_domain_regex,
        "geosite" to R.string.cond_kind_geosite,
        "geoip" to R.string.cond_kind_geoip,
        "ip_cidr" to R.string.cond_kind_ip_cidr,
        "port" to R.string.cond_kind_port,
        "protocol" to R.string.cond_kind_protocol,
    )

    val actions = listOf(
        "proxy" to R.string.rule_action_proxy,
        "direct" to R.string.rule_action_direct,
        "block" to R.string.rule_action_block,
    )

    val logicOptions = listOf(
        "OR" to R.string.rule_logic_or,
        "AND" to R.string.rule_logic_and,
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = stringResource(if (existing == null && editIndex == null) R.string.rule_dialog_add else R.string.rule_dialog_edit),
                style = MaterialTheme.typography.titleLarge,
                color = colors.ink,
                fontWeight = FontWeight.Bold,
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // 1. 规则名称
                Text(
                    text = stringResource(R.string.rule_name_label),
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.inkSecondary,
                    fontWeight = FontWeight.Bold,
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    placeholder = {
                        Text(
                            text = stringResource(R.string.rule_name_hint),
                            fontSize = 13.sp,
                            color = colors.inkSecondary,
                        )
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = colors.ink,
                        unfocusedTextColor = colors.ink,
                        focusedBorderColor = colors.blue,
                        unfocusedBorderColor = colors.outline,
                    ),
                )

                // 2. 动作选择
                Text(
                    text = stringResource(R.string.rule_action_label),
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.inkSecondary,
                    fontWeight = FontWeight.Bold,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    actions.forEach { (actionKey, labelRes) ->
                        val selected = action == actionKey
                        val (selColor, selBg) = when (actionKey) {
                            "direct" -> colors.connected to colors.connected.copy(alpha = 0.15f)
                            "block" -> colors.error to colors.error.copy(alpha = 0.15f)
                            else -> colors.blue to colors.accentTint
                        }
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (selected) selBg else colors.pillBg)
                                .border(
                                    1.dp,
                                    if (selected) selColor else colors.pillStroke,
                                    RoundedCornerShape(8.dp),
                                )
                                .clickable {
                                    haptics.tap()
                                    action = actionKey
                                }
                                .padding(vertical = 8.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = stringResource(labelRes),
                                style = MaterialTheme.typography.labelMedium,
                                color = if (selected) selColor else colors.ink,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                                maxLines = 1,
                            )
                        }
                    }
                }

                // 3. 多条件匹配逻辑 (OR / AND)
                if (conditions.size > 1) {
                    Text(
                        text = stringResource(R.string.rule_logic_label),
                        style = MaterialTheme.typography.labelMedium,
                        color = colors.inkSecondary,
                        fontWeight = FontWeight.Bold,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        logicOptions.forEach { (logicKey, labelRes) ->
                            val selected = logic.equals(logicKey, ignoreCase = true)
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (selected) colors.accentTint else colors.pillBg)
                                    .border(
                                        1.dp,
                                        if (selected) colors.blue else colors.pillStroke,
                                        RoundedCornerShape(8.dp),
                                    )
                                    .clickable {
                                        haptics.tap()
                                        logic = logicKey
                                    }
                                    .padding(vertical = 8.dp, horizontal = 6.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text = stringResource(labelRes),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (selected) colors.blue else colors.ink,
                                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }

                // 4. 匹配条件清单
                Text(
                    text = stringResource(R.string.rule_conditions_label),
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.inkSecondary,
                    fontWeight = FontWeight.Bold,
                )

                val deleteCondDesc = stringResource(R.string.rule_delete_condition)
                conditions.forEachIndexed { condIdx, cond ->
                    val normalizedKind = normalizeConditionType(cond.type)
                    var kindDropdownExpanded by remember { mutableStateOf(false) }
                    val currentKindLabel = kinds.find { it.first == normalizedKind }?.second
                        ?: R.string.cond_kind_domain_suffix

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(colors.pillBg.copy(alpha = 0.45f))
                            .border(1.dp, colors.outline.copy(alpha = 0.6f), RoundedCornerShape(10.dp))
                            .padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Box(modifier = Modifier.weight(1f)) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(colors.pillBg)
                                        .border(1.dp, colors.pillStroke, RoundedCornerShape(8.dp))
                                        .clickable { kindDropdownExpanded = true }
                                        .padding(horizontal = 12.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        text = stringResource(currentKindLabel),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = colors.ink,
                                        modifier = Modifier.weight(1f),
                                    )
                                    Text(text = "▼", fontSize = 10.sp, color = colors.inkSecondary)
                                }
                                DropdownMenu(
                                    expanded = kindDropdownExpanded,
                                    onDismissRequest = { kindDropdownExpanded = false },
                                ) {
                                    kinds.forEach { (kKey, kLabelRes) ->
                                        DropdownMenuItem(
                                            text = {
                                                Text(
                                                    text = stringResource(kLabelRes),
                                                    color = if (normalizedKind == kKey) colors.blue else colors.ink,
                                                    fontWeight = if (normalizedKind == kKey) FontWeight.Bold else FontWeight.Normal,
                                                )
                                            },
                                            onClick = {
                                                conditions[condIdx] = cond.copy(type = kKey)
                                                kindDropdownExpanded = false
                                            },
                                        )
                                    }
                                }
                            }
                            if (conditions.size > 1) {
                                IconButton(
                                    onClick = {
                                        if (conditions.size <= 1) {
                                            Toast.makeText(
                                                context,
                                                R.string.rule_need_one_condition,
                                                Toast.LENGTH_SHORT,
                                            ).show()
                                        } else {
                                            haptics.tap()
                                            conditions.removeAt(condIdx)
                                        }
                                    },
                                    modifier = Modifier.semantics { contentDescription = deleteCondDesc },
                                ) {
                                    Text(
                                        text = "✕",
                                        style = MaterialTheme.typography.titleMedium,
                                        color = colors.error,
                                        fontWeight = FontWeight.Bold,
                                    )
                                }
                            }
                        }

                        OutlinedTextField(
                            value = cond.pattern,
                            onValueChange = { newText ->
                                conditions[condIdx] = cond.copy(pattern = newText)
                            },
                            placeholder = {
                                Text(
                                    text = stringResource(R.string.rule_condition_hint),
                                    fontSize = 13.sp,
                                    color = colors.inkSecondary,
                                )
                            },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = colors.ink,
                                unfocusedTextColor = colors.ink,
                                focusedBorderColor = colors.blue,
                                unfocusedBorderColor = colors.outline,
                            ),
                        )
                    }
                }

                // 5. 添加条件按钮
                ActionChip(
                    label = stringResource(R.string.rule_add_condition),
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        conditions.add(RuleCondition("domain_suffix", ""))
                    },
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val validConds = conditions.mapNotNull { c ->
                        val p = c.pattern.trim().lowercase()
                        if (p.isBlank()) null else RuleCondition(normalizeConditionType(c.type), p)
                    }
                    if (validConds.isEmpty()) {
                        Toast.makeText(context, R.string.rule_need_valid_param, Toast.LENGTH_SHORT).show()
                        return@TextButton
                    }
                    haptics.confirm()
                    val first = validConds.first()
                    val mappedType = when (first.type) {
                        "cidr", "ip_cidr" -> "cidr"
                        "geosite" -> "geosite"
                        "geoip" -> "geoip"
                        "port" -> "port"
                        "protocol" -> "protocol"
                        else -> "domain"
                    }
                    val updated = (existing ?: Rule()).copy(
                        name = name.trim(),
                        logic = if (logic.equals("AND", ignoreCase = true)) "AND" else "OR",
                        pattern = first.pattern,
                        kind = first.type,
                        type = mappedType,
                        action = action,
                        conditions = validConds,
                    )
                    onSave(updated)
                },
            ) {
                Text(
                    text = stringResource(R.string.save_and_enable),
                    color = colors.blue,
                    fontWeight = FontWeight.Bold,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel), color = colors.inkSecondary)
            }
        },
        containerColor = colors.card,
    )
}
