package com.mirage.android.ui

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.mirage.android.R
import com.mirage.android.core.AppFilterStore
import com.mirage.android.data.model.AppFilterConfig
import com.mirage.android.data.model.AppFilterMode
import com.mirage.android.data.model.AppInfo
import com.mirage.android.data.repository.AppListRepository
import com.mirage.android.databinding.ActivityAppFilterBinding
import com.mirage.android.ui.adapter.AppFilterAdapter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AppFilterActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAppFilterBinding
    private lateinit var repository: AppListRepository
    private lateinit var adapter: AppFilterAdapter

    private var currentConfig = AppFilterConfig()
    private var allApps: List<AppInfo> = emptyList()
    private val selectedPackages = mutableSetOf<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAppFilterBinding.inflate(layoutInflater)
        setContentView(binding.root)

        repository = AppListRepository(this)
        currentConfig = AppFilterStore.getConfig(this)
        selectedPackages.addAll(currentConfig.selectedPackages)

        setupToolbar()
        setupControlViews()
        setupRecyclerView()
        loadApps()
    }

    private fun setupToolbar() {
        binding.toolbar.setNavigationOnClickListener {
            saveAndFinish()
        }
    }

    private fun setupControlViews() {
        binding.switchEnable.isChecked = currentConfig.enabled
        when (currentConfig.mode) {
            AppFilterMode.ALLOW -> binding.rbAllow.isChecked = true
            AppFilterMode.DISALLOW -> binding.rbDisallow.isChecked = true
        }

        binding.switchEnable.setOnCheckedChangeListener { _, isChecked ->
            com.mirage.android.util.Haptic.toggle(binding.root)
            currentConfig = currentConfig.copy(enabled = isChecked)
            updateSummary()
        }

        binding.layoutSwitchHeader.setOnClickListener {
            binding.switchEnable.isChecked = !binding.switchEnable.isChecked
        }

        binding.rgMode.setOnCheckedChangeListener { _, checkedId ->
            com.mirage.android.util.Haptic.toggle(binding.root)
            val mode = if (checkedId == R.id.rbAllow) AppFilterMode.ALLOW else AppFilterMode.DISALLOW
            currentConfig = currentConfig.copy(mode = mode)
            updateSummary()
        }

        binding.etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                filterList(s?.toString().orEmpty())
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        binding.btnSelectAll.setOnClickListener {
            com.mirage.android.util.Haptic.tap(it)
            val visible = getCurrentFilteredApps()
            visible.forEach { selectedPackages.add(it.packageName) }
            refreshAdapterList()
            updateSummary()
        }

        binding.btnClearAll.setOnClickListener {
            com.mirage.android.util.Haptic.tap(it)
            val visible = getCurrentFilteredApps()
            visible.forEach { selectedPackages.remove(it.packageName) }
            refreshAdapterList()
            updateSummary()
        }

        updateSummary()
    }

    private fun setupRecyclerView() {
        adapter = AppFilterAdapter(repository) { item, isChecked ->
            com.mirage.android.util.Haptic.toggle(binding.root)
            if (isChecked) {
                selectedPackages.add(item.packageName)
            } else {
                selectedPackages.remove(item.packageName)
            }
            updateSummary()
        }
        binding.rvApps.layoutManager = LinearLayoutManager(this)
        binding.rvApps.adapter = adapter
    }

    private fun loadApps() {
        binding.progressBar.visibility = View.VISIBLE
        lifecycleScope.launch {
            allApps = repository.getInstalledApps(selectedPackages)
            binding.progressBar.visibility = View.GONE
            refreshAdapterList()
            updateSummary()
        }
    }

    private fun filterList(query: String) {
        val q = query.trim().lowercase()
        val filtered = if (q.isEmpty()) {
            allApps
        } else {
            allApps.filter {
                it.name.lowercase().contains(q) || it.packageName.lowercase().contains(q)
            }
        }
        val mapped = filtered.map { it.copy(isSelected = selectedPackages.contains(it.packageName)) }
        adapter.submitList(mapped)
    }

    private fun getCurrentFilteredApps(): List<AppInfo> {
        val q = binding.etSearch.text?.toString()?.trim()?.lowercase().orEmpty()
        return if (q.isEmpty()) allApps else allApps.filter {
            it.name.lowercase().contains(q) || it.packageName.lowercase().contains(q)
        }
    }

    private fun refreshAdapterList() {
        filterList(binding.etSearch.text?.toString().orEmpty())
    }

    private fun updateSummary() {
        val count = selectedPackages.size
        val modeText = if (currentConfig.mode == AppFilterMode.ALLOW) "仅代理模式" else "绕过模式"
        val statusText = if (currentConfig.enabled) "已启用" else "未启用"
        binding.tvSummary.text = "状态: $statusText · 当前: $modeText (已选 $count 款应用)"
    }

    private fun saveAndFinish() {
        val newConfig = currentConfig.copy(selectedPackages = selectedPackages)
        AppFilterStore.saveConfig(this, newConfig)
        Toast.makeText(this, "分应用代理配置已保存", Toast.LENGTH_SHORT).show()
        finish()
    }

    override fun onBackPressed() {
        saveAndFinish()
    }
}
