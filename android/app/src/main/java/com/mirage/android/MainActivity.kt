package com.mirage.android

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.mirage.android.core.GeoManager
import com.mirage.android.data.model.Node
import com.mirage.android.data.repository.NodeRepository
import com.mirage.android.ui.AppFilterActivity
import com.mirage.android.ui.CoreManagerDialog
import com.mirage.android.ui.GeoAssetActivity
import com.mirage.android.ui.RulesHostActivity
import com.mirage.android.ui.SettingsActions
import com.mirage.android.ui.shell.HomePage
import com.mirage.android.ui.shell.MirageShell
import com.mirage.android.ui.shell.NodesPage
import com.mirage.android.ui.shell.SettingsPage
import com.mirage.android.ui.shell.outboundModeTitleRes
import com.mirage.android.ui.theme.MirageTheme
import com.mirage.android.ui.viewmodel.HomeViewModel
import com.mirage.android.ui.viewmodel.NodesViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 主容器: 三页手势外壳（节点 ‹ 首页 › 设置）。
 *
 * 导航模型在 2026-11-06 由「ViewPager2 + 悬浮胶囊底栏 + 4 Tab」换成横向翻页，
 * 方向按手指方向定义（见 `ShellPage` 的注释）。本 Activity 只做四件事：
 * 窗口与权限、深链、把 ViewModel 喂给 Compose、以及代表 Compose 拉起平台弹层
 * 与外挂 Activity —— 后者需要 `ActivityResultLauncher`，而那只能挂在 Activity 上。
 */
class MainActivity : AppCompatActivity() {

    private val homeViewModel: HomeViewModel by viewModels()
    private val nodesViewModel: NodesViewModel by viewModels()

    private var coreManagerDialog: CoreManagerDialog? = null

    /**
     * 设置页摘要的重算触发器。
     *
     * DNS / TUN 的摘要来自 `TunConfigStore` 与 `DnsRepository` 的同步读取，不是
     * `StateFlow`，所以弹层改完值不会自己推给 Compose。弹层回调里自增这个计数，
     * 摘要行就跟着重组。不这么做的表现是：改完 MTU 回到设置页，副标题还是旧值。
     */
    private var settingsRevision = mutableIntStateOf(0)

    private val notifPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            homeViewModel.startVpn()
        } else {
            Toast.makeText(this, R.string.vpn_permission_denied, Toast.LENGTH_SHORT).show()
        }
    }

    private val pickSoLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            coreManagerDialog?.handleImportUri(uri)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Edge-to-edge: 状态栏与导航栏全透明。Insets 由各 Compose 页面用
        // statusBarsPadding / navigationBarsPadding 自行消费, 不在这里写死边距
        // (§0.2 拒绝 3: Android 的 insets 分类远多于 iOS 的 safe area)。
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = android.graphics.Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.isAppearanceLightStatusBars = true
        controller.isAppearanceLightNavigationBars = true

        com.mirage.android.core.ConnectionOwnerResolver.init(applicationContext)

        setContent {
            MirageTheme {
                MirageShell(
                    nodesPage = { NodesPage(viewModel = nodesViewModel) },
                    homePage = { HomeHost() },
                    settingsPage = { SettingsHost() },
                )
            }
        }

        checkNotificationPermission()
        handleIncomingUri(intent)
        checkGeoInitialization()

        if (intent?.getBooleanExtra(EXTRA_AUTO_CONNECT, false) == true) {
            requestVpnPermissionAndConnect()
        } else if (intent?.getBooleanExtra(EXTRA_AUTO_DISCONNECT, false) == true) {
            homeViewModel.disconnect()
        }
    }

    @Composable
    private fun HomeHost() {
        val state by homeViewModel.vpnState.collectAsStateWithLifecycle()
        val stats by homeViewModel.trafficStats.collectAsStateWithLifecycle()
        val latency by homeViewModel.latencyMs.collectAsStateWithLifecycle()
        val nodes by nodesViewModel.nodes.collectAsStateWithLifecycle()
        val selected by homeViewModel.selectedNode.collectAsStateWithLifecycle()

        HomePage(
            state = state,
            stats = stats,
            latencyMs = latency,
            nodes = nodes,
            selectedNode = selected,
            onToggle = {
                homeViewModel.toggleConnection(
                    onRequirePermission = { requestVpnPermissionAndConnect() },
                    onProceedConnect = { requestVpnPermissionAndConnect() },
                )
            },
            onSelectNode = { index -> nodesViewModel.selectNode(index) },
        )
    }

    @Composable
    private fun SettingsHost() {
        val outbound by homeViewModel.outboundMode.collectAsStateWithLifecycle()
        val poolSize by nodesViewModel.poolSize.collectAsStateWithLifecycle()
        val revision = settingsRevision.intValue
        // key(revision): 摘要是同步读取, 必须随 revision 重算 (见字段注释)。
        val dnsSummary = remember(revision) { SettingsActions.dnsSummary(this) }
        val tunSummary = remember(revision) { SettingsActions.tunSummary(this) }

        SettingsPage(
            outboundMode = outbound,
            dnsSummary = dnsSummary,
            tunSummary = tunSummary,
            poolSize = poolSize,
            onOpenRules = { startActivity(Intent(this, RulesHostActivity::class.java)) },
            onOpenGeo = { startActivity(Intent(this, GeoAssetActivity::class.java)) },
            onOpenPerApp = { startActivity(Intent(this, AppFilterActivity::class.java)) },
            onPickOutboundMode = { pickOutboundMode() },
            onOpenDns = { SettingsActions.showDns(this) { bumpSettings() } },
            onOpenPool = { pickPoolSize() },
            onOpenTun = { SettingsActions.showTun(this) { bumpSettings() } },
            onOpenCore = { showCoreManager() },
            onOpenVersion = { SettingsActions.showVersion(this) { showCoreManager() } },
            onBackup = { SettingsActions.showBackup(this) },
            onRestore = { SettingsActions.showRestore(this) },
        )
    }

    private fun bumpSettings() {
        settingsRevision.intValue += 1
    }

    private fun showCoreManager() {
        coreManagerDialog = SettingsActions.showCore(this) { pickSoLauncher.launch("*/*") }
    }

    /** 出站模式三选一。下标语义与 `outboundModeTitleRes` 一致: 0 规则 / 1 全局 / 2 直连。 */
    private fun pickOutboundMode() {
        val modes = intArrayOf(0, 1, 2)
        val items = modes.map { getString(outboundModeTitleRes(it)) }.toTypedArray()
        val current = modes.indexOf(homeViewModel.outboundMode.value).coerceAtLeast(0)
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.settings_row_outbound)
            .setSingleChoiceItems(items, current) { dialog, which ->
                homeViewModel.setOutboundMode(modes[which])
                dialog.dismiss()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** 预热池大小。与节点页的选择器同一组档位, 改一处要改两处 —— 档位表在 [POOL_SIZES]。 */
    private fun pickPoolSize() {
        val items = arrayOf(
            getString(R.string.pool_1),
            getString(R.string.pool_4),
            getString(R.string.pool_8),
            getString(R.string.pool_16),
            getString(R.string.pool_32),
            getString(R.string.pool_64),
        )
        val current = POOL_SIZES.indexOf(nodesViewModel.poolSize.value).coerceAtLeast(2)
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.nodes_pool_title)
            .setSingleChoiceItems(items, current) { dialog, which ->
                val size = POOL_SIZES[which]
                nodesViewModel.setPoolSize(size)
                Toast.makeText(this, getString(R.string.nodes_pool_set, size), Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun checkGeoInitialization() {
        lifecycleScope.launch(Dispatchers.IO) {
            val status = GeoManager.getGeoStatus(this@MainActivity)
            if (!status.isReady) {
                // 自动路径: allowUnverified 保持默认 false。未通过 SHA-256 校验的
                // Geo 数据绝不在无人看着的情况下装进持有 TUN 的 :core 进程。
                val result = GeoManager.updateGeoFiles(this@MainActivity) { _, _ -> }
                if (!result.success) {
                    android.util.Log.w("Mirage", "[geo] 自动更新未安装: ${result.message}")
                }
            }
        }
    }

    private fun checkNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                notifPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    /**
     * 回前台时重新同步 VPN 状态。
     *
     * `VpnRepository` 把 `bindService` 挂在 Activity 的 onStart/onStop 上
     * （见该类 `init` 里的注释：绑定跟随可见性，最后一个 Activity 不可见即解绑，
     * 否则 `BIND_AUTO_CREATE` 会把 `:core` 永久吊住）。所以每次回前台都有一段
     * 「已 bind 但状态未同步」的窗口，而从设置页的子界面（Geo / 分应用 / 规则）
     * 返回必然经过它。被替换的 `HomeFragment.onResume` 正是调这一下；
     * 漏掉的表现是：在子界面停留期间内核停了，回到首页按钮还显示「断开」。
     */
    override fun onResume() {
        super.onResume()
        homeViewModel.checkCurrentState()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIncomingUri(intent)
        if (intent.getBooleanExtra(EXTRA_AUTO_CONNECT, false)) {
            requestVpnPermissionAndConnect()
        } else if (intent.getBooleanExtra(EXTRA_AUTO_DISCONNECT, false)) {
            homeViewModel.disconnect()
        }
    }

    fun requestVpnPermissionAndConnect() {
        val intent = VpnService.prepare(this)
        if (intent != null) {
            vpnPermissionLauncher.launch(intent)
        } else {
            homeViewModel.startVpn()
        }
    }

    /**
     * 处理外部传入的 mirage:// 深链。
     *
     * MainActivity 是 exported + BROWSABLE, 任何应用或网页都能触发这里。
     * 因此必须: (1) 结构化校验而非前缀判断; (2) 展示真实 host:port 让用户确认;
     * (3) 绝不自动选中 —— 否则一个 <a href="mirage://attacker:443"> 就能把用户
     * 下一次连接的出口换成攻击者的服务器, 构成完整中间人。
     */
    private fun handleIncomingUri(intent: Intent?) {
        val uri = intent?.dataString ?: return
        // 消费掉, 避免 onNewIntent/重建时重复弹窗
        intent.data = null

        val node = parseNodeUri(uri)
        if (node == null) {
            Toast.makeText(this, R.string.node_uri_invalid, Toast.LENGTH_SHORT).show()
            return
        }

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.node_import_title)
            .setMessage(
                getString(
                    R.string.node_import_message,
                    node.server,
                    node.port,
                    if (node.sni.isNotBlank()) getString(R.string.node_import_sni, node.sni) else ""
                )
            )
            .setPositiveButton(R.string.import_action) { _, _ ->
                NodeRepository.getInstance(this).addNode(node)
                Toast.makeText(this, R.string.node_imported_hint, Toast.LENGTH_LONG).show()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** 结构化校验 mirage:// 链接; 非法返回 null。 */
    private fun parseNodeUri(uri: String): Node? {
        if (!uri.startsWith("mirage://") || uri.length > MAX_NODE_URI_LEN) return null
        val node = Node(uri = uri, name = Node.defaultName(uri))
        if (node.server.isBlank()) return null
        val port = node.port.toIntOrNull() ?: return null
        if (port !in 1..65535) return null
        return node
    }

    private companion object {
        /** 外部链接长度上限, 防止超长 URI 撑爆解析与对话框。 */
        const val MAX_NODE_URI_LEN = 2048

        const val EXTRA_AUTO_CONNECT = "auto_connect"
        const val EXTRA_AUTO_DISCONNECT = "auto_disconnect"

        /** 预热池档位。与 `pool_1`..`pool_64` 的文案一一对应, 顺序不可错。 */
        val POOL_SIZES = intArrayOf(1, 4, 8, 16, 32, 64)
    }
}
