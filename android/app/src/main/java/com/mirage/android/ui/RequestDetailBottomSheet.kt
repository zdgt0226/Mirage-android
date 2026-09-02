package com.mirage.android.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
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

        // 耗时瀑布流指标绑定
        binding.tvWaterfallTotal.text = "总耗时 ${req.durationFormatted}"
        binding.tvDnsValue.text = req.dnsFormatted
        binding.tvConnectValue.text = req.connectFormatted
        binding.tvTtfbValue.text = req.ttfbFormatted

        if (req.tlsMs > 0) {
            binding.rowTls.visibility = View.VISIBLE
            binding.divTls.visibility = View.VISIBLE
            binding.tvTlsValue.text = "${req.tlsMs}ms"
        } else {
            binding.rowTls.visibility = View.GONE
            binding.divTls.visibility = View.GONE
        }

        // 动态计算瀑布流可视化进度条权重 (DNS, Connect, TLS, TTFB)
        val dnsWeight = if (req.dnsMs > 0) req.dnsMs.toFloat() else 0.2f
        val connWeight = if (req.connectMs > 0) req.connectMs.toFloat() else 0.5f
        val tlsWeight = if (req.tlsMs > 0) req.tlsMs.toFloat() else 0f
        val ttfbWeight = if (req.ttfbMs > 0) req.ttfbMs.toFloat() else 0.5f

        val dnsLp = binding.barDns.layoutParams as? LinearLayout.LayoutParams
        dnsLp?.weight = dnsWeight
        binding.barDns.layoutParams = dnsLp

        val connLp = binding.barConnect.layoutParams as? LinearLayout.LayoutParams
        connLp?.weight = connWeight
        binding.barConnect.layoutParams = connLp

        val tlsLp = binding.barTls.layoutParams as? LinearLayout.LayoutParams
        tlsLp?.weight = tlsWeight
        binding.barTls.visibility = if (req.tlsMs > 0) View.VISIBLE else View.GONE
        binding.barTls.layoutParams = tlsLp

        val ttfbLp = binding.barTtfb.layoutParams as? LinearLayout.LayoutParams
        ttfbLp?.weight = ttfbWeight
        binding.barTtfb.layoutParams = ttfbLp

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
                [Timings Waterfall]
                - DNS Lookup: ${req.dnsFormatted}
                - TCP / Tunnel Connect: ${req.connectFormatted}
                - TLS Handshake: ${if (req.tlsMs > 0) "${req.tlsMs}ms" else "-"}
                - TTFB / First Byte: ${req.ttfbFormatted}
                - Total Lifetime: ${req.durationFormatted}
                [Data Transfer]
                - Upload: ${req.upFormatted} (${req.upBytes} B)
                - Download: ${req.downFormatted} (${req.downBytes} B)
            """.trimIndent()

            val cm = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            cm?.setPrimaryClip(ClipData.newPlainText("Mirage Request Detail", text))
            Toast.makeText(requireContext(), "已复制请求详情与耗时分析", Toast.LENGTH_SHORT).show()
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
