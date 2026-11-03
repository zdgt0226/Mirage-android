package com.mirage.android.core

import java.lang.reflect.Modifier

/**
 * 自定义内核的 JNI 符号集校验策略 (不碰 Android 框架, 可在 JVM 单测里直接跑)。
 *
 * 解决的缺陷:
 * 运行时换核 (NativeLoader 的自定义内核路径) 完全没有符号集校验 —— ElfChecker 只读
 * ELF 头的 e_machine。于是一个架构正确但 JNI 面偏旧的 .so 能顺利导入并 System.load 成功,
 * NativeLoader.isLoaded() 为真, 随后每次调用缺失方法都抛 UnsatisfiedLinkError, 被
 * CoreService.NativeGuard 的 runCatching 兜住并返回默认值 —— **静默降级**: 不崩溃,
 * 但下沉面 (setNodeList / setFailoverPolicy / onNetworkChanged) 整个失效, 只在日志留一行。
 *
 * CI 里的 "dex native count == .so export count" 门禁管的是打包进 APK 的 .so,
 * 覆盖不到运行时换核。本策略是它在运行时侧的对应物。
 *
 * 实测依据 (2026-11-03): 上游 Mirage-rs run 41558057174 的 mirage-jni-so 产物导出 43 个
 * JNI 符号, 而本仓库 MirageNative 声明 46 个, 缺 setNodeList / setFailoverPolicy /
 * onNetworkChanged (该 run 早于 PR #22 合并 25 分钟)。
 */
object NativeSymbolPolicy {

    /**
     * JNI 符号前缀。
     *
     * MirageNative 的方法名全部不含下划线且无重载, 因此 JNI 名称修饰是恒等的:
     * 符号名 == 前缀 + 方法名。一旦将来出现含 `_` 的方法名 (修饰为 `_1`) 或重载
     * (追加 `__` + 签名修饰), 本处必须同步调整, 否则门禁会把合法内核误判为缺符号。
     */
    const val JNI_PREFIX = "Java_com_mirage_android_core_MirageNative_"

    /** 本 APK 期望内核提供的全部 JNI 符号。 */
    fun requiredSymbols(methodNames: Collection<String>): Set<String> =
        methodNames.map { JNI_PREFIX + it }.toSet()

    /** 缺失的符号 (排序后返回, 便于稳定地展示与断言)。 */
    fun missingSymbols(required: Set<String>, exported: Set<String>): List<String> =
        required.filter { it !in exported }.sorted()

    /**
     * 从 Kotlin 侧反射出 MirageNative 的 native 方法名 —— 唯一权威来源, 不维护硬编码清单
     * (硬编码清单漂移正是 CI 门禁当初要治的那类缺陷)。
     *
     * 用 `initialize = false` 取 Class: 绝不触发 MirageNative 的 `init` 块,
     * 否则导入过程会顺手 System.loadLibrary 内置库, 与随后加载自定义内核相互干扰。
     */
    fun declaredNativeMethodNames(classLoader: ClassLoader): Result<List<String>> = runCatching {
        val cls = Class.forName("com.mirage.android.core.MirageNative", false, classLoader)
        cls.declaredMethods
            .filter { Modifier.isNative(it.modifiers) }
            .map { it.name }
            .distinct()
            .sorted()
    }

    /**
     * 组合校验: 该 .so 的 JNI 接口面是否覆盖本版 App 声明的全部 native 方法。
     *
     * 返回 null = 通过; 返回字符串 = 拒绝理由 (可直接展示给用户)。
     * **fail-closed**: 读不出期望清单、读不出符号表, 一律按拒绝处理 ——
     * 解析不出符号表不等于"没有缺符号"。
     */
    fun verify(file: java.io.File, classLoader: ClassLoader?): String? {
        val loader = classLoader ?: return "无法获取 ClassLoader，无法校验内核接口面。"

        val methods = declaredNativeMethodNames(loader).getOrElse { e ->
            return "无法反射 MirageNative 的 native 方法 (${e::class.java.simpleName})，拒绝使用未经校验的内核。"
        }
        if (methods.isEmpty()) {
            // R8 把 native 方法剥了, 或类名变了。此时门禁无从判断, 必须拒绝而不是放行。
            return "未能读出本版 App 需要的 native 方法清单，拒绝使用未经校验的内核。"
        }

        val required = requiredSymbols(methods)
        val exported = ElfChecker.exportedSymbols(file, JNI_PREFIX).getOrElse { e ->
            return "无法读取内核的符号表: ${e.message ?: e::class.java.simpleName}"
        }
        val missing = missingSymbols(required, exported)
        if (missing.isEmpty()) return null
        return rejectionMessage(missing, exported.size, required.size)
    }

    /** 人类可读的拒绝理由。 */
    fun rejectionMessage(missing: List<String>, exportedCount: Int, requiredCount: Int): String {
        val names = missing.map { it.removePrefix(JNI_PREFIX) }
        val shown = names.take(8).joinToString(", ")
        val more = if (names.size > 8) " 等 ${names.size} 项" else ""
        return "该内核的 JNI 接口面与当前 App 不匹配，缺少 ${names.size} 个方法：$shown$more\n" +
            "(内核导出 $exportedCount 个，本版 App 需要 $requiredCount 个)\n" +
            "请使用与当前 App 版本配套构建的内核。"
    }
}
