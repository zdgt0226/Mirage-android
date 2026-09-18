package com.mirage.android.ui

import android.content.Context
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.mirage.android.R
import com.mirage.android.data.model.Node

/**
 * 节点的新增 / 编辑对话框。
 *
 * 原先内联在 NodesFragment 里, 现在首页的节点选择弹层也要用同一套表单 ——
 * 两份表单迟早会走偏 (校验规则、默认 SNI、字段顺序), 所以抽出来共用。
 */
object NodeEditDialog {

    /**
     * @param existing 传 null 表示新增
     * @param onSave   (uri, name) —— 校验通过后回调, 由调用方决定是新增还是更新
     */
    fun show(ctx: Context, existing: Node?, onSave: (uri: String, name: String) -> Unit) {
        val layout = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 20, 40, 10)
        }

        fun field(hintRes: Int, value: String) = EditText(ctx).apply {
            hint = ctx.getString(hintRes)
            setText(value)
            textSize = 14f
            setPadding(16, 16, 16, 16)
        }

        val nameInput = field(R.string.node_name_hint, existing?.name ?: "")

        val radioLink = RadioButton(ctx).apply {
            text = ctx.getString(R.string.node_tab_paste)
            isChecked = true
            id = View.generateViewId()
        }
        val radioManual = RadioButton(ctx).apply {
            text = ctx.getString(R.string.node_tab_manual)
            id = View.generateViewId()
        }
        val radioGroup = RadioGroup(ctx).apply {
            orientation = RadioGroup.HORIZONTAL
            setPadding(0, 10, 0, 10)
            addView(radioLink)
            addView(radioManual)
        }

        val linkInput = field(R.string.node_hint, existing?.uri ?: "")

        val serverInput = field(R.string.node_server_hint, existing?.server ?: "")
        val portInput = field(R.string.node_port_hint, existing?.port ?: "443")
        val pwdInput = field(R.string.node_password_hint, existing?.password ?: "")
        val sniInput = field(R.string.node_sni_hint, existing?.sni ?: "www.apple.com")
        val manualBox = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            addView(serverInput)
            addView(portInput)
            addView(pwdInput)
            addView(sniInput)
        }

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
            .setTitle(if (existing == null) R.string.nodes_add else R.string.node_edit_title)
            .setView(layout)
            .setPositiveButton(R.string.save) { _, _ ->
                val uri = if (radioLink.isChecked) {
                    val v = linkInput.text.toString().trim()
                    if (!v.startsWith("mirage://")) {
                        Toast.makeText(ctx, R.string.node_uri_prefix_error, Toast.LENGTH_LONG).show()
                        return@setPositiveButton
                    }
                    v
                } else {
                    val server = serverInput.text.toString().trim()
                    val port = portInput.text.toString().trim().ifEmpty { "443" }
                    val pwd = pwdInput.text.toString()
                    val sni = sniInput.text.toString().trim().ifEmpty { "www.apple.com" }
                    if (server.isEmpty() || pwd.isEmpty()) {
                        Toast.makeText(ctx, R.string.node_required_fields, Toast.LENGTH_LONG).show()
                        return@setPositiveButton
                    }
                    Node.uriOf(server, port, pwd, sni)
                }
                onSave(uri, nameInput.text.toString().trim())
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }
}
