package com.mirage.android

import com.mirage.android.core.GeoManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Geo 完整性策略回归测试。
 *
 * 断言的是 `GeoManager.isBuiltinUrl` 这个生产判定函数本身，而不是在测试里
 * 重写一遍同样的字符串比较 —— 后者改坏生产代码也不会变红，等于没有防线。
 *
 * 这条判定决定了哪些下载源必须 fail-closed（拿不到 `.sha256sum` 就拒绝该镜像）。
 * 一旦有人把它放宽成域名前缀匹配，攻击者只要在同域名下放一个自定义源
 * 就能继承内置源的信任级别，同时又因自定义源允许无摘要放行而绕过校验。
 */
class GeoIntegrityPolicyTest {

    @Test
    fun builtinSourceUrlsRequireIntegrityCheck() {
        // 三个内置源的六个 URL 必须全部被判定为内置
        for (source in GeoManager.BUILTIN_SOURCES) {
            assertTrue(
                "内置源 ${source.id} 的 geosite URL 未被识别为内置，将绕过强制 SHA-256 校验",
                GeoManager.isBuiltinUrl(source.geositeUrl)
            )
            assertTrue(
                "内置源 ${source.id} 的 geoip URL 未被识别为内置，将绕过强制 SHA-256 校验",
                GeoManager.isBuiltinUrl(source.geoipUrl)
            )
        }
    }

    @Test
    fun builtinFallbackMirrorsRequireIntegrityCheck() {
        // updateGeoFiles 会无条件追加这两组回退镜像，它们同样属于内置信任域
        assertTrue(
            GeoManager.isBuiltinUrl(
                "https://fastly.jsdelivr.net/gh/Loyalsoldier/v2ray-rules-dat@release/geosite.dat"
            )
        )
        assertTrue(
            GeoManager.isBuiltinUrl(
                "https://raw.githubusercontent.com/Loyalsoldier/v2ray-rules-dat/release/geoip.dat"
            )
        )
    }

    @Test
    fun lookalikeUrlsDoNotInheritBuiltinTrust() {
        // 同域名但不同路径：不得继承内置信任
        assertFalse(
            GeoManager.isBuiltinUrl(
                "https://raw.githubusercontent.com/attacker/evil-rules-dat/release/geosite.dat"
            )
        )
        // 内置 URL 作为前缀再拼接：不得命中
        assertFalse(
            GeoManager.isBuiltinUrl(
                "https://raw.githubusercontent.com/Loyalsoldier/v2ray-rules-dat/release/geosite.dat.evil"
            )
        )
        // 内置 URL 出现在 host 部分之后的攻击者域名下
        assertFalse(
            GeoManager.isBuiltinUrl(
                "https://attacker.example/https://raw.githubusercontent.com/Loyalsoldier/v2ray-rules-dat/release/geosite.dat"
            )
        )
        // 明文 http 版本不得命中
        assertFalse(
            GeoManager.isBuiltinUrl(
                "http://raw.githubusercontent.com/Loyalsoldier/v2ray-rules-dat/release/geosite.dat"
            )
        )
        // 普通自定义源
        assertFalse(GeoManager.isBuiltinUrl("https://my-own-mirror.example/geosite.dat"))
        assertFalse(GeoManager.isBuiltinUrl(""))
    }

    /**
     * 匹配必须大小写敏感。
     *
     * GitHub / jsDelivr 路径本身区分大小写，折叠比较会把拼写变体判成内置源，
     * 强制走校验后两个 URL 双双 404，变成本可避免的硬失败。
     * 这些变体属于用户自建的自定义源，应落在宽松分支。
     */
    @Test
    fun builtinMatchIsCaseSensitive() {
        // 路径大小写变体 (小写 loyalsoldier)
        assertFalse(
            GeoManager.isBuiltinUrl(
                "https://raw.githubusercontent.com/loyalsoldier/v2ray-rules-dat/release/geosite.dat"
            )
        )
        // 文件名大小写变体
        assertFalse(
            GeoManager.isBuiltinUrl(
                "https://raw.githubusercontent.com/Loyalsoldier/v2ray-rules-dat/release/GeoSite.dat"
            )
        )
        // scheme 大小写变体
        assertFalse(
            GeoManager.isBuiltinUrl(
                "HTTPS://raw.githubusercontent.com/Loyalsoldier/v2ray-rules-dat/release/geosite.dat"
            )
        )
        // 原样仍必须命中
        assertTrue(
            GeoManager.isBuiltinUrl(
                "https://raw.githubusercontent.com/Loyalsoldier/v2ray-rules-dat/release/geosite.dat"
            )
        )
    }

    @Test
    fun builtinUrlMatchIgnoresSurroundingWhitespace() {
        // 用户从剪贴板粘贴常带首尾空白，不应因此丢失内置源的强制校验
        assertTrue(
            GeoManager.isBuiltinUrl(
                "  https://raw.githubusercontent.com/Loyalsoldier/v2ray-rules-dat/release/geosite.dat  "
            )
        )
    }

    // ── 失败原因分级 ────────────────────────────────────────────────────

    /**
     * 「摘要对不上」与「拿不到摘要」必须给用户不同的说法。
     *
     * 折叠成同一句话时，一次真实的篡改与一个糟糕的 CDN 日在界面上无法区分，
     * 用户既无法判断该重试还是该换源，也看不出自己可能正在被攻击。
     */
    @Test
    fun failureReasonsAreDistinguishableToTheUser() {
        val msgs = GeoManager.FailureReason.values().associateWith {
            GeoManager.describeFailure("GeoIP 数据集", it)
        }
        // 四种原因两两不同
        assertEquals(
            "每种失败原因必须有独立文案",
            GeoManager.FailureReason.values().size,
            msgs.values.toSet().size
        )
        // 篡改与不可用不能混为一谈
        assertTrue(msgs[GeoManager.FailureReason.MISMATCH]!!.contains("篡改"))
        assertFalse(msgs[GeoManager.FailureReason.DIGEST_UNAVAILABLE]!!.contains("篡改"))
        // 不可用应提示重试, 上游不提供则不该提示重试
        assertTrue(msgs[GeoManager.FailureReason.DIGEST_UNAVAILABLE]!!.contains("重试"))
        assertFalse(msgs[GeoManager.FailureReason.DIGEST_ABSENT]!!.contains("重试"))
        // 文案里要带上是哪个数据集
        msgs.values.forEach { assertTrue(it.startsWith("GeoIP 数据集")) }
    }
}
