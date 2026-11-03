package com.mirage.android

import com.mirage.android.CoreService.NativeGuard
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CoreService 原生库调用守卫回归测试。
 *
 * 测试目标：
 * APK 缺 libmirage_jni.so (或内核加载失败) 时，UI 通过 Binder 调用的任何方法
 * 绝不能触达 MirageNative.*，必须安全短路返回默认值，防止抛出 UnsatisfiedLinkError
 * 导致 :core 进程崩溃。
 */
class CoreServiceNativeGuardTest {

    @Test
    fun shouldDispatchNativeOnlyWhenLoaded() {
        assertFalse(NativeGuard.shouldDispatchNative(isLoaded = false))
        assertTrue(NativeGuard.shouldDispatchNative(isLoaded = true))
    }

    @Test
    fun unloaderSkipsExecutionAndReturnsSafeDefaults() {
        var executed = false

        // Boolean 默认值 (isRunning, isHealthy, clearDnsCache, resetRuleHits, clearNativeLogs, etc.)
        val boolRes = NativeGuard.runGuarded(isLoaded = false, default = false) {
            executed = true
            true
        }
        assertFalse("未加载时绝对不得执行原生调用 block", executed)
        assertFalse(boolRes)

        // Setter 成功默认值
        val setterRes = NativeGuard.runGuarded(isLoaded = false, default = true) {
            executed = true
            false
        }
        assertFalse(executed)
        assertTrue(setterRes)

        // Int 默认值 (closeAllConnections)
        val intRes = NativeGuard.runGuarded(isLoaded = false, default = 0) {
            executed = true
            10
        }
        assertFalse(executed)
        assertEquals(0, intRes)

        // Long 默认值 (latencyMs, testNode)
        val longRes = NativeGuard.runGuarded(isLoaded = false, default = -1L) {
            executed = true
            50L
        }
        assertFalse(executed)
        assertEquals(-1L, longRes)

        // Builtin IP count 默认值
        val ipCountRes = NativeGuard.runGuarded(isLoaded = false, default = 0L) {
            executed = true
            100L
        }
        assertFalse(executed)
        assertEquals(0L, ipCountRes)

        // String 默认值 (getRuleHits, getGeoTags, getDiagnosticSnapshotJson)
        val jsonRes = NativeGuard.runGuarded(isLoaded = false, default = "{}") {
            executed = true
            """{"rules": 1}"""
        }
        assertFalse(executed)
        assertEquals("{}", jsonRes)

        // Array JSON 默认值 (getConnectionsJson, getRecentRequestsJson)
        val arrJsonRes = NativeGuard.runGuarded(isLoaded = false, default = "[]") {
            executed = true
            """[{"id": 1}]"""
        }
        assertFalse(executed)
        assertEquals("[]", arrJsonRes)

        // Array 默认值 (getBuiltinDomains, native logs)
        val emptyArr = emptyArray<String>()
        val domainsRes = NativeGuard.runGuarded(isLoaded = false, default = emptyArr) {
            executed = true
            arrayOf("example.com")
        }
        assertFalse(executed)
        assertArrayEquals(emptyArr, domainsRes)

        // DoubleArray 默认值 (getStats)
        val emptyStats = DoubleArray(0)
        val statsRes = NativeGuard.runGuarded(isLoaded = false, default = emptyStats) {
            executed = true
            doubleArrayOf(1.0, 2.0, 3.0, 4.0, 5.0, 6.0, 7.0)
        }
        assertFalse(executed)
        assertArrayEquals(emptyStats, statsRes, 0.0)
    }

    @Test
    fun loadedExecutesBlockAndReturnsResult() {
        var callCount = 0
        val res = NativeGuard.runGuarded(isLoaded = true, default = false) {
            callCount++
            true
        }
        assertEquals(1, callCount)
        assertTrue(res)

        val stats = NativeGuard.runGuarded(isLoaded = true, default = DoubleArray(0)) {
            doubleArrayOf(100.0, 200.0)
        }
        assertArrayEquals(doubleArrayOf(100.0, 200.0), stats, 0.0)
    }

    @Test
    fun loadedCatchesUnsatisfiedLinkErrorSafely() {
        var executed = false
        val res = NativeGuard.runGuarded(isLoaded = true, default = false) {
            executed = true
            throw UnsatisfiedLinkError("No implementation found for boolean com.mirage.android.core.MirageNative.isRunning()")
        }
        assertTrue("已加载标记下应尝试执行", executed)
        assertFalse("抛出 UnsatisfiedLinkError 时必须兜底返回安全默认值，绝不能击穿 Binder 线程", res)
    }

    @Test
    fun loadedCatchesRuntimeExceptionSafely() {
        var executed = false
        val res = NativeGuard.runGuarded(isLoaded = true, default = -1L) {
            executed = true
            throw IllegalStateException("Native panic")
        }
        assertTrue(executed)
        assertEquals(-1L, res)
    }
}
