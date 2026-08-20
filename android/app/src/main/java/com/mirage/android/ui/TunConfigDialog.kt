package com.mirage.android.ui

import android.content.Context
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.widget.Toast
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.mirage.android.core.TunConfigStore
import com.mirage.android.databinding.DialogTunConfigBinding

/**
 * TUN 性能与高级网络参数调优 Dialog
 */
class TunConfigDialog(
    context: Context,
    private val onSaved: (() -> Unit)? = null
) : BottomSheetDialog(context) {

    private lateinit var binding: DialogTunConfigBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = DialogTunConfigBinding.inflate(LayoutInflater.from(context))
        setContentView(binding.root)

        val currentMtu = TunConfigStore.getMtu(context)
        val currentIdle = TunConfigStore.getTcpIdleTimeoutSec(context)
        val currentBatch = TunConfigStore.getBatchSize(context)

        binding.etMtu.setText(currentMtu.toString())
        binding.etTcpIdle.setText(currentIdle.toString())

        syncMtuChips(currentMtu)
        syncIdleChips(currentIdle)
        syncBatchChips(currentBatch)

        setupListeners()
    }

    private fun syncMtuChips(mtu: Int) {
        when (mtu) {
            1400 -> binding.chipMtu1400.isChecked = true
            1420 -> binding.chipMtu1420.isChecked = true
            1500 -> binding.chipMtu1500.isChecked = true
            1280 -> binding.chipMtu1280.isChecked = true
            else -> binding.chipGroupMtu.clearCheck()
        }
    }

    private fun syncIdleChips(idle: Int) {
        when (idle) {
            120 -> binding.chipIdle120.isChecked = true
            300 -> binding.chipIdle300.isChecked = true
            600 -> binding.chipIdle600.isChecked = true
            1800 -> binding.chipIdle1800.isChecked = true
            else -> binding.chipGroupTcpIdle.clearCheck()
        }
    }

    private fun syncBatchChips(batch: Int) {
        when (batch) {
            16 -> binding.chipBatch16.isChecked = true
            32 -> binding.chipBatch32.isChecked = true
            64 -> binding.chipBatch64.isChecked = true
            else -> binding.chipBatch32.isChecked = true
        }
    }

    private fun setupListeners() {
        binding.chipMtu1400.setOnClickListener { binding.etMtu.setText("1400") }
        binding.chipMtu1420.setOnClickListener { binding.etMtu.setText("1420") }
        binding.chipMtu1500.setOnClickListener { binding.etMtu.setText("1500") }
        binding.chipMtu1280.setOnClickListener { binding.etMtu.setText("1280") }

        binding.chipIdle120.setOnClickListener { binding.etTcpIdle.setText("120") }
        binding.chipIdle300.setOnClickListener { binding.etTcpIdle.setText("300") }
        binding.chipIdle600.setOnClickListener { binding.etTcpIdle.setText("600") }
        binding.chipIdle1800.setOnClickListener { binding.etTcpIdle.setText("1800") }

        binding.etMtu.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val v = s?.toString()?.toIntOrNull() ?: 0
                syncMtuChips(v)
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        binding.etTcpIdle.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val v = s?.toString()?.toIntOrNull() ?: 0
                syncIdleChips(v)
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        binding.btnResetDefault.setOnClickListener {
            binding.etMtu.setText(TunConfigStore.DEFAULT_MTU.toString())
            binding.etTcpIdle.setText(TunConfigStore.DEFAULT_TCP_IDLE_SEC.toString())
            syncBatchChips(TunConfigStore.DEFAULT_BATCH_SIZE)
        }

        binding.btnCancel.setOnClickListener {
            dismiss()
        }

        binding.btnSave.setOnClickListener {
            val mtu = binding.etMtu.text?.toString()?.toIntOrNull()
            if (mtu == null || mtu !in 1280..1500) {
                binding.tilMtu.error = "MTU 必须在 1280 ~ 1500 之间"
                return@setOnClickListener
            }
            binding.tilMtu.error = null

            val idle = binding.etTcpIdle.text?.toString()?.toIntOrNull()
            if (idle == null || idle !in 60..1800) {
                binding.tilTcpIdle.error = "空闲超时必须在 60 ~ 1800 秒之间"
                return@setOnClickListener
            }
            binding.tilTcpIdle.error = null

            val batch = when {
                binding.chipBatch16.isChecked -> 16
                binding.chipBatch64.isChecked -> 64
                else -> 32
            }

            TunConfigStore.setMtu(context, mtu)
            TunConfigStore.setTcpIdleTimeoutSec(context, idle)
            TunConfigStore.setBatchSize(context, batch)

            Toast.makeText(context, "TUN 参数已保存 (MTU: $mtu, 超时: ${idle}s, 批处理: $batch)", Toast.LENGTH_SHORT).show()
            onSaved?.invoke()
            dismiss()
        }
    }
}
