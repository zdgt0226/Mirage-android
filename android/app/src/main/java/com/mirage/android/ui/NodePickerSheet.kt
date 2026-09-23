package com.mirage.android.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.mirage.android.R
import com.mirage.android.databinding.SheetNodePickerBinding
import com.mirage.android.ui.adapter.NodeAdapter
import com.mirage.android.ui.viewmodel.NodesViewModel
import kotlinx.coroutines.launch

/**
 * 首页「切换节点」打开的二级选择层。
 *
 * 之前点一下是跳到「节点」Tab —— 选完还得自己切回首页, 而且首页的上下文没了。
 * 现在就地弹出: 选中即生效并关闭, 长按行上的「⋮」可以测速 / 复制 / 编辑 / 删除,
 * 底部还有新增入口。用的是 NodesFragment 同一套 NodeAdapter 与 NodeEditDialog,
 * 不另起一份列表实现。
 *
 * ViewModel 用 activityViewModels: 与 NodesFragment 的实例不同, 但两者读的都是
 * NodeRepository 这个单例的 StateFlow, 所以状态天然一致。
 */
class NodePickerSheet : BottomSheetDialogFragment() {

    private var _binding: SheetNodePickerBinding? = null
    private val binding get() = _binding!!
    private val viewModel: NodesViewModel by activityViewModels()

    private lateinit var adapter: NodeAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = SheetNodePickerBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onStart() {
        super.onStart()
        val dialog = dialog as? BottomSheetDialog ?: return
        dialog.dismissWithAnimation = true
        dialog.behavior.apply {
            // 节点数量可变，内容常比半展开区矮；fitToContents=false 只定顶边、
            // 不拉伸 wrap_content 子视图，会让面板底边吊在半空（API 36 实测空洞 593px）。
            isFitToContents = true
            skipCollapsed = true
            state = BottomSheetBehavior.STATE_EXPANDED
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        adapter = NodeAdapter(
            onSelect = { index, _ ->
                viewModel.selectNode(index)
                dismiss()
            },
            onTest = { index, _ -> viewModel.testNode(index) },
            onEdit = { index, node ->
                NodeEditDialog.show(requireContext(), node) { uri, name ->
                    viewModel.updateNode(index, uri, name)
                }
            },
            onDelete = { index, node ->
                AlertDialog.Builder(requireContext())
                    .setTitle(R.string.node_delete_title)
                    .setMessage(getString(R.string.node_delete_message, node.displayName))
                    .setPositiveButton(R.string.delete) { _, _ -> viewModel.deleteNode(index) }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
            }
        )
        binding.recyclerSheetNodes.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerSheetNodes.adapter = adapter

        binding.btnSheetAdd.setOnClickListener {
            com.mirage.android.util.Haptic.tap(it)
            NodeEditDialog.show(requireContext(), null) { uri, name ->
                val newIdx = viewModel.addNode(uri, name)
                viewModel.selectNode(newIdx)
            }
        }

        binding.btnSheetTestAll.setOnClickListener {
            com.mirage.android.util.Haptic.confirm(it)
            viewModel.testAllNodes()
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.nodes.collect { list ->
                        adapter.submitList(list)
                        binding.tvSheetEmpty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
                        binding.recyclerSheetNodes.visibility = if (list.isEmpty()) View.GONE else View.VISIBLE
                    }
                }
                launch {
                    viewModel.selectedIndex.collect { adapter.setSelected(it) }
                }
                launch {
                    viewModel.isTestingAll.collect { testing ->
                        binding.btnSheetTestAll.isEnabled = !testing
                        binding.btnSheetTestAll.setText(
                            if (testing) R.string.nodes_testing else R.string.nodes_test_all
                        )
                    }
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val TAG = "NodePickerSheet"
    }
}
