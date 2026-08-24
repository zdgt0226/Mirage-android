package com.mirage.android.data.repository

import android.content.Context
import com.mirage.android.core.CoreController
import com.mirage.android.core.RuleStore
import com.mirage.android.data.model.Rule
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 路由规则仓库 (对齐 RuleStore 与 CoreController)。
 */
class RuleRepository(private val context: Context) {

    private val _rules = MutableStateFlow<List<Rule>>(emptyList())
    val rules: StateFlow<List<Rule>> = _rules.asStateFlow()

    private val _defaultAction = MutableStateFlow("proxy")
    val defaultAction: StateFlow<String> = _defaultAction.asStateFlow()

    private val _builtinDomains = MutableStateFlow<List<String>>(emptyList())
    val builtinDomains: StateFlow<List<String>> = _builtinDomains.asStateFlow()

    private val _builtinIpCount = MutableStateFlow(0L)
    val builtinIpCount: StateFlow<Long> = _builtinIpCount.asStateFlow()

    init {
        loadData()
    }

    fun reload() {
        loadData()
    }

    private fun loadData() {
        _rules.value = RuleStore.getRules(context)
        _defaultAction.value = RuleStore.getDefaultAction(context)
    }

    fun applyHits(hitsMap: Map<String, Long>) {
        _rules.value = _rules.value.map { r ->
            val key = "${r.id}|${r.displayName}|${r.action}"
            val altKey = "${r.kind}|${r.pattern}|${r.action}"
            val hits = hitsMap[key] ?: hitsMap[altKey] ?: 0L
            r.copy(hits = hits)
        }
    }

    fun resetRuleHits() {
        _rules.value = _rules.value.map { it.copy(hits = 0L) }
        CoreController.resetRuleHits()
    }

    fun addRule(rule: Rule) {
        RuleStore.addRule(context, rule)
        loadData()
    }

    fun updateRule(index: Int, rule: Rule) {
        RuleStore.updateRule(context, index, rule)
        loadData()
    }

    fun toggleRuleEnabled(index: Int): Boolean {
        val result = RuleStore.toggleRuleEnabled(context, index)
        loadData()
        return result
    }

    fun removeRule(index: Int) {
        RuleStore.removeRule(context, index)
        loadData()
    }

    fun moveRule(from: Int, to: Int) {
        RuleStore.moveRule(context, from, to)
        loadData()
    }

    fun setDefaultAction(action: String) {
        RuleStore.setDefaultAction(context, action)
        _defaultAction.value = action
    }

    fun applyRules(): Boolean {
        val json = RuleStore.toJson(context)
        return CoreController.setRules(json)
    }

    fun refreshBuiltin() {
        _builtinDomains.value = emptyList()
        _builtinIpCount.value = 0L
    }

    companion object {
        @Volatile
        private var instance: RuleRepository? = null

        fun getInstance(context: Context): RuleRepository =
            instance ?: synchronized(this) {
                instance ?: RuleRepository(context.applicationContext).also { instance = it }
            }
    }
}
