package com.mirage.android.ui

import android.app.AlertDialog
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.mirage.android.data.model.Node
import com.mirage.android.databinding.FragmentNodesBinding
import com.mirage.android.ui.adapter.NodeAdapter
import com.mirage.android.ui.viewmodel.NodesViewModel
import kotlinx.coroutines.launch
import com.google.android.material.snackbar.Snackbar
import com.mirage.android.R

/**
 * 节点管理 Tab: 现代 RecyclerView 列表 + 响应式并发测速与剪贴板导入。
 */
class NodesFragment : Fragment() {

    private var _binding: FragmentNodesBinding? = null
    private val binding get() = _binding!!

    private val viewModel: NodesViewModel by viewModels()
    private lateinit var adapter: NodeAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentNodesBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupRecyclerView()
        setupButtons()
        observeState()
    }

    private fun setupRecyclerView() {
        adapter = NodeAdapter(
            onSelect = { index, _ ->
                com.mirage.android.util.Haptic.toggle(binding.root)
                viewModel.selectNode(index)
            },
            onTest = { index, _ ->
                com.mirage.android.util.Haptic.tap(binding.root)
                viewModel.testNode(index)
            },
            onEdit = { index, _ -> showNodeDialog(index) },
            onDelete = { index, node ->
                AlertDialog.Builder(requireContext())
                    .setTitle(R.string.node_delete_title)
                    .setMessage(getString(R.string.node_delete_message, node.displayName))
                    .setPositiveButton(R.string.delete) { _, _ -> viewModel.deleteNode(index) }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
            }
        )

        binding.recyclerNodes.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerNodes.adapter = adapter
    }

    private fun setupButtons() {
        binding.addNodeBtn.setOnClickListener {
            com.mirage.android.util.Haptic.tap(it)
            showNodeDialog(null)
        }

        binding.btnImportClipboard.setOnClickListener {
            com.mirage.android.util.Haptic.tap(it)
            val cm = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val text = cm.primaryClip?.getItemAt(0)?.text?.toString().orEmpty()
            val count = viewModel.importFromClipboard(text)
            if (count > 0) {
                Toast.makeText(requireContext(), getString(R.string.nodes_imported, count), Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(requireContext(), R.string.nodes_import_none, Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnTestAll.setOnClickListener {
            com.mirage.android.util.Haptic.confirm(it)
            // 批量测速 + RTT 排序展示
            // 结果不再倒进对话框: 每一行本来就会显示自己的 RTT (NodeAdapter 里按
            // 100/250ms 分三档着色)。订阅动辄上百个节点, 对话框里那一坨纯文本既读
            // 不了也点不了。这里只留一句概要和"选最快"这个唯一有用的动作。
            viewModel.testAllWithSort { sorted ->
                val root = _binding?.root ?: return@testAllWithSort
                if (sorted.isEmpty()) {
                    Toast.makeText(requireContext(), R.string.nodes_all_unavailable, Toast.LENGTH_LONG).show()
                    return@testAllWithSort
                }
                val best = sorted.first()
                val bar = Snackbar.make(
                    root,
                    getString(R.string.nodes_test_summary, sorted.size, best.first.displayName, best.second),
                    Snackbar.LENGTH_LONG
                )
                // 悬浮胶囊导航栏盖在底部, 不锚定就会被它压住
                activity?.findViewById<View>(R.id.cardFloatingNav)?.let(bar::setAnchorView)
                bar.setAction(R.string.nodes_pick_best) {
                    val idx = viewModel.nodes.value.indexOfFirst { it.uri == best.first.uri }
                    if (idx >= 0) viewModel.selectNode(idx)
                }
                bar.show()
            }
        }

        binding.autoSelectBtn.setOnClickListener {
            com.mirage.android.util.Haptic.toggle(it)
            viewModel.toggleAutoSelect()
        }

        binding.testMethodBtn.setOnClickListener {
            com.mirage.android.util.Haptic.tap(it)
            chooseTestMethod()
        }

        binding.btnPoolSize.setOnClickListener {
            com.mirage.android.util.Haptic.tap(it)
            choosePoolSize()
        }
    }

    private fun observeState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.nodes.collect { list ->
                        adapter.submitList(list)
                        binding.tvEmptyNodes.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
                    }
                }
                launch {
                    viewModel.selectedIndex.collect { index ->
                        adapter.setSelected(index)
                    }
                }
                launch {
                    viewModel.isTestingAll.collect { testing ->
                        binding.btnTestAll.isEnabled = !testing
                        binding.btnTestAll.text = getString(if (testing) R.string.nodes_testing else R.string.nodes_test_all)
                    }
                }
                launch {
                    viewModel.isAutoSelect.collect { auto ->
                        binding.autoSelectBtn.text = getString(if (auto) R.string.nodes_auto_on else R.string.nodes_auto_off)
                    }
                }
                launch {
                    viewModel.testMethod.collect { method ->
                        binding.testMethodBtn.text = getString(R.string.nodes_test_method_fmt, method.uppercase())
                    }
                }
                launch {
                    viewModel.poolSize.collect { size ->
                        binding.btnPoolSize.text = getString(R.string.nodes_pool_fmt, size)
                    }
                }
            }
        }
    }

    private fun choosePoolSize() {
        val sizes = intArrayOf(1, 4, 8, 16, 32, 64)
        val items = arrayOf(
            "1 (低功耗 / 单路)",
            "4 (轻量 / 省电)",
            "8 (标准推荐 / 兼顾性能)",
            "16 (高并发 / 极速浏览)",
            "32 (超快响应 / 大带宽)",
            "64 (极限并发)"
        )
        val currentSize = viewModel.poolSize.value
        val currentIdx = sizes.indexOf(currentSize).coerceAtLeast(2)

        AlertDialog.Builder(requireContext())
            .setTitle(R.string.nodes_pool_title)
            .setSingleChoiceItems(items, currentIdx) { dialog, which ->
                val selected = sizes[which]
                viewModel.setPoolSize(selected)
                Toast.makeText(requireContext(), getString(R.string.nodes_pool_set, selected), Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun chooseTestMethod() {
        val items = arrayOf(getString(R.string.nodes_test_tcp), getString(R.string.nodes_test_ping), getString(R.string.nodes_test_connect))
        val keys = arrayOf("tcp", "ping", "connect")
        val currentIdx = keys.indexOf(viewModel.testMethod.value).coerceAtLeast(0)

        AlertDialog.Builder(requireContext())
            .setTitle(R.string.nodes_test_method_title)
            .setSingleChoiceItems(items, currentIdx) { dialog, which ->
                viewModel.setTestMethod(keys[which])
                dialog.dismiss()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showNodeDialog(index: Int?) {
        val existing = index?.let { viewModel.nodes.value.getOrNull(it) }
        val ctx = requireContext()

        val layout = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 20, 40, 10)
        }

        fun createField(hint: String, value: String) = EditText(ctx).apply {
            this.hint = hint
            setText(value)
            textSize = 14f
            setPadding(16, 16, 16, 16)
        }

        val nameInput = createField("节点名称 (可选，如 香港 01)", existing?.name ?: "")

        val radioGroup = RadioGroup(ctx).apply {
            orientation = RadioGroup.HORIZONTAL
            setPadding(0, 10, 0, 10)
        }
        val radioLink = RadioButton(ctx).apply {
            text = "粘贴链接"
            isChecked = true
            id = View.generateViewId()
        }
        val radioManual = RadioButton(ctx).apply {
            text = "手动填写"
            id = View.generateViewId()
        }
        radioGroup.addView(radioLink)
        radioGroup.addView(radioManual)

        val linkInput = createField("mirage://密码@host:端口?sni=www.apple.com", existing?.uri ?: "")

        val manualBox = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        val serverInput = createField("服务器 (IP 或域名)", existing?.server ?: "")
        val portInput = createField("端口 (默认 443)", existing?.port ?: "443")
        val pwdInput = createField("密码", existing?.password ?: "")
        val sniInput = createField("SNI 伪装域名 (如 www.apple.com)", existing?.sni ?: "www.apple.com")

        manualBox.addView(serverInput)
        manualBox.addView(portInput)
        manualBox.addView(pwdInput)
        manualBox.addView(sniInput)

        radioGroup.setOnCheckedChangeListener { _, checkedId ->
            val manual = checkedId == radioManual.id
            manualBox.visibility = if (manual) View.VISIBLE else View.GONE
            linkInput.visibility = if (manual) View.GONE else View.VISIBLE
        }

        layout.addView(nameInput)
        layout.addView(radioGroup)
        layout.addView(linkInput)
        layout.addView(manualBox)

        AlertDialog.Builder(ctx)
            .setTitle(if (index == null) R.string.nodes_add else R.string.node_edit_title)
            .setView(layout)
            .setPositiveButton(R.string.save) { _, _ ->
                val uri: String
                if (radioLink.isChecked) {
                    uri = linkInput.text.toString().trim()
                    if (!uri.startsWith("mirage://")) {
                        Toast.makeText(ctx, R.string.node_uri_prefix_error, Toast.LENGTH_LONG).show()
                        return@setPositiveButton
                    }
                } else {
                    val server = serverInput.text.toString().trim()
                    val port = portInput.text.toString().trim().ifEmpty { "443" }
                    val pwd = pwdInput.text.toString()
                    val sni = sniInput.text.toString().trim().ifEmpty { "www.apple.com" }

                    if (server.isEmpty() || pwd.isEmpty()) {
                        Toast.makeText(ctx, R.string.node_required_fields, Toast.LENGTH_LONG).show()
                        return@setPositiveButton
                    }
                    uri = Node.uriOf(server, port, pwd, sni)
                }

                val name = nameInput.text.toString().trim()
                if (index == null) {
                    val newIdx = viewModel.addNode(uri, name)
                    viewModel.selectNode(newIdx)
                } else {
                    viewModel.updateNode(index, uri, name)
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
