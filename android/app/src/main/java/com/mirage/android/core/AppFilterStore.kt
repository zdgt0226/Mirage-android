package com.mirage.android.core

import android.content.Context
import com.mirage.android.data.model.AppFilterConfig
import com.mirage.android.data.model.AppFilterMode
import org.json.JSONArray
import org.json.JSONObject

object AppFilterStore {
    private const val PREFS = "mirage_app_filter"
    private const val KEY_ENABLED = "filter_enabled"
    private const val KEY_MODE = "filter_mode"
    private const val KEY_PACKAGES = "filter_packages"

    @Synchronized
    fun getConfig(ctx: Context): AppFilterConfig {
        val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val enabled = sp.getBoolean(KEY_ENABLED, false)
        val modeStr = sp.getString(KEY_MODE, AppFilterMode.DISALLOW.name) ?: AppFilterMode.DISALLOW.name
        val mode = runCatching { AppFilterMode.valueOf(modeStr) }.getOrDefault(AppFilterMode.DISALLOW)
        val jsonStr = sp.getString(KEY_PACKAGES, null)
        val set = mutableSetOf<String>()
        if (jsonStr != null) {
            runCatching {
                val array = JSONArray(jsonStr)
                for (i in 0 until array.length()) {
                    set.add(array.getString(i))
                }
            }
        }
        return AppFilterConfig(enabled, mode, set)
    }

    @Synchronized
    fun saveConfig(ctx: Context, config: AppFilterConfig) {
        val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val array = JSONArray()
        config.selectedPackages.forEach { array.put(it) }
        sp.edit()
            .putBoolean(KEY_ENABLED, config.enabled)
            .putString(KEY_MODE, config.mode.name)
            .putString(KEY_PACKAGES, array.toString())
            .apply()
    }

    fun toJson(config: AppFilterConfig): String {
        val obj = JSONObject()
        obj.put("enabled", config.enabled)
        obj.put("mode", config.mode.name)
        val array = JSONArray()
        config.selectedPackages.forEach { array.put(it) }
        obj.put("packages", array)
        return obj.toString()
    }

    fun fromJson(json: String): AppFilterConfig {
        return runCatching {
            val obj = JSONObject(json)
            val enabled = obj.optBoolean("enabled", false)
            val modeStr = obj.optString("mode", AppFilterMode.DISALLOW.name)
            val mode = runCatching { AppFilterMode.valueOf(modeStr) }.getOrDefault(AppFilterMode.DISALLOW)
            val array = obj.optJSONArray("packages")
            val set = mutableSetOf<String>()
            if (array != null) {
                for (i in 0 until array.length()) {
                    set.add(array.getString(i))
                }
            }
            AppFilterConfig(enabled, mode, set)
        }.getOrDefault(AppFilterConfig(false, AppFilterMode.DISALLOW, emptySet()))
    }
}
