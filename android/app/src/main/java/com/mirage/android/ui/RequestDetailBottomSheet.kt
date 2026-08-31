package com.mirage.android.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.mirage.android.core.CoreController
import com.mirage.android.data.model.RecentRequestInfo
import com.mirage.android.databinding.DialogRequestDetailBinding

class RequestDetailBottomSheet(
    private val item: RecentRequestInfo
) : BottomSheetDialogFragment() {

    private var _binding: DialogRequestDetailBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = DialogRequestDetailBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.tvDetailProtocol.text = item.protocol
        binding.tvDetailTitle.text = item.target

        binding.tvHostValue.text = item.target
        binding.tvIpValue.text = item.resolvedIp.ifBlank { "直接转发" }
        binding.tvOutboundValue.text = item.outbound
        binding.tvRuleValue.text = item.matchedRule
        binding.tvStatusValue.text = item.status

        binding.tvDurationValue.text = item.durationFormatted
        binding.tvUploadValue.text = "${item.upFormatted} (${item.upBytes} B)"
        binding.tvDownloadValue.text = "${item.downFormatted} (${item.downBytes} B)"

        binding.btnCopyDetail.setOnClickListener {
            val text = """
                [Mirage Request]
                Protocol: ${item.protocol}
                Target: ${item.target}
                Resolved IP: ${item.resolvedIp}
                Outbound: ${item.outbound}
                Matched Rule: ${item.matchedRule}
                Status: ${item.status}
                Duration: ${item.durationFormatted}
                Upload: ${item.upFormatted} (${item.upBytes} B)
                Download: ${item.downFormatted} (${item.downBytes} B)
            """.trimIndent()

            val cm = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            cm?.setPrimaryClip(ClipData.newPlainText("Mirage Request Detail", text))
            Toast.makeText(requireContext(), "已复制请求详情", Toast.LENGTH_SHORT).show()
        }

        binding.btnCloseConn.setOnClickListener {
            val ok = CoreController.closeConnection(item.id)
            if (ok) {
                Toast.makeText(requireContext(), "已重置并切断连接 #${item.id}", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(requireContext(), "连接已处于关闭状态", Toast.LENGTH_SHORT).show()
            }
            dismiss()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val TAG = "RequestDetailBottomSheet"
    }
}
