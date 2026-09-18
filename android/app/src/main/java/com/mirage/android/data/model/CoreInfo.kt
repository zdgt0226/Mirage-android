package com.mirage.android.data.model

import java.io.File

/**
 * Mirage 内核描述模型。
 */
data class CoreInfo(
    val id: String,
    val name: String,
    val version: String,
    val abi: String,
    val filePath: String? = null,
    val fileSize: Long = 0L,
    val sha256: String? = null,
    val isBuiltin: Boolean = false,
    val addedTime: Long = System.currentTimeMillis()
) {
    val file: File? get() = filePath?.let { File(it) }

    val formattedSize: String get() {
        // i18n-exempt: 内置内核的标识串, 同时进 JSON 持久化; 随语言变化会破坏已存记录
        if (isBuiltin || fileSize <= 0) return "内置 (ROM)"
        return when {
            fileSize >= 1 shl 20 -> "%.2f MB".format(fileSize.toDouble() / (1 shl 20))
            fileSize >= 1 shl 10 -> "%.1f KB".format(fileSize.toDouble() / (1 shl 10))
            else -> "$fileSize B"
        }
    }

    val shortSha256: String? get() = sha256?.take(10)

    companion object {
        const val BUILTIN_ID = "builtin"

        // i18n-exempt: 同上, builtin() 的这几个字段是持久化标识而非界面文案
        fun builtin(version: String = "v0.10.4 (内置)"): CoreInfo {
            return CoreInfo(
                id = BUILTIN_ID,
                name = "内置默认内核", // i18n-exempt
                version = version,
                abi = "系统原生", // i18n-exempt
                filePath = null,
                sha256 = null,
                isBuiltin = true
            )
        }
    }
}

/**
 * GitHub Releases 在线内核发布版本信息。
 */
data class OnlineReleaseInfo(
    val tagName: String,
    val name: String,
    val body: String,
    val publishedAt: String,
    val assetName: String,
    val downloadUrl: String,
    val sizeBytes: Long,
    val targetAbi: String,
    val expectedSha256: String? = null
) {
    val formattedSize: String get() {
        return when {
            sizeBytes >= 1 shl 20 -> "%.2f MB".format(sizeBytes.toDouble() / (1 shl 20))
            sizeBytes >= 1 shl 10 -> "%.1f KB".format(sizeBytes.toDouble() / (1 shl 10))
            else -> "$sizeBytes B"
        }
    }

    val shortDigest: String? get() = expectedSha256?.take(12)
}
