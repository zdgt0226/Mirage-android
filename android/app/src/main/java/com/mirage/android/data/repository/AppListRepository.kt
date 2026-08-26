package com.mirage.android.data.repository

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import com.mirage.android.data.model.AppInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AppListRepository(private val context: Context) {

    private val iconCache = mutableMapOf<String, Drawable>()

    suspend fun getInstalledApps(selectedSet: Set<String>): List<AppInfo> = withContext(Dispatchers.IO) {
        val pm = context.packageManager
        val apps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
        val selfPkg = context.packageName

        apps.filter { it.packageName != selfPkg }
            .map { appInfo ->
                val isSystem = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                val label = pm.getApplicationLabel(appInfo).toString()
                val pkg = appInfo.packageName
                AppInfo(
                    name = label,
                    packageName = pkg,
                    isSystemApp = isSystem,
                    isSelected = selectedSet.contains(pkg)
                )
            }
            .sortedWith(compareBy<AppInfo> { if (it.isSystemApp) 1 else 0 }
                .thenByDescending { it.isSelected }
                .thenBy { it.name.lowercase() })
    }

    fun getAppIcon(packageName: String): Drawable? {
        return iconCache.getOrPut(packageName) {
            runCatching {
                context.packageManager.getApplicationIcon(packageName)
            }.getOrNull() ?: context.packageManager.defaultActivityIcon
        }
    }
}
