package com.mirage.android.ui

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.mirage.android.R
import com.mirage.android.core.GeoManager
import com.mirage.android.data.model.Rule
import com.mirage.android.data.model.RuleCondition
import com.mirage.android.databinding.DialogEditRuleBinding
import com.mirage.android.databinding.FragmentRulesBinding
import com.mirage.android.databinding.ItemDialogConditionBinding
import com.mirage.android.ui.adapter.RuleAdapter
import com.mirage.android.ui.viewmodel.RulesViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 分流规则 Tab: 复合多条件规则 + 原生拖拽排序 + 独立 Geo 资产中心集成。
 */
class RulesFragment : Fragment() {

    private var _binding: FragmentRulesBinding? = null
    private val binding get() = _binding!!

    private val viewModel: RulesViewModel by viewModels()
    private lateinit var adapter: RuleAdapter
    private lateinit var itemTouchHelper: ItemTouchHelper

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentRulesBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupRecyclerView()
        setupButtons()
        observeState()
        refreshGeoSummary()
        refreshAppFilterSummary()
    }

    override fun onResume() {
        super.onResume()
        refreshGeoSummary()
        refreshAppFilterSummary()
    }

    private fun setupRecyclerView() {
        adapter = RuleAdapter(
            onEdit = { index, _ -> showRuleDialog(index) },
            onDelete = { index, rule ->
                AlertDialog.Builder(requireContext())
                    .setTitle("删除规则")
                    .setMessage("确定要删除规则「${rule.displayName}」吗？")
                    .setPositiveButton("删除") { _, _ -> viewModel.deleteRule(index) }
                    .setNegativeButton("取消", null)
                    .show()
            },
            onToggleEnabled = { index, _, _ ->
                viewModel.toggleRuleEnabled(index)
            },
            onMove = { from, to ->
                viewModel.moveRule(from, to)
            },
            onStartDrag = { viewHolder ->
                itemTouchHelper.startDrag(viewHolder)
            }
        )

        val callback = object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0
        ) {
            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean {
                val fromPos = viewHolder.bindingAdapterPosition
                val toPos = target.bindingAdapterPosition
                if (fromPos != RecyclerView.NO_POSITION && toPos != RecyclerView.NO_POSITION && fromPos != toPos) {
                    viewModel.moveRule(fromPos, toPos)
                    return true
                }
                return false
            }

            override fun onSelectedChanged(viewHolder: RecyclerView.ViewHolder?, actionState: Int) {
                super.onSelectedChanged(viewHolder, actionState)
                if (actionState == ItemTouchHelper.ACTION_STATE_DRAG && viewHolder is RuleAdapter.RuleViewHolder) {
                    // 触觉震动反馈
                    viewHolder.itemView.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                    // 浮起放大与高亮
                    viewHolder.binding.cardRule.apply {
                        animate().scaleX(1.03f).scaleY(1.03f).setDuration(120).start()
                        cardElevation = 16f
                        strokeWidth = (2 * resources.displayMetrics.density).toInt()
                        strokeColor = ContextCompat.getColor(context, R.color.meow_blue)
                    }
                }
            }

            override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
                super.clearView(recyclerView, viewHolder)
                if (viewHolder is RuleAdapter.RuleViewHolder) {
                    viewHolder.binding.cardRule.apply {
                        animate().scaleX(1.0f).scaleY(1.0f).setDuration(120).start()
                        cardElevation = 0f
                        strokeWidth = (1 * resources.displayMetrics.density).toInt()
                        strokeColor = ContextCompat.getColor(context, R.color.meow_outline)
                    }
                }
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {}
            override fun isLongPressDragEnabled(): Boolean = false // 由左侧专有把手与长按精确触发
        }

        itemTouchHelper = ItemTouchHelper(callback)
        itemTouchHelper.attachToRecyclerView(binding.recyclerRules)

        binding.recyclerRules.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerRules.adapter = adapter
    }

    private fun setupButtons() {
        binding.cardGeoAssetCenter.setOnClickListener {
            startActivity(Intent(requireContext(), GeoAssetActivity::class.java))
        }

        binding.cardAppFilter.setOnClickListener {
            startActivity(Intent(requireContext(), AppFilterActivity::class.java))
        }

        binding.btnQuickGeoPreset.setOnClickListener { showQuickGeoPresetDialog() }

        binding.addRuleBtn.setOnClickListener { showRuleDialog(null) }

        binding.btnResetHits.setOnClickListener {
            viewModel.resetRuleHits()
            Toast.makeText(requireContext(), "已清空规则命中统计", Toast.LENGTH_SHORT).show()
        }

        binding.defaultActionBtn.setOnClickListener { chooseDefaultAction() }

        binding.applyRulesBtn.setOnClickListener {
            val ok = viewModel.applyRules()
            Toast.makeText(requireContext(), if (ok) "分流规则已立即生效" else "规则应用失败", Toast.LENGTH_SHORT).show()
        }
    }

    private fun refreshAppFilterSummary() {
        val ctx = context ?: return
        val config = com.mirage.android.core.AppFilterStore.getConfig(ctx)
        if (!config.enabled) {
            binding.tvAppFilterSummary.text = "未启用 (默认全部应用由 VPN 规则接管)"
        } else {
            val modeText = if (config.mode == com.mirage.android.data.model.AppFilterMode.ALLOW) "白名单模式" else "黑名单模式"
            binding.tvAppFilterSummary.text = "已启用 · $modeText (${config.selectedPackages.size} 款应用)"
        }
    }

    private fun refreshGeoSummary() {
        val ctx = context ?: return
        val status = GeoManager.getGeoStatus(ctx)
        val source = GeoManager.getActiveSource(ctx)
        binding.tvGeoStatusSummary.text = if (status.isReady) {
            "源: ${source.name} · ${status.geositeTagCount} Sites / ${status.geoipCodeCount} IPs · ${status.lastUpdateTime}"
        } else {
            "未就绪 · 点击进入资产中心在线下载 Geo 规则库"
        }
    }

    private fun observeState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.rules.collect { list ->
                        adapter.submitList(list)
                        binding.tvEmptyRules.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
                    }
                }
                launch {
                    viewModel.defaultAction.collect { act ->
                        binding.defaultActionBtn.text = "默认: ${if (act == "direct") "直连" else "代理"}"
                    }
                }
                // 周期性拉取内核命中统计
                launch {
                    while (isActive) {
                        viewModel.refreshRuleHits()
                        kotlinx.coroutines.delay(2000)
                    }
                }
            }
        }
    }

    private fun chooseDefaultAction() {
        val items = arrayOf("代理 (默认)", "直连 (默认)")
        val current = if (viewModel.defaultAction.value == "direct") 1 else 0

        AlertDialog.Builder(requireContext())
            .setTitle("默认策略 (未匹配任何规则时)")
            .setSingleChoiceItems(items, current) { dialog, which ->
                viewModel.setDefaultAction(if (which == 0) "proxy" else "direct")
                dialog.dismiss()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /**
     * 常用 Geo 预设规则快捷添加弹窗
     */
    private fun showQuickGeoPresetDialog() {
        val ctx = requireContext()
        val layout = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 20, 40, 10)
        }

        val tvDesc = TextView(ctx).apply {
            text = "选择常用的 GeoSite / GeoIP 规则模板，可快速添加为自定义分流规则："
            textSize = 13f
            setTextColor(ContextCompat.getColor(ctx, R.color.meow_ink))
            setPadding(0, 0, 0, 16)
        }
        layout.addView(tvDesc)

        val presetTitles = GeoManager.PRESET_GEO_TAGS.map { it.title }
        val presetSpinner = Spinner(ctx).apply {
            adapter = ArrayAdapter(ctx, android.R.layout.simple_spinner_dropdown_item, presetTitles)
            setSelection(0)
            setPadding(0, 16, 0, 16)
        }
        layout.addView(TextView(ctx).apply {
            text = "选择常用 Tag 模板:"
            textSize = 12f
            setTextColor(ContextCompat.getColor(ctx, R.color.meow_ink_secondary))
        })
        layout.addView(presetSpinner)

        val tvTagDesc = TextView(ctx).apply {
            text = GeoManager.PRESET_GEO_TAGS.firstOrNull()?.description ?: ""
            textSize = 12f
            setTextColor(ContextCompat.getColor(ctx, R.color.meow_ink_secondary))
            setPadding(0, 8, 0, 16)
        }
        layout.addView(tvTagDesc)

        val actions = arrayOf("直连 (direct)", "代理 (proxy)", "拦截 (block)")
        val actionKeys = arrayOf("direct", "proxy", "block")
        val actionSpinner = Spinner(ctx).apply {
            adapter = ArrayAdapter(ctx, android.R.layout.simple_spinner_dropdown_item, actions)
            setPadding(0, 16, 0, 16)
        }
        layout.addView(TextView(ctx).apply {
            text = "路由动作:"
            textSize = 12f
            setTextColor(ContextCompat.getColor(ctx, R.color.meow_ink_secondary))
        })
        layout.addView(actionSpinner)

        presetSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (position in GeoManager.PRESET_GEO_TAGS.indices) {
                    val preset = GeoManager.PRESET_GEO_TAGS[position]
                    tvTagDesc.text = preset.description
                    val aIdx = actionKeys.indexOf(preset.defaultAction).coerceAtLeast(0)
                    actionSpinner.setSelection(aIdx)
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        if (GeoManager.PRESET_GEO_TAGS.isNotEmpty()) {
            val aIdx = actionKeys.indexOf(GeoManager.PRESET_GEO_TAGS[0].defaultAction).coerceAtLeast(0)
            actionSpinner.setSelection(aIdx)
        }

        AlertDialog.Builder(ctx)
            .setTitle("常用 Geo 规则预设")
            .setView(layout)
            .setPositiveButton("添加此规则") { _, _ ->
                val pos = presetSpinner.selectedItemPosition
                if (pos in GeoManager.PRESET_GEO_TAGS.indices) {
                    val preset = GeoManager.PRESET_GEO_TAGS[pos]
                    val action = actionKeys[actionSpinner.selectedItemPosition]
                    val rule = Rule(
                        name = preset.title,
                        enabled = true,
                        logic = "OR",
                        conditions = listOf(RuleCondition(preset.kind, preset.tag)),
                        type = preset.kind,
                        kind = preset.kind,
                        pattern = preset.tag,
                        action = action
                    )
                    viewModel.saveRule(null, rule)
                    Toast.makeText(ctx, "已添加规则: ${preset.kind}:${preset.tag} -> $action", Toast.LENGTH_SHORT).show()
                }
            }
            .setNeutralButton("一键添加推荐组合") { _, _ ->
                val bundle = listOf(
                    Triple("category-ads-all", "geosite", "block"),
                    Triple("google", "geosite", "proxy"),
                    Triple("openai", "geosite", "proxy"),
                    Triple("telegram", "geosite", "proxy"),
                    Triple("cn", "geosite", "direct")
                )
                for ((tag, kind, act) in bundle) {
                    val rule = Rule(
                        name = "Geo: $tag",
                        enabled = true,
                        logic = "OR",
                        conditions = listOf(RuleCondition(kind, tag)),
                        type = kind,
                        kind = kind,
                        pattern = tag,
                        action = act
                    )
                    viewModel.saveRule(null, rule)
                }
                Toast.makeText(ctx, "已一键添加 5 条推荐常用 Geo 规则！", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /**
     * 添加/编辑复合多条件分流规则弹窗 (全宽 BottomSheetDialog，给小屏提供充裕的滑动手势与按键触控区)
     */
    private fun showRuleDialog(index: Int?) {
        val existing = index?.let { viewModel.rules.value.getOrNull(it) }
        val ctx = requireContext()

        val dialog = BottomSheetDialog(ctx)
        val dBinding = DialogEditRuleBinding.inflate(layoutInflater)
        dialog.setContentView(dBinding.root)

        dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        dialog.behavior.skipCollapsed = true

        dBinding.tvDialogTitle.text = if (index == null) "添加分流规则" else "编辑分流规则"
        dBinding.etRuleName.setText(existing?.name ?: "")

        // 动作选择
        when (existing?.action) {
            "direct" -> dBinding.toggleAction.check(R.id.btnActionDirect)
            "block" -> dBinding.toggleAction.check(R.id.btnActionBlock)
            else -> dBinding.toggleAction.check(R.id.btnActionProxy)
        }

        // 逻辑选择
        if (existing?.logic?.equals("AND", ignoreCase = true) == true) {
            dBinding.toggleLogic.check(R.id.btnLogicAnd)
        } else {
            dBinding.toggleLogic.check(R.id.btnLogicOr)
        }

        val conditionTypes = arrayOf(
            "GEOSITE (域名集标签，如 google, cn)",
            "GEOIP (IP分类/代码，如 cn, telegram)",
            "DOMAIN-SUFFIX (域名后缀，如 google.com)",
            "DOMAIN (完整域名精确匹配)",
            "DOMAIN-KEYWORD (域名关键词包含)",
            "DOMAIN-REGEX (正则表达式)",
            "IP-CIDR (IP/掩码，如 192.168.0.0/16)",
            "PORT (目标端口，如 443 或 8000-8888)",
            "PROTOCOL (传输协议，如 tcp / udp)"
        )
        val conditionTypeKeys = arrayOf(
            "geosite", "geoip", "domain_suffix", "domain_exact", "domain_keyword",
            "domain_regex", "ip_cidr", "port", "protocol"
        )

        val conditionsList = (existing?.effectiveConditions ?: listOf(RuleCondition("domain_suffix", ""))).toMutableList()

        fun renderConditions() {
            dBinding.containerConditions.removeAllViews()
            for ((cIdx, cond) in conditionsList.withIndex()) {
                val itemBinding = ItemDialogConditionBinding.inflate(layoutInflater, dBinding.containerConditions, false)
                
                val adapter = ArrayAdapter(ctx, android.R.layout.simple_spinner_dropdown_item, conditionTypes)
                itemBinding.spinnerCondType.adapter = adapter
                val kIdx = conditionTypeKeys.indexOf(cond.type).coerceAtLeast(0)
                itemBinding.spinnerCondType.setSelection(kIdx)

                itemBinding.etCondPattern.setText(cond.pattern)

                itemBinding.spinnerCondType.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                    override fun onItemSelected(p0: AdapterView<*>?, p1: View?, pos: Int, p3: Long) {
                        conditionsList[cIdx] = conditionsList[cIdx].copy(type = conditionTypeKeys[pos])
                    }
                    override fun onNothingSelected(p0: AdapterView<*>?) {}
                }

                itemBinding.etCondPattern.addTextChangedListener(object : android.text.TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                        conditionsList[cIdx] = conditionsList[cIdx].copy(pattern = s?.toString()?.trim() ?: "")
                    }
                    override fun afterTextChanged(s: android.text.Editable?) {}
                })

                itemBinding.btnDeleteCond.setOnClickListener {
                    if (conditionsList.size > 1) {
                        conditionsList.removeAt(cIdx)
                        renderConditions()
                    } else {
                        Toast.makeText(ctx, "至少需保留一个匹配条件", Toast.LENGTH_SHORT).show()
                    }
                }

                dBinding.containerConditions.addView(itemBinding.root)
            }
        }

        renderConditions()

        dBinding.btnAddCondition.setOnClickListener {
            conditionsList.add(RuleCondition("domain_suffix", ""))
            renderConditions()
        }

        dBinding.btnCancel.setOnClickListener {
            dialog.dismiss()
        }

        dBinding.btnSave.setOnClickListener {
            val validConds = conditionsList.filter { it.pattern.isNotBlank() }
            if (validConds.isEmpty()) {
                Toast.makeText(ctx, "请至少填写一个有效的匹配参数", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val name = dBinding.etRuleName.text.toString().trim()
            val action = when (dBinding.toggleAction.checkedButtonId) {
                R.id.btnActionDirect -> "direct"
                R.id.btnActionBlock -> "block"
                else -> "proxy"
            }
            val logic = if (dBinding.toggleLogic.checkedButtonId == R.id.btnLogicAnd) "AND" else "OR"

            val first = validConds.first()
            val rule = Rule(
                id = existing?.id ?: java.util.UUID.randomUUID().toString(),
                name = name,
                enabled = existing?.enabled ?: true,
                logic = logic,
                conditions = validConds,
                type = first.type,
                kind = first.type,
                pattern = first.pattern,
                action = action,
                hits = existing?.hits ?: 0L
            )
            viewModel.saveRule(index, rule)
            dialog.dismiss()
            Toast.makeText(ctx, "分流规则已更新并热生效！", Toast.LENGTH_SHORT).show()
        }

        dialog.show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
