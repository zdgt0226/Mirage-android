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
        return Regex("<string name=\"([^\"]+)\">(.*?)</string>", RegexOption.DOT_MATCHES_ALL)
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
        val arg = Regex("%(\\d+)\\$[sd]")
        val mismatched = zh.keys.intersect(en.keys).filter { k ->
            arg.findAll(zh.getValue(k)).map { it.groupValues[1] }.toSet() !=
                arg.findAll(en.getValue(k)).map { it.groupValues[1] }.toSet()
        }
        assertEquals("这些 key 的中英文占位符对不上，切到该语言会 IllegalFormatException", emptyList<String>(), mismatched)
    }

    /**
     * 扫 res/ 下所有非 values 目录, 不只是 layout。
     *
     * 第一版只扫了 layout, 于是 res/menu/bottom_nav.xml 里四个底部导航标题
     * 一直是硬编码 —— 真机切到英文后底栏仍是中文才发现。
     */
    @Test
    fun `资源文件里没有硬编码的中文文案`() {
        // 普通转义字符串: 原始字符串以 " 结尾时与结束定界符有歧义
        val attrs = Regex("(?:android:text|android:hint|android:contentDescription|android:title|app:title)=\"([^@\"][^\"]*)\"")
        val offenders = resDir.listFiles { f -> f.isDirectory && !f.name.startsWith("values") }
            .orEmpty()
            .flatMap { dir -> dir.listFiles { f -> f.extension == "xml" }.orEmpty().toList() }
            .flatMap { f ->
                attrs.findAll(f.readText())
                    .map { it.groupValues[1] }
                    .filter { CJK.containsMatchIn(it) }
                    .map { "${f.parentFile.name}/${f.name}: $it" }
            }
        assertEquals("这些文案应抽到 strings.xml", emptyList<String>(), offenders)
    }

    /**
     * UI 层的 Kotlin 里不该有中文字面量。
     *
     * 资源扫描抓不到代码里拼出来的文案 —— 首页的「节点: …」「累计: …」「连接: …」
     * 都是多行赋值, 真机切英文后那几行仍是中文才暴露。
     *
     * 例外只有一种: 用来匹配内核输出、或进持久化/去重键的数据字面量
     * (如 outbound == "隧道代理"、Rule.displayName)。那种必须留在代码里,
     * 否则换语言就匹配不上或产生重复记录 —— 在该行或上一行写
     * `// i18n-exempt: <理由>` 显式豁免。注释行与 Log 调用不计入。
     *
     * **尚未覆盖 core 包**: 那里还有约 90 处中文, 混着两类东西 —— 真正的用户可见
     * 文案 (GeoManager.describeFailure、CoreManager 的错误与进度串) 和必须保持
     * 语言无关的标识 (RuleStore 的预设规则 name 会进持久化与去重键、各更新源的
     * name)。把这两类分开需要单独一轮, 在那之前不把 core/ 纳入扫描, 免得为了让
     * 测试变绿而把不该翻译的东西翻译掉。
     * (注: 上面刻意不写 "core/" 加星号 —— Kotlin 块注释可嵌套, "/" 紧跟 "*" 会开一层
     * 嵌套注释, 把后面的代码整段吃掉, 编译报的却是几十行外的 Unresolved reference。)
     */
    @Test
    fun `UI 层 Kotlin 里没有中文字面量`() {
        val dirs = listOf("ui", "data").map { File("src/main/java/com/mirage/android/$it") }
        dirs.forEach { assertTrue("找不到 ${it.path}", it.isDirectory) }
        val literal = Regex("\"(?:[^\"\\\\]|\\\\.)*\"")
        val offenders = dirs.asSequence().flatMap { it.walkTopDown() }.filter { it.extension == "kt" }.flatMap { f ->
            val lines = f.readLines()
            lines.asSequence().withIndex().filter { (i, line) ->
                val t = line.trimStart()
                when {
                    t.startsWith("//") || t.startsWith("*") || t.startsWith("/*") -> false
                    "Log." in line -> false
                    "i18n-exempt" in line -> false
                    i > 0 && "i18n-exempt" in lines[i - 1] -> false
                    else -> literal.findAll(line).any { CJK.containsMatchIn(it.value) }
                }
            }.map { (i, line) -> "${f.name}:${i + 1}  ${line.trim().take(70)}" }
        }.toList()
        assertEquals("这些文案应抽到 strings.xml (确属内核数据匹配的请加 // i18n-exempt 注释)", emptyList<String>(), offenders)
    }

    private companion object {
        val CJK = Regex("[\\u4e00-\\u9fff]")
    }
}
