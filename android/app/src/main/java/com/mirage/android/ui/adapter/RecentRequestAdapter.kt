package com.mirage.android.ui.adapter

import android.graphics.Color
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.mirage.android.data.model.RecentRequestInfo
import com.mirage.android.databinding.ItemRecentRequestBinding

class RecentRequestAdapter(
    private val onItemClick: (RecentRequestInfo) -> Unit
) : ListAdapter<RecentRequestInfo, RecentRequestAdapter.ViewHolder>(DiffCallback) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemRecentRequestBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ViewHolder(private val binding: ItemRecentRequestBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(item: RecentRequestInfo) {
            binding.root.setOnClickListener { onItemClick(item) }

            binding.tvProtocolBadge.text = item.protocol
            when (item.protocol.uppercase()) {
                "TCP" -> {
                    binding.tvProtocolBadge.setTextColor(Color.parseColor("#007AFF"))
                }
                "UDP" -> {
                    binding.tvProtocolBadge.setTextColor(Color.parseColor("#34C759"))
                }
                "DNS" -> {
                    binding.tvProtocolBadge.setTextColor(Color.parseColor("#FF9500"))
                }
                else -> {
                    binding.tvProtocolBadge.setTextColor(Color.parseColor("#8E8E93"))
                }
            }

            binding.tvTarget.text = item.target
            binding.tvRuleInfo.text = if (item.resolvedIp.isNotBlank()) {
                "${item.matchedRule} · ${item.resolvedIp}"
            } else {
                item.matchedRule
            }

            binding.tvOutboundBadge.text = item.outbound
            when (item.outbound.uppercase()) {
                "PROXY", "隧道代理" -> {
                    binding.tvOutboundBadge.setTextColor(Color.parseColor("#007AFF"))
                }
                "DIRECT", "直连" -> {
                    binding.tvOutboundBadge.setTextColor(Color.parseColor("#FF9500"))
                }
                "BLOCK", "拦截" -> {
                    binding.tvOutboundBadge.setTextColor(Color.parseColor("#FF3B30"))
                }
                else -> {
                    binding.tvOutboundBadge.setTextColor(Color.parseColor("#34C759"))
                }
            }

            binding.tvDuration.text = item.durationFormatted
            binding.tvTrafficBytes.text = "↑${item.upFormatted} ↓${item.downFormatted}"

            // 状态小圆点
            if (item.status.startsWith("Active", ignoreCase = true) || item.status.contains("已连接")) {
                binding.tvStatusDot.setBackgroundColor(Color.parseColor("#34C759"))
            } else if (item.status.contains("Block", ignoreCase = true) || item.status.contains("Timeout", ignoreCase = true) || item.status.contains("Fail", ignoreCase = true)) {
                binding.tvStatusDot.setBackgroundColor(Color.parseColor("#FF3B30"))
            } else {
                binding.tvStatusDot.setBackgroundColor(Color.parseColor("#8E8E93"))
            }
        }
    }

    companion object DiffCallback : DiffUtil.ItemCallback<RecentRequestInfo>() {
        override fun areItemsTheSame(oldItem: RecentRequestInfo, newItem: RecentRequestInfo): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: RecentRequestInfo, newItem: RecentRequestInfo): Boolean {
            return oldItem == newItem
        }
    }
}
