package com.mirage.android

import com.mirage.android.core.ElfChecker
import com.mirage.android.core.NativeSymbolPolicy
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 自定义内核 JNI 符号集门禁回归测试。
 *
 * 为什么要自己造 ELF: 真机 .so 有 3MB 且 jniLibs 被 gitignore, 不能进仓库。
 * 测试里现搭最小合法 ELF (含 .dynsym + .dynstr), 门禁因此永不空洞 ——
 * 解析器真的在解析字节, 而不是靠某个恰好存在的产物。
 */
class NativeSymbolGateTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val prefix = NativeSymbolPolicy.JNI_PREFIX

    // ---- 纯逻辑 ----

    @Test
    fun requiredSymbolsPrefixesEveryMethod() {
        val req = NativeSymbolPolicy.requiredSymbols(listOf("start", "stop"))
        assertEquals(setOf("${prefix}start", "${prefix}stop"), req)
    }

    @Test
    fun missingSymbolsIsSortedDifference() {
        val required = setOf("${prefix}a", "${prefix}b", "${prefix}c")
        val exported = setOf("${prefix}b")
        assertEquals(listOf("${prefix}a", "${prefix}c"), NativeSymbolPolicy.missingSymbols(required, exported))
    }

    @Test
    fun noMissingWhenExportedIsSuperset() {
        val required = setOf("${prefix}a")
        val exported = setOf("${prefix}a", "${prefix}zzz_extra")
        assertTrue(NativeSymbolPolicy.missingSymbols(required, exported).isEmpty())
    }

    // ---- 权威来源: 反射 MirageNative ----

    @Test
    fun declaredNativeMethodsAreReadableAndMangleIdentically() {
        val names = NativeSymbolPolicy
            .declaredNativeMethodNames(javaClass.classLoader!!)
            .getOrThrow()
        assertTrue("必须能读出 native 方法清单, 否则门禁只会拒绝一切", names.isNotEmpty())
        assertTrue("start 应在清单内", names.contains("start"))
        assertTrue("P1 下沉接口应在清单内", names.contains("setNodeList"))
        // JNI_PREFIX 的恒等修饰假设: 方法名一旦含下划线 (修饰为 _1) 或出现重载 (追加 __签名),
        // 符号名就不再等于 前缀+方法名, 必须同步改 NativeSymbolPolicy。
        val withUnderscore = names.filter { it.contains('_') }
        assertTrue("方法名含下划线会破坏恒等修饰假设: $withUnderscore", withUnderscore.isEmpty())
    }

    // ---- ELF 解析 ----

    @Test
    fun elf64ExportsAreParsed() {
        val syms = listOf("${prefix}start", "${prefix}stop", "unrelated_symbol")
        val f = writeElf(tmp.newFile("ok64.so"), syms, is64 = true)
        val got = ElfChecker.exportedSymbols(f, prefix).getOrThrow()
        assertEquals(setOf("${prefix}start", "${prefix}stop"), got)
    }

    @Test
    fun elf32ExportsAreParsed() {
        val syms = listOf("${prefix}start", "${prefix}stop")
        val f = writeElf(tmp.newFile("ok32.so"), syms, is64 = false)
        val got = ElfChecker.exportedSymbols(f, prefix).getOrThrow()
        assertEquals(setOf("${prefix}start", "${prefix}stop"), got)
    }

    /** 复刻 2026-11-03 实测: 上游产物比本版 App 少了三个下沉接口。 */
    @Test
    fun staleUpstreamCoreIsDetectedAsMissingExactlyThree() {
        val appMethods = listOf(
            "start", "stop", "isRunning", "setNodeList", "setFailoverPolicy", "onNetworkChanged"
        )
        val staleExports = listOf("start", "stop", "isRunning").map { prefix + it }
        val f = writeElf(tmp.newFile("stale.so"), staleExports, is64 = true)

        val exported = ElfChecker.exportedSymbols(f, prefix).getOrThrow()
        val required = NativeSymbolPolicy.requiredSymbols(appMethods)
        val missing = NativeSymbolPolicy.missingSymbols(required, exported)

        assertEquals(
            listOf("${prefix}onNetworkChanged", "${prefix}setFailoverPolicy", "${prefix}setNodeList"),
            missing
        )
        val msg = NativeSymbolPolicy.rejectionMessage(missing, exported.size, required.size)
        assertTrue("拒绝理由须点名缺失方法", msg.contains("setNodeList"))
        assertFalse("拒绝理由不应带 JNI 前缀噪音", msg.contains(prefix))
    }

    @Test
    fun symbolContainingPrefixMidStringIsNotMatched() {
        val f = writeElf(tmp.newFile("mid.so"), listOf("not_a_${prefix}start"), is64 = true)
        assertTrue(ElfChecker.exportedSymbols(f, prefix).getOrThrow().isEmpty())
    }

    @Test
    fun undefinedAndLocalSymbolsAreIgnored() {
        val f = writeElf(
            tmp.newFile("undef_local.so"),
            listOf("${prefix}start", "${prefix}stop", "${prefix}isRunning"),
            is64 = true,
            symbolShndx = mapOf("${prefix}start" to 0),       // SHN_UNDEF (imported, not exported)
            symbolInfo = mapOf("${prefix}stop" to 0x02)        // STB_LOCAL | STT_FUNC
        )
        val got = ElfChecker.exportedSymbols(f, prefix).getOrThrow()
        assertEquals(setOf("${prefix}isRunning"), got)
    }

    @Test
    fun signedLongOverflowInSectionOffsetsFailsClosed() {
        val f = writeElf(tmp.newFile("overflow.so"), listOf("${prefix}start"), is64 = true)
        val bytes = f.readBytes()
        // 将 .dynsym 节头中的 sh_offset 篡改为 Long.MAX_VALUE - 8，使 sh_offset + sh_size 发生有符号 Long 溢出变为负数
        val shOff = 64 + 2 * 24 + (1 + "${prefix}start".length + 1)
        val sh1 = shOff + 64
        putLong(bytes, sh1 + 0x18, Long.MAX_VALUE - 8L)
        f.writeBytes(bytes)
        assertTrue("sh_offset + sh_size 溢出必须失败", ElfChecker.exportedSymbols(f, prefix).isFailure)
    }

    // ---- fail-closed: 解析不出来必须 failure, 不能当成"没缺符号" ----

    @Test
    fun missingDynsymSectionFails() {
        val f = writeElf(tmp.newFile("nodynsym.so"), listOf("${prefix}start"), is64 = true, omitDynsym = true)
        val r = ElfChecker.exportedSymbols(f, prefix)
        assertTrue("没有 .dynsym 必须失败而不是返回空集", r.isFailure)
    }

    @Test
    fun truncatedFileFails() {
        val full = writeElf(tmp.newFile("trunc_src.so"), listOf("${prefix}start"), is64 = true)
        val cut = tmp.newFile("trunc.so")
        cut.writeBytes(full.readBytes().copyOfRange(0, 80))
        assertTrue(ElfChecker.exportedSymbols(cut, prefix).isFailure)
    }

    @Test
    fun nonElfFails() {
        val f = tmp.newFile("garbage.so")
        f.writeBytes(ByteArray(256) { 0x41 })
        assertTrue(ElfChecker.exportedSymbols(f, prefix).isFailure)
    }

    @Test
    fun emptyFileFails() {
        assertTrue(ElfChecker.exportedSymbols(tmp.newFile("empty.so"), prefix).isFailure)
    }

    @Test
    fun absentFileFails() {
        assertTrue(ElfChecker.exportedSymbols(File(tmp.root, "nope.so"), prefix).isFailure)
    }

    // ---- 组合校验 verify(): 真实方法清单 + 合成 .so ----

    @Test
    fun verifyPassesWhenSoExportsEveryDeclaredMethod() {
        val methods = NativeSymbolPolicy
            .declaredNativeMethodNames(javaClass.classLoader!!)
            .getOrThrow()
        val f = writeElf(tmp.newFile("full.so"), methods.map { prefix + it }, is64 = true)
        assertNull(
            "导出齐全的内核必须放行",
            NativeSymbolPolicy.verify(f, javaClass.classLoader)
        )
    }

    @Test
    fun verifyRejectsAndNamesTheMissingMethod() {
        val methods = NativeSymbolPolicy
            .declaredNativeMethodNames(javaClass.classLoader!!)
            .getOrThrow()
        val dropped = methods.first { it == "setNodeList" }
        val f = writeElf(
            tmp.newFile("partial.so"),
            methods.filter { it != dropped }.map { prefix + it },
            is64 = true
        )
        val reason = NativeSymbolPolicy.verify(f, javaClass.classLoader)
        assertNotNull("缺符号的内核必须被拒绝", reason)
        assertTrue("拒绝理由须点名 $dropped", reason!!.contains(dropped))
    }

    @Test
    fun verifyFailsClosedOnUnparsableFile() {
        val f = tmp.newFile("bad.so")
        f.writeBytes(ByteArray(128) { 0x7A })
        assertNotNull("解析失败必须拒绝, 不能当成没缺符号", NativeSymbolPolicy.verify(f, javaClass.classLoader))
    }

    @Test
    fun verifyFailsClosedWithoutClassLoader() {
        val f = writeElf(tmp.newFile("noloader.so"), listOf("${prefix}start"), is64 = true)
        assertNotNull(NativeSymbolPolicy.verify(f, null))
    }

    // ---- 最小 ELF 构造器 ----

    /**
     * 造一个只含 .dynsym + .dynstr 的最小合法 ELF。
     * 布局: ELF 头 | dynsym | dynstr | 节头表(null/.dynsym/.dynstr)
     */
    private fun writeElf(
        file: File,
        symbolNames: List<String>,
        is64: Boolean,
        omitDynsym: Boolean = false,
        symbolShndx: Map<String, Int> = emptyMap(),
        symbolInfo: Map<String, Int> = emptyMap(),
    ): File {
        val ehSize = if (is64) 64 else 52
        val shEntSize = if (is64) 64 else 40
        val symEntSize = if (is64) 24 else 16

        // dynstr: 索引 0 固定为空名
        val strBytes = java.io.ByteArrayOutputStream()
        strBytes.write(0)
        val offsets = LinkedHashMap<String, Int>()
        for (n in symbolNames) {
            offsets[n] = strBytes.size()
            strBytes.write(n.toByteArray(Charsets.US_ASCII))
            strBytes.write(0)
        }
        val dynstr = strBytes.toByteArray()

        // dynsym: 第 0 项按 ELF 规范为全零项
        val symCount = symbolNames.size + 1
        val dynsym = ByteArray(symCount * symEntSize)
        for ((i, n) in symbolNames.withIndex()) {
            val base = (i + 1) * symEntSize
            putInt(dynsym, base, offsets.getValue(n))   // st_name 在两种位宽下偏移都是 0
            val info = symbolInfo[n] ?: 0x12            // 默认 GLOBAL|FUNC
            val shndx = symbolShndx[n] ?: 1             // 默认已定义节索引 1
            dynsym[base + (if (is64) 4 else 12)] = info.toByte()
            putShort(dynsym, base + (if (is64) 6 else 14), shndx)
        }

        val symOff = ehSize
        val strOff = symOff + dynsym.size
        val shOff = strOff + dynstr.size
        val shNum = 3

        val out = ByteArray(shOff + shNum * shEntSize)

        // ELF 头
        out[0] = 0x7F; out[1] = 'E'.code.toByte(); out[2] = 'L'.code.toByte(); out[3] = 'F'.code.toByte()
        out[4] = if (is64) 2 else 1   // EI_CLASS
        out[5] = 1                    // EI_DATA = little endian
        out[6] = 1                    // EI_VERSION
        putShort(out, 0x10, 3)                              // e_type = ET_DYN
        putShort(out, 0x12, if (is64) 183 else 40)          // e_machine
        putInt(out, 0x14, 1)                                // e_version
        if (is64) {
            putLong(out, 0x28, shOff.toLong())
            putShort(out, 0x34, ehSize)
            putShort(out, 0x3A, shEntSize)
            putShort(out, 0x3C, shNum)
            putShort(out, 0x3E, 2)
        } else {
            putInt(out, 0x20, shOff)
            putShort(out, 0x28, ehSize)
            putShort(out, 0x2E, shEntSize)
            putShort(out, 0x30, shNum)
            putShort(out, 0x32, 2)
        }

        dynsym.copyInto(out, symOff)
        dynstr.copyInto(out, strOff)

        // 节头 [1] = .dynsym (omitDynsym 时把类型改成 PROGBITS, 让解析器找不到符号表)
        val sh1 = shOff + shEntSize
        putInt(out, sh1 + 4, if (omitDynsym) 1 else 11)     // sh_type
        if (is64) {
            putLong(out, sh1 + 0x18, symOff.toLong())
            putLong(out, sh1 + 0x20, dynsym.size.toLong())
            putInt(out, sh1 + 0x28, 2)                      // sh_link -> dynstr
            putLong(out, sh1 + 0x38, symEntSize.toLong())
        } else {
            putInt(out, sh1 + 0x10, symOff)
            putInt(out, sh1 + 0x14, dynsym.size)
            putInt(out, sh1 + 0x18, 2)
            putInt(out, sh1 + 0x24, symEntSize)
        }

        // 节头 [2] = .dynstr
        val sh2 = shOff + 2 * shEntSize
        putInt(out, sh2 + 4, 3)                             // SHT_STRTAB
        if (is64) {
            putLong(out, sh2 + 0x18, strOff.toLong())
            putLong(out, sh2 + 0x20, dynstr.size.toLong())
        } else {
            putInt(out, sh2 + 0x10, strOff)
            putInt(out, sh2 + 0x14, dynstr.size)
        }

        file.writeBytes(out)
        return file
    }

    private fun putShort(b: ByteArray, at: Int, v: Int) {
        b[at] = (v and 0xFF).toByte()
        b[at + 1] = ((v shr 8) and 0xFF).toByte()
    }

    private fun putInt(b: ByteArray, at: Int, v: Int) {
        for (i in 0 until 4) b[at + i] = ((v shr (8 * i)) and 0xFF).toByte()
    }

    private fun putLong(b: ByteArray, at: Int, v: Long) {
        for (i in 0 until 8) b[at + i] = ((v shr (8 * i)) and 0xFF).toByte()
    }
}
