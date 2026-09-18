package com.mirage.android.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.mirage.android.R
import com.mirage.android.core.ConfigBackup
import com.mirage.android.core.CoreManager
import com.mirage.android.core.MirageNative
import com.mirage.android.core.TunConfigStore
import com.mirage.android.data.repository.NodeRepository
import com.mirage.android.data.repository.RuleRepository
import com.mirage.android.data.repository.VpnRepository
import com.mirage.android.databinding.ActivitySettingsBinding

/**
 * 设置页。
 *
 * 在此之前 DNS、TUN、内核、备份/恢复都挂在首页那一个 ScrollView 里，和连接开关、
 * 速率图表混在一起。它们是配置项而非首页内容 —— 同类客户端 (SFA / Hiddify) 的首页
 * 只留「开关 + 当前出站 + 一行流量」，其余进二级页。这里就是那个二级页。
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private var coreManagerDialog: CoreManagerDialog? = null

    private val pickSoLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) coreManagerDialog?.handleImportUri(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.toolbar.setNavigationOnClickListener { finish() }

        bindRow(binding.rowDns, R.drawable.ic_dns, R.string.home_dns_card) { showDnsDialog() }
        bindRow(binding.rowTun, R.drawable.ic_nav_traffic, R.string.tun_title) { showTunDialog() }
        bindRow(binding.rowCore, R.drawable.ic_nav_home, R.string.core_mgr_title) { showCoreDialog() }
        bindRow(binding.rowVersion, R.drawable.ic_nav_home, R.string.version_dialog_title) { showVersionDialog() }
        bindRow(binding.rowBackup, R.drawable.ic_nav_rules, R.string.backup_config) { showBackupDialog() }
        bindRow(binding.rowRestore, R.drawable.ic_nav_rules, R.string.restore_config) { showRestoreDialog() }

        binding.rowVersion.tvRowSummary.setText(R.string.version_summary)
        binding.rowCore.tvRowSummary.setText(R.string.core_mgr_summary)
        binding.rowBackup.tvRowSummary.setText(R.string.backup_summary)
        binding.rowRestore.tvRowSummary.setText(R.string.restore_summary)
    }

    override fun onResume() {
        super.onResume()
        refreshSummaries()
    }

    private fun bindRow(
        row: com.mirage.android.databinding.ItemSettingsRowBinding,
        iconRes: Int,
        titleRes: Int,
        onClick: () -> Unit
    ) {
        row.ivRowIcon.setImageResource(iconRes)
        row.tvRowTitle.setText(titleRes)
        row.root.setOnClickListener {
            com.mirage.android.util.Haptic.tap(it)
            onClick()
        }
    }

    /** DNS 与 TUN 两行的副标题要反映当前值, 否则得点进去才知道设的是什么。 */
    private fun refreshSummaries() {
        val dnsRepo = com.mirage.android.data.repository.DnsRepository.getInstance(this)
        binding.rowDns.tvRowSummary.text =
            getString(R.string.home_dns_summary, dnsRepo.getDirectDns(), dnsRepo.getRemoteDns())
        binding.rowTun.tvRowSummary.text = tunSummary()
    }

    private fun tunSummary(): String {
        val mtu = TunConfigStore.getMtu(this)
        val batch = TunConfigStore.getBatchSize(this)
        val vpnRepo = VpnRepository.getInstance(this)
        val ipv6 = getString(if (vpnRepo.isIpv6Enabled.value) R.string.tun_sum_ipv6_on else R.string.tun_sum_ipv6_off)
        val quic = getString(if (vpnRepo.isBlockQuic.value) R.string.tun_sum_quic_block else R.string.tun_sum_quic_pass)
        val mux = getString(if (vpnRepo.isUdpMux.value) R.string.tun_sum_mux_on else R.string.tun_sum_mux_off)
        return getString(R.string.home_tun_summary, mtu.toString(), batch, ipv6, quic, mux)
    }

    private fun showDnsDialog() = DnsConfigDialog(this) { refreshSummaries() }.show()

    private fun showTunDialog() = TunConfigDialog(this) { refreshSummaries() }.show()

    private fun showCoreDialog() {
        coreManagerDialog = CoreManagerDialog(
            context = this,
            onPickSoFile = { pickSoLauncher.launch("*/*") },
            onCoreChanged = { }
        )
        coreManagerDialog?.show()
    }

    private fun showVersionDialog() {
        val activeCore = CoreManager.getInstance(this).getActiveCore()
        val coreDesc = if (activeCore.isBuiltin) {
            getString(R.string.core_builtin)
        } else {
            getString(R.string.core_custom, activeCore.name)
        }
        val nativeVer = runCatching { MirageNative.version() }.getOrDefault("")
        val info = getString(
            R.string.version_info,
            com.mirage.android.BuildConfig.VERSION_NAME,
            com.mirage.android.BuildConfig.VERSION_CODE,
            com.mirage.android.BuildConfig.BUILD_TAG,
            com.mirage.android.BuildConfig.BUILD_TIME,
            coreDesc,
            nativeVer
        )
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.version_dialog_title)
            .setMessage(info)
            .setPositiveButton(R.string.manage_core) { _, _ -> showCoreDialog() }
            .setNegativeButton(R.string.close, null)
            .show()
    }

    private fun showBackupDialog() {
        val json = ConfigBackup.export(this)
        val input = android.widget.EditText(this).apply {
            setText(json)
            setTextSize(12f)
            isSingleLine = false
            minLines = 8
        }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.backup_title)
            .setView(input)
            .setPositiveButton(R.string.copy) { _, _ ->
                val cm = getSystemService(android.content.ClipboardManager::class.java)
                cm.setPrimaryClip(android.content.ClipData.newPlainText("mirage-config", json))
                Toast.makeText(this, R.string.backup_copied, Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(R.string.close, null)
            .show()
    }

    private fun showRestoreDialog() {
        val input = android.widget.EditText(this).apply {
            hint = getString(R.string.restore_hint)
            setTextSize(12f)
            isSingleLine = false
            minLines = 8
        }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.restore_title)
            .setView(input)
            .setPositiveButton(R.string.restore_merge) { _, _ -> doRestore(input, overwrite = false) }
            .setNeutralButton(R.string.restore_overwrite) { _, _ -> doRestore(input, overwrite = true) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun doRestore(input: android.widget.EditText, overwrite: Boolean) {
        val json = input.text.toString().trim()
        if (json.isEmpty()) {
            Toast.makeText(this, R.string.content_empty, Toast.LENGTH_SHORT).show()
            return
        }
        runCatching {
            val (nodes, rules) = ConfigBackup.import(this, json, overwrite = overwrite)
            NodeRepository.getInstance(this).reload()
            RuleRepository.getInstance(this).reload()
            val msg = if (overwrite) R.string.restore_overwritten else R.string.restore_merged
            Toast.makeText(this, getString(msg, nodes, rules), Toast.LENGTH_LONG).show()
        }.onFailure { e ->
            val msg = if (overwrite) R.string.restore_overwrite_failed else R.string.restore_failed
            Toast.makeText(this, getString(msg, e.message ?: ""), Toast.LENGTH_LONG).show()
        }
    }

    companion object {
        fun start(ctx: Context) = ctx.startActivity(Intent(ctx, SettingsActivity::class.java))
    }
}
