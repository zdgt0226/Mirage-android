package com.mirage.android.ui.adapter

import android.annotation.SuppressLint
import android.graphics.Color
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.PopupMenu
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.mirage.android.data.model.Rule
import com.mirage.android.databinding.ItemRuleBinding

class RuleAdapter(
    private val onEdit: (Int, Rule) -> Unit,
    private val onDelete: (Int, Rule) -> Unit,
    private val onToggleEnabled: (Int, Rule, Boolean) -> Unit,
    private val onMove: (Int, Int) -> Unit,
    private val onStartDrag: (RecyclerView.ViewHolder) -> Unit
) : ListAdapter<Rule, RuleAdapter.RuleViewHolder>(RuleDiffCallback) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RuleViewHolder {
        val binding = ItemRuleBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return RuleViewHolder(binding)
    }

    override fun onBindViewHolder(holder: RuleViewHolder, position: Int) {
        holder.bind(getItem(position), position)
    }

    inner class RuleViewHolder(val binding: ItemRuleBinding) :
        RecyclerView.ViewHolder(binding.root) {

        @SuppressLint("ClickableViewAccessibility")
        fun bind(rule: Rule, position: Int) {
            binding.tvPriorityIndex.text = "#${position + 1}"
            binding.tvPattern.text = rule.displayName
            binding.tvKind.text = rule.summaryText

            if (rule.isComposite) {
                binding.tvLogicBadge.visibility = View.VISIBLE
                binding.tvLogicBadge.text = "${rule.logic.uppercase()} 复合"
            } else {
                binding.tvLogicBadge.visibility = View.GONE
            }

            binding.tvAction.text = rule.actionDisplayName
            if (rule.hits > 0) {
                binding.tvHits.visibility = View.VISIBLE
                binding.tvHits.text = "${rule.hits}次"
            } else {
                binding.tvHits.visibility = View.GONE
            }

            when {
                rule.isDirect -> binding.tvAction.setTextColor(Color.parseColor("#10B981")) // 绿
                rule.isBlock -> binding.tvAction.setTextColor(Color.parseColor("#EF4444")) // 红
                else -> binding.tvAction.setTextColor(Color.parseColor("#0077CC")) // 蓝
            }

            // 启闭 Switch 状态与卡片透明度
            binding.switchEnabled.setOnCheckedChangeListener(null)
            binding.switchEnabled.isChecked = rule.enabled
            binding.cardRule.alpha = if (rule.enabled) 1.0f else 0.5f

            binding.switchEnabled.setOnCheckedChangeListener { _, isChecked ->
                val pos = bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) {
                    binding.cardRule.alpha = if (isChecked) 1.0f else 0.5f
                    onToggleEnabled(pos, rule, isChecked)
                }
            }

            // 左侧专属大触控区域：触摸直接触发平滑拖拽
            binding.layoutDragGrip.setOnTouchListener { _, event ->
                if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                    onStartDrag(this)
                }
                false
            }

            binding.root.setOnClickListener {
                val pos = bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) {
                    onEdit(pos, rule)
                }
            }

            // 长按卡片也可直接发起拖动
            binding.root.setOnLongClickListener {
                onStartDrag(this)
                true
            }

            binding.btnMore.setOnClickListener { v ->
                val pos = bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) {
                    showPopupMenu(v, pos, rule)
                }
            }
        }

        private fun showPopupMenu(anchor: View, position: Int, rule: Rule) {
            val ctx = anchor.context
            val popup = PopupMenu(ctx, anchor)
            val total = itemCount

            if (position > 0) {
                popup.menu.add(0, 10, 0, "📌 置顶 (移至 #1)")
                popup.menu.add(0, 11, 1, "⬆️ 上移一位")
            }
            if (position < total - 1) {
                popup.menu.add(0, 12, 2, "⬇️ 下移一位")
                popup.menu.add(0, 13, 3, "🔻 置底 (移至最后)")
            }
            popup.menu.add(0, 1, 4, "✏️ 编辑规则")
            popup.menu.add(0, 2, 5, if (rule.enabled) "⏸️ 禁用规则" else "▶️ 启用规则")
            popup.menu.add(0, 3, 6, "🗑️ 删除规则")

            popup.setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    10 -> onMove(position, 0)
                    11 -> onMove(position, position - 1)
                    12 -> onMove(position, position + 1)
                    13 -> onMove(position, total - 1)
                    1 -> onEdit(position, rule)
                    2 -> {
                        val newEnabled = !rule.enabled
                        binding.switchEnabled.isChecked = newEnabled
                        onToggleEnabled(position, rule, newEnabled)
                    }
                    3 -> onDelete(position, rule)
                }
                true
            }
            popup.show()
        }
    }

    object RuleDiffCallback : DiffUtil.ItemCallback<Rule>() {
        override fun areItemsTheSame(oldItem: Rule, newItem: Rule): Boolean =
            oldItem.id == newItem.id

        override fun areContentsTheSame(oldItem: Rule, newItem: Rule): Boolean =
            oldItem == newItem
    }
}
