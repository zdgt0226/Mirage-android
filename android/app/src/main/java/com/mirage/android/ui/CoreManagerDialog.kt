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

        b.tvDeviceAbi.text = "设备: ${Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown"}"
        updateCurrentCoreView()

        adapter = CoreAdapter(
            onSelect = { core ->
                coreManager.setActiveCore(core.id)
                adapter?.setActiveId(core.id)
                updateCurrentCoreView()
                onCoreChanged()
                Toast.makeText(context, "已切换内核: ${core.name} (若已连接VPN请重新连接生效)", Toast.LENGTH_SHORT).show()
            },
            onDelete = { core ->
                AlertDialog.Builder(context)
                    .setTitle("删除内核")
                    .setMessage("确定删除自定义内核「${core.name}」吗？")
                    .setPositiveButton("删除") { _, _ ->
                        coreManager.deleteCore(core.id)
                        refreshList()
                        updateCurrentCoreView()
                        onCoreChanged()
                    }
                    .setNegativeButton("取消", null)
                    .show()
            }
        )

        b.recyclerCores.layoutManager = LinearLayoutManager(context)
        b.recyclerCores.adapter = adapter
        refreshList()

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
            Toast.makeText(context, "已恢复为内置默认内核", Toast.LENGTH_SHORT).show()
        }

        alertDialog = AlertDialog.Builder(context)
            .setView(b.root)
            .setPositiveButton("完成", null)
            .create()

        alertDialog?.show()
    }

    private fun checkOnlineReleases() {
        val b = binding ?: return
        val scope = (context as? androidx.lifecycle.LifecycleOwner)?.lifecycleScope 
            ?: kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main)

        b.btnCheckOnlineCore.isEnabled = false
        b.btnCheckOnlineCore.text = "正在查询 GitHub Releases..."

        scope.launch {
            val result = coreManager.fetchOnlineReleases()

            result.onSuccess { releases ->
                b.btnCheckOnlineCore.isEnabled = true
                b.btnCheckOnlineCore.text = "检查 GitHub 在线内核 (Releases)"

                if (releases.isEmpty()) {
                    Toast.makeText(context, "暂无与当前架构 (${Build.SUPPORTED_ABIS.firstOrNull()}) 兼容的在线内核", Toast.LENGTH_LONG).show()
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
                                "当前已是最新内核版本 (${latest.tagName})\n无需重复下载",
                                Toast.LENGTH_SHORT
                            ).show()
                        } else {
                            coreManager.setActiveCore(existing.id)
                            refreshList()
                            updateCurrentCoreView()
                            onCoreChanged()
                            Toast.makeText(
                                context,
                                "已在本地找到已下载的最新内核 (${latest.tagName})\n已为你自动切换激活 (免重复下载)",
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
                b.btnCheckOnlineCore.text = "检查 GitHub 在线内核 (Releases)"
                Toast.makeText(context, "查询失败: ${e.message} (请开启代理重试)", Toast.LENGTH_LONG).show()
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
        b.tvDownloadStatus.text = "正在下载并校验 ${release.tagName} (${release.formattedSize})$digestHint..."
        b.tvDownloadPercent.text = "0%"
        b.btnCheckOnlineCore.isEnabled = false
        b.btnCheckOnlineCore.text = "正在下载并校验内核 ${release.tagName}..."

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
            b.btnCheckOnlineCore.text = "检查 GitHub 在线内核 (Releases)"

            result.onSuccess { core ->
                android.util.Log.i("Mirage", "[loader] 在线内核下载并校验通过: ${core.name} (SHA: ${core.sha256})")
                coreManager.setActiveCore(core.id)
                refreshList()
                updateCurrentCoreView()
                onCoreChanged()
                val shaInfo = core.shortSha256?.let { " (SHA: $it)" } ?: ""
                Toast.makeText(context, "成功下载并通过完整性校验: ${core.name}$shaInfo\n(若已连接VPN请重新连接生效)", Toast.LENGTH_LONG).show()
            }.onFailure { e ->
                android.util.Log.e("Mirage", "[loader] 在线内核下载或校验失败: ${e.message}", e)
                Toast.makeText(context, "下载或校验失败: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    fun handleImportUri(uri: Uri) {
        try {
            val fileName = uri.lastPathSegment ?: "libmirage_custom.so"
            val displayName = fileName.substringAfterLast("/").removeSuffix(".so")

            val result = context.contentResolver.openInputStream(uri)?.use { stream ->
                coreManager.importCore(stream, displayName)
            } ?: throw IllegalArgumentException("无法打开所选文件")
            if (result.isSuccess) {
                val core = result.getOrThrow()
                coreManager.setActiveCore(core.id)
                refreshList()
                updateCurrentCoreView()
                onCoreChanged()
                Toast.makeText(context, "成功导入并激活内核: ${core.name}", Toast.LENGTH_LONG).show()
            } else {
                val msg = result.exceptionOrNull()?.message ?: "导入失败"
                AlertDialog.Builder(context)
                    .setTitle("内核导入失败")
                    .setMessage(msg)
                    .setPositiveButton("确定", null)
                    .show()
            }
        } catch (e: Exception) {
            AlertDialog.Builder(context)
                .setTitle("导入出错")
                .setMessage(e.message)
                .setPositiveButton("确定", null)
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
