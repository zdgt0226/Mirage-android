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

class RequestDetailBottomSheet() : BottomSheetDialogFragment() {

    private var item: RecentRequestInfo? = null

    constructor(item: RecentRequestInfo) : this() {
        this.item = item
    }

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
        val req = item ?: return

        binding.tvDetailProtocol.text = req.protocol
        binding.tvDetailTitle.text = req.target

        binding.tvHostValue.text = req.target
        binding.tvIpValue.text = req.resolvedIp.ifBlank { "直接转发" }
        binding.tvOutboundValue.text = req.outbound
        binding.tvRuleValue.text = req.matchedRule
        binding.tvStatusValue.text = req.status

        binding.tvDurationValue.text = req.durationFormatted
        binding.tvUploadValue.text = "${req.upFormatted} (${req.upBytes} B)"
        binding.tvDownloadValue.text = "${req.downFormatted} (${req.downBytes} B)"

        binding.btnCopyDetail.setOnClickListener {
            val text = """
                [Mirage Request]
                Protocol: ${req.protocol}
                Target: ${req.target}
                Resolved IP: ${req.resolvedIp}
                Outbound: ${req.outbound}
                Matched Rule: ${req.matchedRule}
                Status: ${req.status}
                Duration: ${req.durationFormatted}
                Upload: ${req.upFormatted} (${req.upBytes} B)
                Download: ${req.downFormatted} (${req.downBytes} B)
            """.trimIndent()

            val cm = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            cm?.setPrimaryClip(ClipData.newPlainText("Mirage Request Detail", text))
            Toast.makeText(requireContext(), "已复制请求详情", Toast.LENGTH_SHORT).show()
        }

        binding.btnCloseConn.setOnClickListener {
            val ok = CoreController.closeConnection(req.id)
            if (ok) {
                Toast.makeText(requireContext(), "已重置并切断连接 #${req.id}", Toast.LENGTH_SHORT).show()
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
