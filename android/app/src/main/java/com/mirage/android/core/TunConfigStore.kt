package com.mirage.android.core

import android.content.Context
import android.content.SharedPreferences

/**
 * TUN 接口与移动网络高级性能参数配置持久化存储
 */
object TunConfigStore {
    private const val PREFS_NAME = "mirage_tun_prefs"
    private const val KEY_MTU = "tun_mtu"
    private const val KEY_MSS_CLAMP = "tun_mss_clamp"
    private const val KEY_TCP_IDLE = "tun_tcp_idle_sec"
    private const val KEY_BATCH_SIZE = "tun_batch_size"
    private const val KEY_ENABLE_IPV6 = "tun_enable_ipv6"
    private const val KEY_BYPASS_LAN = "tun_bypass_lan"

    const val DEFAULT_MTU = 1400
    const val DEFAULT_TCP_IDLE_SEC = 300
    const val DEFAULT_BATCH_SIZE = 32
    const val DEFAULT_ENABLE_IPV6 = true
    const val DEFAULT_BYPASS_LAN = true

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun isBypassLanEnabled(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_BYPASS_LAN, DEFAULT_BYPASS_LAN)
    }

    fun setBypassLanEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_BYPASS_LAN, enabled).apply()
    }

    fun isIpv6Enabled(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_ENABLE_IPV6, DEFAULT_ENABLE_IPV6)
    }

    fun setIpv6Enabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLE_IPV6, enabled).apply()
    }

    fun getMtu(context: Context): Int {
        return prefs(context).getInt(KEY_MTU, DEFAULT_MTU).coerceIn(1280, 1500)
    }

    fun setMtu(context: Context, mtu: Int) {
        prefs(context).edit().putInt(KEY_MTU, mtu.coerceIn(1280, 1500)).apply()
    }

    fun isMssClampEnabled(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_MSS_CLAMP, true)
    }

    fun setMssClampEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_MSS_CLAMP, enabled).apply()
    }

    fun getTcpIdleTimeoutSec(context: Context): Int {
        return prefs(context).getInt(KEY_TCP_IDLE, DEFAULT_TCP_IDLE_SEC).coerceIn(60, 1800)
    }

    fun setTcpIdleTimeoutSec(context: Context, sec: Int) {
        prefs(context).edit().putInt(KEY_TCP_IDLE, sec.coerceIn(60, 1800)).apply()
    }

    fun getBatchSize(context: Context): Int {
        return prefs(context).getInt(KEY_BATCH_SIZE, DEFAULT_BATCH_SIZE).coerceIn(8, 64)
    }

    fun setBatchSize(context: Context, size: Int) {
        prefs(context).edit().putInt(KEY_BATCH_SIZE, size.coerceIn(8, 64)).apply()
    }
}
