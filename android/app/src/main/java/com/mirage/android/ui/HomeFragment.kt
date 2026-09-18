package com.mirage.android.ui

import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.mirage.android.MainActivity
import com.mirage.android.R
import com.mirage.android.data.model.VpnState
import com.mirage.android.databinding.FragmentHomeBinding
import com.mirage.android.ui.viewmodel.HomeViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 首页: 现代 Material 3 卡片布局 + 响应式状态流绑定。
 */
class HomeFragment : Fragment() {

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!

    private val viewModel: HomeViewModel by activityViewModels()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        updateVersionBadge()

        binding.btnSettings.setOnClickListener {
            com.mirage.android.util.Haptic.tap(it)
            SettingsActivity.start(requireContext())
        }

        binding.connectBtn.setOnClickListener {
            com.mirage.android.util.Haptic.confirm(it)
            if (viewModel.vpnState.value.isRunning) {
                viewModel.disconnect()
            } else {
                performConnect()
            }
        }

        binding.nodeSelectCard.setOnClickListener {
            com.mirage.android.util.Haptic.tap(it)
            (activity as? MainActivity)?.navigateToTab(1)
        }

        setupOutboundModeToggle()

        observeState()
    }

    private fun setupOutboundModeToggle() {
        binding.toggleOutboundMode.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                com.mirage.android.util.Haptic.tap(binding.toggleOutboundMode)
                val targetMode = when (checkedId) {
                    R.id.btnModeProxy -> 1
                    R.id.btnModeDirect -> 2
                    else -> 0
                }
                if (viewModel.outboundMode.value != targetMode) {
                    viewModel.setOutboundMode(targetMode)
                    val modeName = getString(when (targetMode) {
                        1 -> R.string.mode_global_full
                        2 -> R.string.mode_direct_full
                        else -> R.string.mode_rule_full
                    })
                    Toast.makeText(requireContext(), getString(R.string.mode_switched, modeName), Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun performConnect() {
        val selected = viewModel.selectedNode.value
        if (selected == null) {
            Toast.makeText(requireContext(), R.string.select_node_first, Toast.LENGTH_SHORT).show()
            return
        }

        (activity as? MainActivity)?.requestVpnPermissionAndConnect()
    }

    private fun observeState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    // 自愈: 仅在状态处于稳态且有偏差时纠正，不干扰 Connecting/Stopping 中间态
                    while (true) {
                        delay(3000)
                        val running = com.mirage.android.core.CoreController.isRunning()
                        if (running && viewModel.vpnState.value !is VpnState.Connected) {
                            updateVpnUi(VpnState.Connected(viewModel.selectedNode.value))
                        } else if (!running && viewModel.vpnState.value !is VpnState.Disconnected && viewModel.vpnState.value !is VpnState.Connecting && viewModel.vpnState.value !is VpnState.Stopping) {
                            updateVpnUi(VpnState.Disconnected)
                        }
                    }
                }
                launch {
                    viewModel.vpnState.collect { state ->
                        updateVpnUi(state)
                    }
                }
                launch {
                    viewModel.selectedNode.collect { node ->
                        binding.currentNode.text = if (node != null) {
                            "节点: ${node.displayName}"
                        } else {
                            "节点: (无) → 点击添加"
                        }
                    }
                }
                launch {
                    viewModel.trafficStats.collect { stats ->
                        binding.upRate.text = stats.upRateFormatted
                        binding.downRate.text = stats.downRateFormatted
                        binding.totalFlow.text =
                            "累计: ↑${stats.upTotalFormatted} / ↓${stats.downTotalFormatted}"
                        binding.connsInfo.text =
                            "连接: ${stats.tcpConns + stats.udpFlows}"
                        // 今日/本月用量 (持久化统计)
                        val today = com.mirage.android.core.TrafficStatsStore.getToday(requireContext())
                        val month = com.mirage.android.core.TrafficStatsStore.getThisMonth(requireContext())
                        binding.todayUsage.text =
                            "今日用量: ↑${fmtBytes(today.first.toDouble())} / ↓${fmtBytes(today.second.toDouble())} · 本月: ↑${fmtBytes(month.first.toDouble())} / ↓${fmtBytes(month.second.toDouble())}"
                    }
                }
                launch {
                    viewModel.historyUp.collect { upList ->
                        binding.homeChart.setData(upList, viewModel.historyDown.value)
                    }
                }
                launch {
                    viewModel.historyDown.collect { downList ->
                        binding.homeChart.setData(viewModel.historyUp.value, downList)
                    }
                }
                launch {
                    viewModel.outboundMode.collect { mode ->
                        val targetBtn = when (mode) {
                            1 -> R.id.btnModeProxy
                            2 -> R.id.btnModeDirect
                            else -> R.id.btnModeRule
                        }
                        if (binding.toggleOutboundMode.checkedButtonId != targetBtn) {
                            binding.toggleOutboundMode.check(targetBtn)
                        }
                    }
                }
                launch {
                    viewModel.latencyMs.collect { rtt ->
                        if (rtt >= 0 && viewModel.vpnState.value is VpnState.Connected) {
                            binding.tvLatency.visibility = View.VISIBLE
                            binding.tvLatency.text = getString(R.string.home_rtt, rtt)
                        } else {
                            binding.tvLatency.visibility = View.GONE
                        }
                    }
                }
            }
        }
    }

    private fun updateVpnUi(state: VpnState) {
        android.util.Log.d("Mirage", "[ui] updateVpnUi state=${state}")

        when (state) {
            is VpnState.Connected -> {
                binding.statusDot.backgroundTintList = android.content.res.ColorStateList.valueOf(ContextCompat.getColor(requireContext(), R.color.meow_connected))
                binding.statusText.text = getString(R.string.status_connected_detail)
                binding.statusText.setTextColor(ContextCompat.getColor(requireContext(), R.color.meow_connected))
                binding.connectBtn.text = getString(R.string.disconnect)
                binding.connectBtn.setBackgroundColor(ContextCompat.getColor(requireContext(), R.color.meow_error))
            }
            is VpnState.Connecting -> {
                binding.statusDot.backgroundTintList = android.content.res.ColorStateList.valueOf(ContextCompat.getColor(requireContext(), R.color.meow_ginger))
                binding.statusText.text = getString(R.string.status_establishing)
                binding.statusText.setTextColor(ContextCompat.getColor(requireContext(), R.color.meow_ginger))
                binding.connectBtn.text = getString(R.string.connecting_btn)
                binding.connectBtn.setBackgroundColor(ContextCompat.getColor(requireContext(), R.color.meow_ginger))
            }
            is VpnState.Stopping -> {
                binding.statusDot.backgroundTintList = android.content.res.ColorStateList.valueOf(ContextCompat.getColor(requireContext(), R.color.meow_disconnected))
                binding.statusText.text = getString(R.string.status_disconnecting)
                binding.statusText.setTextColor(ContextCompat.getColor(requireContext(), R.color.meow_disconnected))
                binding.connectBtn.text = getString(R.string.disconnecting_btn)
            }
            is VpnState.Error -> {
                binding.statusDot.backgroundTintList = android.content.res.ColorStateList.valueOf(ContextCompat.getColor(requireContext(), R.color.meow_error))
                binding.statusText.text = getString(R.string.status_error, state.message)
                binding.statusText.setTextColor(ContextCompat.getColor(requireContext(), R.color.meow_error))
                binding.connectBtn.text = getString(R.string.connect)
                binding.connectBtn.setBackgroundColor(ContextCompat.getColor(requireContext(), R.color.meow_blue))
            }
            is VpnState.Disconnected -> {
                binding.statusDot.backgroundTintList = android.content.res.ColorStateList.valueOf(ContextCompat.getColor(requireContext(), R.color.meow_disconnected))
                binding.statusText.text = getString(R.string.status_idle)
                binding.statusText.setTextColor(ContextCompat.getColor(requireContext(), R.color.meow_disconnected))
                binding.connectBtn.text = getString(R.string.connect)
                binding.connectBtn.setBackgroundColor(ContextCompat.getColor(requireContext(), R.color.meow_blue))
            }
        }
    }

    override fun onResume() {
        super.onResume()
        updateVersionBadge()
        viewModel.checkCurrentState()
    }

    fun updateVersionBadge() {
        val ctx = context ?: return
        val activeCore = com.mirage.android.core.CoreManager.getInstance(ctx).getActiveCore()
        val coreTag = getString(if (activeCore.isBuiltin) R.string.core_tag_builtin else R.string.core_tag_custom)
        _binding?.tvVersion?.text = getString(R.string.home_version_badge, com.mirage.android.BuildConfig.VERSION_NAME, coreTag)
        _binding?.tvAppSubtitle?.text = getString(R.string.home_build_subtitle, com.mirage.android.BuildConfig.BUILD_TIME, com.mirage.android.BuildConfig.VERSION_CODE)
    }

    private fun fmtBytes(b: Double): String = when {
        b >= 1 shl 30 -> "%.2fG".format(b / (1 shl 30))
        b >= 1 shl 20 -> "%.1fM".format(b / (1 shl 20))
        b >= 1 shl 10 -> "%.1fK".format(b / (1 shl 10))
        else -> "%.0fB".format(b)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
