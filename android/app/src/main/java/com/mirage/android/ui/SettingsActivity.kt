package com.mirage.android.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.mirage.android.R
import com.mirage.android.databinding.ActivitySettingsBinding
import com.mirage.android.ui.common.FluidSpring


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

    private val pickSoLauncher: androidx.activity.result.ActivityResultLauncher<String> =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
            if (uri != null) {
                val dialog = coreManagerDialog ?: CoreManagerDialog(
                    context = this,
                    onPickSoFile = { launchSoPicker() },
                    onCoreChanged = { refreshSummaries() }
                ).also { coreManagerDialog = it }
                dialog.handleImportUri(uri)
            }
        }

    private fun launchSoPicker() {
        pickSoLauncher.launch("*/*")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }

        val initialToolbarPaddingLeft = binding.toolbar.paddingLeft
        val initialToolbarPaddingTop = binding.toolbar.paddingTop
        val initialToolbarPaddingRight = binding.toolbar.paddingRight
        val initialToolbarPaddingBottom = binding.toolbar.paddingBottom

        val initialScrollPaddingLeft = binding.scrollView.paddingLeft
        val initialScrollPaddingTop = binding.scrollView.paddingTop
        val initialScrollPaddingRight = binding.scrollView.paddingRight
        val initialScrollPaddingBottom = binding.scrollView.paddingBottom

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val topInsets = insets.getInsets(
                WindowInsetsCompat.Type.statusBars() or WindowInsetsCompat.Type.displayCutout()
            )
            val bottomInsets = insets.getInsets(
                WindowInsetsCompat.Type.navigationBars() or WindowInsetsCompat.Type.displayCutout()
            )
            val imeInsets = insets.getInsets(WindowInsetsCompat.Type.ime())
            val sideInsets = insets.getInsets(
                WindowInsetsCompat.Type.displayCutout() or WindowInsetsCompat.Type.navigationBars()
            )

            val bottomPadding = maxOf(bottomInsets.bottom, imeInsets.bottom)

            binding.toolbar.setPadding(
                initialToolbarPaddingLeft + sideInsets.left,
                initialToolbarPaddingTop + topInsets.top,
                initialToolbarPaddingRight + sideInsets.right,
                initialToolbarPaddingBottom
            )
            binding.scrollView.setPadding(
                initialScrollPaddingLeft + sideInsets.left,
                initialScrollPaddingTop,
                initialScrollPaddingRight + sideInsets.right,
                initialScrollPaddingBottom + bottomPadding
            )
            WindowInsetsCompat.CONSUMED
        }

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
        FluidSpring.attachPressScale(row.root)
        row.root.setOnClickListener {
            com.mirage.android.util.Haptic.tap(it)
            onClick()
        }
    }

    /** DNS 与 TUN 两行的副标题要反映当前值, 否则得点进去才知道设的是什么。 */
    private fun refreshSummaries() {
        binding.rowDns.tvRowSummary.text = SettingsActions.dnsSummary(this)
        binding.rowTun.tvRowSummary.text = SettingsActions.tunSummary(this)
    }

    private fun showDnsDialog() = SettingsActions.showDns(this) { refreshSummaries() }

    private fun showTunDialog() = SettingsActions.showTun(this) { refreshSummaries() }

    private fun showCoreDialog() {
        coreManagerDialog = SettingsActions.showCore(
            activity = this,
            onPickSo = { pickSoLauncher.launch("*/*") },
            onCoreChanged = { refreshSummaries() }
        )
    }

    private fun showVersionDialog() = SettingsActions.showVersion(this) { showCoreDialog() }

    private fun showBackupDialog() = SettingsActions.showBackup(this)

    private fun showRestoreDialog() = SettingsActions.showRestore(this) { refreshSummaries() }

    companion object {
        fun start(ctx: Context) = ctx.startActivity(Intent(ctx, SettingsActivity::class.java))
    }
}
