package com.mirage.android

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * i18n 回归门禁。
 *
 * 这三条不是风格检查，每一条都对应一种线上会炸或会漏的故障：
 *  1. 少一个 key -> 该语言下 getString 抛 Resources.NotFoundException。
 *  2. 两边格式占位符数量不一致 -> 切到该语言就 IllegalFormatException 崩溃，
 *     而中文环境下测不出来。
 *  3. 布局里回写硬编码字面量 -> 那一处永远不会被翻译，且以后改文案要改代码。
 *
 * 读的是真实工程文件，不是夹具。
 */
class LocalizationTest {

    private val resDir = File("src/main/res")

    private fun strings(dir: String): Map<String, String> {
        val f = File(resDir, "$dir/strings.xml")
        assertTrue("找不到 ${f.path}（单测工作目录应为 app 模块根）", f.isFile)
        return Regex("""<string name="([^"]+)">(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
            .findAll(f.readText())
            .associate { it.groupValues[1] to it.groupValues[2] }
    }

    /** app_name 是专有名词，刻意不翻译。 */
    private val untranslated = setOf("app_name")

    @Test
    fun `每条中文字符串都有对应的英文翻译`() {
        val zh = strings("values").keys - untranslated
        val en = strings("values-en").keys
        assertEquals("values-en 缺少这些 key", emptySet<String>(), zh - en)
        assertEquals("values-en 多出这些 key", emptySet<String>(), en - zh)
    }

    @Test
    fun `中英文的格式占位符数量一致`() {
        val zh = strings("values")
        val en = strings("values-en")
        val arg = Regex("""%(\d+)\$[sd]""")
        val mismatched = zh.keys.intersect(en.keys).filter { k ->
            arg.findAll(zh.getValue(k)).map { it.groupValues[1] }.toSet() !=
                arg.findAll(en.getValue(k)).map { it.groupValues[1] }.toSet()
        }
        assertEquals("这些 key 的中英文占位符对不上，切到该语言会 IllegalFormatException", emptyList<String>(), mismatched)
    }

    @Test
    fun `布局里没有硬编码的中文文案`() {
        val attrs = Regex("""(?:android:text|android:hint|android:contentDescription|app:title)="([^@"][^"]*)"""")
        val cjk = Regex("""[一-鿿]""")
        val offenders = File(resDir, "layout").listFiles { f -> f.extension == "xml" }
            .orEmpty()
            .flatMap { f ->
                attrs.findAll(f.readText())
                    .map { it.groupValues[1] }
                    .filter { cjk.containsMatchIn(it) }
                    .map { "${f.name}: $it" }
            }
        assertEquals("这些文案应抽到 strings.xml", emptyList<String>(), offenders)
    }
}
