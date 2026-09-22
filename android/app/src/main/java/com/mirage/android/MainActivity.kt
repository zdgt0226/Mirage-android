package com.mirage.android

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import com.mirage.android.core.NativeLoader
import com.mirage.android.data.model.Node
import com.mirage.android.data.repository.NodeRepository
import com.mirage.android.databinding.ActivityMainBinding
import com.mirage.android.ui.CoreManagerDialog
import com.mirage.android.ui.HomeFragment
import com.mirage.android.ui.NodesFragment
import com.mirage.android.ui.RulesFragment
import com.mirage.android.ui.TrafficFragment
import com.mirage.android.ui.viewmodel.HomeViewModel
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import com.mirage.android.core.GeoManager

/**
 * 主容器: ViewPager2 + 底部导航 (状态保持, 消除切换卡顿)。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val homeViewModel: HomeViewModel by viewModels()

    private var coreManagerDialog: CoreManagerDialog? = null

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

        // 开启现代 Edge-to-Edge: 状态栏与导航栏完全透明
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = android.graphics.Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.isAppearanceLightStatusBars = true
        controller.isAppearanceLightNavigationBars = true

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        com.mirage.android.core.ConnectionOwnerResolver.init(applicationContext)

        // 统一处理 Window Insets: 顶部状态栏沉浸, 底部避让系统导航栏与悬浮底栏
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val statusBars = insets.getInsets(
                androidx.core.view.WindowInsetsCompat.Type.statusBars() or
                    androidx.core.view.WindowInsetsCompat.Type.displayCutout()
            )
            val navBars = insets.getInsets(
                androidx.core.view.WindowInsetsCompat.Type.navigationBars() or
                    androidx.core.view.WindowInsetsCompat.Type.displayCutout()
            )
            
            val density = resources.displayMetrics.density
            val floatingNavHeightWithMargin = (84 * density).toInt()
            binding.viewPager.setPadding(statusBars.left, statusBars.top, statusBars.right, floatingNavHeightWithMargin + navBars.bottom)

            val lp = binding.cardFloatingNav.layoutParams as? androidx.coordinatorlayout.widget.CoordinatorLayout.LayoutParams
            if (lp != null) {
                val sideMargin = (20 * density).toInt() + maxOf(navBars.left, navBars.right)
                val bottomMargin = (12 * density).toInt() + navBars.bottom
                lp.setMargins(sideMargin, 0, sideMargin, bottomMargin)
                binding.cardFloatingNav.layoutParams = lp
            }
            insets
        }

        // 禁止 BottomNavigationView 内部自动追加导航栏高度内边距 (防止图标向上偏移/文字被裁剪)
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(binding.bottomNav) { view, insets ->
            view.setPadding(0, 0, 0, 0)
            insets
        }

        checkNotificationPermission()
        handleIncomingUri(intent)

        setupViewPager()
        checkGeoInitialization()

        if (intent?.getBooleanExtra("auto_connect", false) == true) {
            requestVpnPermissionAndConnect()
        } else if (intent?.getBooleanExtra("auto_disconnect", false) == true) {
            homeViewModel.disconnect()
        }
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
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                notifPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIncomingUri(intent)
        if (intent.getBooleanExtra("auto_connect", false)) {
            requestVpnPermissionAndConnect()
        } else if (intent.getBooleanExtra("auto_disconnect", false)) {
            homeViewModel.disconnect()
        }
    }

    private fun setupViewPager() {
        binding.viewPager.adapter = object : FragmentStateAdapter(this) {
            override fun getItemCount(): Int = 4
            override fun createFragment(position: Int): Fragment = when (position) {
                0 -> HomeFragment()
                1 -> NodesFragment()
                2 -> RulesFragment()
                3 -> TrafficFragment()
                else -> throw IllegalStateException("Invalid position $position")
            }
        }
        binding.viewPager.isUserInputEnabled = false // 禁用滑动切换，依靠底部导航
        binding.viewPager.offscreenPageLimit = 3

        binding.bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_home -> { binding.viewPager.setCurrentItem(0, false); true }
                R.id.nav_nodes -> { binding.viewPager.setCurrentItem(1, false); true }
                R.id.nav_rules -> { binding.viewPager.setCurrentItem(2, false); true }
                R.id.nav_traffic -> { binding.viewPager.setCurrentItem(3, false); true }
                else -> false
            }
        }

        val backCallback = object : androidx.activity.OnBackPressedCallback(false) {
            override fun handleOnBackPressed() {
                binding.viewPager.setCurrentItem(0, false)
            }
        }
        onBackPressedDispatcher.addCallback(this, backCallback)

        binding.viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                binding.bottomNav.menu.getItem(position).isChecked = true
                backCallback.isEnabled = position != 0
            }
        })
    }

    fun requestVpnPermissionAndConnect() {
        val intent = VpnService.prepare(this)
        if (intent != null) {
            vpnPermissionLauncher.launch(intent)
        } else {
            homeViewModel.startVpn()
        }
    }

    fun navigateToTab(tabIndex: Int) {
        if (tabIndex in 0..3) {
            binding.viewPager.setCurrentItem(tabIndex, false)
        }
    }

    fun showCoreManagerDialog() {
        coreManagerDialog = CoreManagerDialog(
            context = this,
            onPickSoFile = {
                // 打开文件选择器选择 .so 文件或任意二进制
                pickSoLauncher.launch("*/*")
            },
            // 版本/内核信息现在只在设置页显示, 首页没有要刷新的东西
            onCoreChanged = { }
        )
        coreManagerDialog?.show()
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
    }
}
