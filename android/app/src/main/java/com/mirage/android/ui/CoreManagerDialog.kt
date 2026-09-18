package com.mirage.android.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.view.LayoutInflater
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.mirage.android.R
import com.mirage.android.core.CoreManager
import com.mirage.android.core.CoreSource
import com.mirage.android.core.NativeLoader
import com.mirage.android.data.model.CoreInfo
import com.mirage.android.databinding.DialogCoreManagerBinding
import com.mirage.android.ui.adapter.CoreAdapter
import kotlinx.coroutines.launch

/**
 * 内核管理对话框控制器。
 */
class CoreManagerDialog(
    private val context: Context,
    private val onPickSoFile: () -> Unit,
    private val onCoreChanged: () -> Unit
) {

    private val coreManager = CoreManager.getInstance(context)
    private var alertDialog: AlertDialog? = null
    private var binding: DialogCoreManagerBinding? = null
    private var adapter: CoreAdapter? = null

    fun show() {
        binding = DialogCoreManagerBinding.inflate(LayoutInflater.from(context))
        val b = binding!!

        b.tvDeviceAbi.text = context.getString(R.string.core_device_abi, Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown")
        updateCurrentCoreView()

        adapter = CoreAdapter(
            onSelect = { core ->
                coreManager.setActiveCore(core.id)
                adapter?.setActiveId(core.id)
                updateCurrentCoreView()
                onCoreChanged()
                Toast.makeText(context, context.getString(R.string.core_switched, core.name), Toast.LENGTH_SHORT).show()
            },
            onDelete = { core ->
                AlertDialog.Builder(context)
                    .setTitle(R.string.core_delete_title)
                    .setMessage(context.getString(R.string.core_delete_message, core.name))
                    .setPositiveButton(R.string.delete) { _, _ ->
                        coreManager.deleteCore(core.id)
                        refreshList()
                        updateCurrentCoreView()
                        onCoreChanged()
                    }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
            }
        )

        b.recyclerCores.layoutManager = LinearLayoutManager(context)
        b.recyclerCores.adapter = adapter
        refreshList()

        updateSourceView()
        b.layoutSelectSource.setOnClickListener {
            showSourceSelectionDialog()
        }

        b.btnCheckOnlineCore.setOnClickListener {
            checkOnlineReleases()
        }

        b.btnImportCore.setOnClickListener {
            onPickSoFile()
        }

        b.btnResetBuiltin.setOnClickListener {
            coreManager.resetToBuiltin()
            adapter?.setActiveId(CoreInfo.BUILTIN_ID)
            updateCurrentCoreView()
            onCoreChanged()
            Toast.makeText(context, R.string.core_restored_builtin, Toast.LENGTH_SHORT).show()
        }

        alertDialog = AlertDialog.Builder(context)
            .setView(b.root)
            .setPositiveButton(R.string.done, null)
            .create()

        alertDialog?.show()
    }

    private fun updateSourceView() {
        val b = binding ?: return
        val currentSource = coreManager.getActiveSource()
        b.tvCurrentSource.text = "${currentSource.name} ▾"
    }

    private fun showSourceSelectionDialog() {
        val sources = coreManager.getSources()
        val names = sources.map { it.name }.toTypedArray()
        val active = coreManager.getActiveSource()
        var selectedIdx = sources.indexOfFirst { it.id == active.id }.coerceAtLeast(0)

        AlertDialog.Builder(context)
            .setTitle(R.string.core_source_title)
            .setSingleChoiceItems(names, selectedIdx) { _, which ->
                selectedIdx = which
            }
            .setPositiveButton(R.string.ok) { _, _ ->
                val chosen = sources[selectedIdx]
                coreManager.setActiveSource(chosen.id)
                updateSourceView()
                Toast.makeText(context, context.getString(R.string.geo_source_switched, chosen.name), Toast.LENGTH_SHORT).show()
            }
            .setNeutralButton(R.string.geo_source_add_custom) { _, _ ->
                showAddCustomSourceDialog()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showAddCustomSourceDialog() {
        val etName = android.widget.EditText(context).apply { hint = context.getString(R.string.core_source_name_hint) }
        val etApiUrl = android.widget.EditText(context).apply {
            hint = "GitHub Releases API URL"
            setText("https://api.github.com/repos/zdgt0226/Mirage-rs/releases")
        }
        val etDownloadPrefix = android.widget.EditText(context).apply {
            hint = context.getString(R.string.core_source_prefix_hint)
        }

        val layout = android.widget.LinearLayout(context).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(50, 20, 50, 20)
            addView(etName)
            addView(etApiUrl)
            addView(etDownloadPrefix)
        }

        AlertDialog.Builder(context)
            .setTitle(R.string.core_source_add_title)
            .setView(layout)
            .setPositiveButton(R.string.geo_source_save_use) { _, _ ->
                val name = etName.text.toString().trim()
                val apiUrl = etApiUrl.text.toString().trim()
                val prefix = etDownloadPrefix.text.toString().trim().takeIf { it.isNotBlank() }
                if (name.isNotEmpty() && apiUrl.isNotEmpty()) {
                    val id = "custom_" + System.currentTimeMillis()
                    val newSource = CoreSource(id, name, apiUrl, prefix, false)
                    val list = coreManager.getSources().toMutableList()
                    list.add(newSource)
                    coreManager.saveCustomSources(list)
                    coreManager.setActiveSource(id)
                    updateSourceView()
                    Toast.makeText(context, context.getString(R.string.geo_source_added, name), Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun checkOnlineReleases() {
        val b = binding ?: return
        val scope = (context as? androidx.lifecycle.LifecycleOwner)?.lifecycleScope 
            ?: kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main)

        b.btnCheckOnlineCore.isEnabled = false
        b.btnCheckOnlineCore.text = context.getString(R.string.core_querying_github)

        scope.launch {
            val result = coreManager.fetchOnlineReleases()

            result.onSuccess { releases ->
                b.btnCheckOnlineCore.isEnabled = true
                b.btnCheckOnlineCore.text = context.getString(R.string.core_mgr_check_github)

                if (releases.isEmpty()) {
                    Toast.makeText(context, context.getString(R.string.core_no_compatible, Build.SUPPORTED_ABIS.firstOrNull() ?: ""), Toast.LENGTH_LONG).show()
                } else {
                    val latest = releases.first()
                    val activeCore = coreManager.getActiveCore()
                    val existing = coreManager.cores.value.firstOrNull { core ->
                        (!core.isBuiltin) && (
                            (!latest.expectedSha256.isNullOrBlank() && core.sha256.equals(latest.expectedSha256, ignoreCase = true)) ||
                            core.name.equals("Mirage-rs ${latest.tagName}", ignoreCase = true)
                        ) && core.file?.exists() == true
                    }

                    if (existing != null) {
                        if (activeCore.id == existing.id) {
                            Toast.makeText(
                                context,
                                context.getString(R.string.core_already_latest, latest.tagName),
                                Toast.LENGTH_SHORT
                            ).show()
                        } else {
                            coreManager.setActiveCore(existing.id)
                            refreshList()
                            updateCurrentCoreView()
                            onCoreChanged()
                            Toast.makeText(
                                context,
                                context.getString(R.string.core_found_local, latest.tagName),
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    } else {
                        // 本地尚未下载该版本，启动下载并校验
                        startDownloadRelease(latest)
                    }
                }
            }.onFailure { e ->
                b.btnCheckOnlineCore.isEnabled = true
                b.btnCheckOnlineCore.text = context.getString(R.string.core_mgr_check_github)
                Toast.makeText(context, context.getString(R.string.core_query_failed, e.message ?: ""), Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun startDownloadRelease(release: com.mirage.android.data.model.OnlineReleaseInfo) {
        val b = binding ?: return
        val scope = (context as? androidx.lifecycle.LifecycleOwner)?.lifecycleScope 
            ?: kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main)

        val digestHint = if (release.shortDigest != null) " [SHA-256: ${release.shortDigest}]" else ""
        b.layoutDownloadProgress.visibility = android.view.View.VISIBLE
        b.progressBarDownload.progress = 0
        b.tvDownloadStatus.text = context.getString(R.string.core_downloading_fmt, release.tagName, release.formattedSize, digestHint)
        b.tvDownloadPercent.text = "0%"
        b.btnCheckOnlineCore.isEnabled = false
        b.btnCheckOnlineCore.text = context.getString(R.string.core_downloading_btn, release.tagName)

        android.util.Log.i("Mirage", "[loader] 开始下载在线内核: ${release.tagName} from ${release.downloadUrl} (期望SHA256: ${release.expectedSha256 ?: "未指定"})")
        scope.launch {
            val result = coreManager.downloadAndImportRelease(release) { percent ->
                b.root.post {
                    b.progressBarDownload.progress = percent
                    b.tvDownloadPercent.text = "$percent%"
                }
            }

            b.layoutDownloadProgress.visibility = android.view.View.GONE
            b.btnCheckOnlineCore.isEnabled = true
            b.btnCheckOnlineCore.text = context.getString(R.string.core_mgr_check_github)

            result.onSuccess { core ->
                android.util.Log.i("Mirage", "[loader] 在线内核下载并校验通过: ${core.name} (SHA: ${core.sha256})")
                coreManager.setActiveCore(core.id)
                refreshList()
                updateCurrentCoreView()
                onCoreChanged()
                val shaInfo = core.shortSha256?.let { " (SHA: $it)" } ?: ""
                Toast.makeText(context, context.getString(R.string.core_download_ok, core.name, shaInfo), Toast.LENGTH_LONG).show()
            }.onFailure { e ->
                android.util.Log.e("Mirage", "[loader] 在线内核下载或校验失败: ${e.message}", e)
                Toast.makeText(context, context.getString(R.string.core_download_failed, e.message ?: ""), Toast.LENGTH_LONG).show()
            }
        }
    }

    fun handleImportUri(uri: Uri) {
        try {
            val fileName = uri.lastPathSegment ?: "libmirage_custom.so"
            val displayName = fileName.substringAfterLast("/").removeSuffix(".so")

            val result = context.contentResolver.openInputStream(uri)?.use { stream ->
                coreManager.importCore(stream, displayName)
            } ?: throw IllegalArgumentException(context.getString(R.string.core_cannot_open_file))
            if (result.isSuccess) {
                val core = result.getOrThrow()
                coreManager.setActiveCore(core.id)
                refreshList()
                updateCurrentCoreView()
                onCoreChanged()
                Toast.makeText(context, context.getString(R.string.core_import_ok, core.name), Toast.LENGTH_LONG).show()
            } else {
                val msg = result.exceptionOrNull()?.message ?: context.getString(R.string.core_import_failed_default)
                AlertDialog.Builder(context)
                    .setTitle(R.string.core_import_failed_title)
                    .setMessage(msg)
                    .setPositiveButton(R.string.ok, null)
                    .show()
            }
        } catch (e: Exception) {
            AlertDialog.Builder(context)
                .setTitle(R.string.core_import_error_title)
                .setMessage(e.message)
                .setPositiveButton(R.string.ok, null)
                .show()
        }
    }

    private fun refreshList() {
        val list = coreManager.cores.value
        adapter?.submitList(list)
        adapter?.setActiveId(coreManager.activeCoreId.value)
    }

    private fun updateCurrentCoreView() {
        val b = binding ?: return
        val active = coreManager.getActiveCore()
        b.tvCurrentCoreName.text = active.name
        val loadedVer = NativeLoader.getLoadedVersion().ifBlank { active.version }
        b.tvCurrentCoreVersion.text = "$loadedVer (${active.abi})"
    }
}
