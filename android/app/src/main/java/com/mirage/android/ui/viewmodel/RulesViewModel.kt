package com.mirage.android.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.mirage.android.core.CoreController
import com.mirage.android.data.model.Rule
import com.mirage.android.data.repository.RuleRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

class RulesViewModel(application: Application) : AndroidViewModel(application) {
    private val ruleRepo = RuleRepository.getInstance(application)
    private val vpnRepo = com.mirage.android.data.repository.VpnRepository.getInstance(application)

    val rules: StateFlow<List<Rule>> = ruleRepo.rules
    val defaultAction: StateFlow<String> = ruleRepo.defaultAction
    val builtinDomains: StateFlow<List<String>> = ruleRepo.builtinDomains
    val builtinIpCount: StateFlow<Long> = ruleRepo.builtinIpCount
    val isBlockQuic: StateFlow<Boolean> = vpnRepo.isBlockQuic
    val isUdpMux: StateFlow<Boolean> = vpnRepo.isUdpMux

    private val _hasUnappliedChanges = MutableStateFlow(false)
    val hasUnappliedChanges: StateFlow<Boolean> = _hasUnappliedChanges.asStateFlow()

    /** 应用内核规则命中统计到规则列表。 */
    fun applyHits(hitsMap: Map<String, Long>) {
        ruleRepo.applyHits(hitsMap)
    }

    /** 从内核拉取最新命中统计并刷新列表。 */
    fun refreshRuleHits() {
        val json = CoreController.getRuleHits()
        if (json.isNotEmpty() && json != "[]") {
            runCatching {
                val arr = org.json.JSONArray(json)
                val hitsMap = HashMap<String, Long>()
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val kind = o.optString("kind")
                    val pat = o.optString("pattern")
                    val act = o.optString("action")
                    hitsMap["$kind|$pat|$act"] = o.optLong("hits", 0)
                }
                ruleRepo.applyHits(hitsMap)
            }
        }
    }

    /** 清空命中统计。 */
    fun resetRuleHits() {
        ruleRepo.resetRuleHits()
    }

    fun setBlockQuic(block: Boolean) {
        vpnRepo.setBlockQuic(block)
    }

    fun setUdpMux(enabled: Boolean) {
        vpnRepo.setUdpMux(enabled)
    }

    init {
        ruleRepo.refreshBuiltin()
    }

    fun addRule(pattern: String, kind: String, action: String) {
        val type = when (kind) {
            "cidr" -> "cidr"
            "geosite" -> "geosite"
            "geoip" -> "geoip"
            else -> "domain"
        }
        ruleRepo.addRule(Rule(type = type, kind = kind, pattern = pattern.trim().lowercase(), action = action))
        _hasUnappliedChanges.value = true
    }

    fun updateRule(index: Int, pattern: String, kind: String, action: String) {
        val type = when (kind) {
            "cidr" -> "cidr"
            "geosite" -> "geosite"
            "geoip" -> "geoip"
            else -> "domain"
        }
        ruleRepo.updateRule(index, Rule(type = type, kind = kind, pattern = pattern.trim().lowercase(), action = action))
        _hasUnappliedChanges.value = true
    }

    fun saveRule(index: Int?, rule: Rule) {
        if (index == null || index < 0) {
            ruleRepo.addRule(rule)
        } else {
            ruleRepo.updateRule(index, rule)
        }
        _hasUnappliedChanges.value = true
    }

    fun toggleRuleEnabled(index: Int): Boolean {
        val res = ruleRepo.toggleRuleEnabled(index)
        _hasUnappliedChanges.value = true
        return res
    }

    fun deleteRule(index: Int) {
        ruleRepo.removeRule(index)
        _hasUnappliedChanges.value = true
    }

    fun moveRule(from: Int, to: Int) {
        if (from != to) {
            ruleRepo.moveRule(from, to)
            _hasUnappliedChanges.value = true
        }
    }

    fun setDefaultAction(action: String) {
        if (ruleRepo.defaultAction.value != action) {
            ruleRepo.setDefaultAction(action)
            _hasUnappliedChanges.value = true
        }
    }

    fun applyPresetTemplate(templateId: Int): Boolean {
        val ok = ruleRepo.applyPresetTemplate(templateId)
        if (ok) {
            _hasUnappliedChanges.value = false
        }
        return ok
    }

    fun applyRules(): Boolean {
        val ok = ruleRepo.applyRules()
        if (ok) {
            _hasUnappliedChanges.value = false
        }
        return ok
    }

    fun refreshBuiltin() {
        ruleRepo.refreshBuiltin()
    }
}
