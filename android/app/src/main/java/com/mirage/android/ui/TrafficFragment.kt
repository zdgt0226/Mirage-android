package com.mirage.android.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.text.Editable
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.TextWatcher
import android.text.style.ForegroundColorSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.mirage.android.R
import com.mirage.android.data.model.LogLevel
import com.mirage.android.databinding.FragmentTrafficBinding
import com.mirage.android.ui.adapter.RecentRequestAdapter
import com.mirage.android.ui.viewmodel.TrafficViewModel
import kotlinx.coroutines.launch

/**
 * 流量监控与活动 Tab (Surge 级 Recent Requests 请求流瀑布 + 实时双线速率图 + 运行日志控制台)。
 */
class TrafficFragment : Fragment() {

    private var _binding: FragmentTrafficBinding? = null
    private val binding get() = _binding!!

    private val viewModel: TrafficViewModel by viewModels()
    private lateinit var recentAdapter: RecentRequestAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentTrafficBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupRequestsRecycler()
        setupSearchAndFilters()
        setupToggleMode()
        setupLogLevelChips()

        binding.btnClearLogs.setOnClickListener {
            viewModel.clearLogs()
            Toast.makeText(requireContext(), "日志已清空", Toast.LENGTH_SHORT).show()
        }

        binding.btnCopyLogs.setOnClickListener {
            if (binding.boxLogs.visibility == View.VISIBLE) {
                val logs = viewModel.filteredLogs.value.joinToString("\n")
                if (logs.isNotBlank()) {
                    val cm = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                    cm?.setPrimaryClip(ClipData.newPlainText("Mirage Logs", logs))
                    Toast.makeText(requireContext(), "已复制当前日志到剪贴板", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(requireContext(), "当前没有可复制的日志", Toast.LENGTH_SHORT).show()
                }
            } else {
                val reqs = viewModel.filteredRecentRequests.value.joinToString("\n") {
                    "${it.protocol} | ${it.target} | ${it.outbound} | ${it.matchedRule} | ${it.status} | ↑${it.upFormatted} ↓${it.downFormatted} | ${it.durationFormatted}"
                }
                if (reqs.isNotBlank()) {
                    val cm = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                    cm?.setPrimaryClip(ClipData.newPlainText("Mirage Recent Requests", reqs))
                    Toast.makeText(requireContext(), "已复制最近请求流记录到剪贴板", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(requireContext(), "当前暂无请求流记录", Toast.LENGTH_SHORT).show()
                }
            }
        }

        binding.btnExportLogs.setOnClickListener {
            viewLifecycleOwner.lifecycleScope.launch {
                Toast.makeText(requireContext(), "正在打包生成诊断日志包...", Toast.LENGTH_SHORT).show()
                runCatching {
                    com.mirage.android.core.LogExporter.shareDiagnosticZip(requireContext())
                }.onFailure { e ->
                    Toast.makeText(requireContext(), "导出失败: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }

        observeState()
    }

    private fun setupRequestsRecycler() {
        recentAdapter = RecentRequestAdapter { item ->
            RequestDetailBottomSheet(item).show(childFragmentManager, RequestDetailBottomSheet.TAG)
        }
        binding.recyclerRecentRequests.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerRecentRequests.adapter = recentAdapter
    }

    private fun setupSearchAndFilters() {
        binding.etSearchRequests.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                viewModel.setSearchQuery(s?.toString() ?: "")
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        binding.chipGroupRequestFilter.setOnCheckedStateChangeListener { _, checkedIds ->
            when (checkedIds.firstOrNull()) {
                R.id.chipFilterProxy -> {
                    viewModel.setProtocolFilter("ALL")
                    viewModel.setOutboundFilter("PROXY")
                }
                R.id.chipFilterDirect -> {
                    viewModel.setProtocolFilter("ALL")
                    viewModel.setOutboundFilter("DIRECT")
                }
                R.id.chipFilterBlock -> {
                    viewModel.setProtocolFilter("ALL")
                    viewModel.setOutboundFilter("BLOCK")
                }
                R.id.chipFilterDns -> {
                    viewModel.setProtocolFilter("DNS")
                    viewModel.setOutboundFilter("ALL")
                }
                else -> {
                    viewModel.setProtocolFilter("ALL")
                    viewModel.setOutboundFilter("ALL")
                }
            }
        }
    }

    private fun setupToggleMode() {
        fun updateModeUI(mode: Int) {
            if (mode == 0) {
                binding.btnTabRequests.setBackgroundResource(R.drawable.bg_telegram_pill_active)
                binding.btnTabRequests.setTextColor(Color.WHITE)
                binding.btnTabRequests.typeface = android.graphics.Typeface.DEFAULT_BOLD

                binding.btnTabLogs.setBackgroundColor(Color.TRANSPARENT)
                binding.btnTabLogs.setTextColor(requireContext().getColor(R.color.meow_ink_secondary))
                binding.btnTabLogs.typeface = android.graphics.Typeface.DEFAULT

                binding.boxRecentRequests.visibility = View.VISIBLE
                binding.boxLogs.visibility = View.GONE
            } else {
                binding.btnTabLogs.setBackgroundResource(R.drawable.bg_telegram_pill_active)
                binding.btnTabLogs.setTextColor(Color.WHITE)
                binding.btnTabLogs.typeface = android.graphics.Typeface.DEFAULT_BOLD

                binding.btnTabRequests.setBackgroundColor(Color.TRANSPARENT)
                binding.btnTabRequests.setTextColor(requireContext().getColor(R.color.meow_ink_secondary))
                binding.btnTabRequests.typeface = android.graphics.Typeface.DEFAULT

                binding.boxRecentRequests.visibility = View.GONE
                binding.boxLogs.visibility = View.VISIBLE
            }
        }

        binding.btnTabRequests.setOnClickListener { updateModeUI(0) }
        binding.btnTabLogs.setOnClickListener { updateModeUI(1) }
        updateModeUI(0)
    }

    private fun setupLogLevelChips() {
        binding.chipGroupLogLevel.setOnCheckedStateChangeListener { _, checkedIds ->
            val level = when (checkedIds.firstOrNull()) {
                R.id.chipLevelInfo -> LogLevel.INFO
                R.id.chipLevelWarn -> LogLevel.WARN
                R.id.chipLevelError -> LogLevel.ERROR
                R.id.chipLevelDebug -> LogLevel.DEBUG
                else -> LogLevel.ALL
            }
            viewModel.setLogLevel(level)
        }
    }

    private fun observeState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.stats.collect { stats ->
                        binding.upRate.text = stats.upRateFormatted
                        binding.downRate.text = stats.downRateFormatted
                        binding.totalUp.text = "累计上行: ${stats.upTotalFormatted}"
                        binding.totalDown.text = "累计下行: ${stats.downTotalFormatted}"
                    }
                }
                launch {
                    viewModel.historyUp.collect { upList ->
                        binding.chart.setData(upList, viewModel.historyDown.value)
                    }
                }
                launch {
                    viewModel.historyDown.collect { downList ->
                        binding.chart.setData(viewModel.historyUp.value, downList)
                    }
                }
                launch {
                    viewModel.filteredRecentRequests.collect { reqs ->
                        recentAdapter.submitList(reqs)
                        binding.btnTabRequests.text = if (reqs.isNotEmpty()) "请求流 (${reqs.size})" else "请求流"
                        binding.tvEmptyRequests.visibility = if (reqs.isEmpty()) View.VISIBLE else View.GONE
                    }
                }
                launch {
                    viewModel.filteredLogs.collect { logLines ->
                        renderColoredLogs(logLines)
                        binding.scrollLogs.post {
                            binding.scrollLogs.fullScroll(View.FOCUS_DOWN)
                        }
                    }
                }
            }
        }
    }

    private fun renderColoredLogs(logLines: List<String>) {
        if (logLines.isEmpty()) {
            binding.trafficLogView.text = "暂无匹配的日志记录"
            return
        }

        val builder = SpannableStringBuilder()
        for ((idx, line) in logLines.withIndex()) {
            val start = builder.length
            builder.append(line)
            val end = builder.length

            val upper = line.uppercase()
            val color = when {
                upper.contains("ERROR") || upper.contains("FATAL") || upper.contains("EXCEPTION") || upper.contains("FAILED") ->
                    Color.parseColor("#FF6B6B")
                upper.contains("WARN") || upper.contains("WARNING") ->
                    Color.parseColor("#FFD166")
                upper.contains("OK=TRUE") || upper.contains("已启动") || upper.contains("成功") ->
                    Color.parseColor("#06D6A0")
                upper.contains("[CORE]") || upper.contains("[ROUTER]") || upper.contains("[TUN-") ->
                    Color.parseColor("#4CC9F0")
                else ->
                    Color.parseColor("#CBD5E1")
            }

            builder.setSpan(ForegroundColorSpan(color), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            if (idx < logLines.size - 1) {
                builder.append("\n")
            }
        }
        binding.trafficLogView.text = builder
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
