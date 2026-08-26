package com.mirage.android.ui.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.mirage.android.data.model.AppInfo
import com.mirage.android.data.repository.AppListRepository
import com.mirage.android.databinding.ItemAppFilterBinding

class AppFilterAdapter(
    private val repository: AppListRepository,
    private val onItemClick: (AppInfo, Boolean) -> Unit
) : ListAdapter<AppInfo, AppFilterAdapter.AppViewHolder>(AppDiffCallback) {

    inner class AppViewHolder(private val binding: ItemAppFilterBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: AppInfo) {
            binding.tvAppName.text = item.name
            binding.tvPackageName.text = item.packageName
            binding.tvSystemTag.visibility = if (item.isSystemApp) View.VISIBLE else View.GONE
            binding.cbSelect.isChecked = item.isSelected
            binding.ivAppIcon.setImageDrawable(repository.getAppIcon(item.packageName))

            binding.root.setOnClickListener {
                val newChecked = !binding.cbSelect.isChecked
                binding.cbSelect.isChecked = newChecked
                onItemClick(item, newChecked)
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AppViewHolder {
        val binding = ItemAppFilterBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return AppViewHolder(binding)
    }

    override fun onBindViewHolder(holder: AppViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    object AppDiffCallback : DiffUtil.ItemCallback<AppInfo>() {
        override fun areItemsTheSame(oldItem: AppInfo, newItem: AppInfo): Boolean =
            oldItem.packageName == newItem.packageName

        override fun areContentsTheSame(oldItem: AppInfo, newItem: AppInfo): Boolean =
            oldItem == newItem
    }
}
