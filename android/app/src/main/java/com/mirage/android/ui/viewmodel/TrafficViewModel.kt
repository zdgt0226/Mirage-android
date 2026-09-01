package com.mirage.android.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mirage.android.data.model.LogLevel
import com.mirage.android.data.model.TrafficStats
import com.mirage.android.data.repository.VpnRepository
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class TrafficViewModel(application: Application) : AndroidViewModel(application) {

    private val vpnRepo = VpnRepository.getInstance(application)

    val stats: StateFlow<TrafficStats> = vpnRepo.trafficStats
    val latencyMs: StateFlow<Long> = vpnRepo.latencyMs
    val connections: StateFlow<List<com.mirage.android.data.model.ConnectionInfo>> = vpnRepo.connections
    val recentRequests: StateFlow<List<com.mirage.android.data.model.RecentRequestInfo>> = vpnRepo.recentRequests
    val rawLogs: StateFlow<List<String>> = vpnRepo.logs

    private val _selectedLogLevel = MutableStateFlow(LogLevel.ALL)
    val selectedLogLevel: StateFlow<LogLevel> = _selectedLogLevel.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _protocolFilter = MutableStateFlow("ALL")
    val protocolFilter: StateFlow<String> = _protocolFilter.asStateFlow()

    private val _outboundFilter = MutableStateFlow("ALL")
    val outboundFilter: StateFlow<String> = _outboundFilter.asStateFlow()

    val filteredRecentRequests: StateFlow<List<com.mirage.android.data.model.RecentRequestInfo>> = combine(
        recentRequests,
        searchQuery,
        protocolFilter,
        outboundFilter
    ) { list, query, proto, outbound ->
        list.filter { item ->
            val matchesQuery = query.isBlank() || item.target.contains(query, ignoreCase = true) || item.resolvedIp.contains(query, ignoreCase = true)
            val matchesProto = proto == "ALL" || item.protocol.equals(proto, ignoreCase = true)
            val matchesOutbound = outbound == "ALL" || item.outbound.contains(outbound, ignoreCase = true)
            matchesQuery && matchesProto && matchesOutbound
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val filteredLogs: StateFlow<List<String>> = combine(rawLogs, selectedLogLevel) { logs, level ->
        if (level == LogLevel.ALL) logs else logs.filter { level.matches(it) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _historyUp = MutableStateFlow<List<Float>>(emptyList())
    val historyUp: StateFlow<List<Float>> = _historyUp.asStateFlow()

    private val _historyDown = MutableStateFlow<List<Float>>(emptyList())
    val historyDown: StateFlow<List<Float>> = _historyDown.asStateFlow()

    private val maxHistoryPoints = 60
    private val upList = ArrayDeque<Float>()
    private val downList = ArrayDeque<Float>()

    init {
        viewModelScope.launch {
            stats.collect { s ->
                upList.addLast(s.upRate.toFloat())
                downList.addLast(s.downRate.toFloat())
                while (upList.size > maxHistoryPoints) upList.removeFirst()
                while (downList.size > maxHistoryPoints) downList.removeFirst()
                _historyUp.value = upList.toList()
                _historyDown.value = downList.toList()
            }
        }
    }

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun setProtocolFilter(proto: String) {
        _protocolFilter.value = proto
    }

    fun setOutboundFilter(outbound: String) {
        _outboundFilter.value = outbound
    }

    fun setLogLevel(level: LogLevel) {
        _selectedLogLevel.value = level
        vpnRepo.setLogLevel(level)
    }

    fun clearLogs() {
        vpnRepo.clearLogs()
    }
}
