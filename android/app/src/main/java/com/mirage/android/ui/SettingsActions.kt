package com.mirage.android.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.mirage.android.R
import com.mirage.android.core.ConfigBackup
import com.mirage.android.core.CoreManager
import com.mirage.android.core.MirageNative
import com.mirage.android.core.TunConfigStore
import com.mirage.android.data.repository.DnsRepository
import com.mirage.android.data.repository.NodeRepository
import com.mirage.android.data.repository.RuleRepository
import com.mirage.android.data.repository.VpnRepository
import com.mirage.android.ui.theme.MirageTheme

/**
 * 设置项的弹层拉起与摘要生成。
 *
 * **这是过渡件。** 从 [SettingsActivity] 原地抽出，供手势外壳的
 * `ui.shell.SettingsPage` 与旧 [SettingsActivity] 共用 —— 不抽的话备份/还原/版本
 * 这三套弹层会被写两遍，而其中版本弹层带一串 `setViewTree*Owner` 的桥接，
 * 抄错一行就是 Compose 内容拿不到 lifecycle 而崩在 `BottomSheetDialog` 里。
 * `SettingsActivity` 被外壳的设置页完全取代（下沉方案 S4）时，本文件随之处置。
 *
 * 入参收 [AppCompatActivity] 而非 `Context`：版本弹层要把三个 ViewTree owner
 * 挂到 dialog 的 decorView 上，而那三个 owner 只有 Activity 有。
 */
internal object SettingsActions {

    fun dnsSummary(context: Context): String {
        val dnsRepo = DnsRepository.getInstance(context)
        return context.getString(R.string.home_dns_summary, dnsRepo.getDirectDns(), dnsRepo.getRemoteDns())
    }

    fun tunSummary(context: Context): String {
        val mtu = TunConfigStore.getMtu(context)
        val batch = TunConfigStore.getBatchSize(context)
        val vpnRepo = VpnRepository.getInstance(context)
        val ipv6 = context.getString(if (vpnRepo.isIpv6Enabled.value) R.string.tun_sum_ipv6_on else R.string.tun_sum_ipv6_off)
        val quic = context.getString(if (vpnRepo.isBlockQuic.value) R.string.tun_sum_quic_block else R.string.tun_sum_quic_pass)
        val mux = context.getString(if (vpnRepo.isUdpMux.value) R.string.tun_sum_mux_on else R.string.tun_sum_mux_off)
        return context.getString(R.string.home_tun_summary, mtu.toString(), batch, ipv6, quic, mux)
    }

    fun showDns(activity: AppCompatActivity, onChanged: () -> Unit) {
        DnsConfigDialog(activity) { onChanged() }.show()
    }

    fun showTun(activity: AppCompatActivity, onChanged: () -> Unit) {
        TunConfigDialog(activity) { onChanged() }.show()
    }

    fun showCore(
        activity: AppCompatActivity,
        onPickSo: () -> Unit,
        onCoreChanged: () -> Unit = {},
    ): CoreManagerDialog {
        val dialog = CoreManagerDialog(
            context = activity,
            onPickSoFile = onPickSo,
            onCoreChanged = onCoreChanged
        )
        dialog.show()
        return dialog
    }

    fun showVersion(activity: AppCompatActivity, onManageCore: () -> Unit) {
        val activeCore = CoreManager.getInstance(activity).getActiveCore()
        val nativeVer = runCatching { MirageNative.version() }.getOrDefault("")

        val dialog = BottomSheetDialog(activity)
        val composeView = ComposeView(activity).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindowOrReleasedFromPool)
            setContent {
                MirageTheme {
                    VersionSheetContent(
                        versionName = com.mirage.android.BuildConfig.VERSION_NAME,
                        versionCode = com.mirage.android.BuildConfig.VERSION_CODE,
                        buildTag = com.mirage.android.BuildConfig.BUILD_TAG,
                        buildTime = com.mirage.android.BuildConfig.BUILD_TIME,
                        isBuiltinCore = activeCore.isBuiltin,
                        customCoreName = activeCore.name,
                        nativeVer = nativeVer,
                        onManageCore = {
                            dialog.dismiss()
                            onManageCore()
                        },
                        onDismiss = {
                            dialog.dismiss()
                        }
                    )
                }
            }
        }

        dialog.setContentView(composeView)
        dialog.dismissWithAnimation = true
        dialog.behavior.apply {
            isFitToContents = true
            skipCollapsed = true
            state = BottomSheetBehavior.STATE_EXPANDED
        }

        val decorView = dialog.window?.decorView ?: composeView
        decorView.setViewTreeLifecycleOwner(activity)
        decorView.setViewTreeViewModelStoreOwner(activity)
        decorView.setViewTreeSavedStateRegistryOwner(activity)

        composeView.setViewTreeLifecycleOwner(activity)
        composeView.setViewTreeViewModelStoreOwner(activity)
        composeView.setViewTreeSavedStateRegistryOwner(activity)

        dialog.show()
    }

    fun showBackup(activity: AppCompatActivity) {
        val json = ConfigBackup.export(activity)
        val input = EditText(activity).apply {
            setText(json)
            setTextSize(12f)
            isSingleLine = false
            minLines = 8
        }
        AlertDialog.Builder(activity)
            .setTitle(R.string.backup_title)
            .setView(input)
            .setPositiveButton(R.string.copy) { _, _ ->
                val cm = activity.getSystemService(ClipboardManager::class.java)
                cm.setPrimaryClip(ClipData.newPlainText("mirage-config", json))
                Toast.makeText(activity, R.string.backup_copied, Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(R.string.close, null)
            .show()
    }

    fun showRestore(activity: AppCompatActivity, onRestored: () -> Unit = {}) {
        val input = EditText(activity).apply {
            hint = activity.getString(R.string.restore_hint)
            setTextSize(12f)
            isSingleLine = false
            minLines = 8
        }
        AlertDialog.Builder(activity)
            .setTitle(R.string.restore_title)
            .setView(input)
            .setPositiveButton(R.string.restore_merge) { _, _ ->
                doRestore(activity, input, overwrite = false, onRestored = onRestored)
            }
            .setNeutralButton(R.string.restore_overwrite) { _, _ ->
                doRestore(activity, input, overwrite = true, onRestored = onRestored)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun doRestore(
        activity: AppCompatActivity,
        input: EditText,
        overwrite: Boolean,
        onRestored: () -> Unit,
    ) {
        val json = input.text.toString().trim()
        if (json.isEmpty()) {
            Toast.makeText(activity, R.string.content_empty, Toast.LENGTH_SHORT).show()
            return
        }
        runCatching {
            val (nodes, rules) = ConfigBackup.import(activity, json, overwrite = overwrite)
            NodeRepository.getInstance(activity).reload()
            RuleRepository.getInstance(activity).reload()
            onRestored()
            val msg = if (overwrite) R.string.restore_overwritten else R.string.restore_merged
            Toast.makeText(activity, activity.getString(msg, nodes, rules), Toast.LENGTH_LONG).show()
        }.onFailure { e ->
            val msg = if (overwrite) R.string.restore_overwrite_failed else R.string.restore_failed
            Toast.makeText(activity, activity.getString(msg, e.message ?: ""), Toast.LENGTH_LONG).show()
        }
    }
}
