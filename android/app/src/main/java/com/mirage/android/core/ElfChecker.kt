package com.mirage.android.core

import android.os.Build
import java.io.File
import java.io.RandomAccessFile

/**
 * ELF 二进制架构校验器 (读取 .so 文件头 e_machine 字段)。
 */
object ElfChecker {

    private const val ELF_MAGIC_0 = 0x7F.toByte()
    private const val ELF_MAGIC_1 = 'E'.code.toByte()
    private const val ELF_MAGIC_2 = 'L'.code.toByte()
    private const val ELF_MAGIC_3 = 'F'.code.toByte()

    /**
     * 检查 .so 文件架构。
     * 返回对应 Android ABI 字符串 (如 "arm64-v8a", "x86_64", "armeabi-v7a", "x86")。
     */
    fun checkAbi(file: File): Result<String> {
        if (!file.exists() || file.length() < 52) {
            return Result.failure(IllegalArgumentException("文件不存在或大小异常"))
        }

        return try {
            RandomAccessFile(file, "r").use { raf ->
                val magic = ByteArray(4)
                raf.readFully(magic)
                if (magic[0] != ELF_MAGIC_0 || magic[1] != ELF_MAGIC_1 || magic[2] != ELF_MAGIC_2 || magic[3] != ELF_MAGIC_3) {
                    return Result.failure(IllegalArgumentException("不是合法的 ELF/SO 原生库文件 (Magic 不匹配)"))
                }

                // 读 ELF 编码方式 (byte 5: 1=Little Endian, 2=Big Endian)
                raf.seek(5)
                val isLittleEndian = raf.read() == 1

                // 读 e_machine 字段 (偏移 18)
                raf.seek(18)
                val b0 = raf.read()
                val b1 = raf.read()
                val eMachine = if (isLittleEndian) {
                    (b1 shl 8) or b0
                } else {
                    (b0 shl 8) or b1
                }

                val abi = when (eMachine) {
                    183 -> "arm64-v8a"   // EM_AARCH64
                    40 -> "armeabi-v7a"   // EM_ARM
                    62 -> "x86_64"        // EM_X86_64
                    3 -> "x86"            // EM_386
                    else -> "unknown ($eMachine)"
                }

                Result.success(abi)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * 检查该 ABI 是否能在当前 Android 设备上运行。
     */
    fun isDeviceCompatible(abi: String): Boolean {
        return Build.SUPPORTED_ABIS.any { it.equals(abi, ignoreCase = true) }
    }

    // ---- .dynsym 符号表解析 (用于自定义内核的 JNI 符号集门禁) ----
    //
    // 输入是用户提供的任意文件, 必须当成不可信数据: 每个偏移/长度都先夹到文件范围内,
    // 并对节数量、符号数量、字符串表大小设上限, 避免恶意头部导致 OOM 或长循环。

    private const val SHT_DYNSYM = 11
    private const val MAX_SECTIONS = 4096
    private const val MAX_SYMBOLS = 200_000
    private const val MAX_STRTAB_BYTES = 8 * 1024 * 1024

    /**
     * 读取 .so 动态符号表里以 [prefix] 开头的符号名。
     *
     * 只读 .dynsym (动态符号表) —— `strip` 不会移除它, 导出函数名一定在其中;
     * 相比扫描整个文件找字符串, 不会把 .rodata 里偶然同形的字面量误当成导出符号。
     *
     * 失败一律返回 failure, **调用方必须按拒绝处理**: 解析不出符号表不等于"没有缺符号"。
     */
    fun exportedSymbols(file: File, prefix: String): Result<Set<String>> {
        if (!file.exists()) return Result.failure(IllegalArgumentException("文件不存在"))
        val fileLen = file.length()
        if (fileLen < 64) return Result.failure(IllegalArgumentException("文件过小, 不是合法 ELF"))

        return try {
            RandomAccessFile(file, "r").use { raf ->
                val ident = ByteArray(16)
                raf.readFully(ident)
                if (ident[0] != ELF_MAGIC_0 || ident[1] != ELF_MAGIC_1 ||
                    ident[2] != ELF_MAGIC_2 || ident[3] != ELF_MAGIC_3
                ) {
                    return Result.failure(IllegalArgumentException("不是合法的 ELF/SO 原生库文件 (Magic 不匹配)"))
                }
                val is64 = when (ident[4].toInt()) {
                    2 -> true
                    1 -> false
                    else -> return Result.failure(IllegalArgumentException("未知 ELF 位宽 (EI_CLASS=${ident[4].toInt()})"))
                }
                val little = when (ident[5].toInt()) {
                    1 -> true
                    2 -> false
                    else -> return Result.failure(IllegalArgumentException("未知 ELF 字节序 (EI_DATA=${ident[5].toInt()})"))
                }

                // e_shoff / e_shentsize / e_shnum
                val shOff: Long
                val shEntSize: Int
                val shNum: Int
                if (is64) {
                    shOff = raf.readAt(0x28, 8, little)
                    shEntSize = raf.readAt(0x3A, 2, little).toInt()
                    shNum = raf.readAt(0x3C, 2, little).toInt()
                } else {
                    shOff = raf.readAt(0x20, 4, little)
                    shEntSize = raf.readAt(0x2E, 2, little).toInt()
                    shNum = raf.readAt(0x30, 2, little).toInt()
                }

                val minEntSize = if (is64) 64 else 40
                if (shOff <= 0L || shNum <= 0 || shEntSize < minEntSize) {
                    return Result.failure(IllegalArgumentException("节头表缺失或不合法 (该 .so 可能被过度裁剪)"))
                }
                if (shNum > MAX_SECTIONS) {
                    return Result.failure(IllegalArgumentException("节头数量异常 ($shNum)"))
                }
                if (shOff + shNum.toLong() * shEntSize > fileLen) {
                    return Result.failure(IllegalArgumentException("节头表越界, 文件可能被截断"))
                }

                // 找 .dynsym; 它的 sh_link 指向配套的字符串表
                var symOff = -1L
                var symSize = -1L
                var symEnt = -1L
                var strIdx = -1
                for (i in 0 until shNum) {
                    val base = shOff + i.toLong() * shEntSize
                    val shType = raf.readAt(base + 4, 4, little).toInt()
                    if (shType != SHT_DYNSYM) continue
                    if (is64) {
                        symOff = raf.readAt(base + 0x18, 8, little)
                        symSize = raf.readAt(base + 0x20, 8, little)
                        strIdx = raf.readAt(base + 0x28, 4, little).toInt()
                        symEnt = raf.readAt(base + 0x38, 8, little)
                    } else {
                        symOff = raf.readAt(base + 0x10, 4, little)
                        symSize = raf.readAt(base + 0x14, 4, little)
                        strIdx = raf.readAt(base + 0x18, 4, little).toInt()
                        symEnt = raf.readAt(base + 0x24, 4, little)
                    }
                    break
                }

                val symEntExpected = (if (is64) 24 else 16).toLong()
                if (symOff < 0L) {
                    return Result.failure(IllegalArgumentException("该 .so 没有动态符号表 (.dynsym), 无法校验接口面"))
                }
                if (symEnt != symEntExpected) {
                    return Result.failure(IllegalArgumentException("动态符号表项大小异常 ($symEnt)"))
                }
                if (symSize <= 0L || symOff + symSize > fileLen) {
                    return Result.failure(IllegalArgumentException("动态符号表越界, 文件可能被截断"))
                }
                if (strIdx <= 0 || strIdx >= shNum) {
                    return Result.failure(IllegalArgumentException("动态符号表未关联字符串表"))
                }

                val strBase = shOff + strIdx.toLong() * shEntSize
                val strOff: Long
                val strSize: Long
                if (is64) {
                    strOff = raf.readAt(strBase + 0x18, 8, little)
                    strSize = raf.readAt(strBase + 0x20, 8, little)
                } else {
                    strOff = raf.readAt(strBase + 0x10, 4, little)
                    strSize = raf.readAt(strBase + 0x14, 4, little)
                }
                if (strSize <= 0L || strOff + strSize > fileLen) {
                    return Result.failure(IllegalArgumentException("字符串表越界, 文件可能被截断"))
                }
                if (strSize > MAX_STRTAB_BYTES) {
                    return Result.failure(IllegalArgumentException("字符串表过大 ($strSize 字节)"))
                }

                val symCount = symSize / symEntExpected
                if (symCount > MAX_SYMBOLS) {
                    return Result.failure(IllegalArgumentException("符号数量异常 ($symCount)"))
                }

                val strtab = ByteArray(strSize.toInt())
                raf.seek(strOff)
                raf.readFully(strtab)

                val prefixBytes = prefix.toByteArray(Charsets.US_ASCII)
                val out = HashSet<String>()
                for (i in 0 until symCount) {
                    // st_name 是两种位宽下的首个字段, 偏移都为 0
                    val nameOff = raf.readAt(symOff + i * symEntExpected, 4, little)
                    if (nameOff <= 0L || nameOff >= strSize) continue
                    val start = nameOff.toInt()
                    if (!startsWithAt(strtab, start, prefixBytes)) continue
                    var end = start
                    while (end < strtab.size && strtab[end] != 0.toByte()) end++
                    out.add(String(strtab, start, end - start, Charsets.US_ASCII))
                }
                Result.success(out)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** 在 [buf] 的 [at] 处是否恰好以 [prefix] 开头 (不越界)。 */
    private fun startsWithAt(buf: ByteArray, at: Int, prefix: ByteArray): Boolean {
        if (at < 0 || at + prefix.size > buf.size) return false
        for (i in prefix.indices) if (buf[at + i] != prefix[i]) return false
        return true
    }

    /** 从 [pos] 读 [width] 字节的无符号小端/大端整数。 */
    private fun RandomAccessFile.readAt(pos: Long, width: Int, little: Boolean): Long {
        seek(pos)
        val b = ByteArray(width)
        readFully(b)
        var v = 0L
        if (little) {
            for (i in width - 1 downTo 0) v = (v shl 8) or (b[i].toLong() and 0xFF)
        } else {
            for (i in 0 until width) v = (v shl 8) or (b[i].toLong() and 0xFF)
        }
        return v
    }
}
