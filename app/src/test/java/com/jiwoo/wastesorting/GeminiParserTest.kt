package com.jiwoo.wastesorting

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class GeminiParserTest {

    private fun resp(
        itemName: String? = "캔",
        steps: List<String>? = listOf("헹궈요"),
        yoloClass: String? = null
    ) = DisposalResponse(itemName, "캔류", steps, null, null, yoloClass)

    // ---------- parse: 글 → 답변 ----------

    @Test
    fun `일반 JSON을 읽는다`() {
        val r = GeminiParser.parse("""{"itemName":"캔","category":"캔류","steps":["헹궈요"]}""")
        assertEquals("캔", r.itemName)
        assertEquals(listOf("헹궈요"), r.steps)
    }

    @Test
    fun `코드블록으로 감싸도 읽는다`() {
        val text = "```json\n{\"itemName\":\"캔\",\"steps\":[\"헹궈요\"]}\n```"
        assertEquals("캔", GeminiParser.parse(text).itemName)
    }

    @Test
    fun `JSON이 아니면 오류`() {
        assertThrows(Exception::class.java) { GeminiParser.parse("안녕하세요") }
    }

    @Test
    fun `빈 응답이면 오류`() {
        assertThrows(Exception::class.java) { GeminiParser.parse("") }
    }

    @Test
    fun `단계에 null이 섞여도 오류 없이 걸러낸다`() {
        val r = GeminiParser.parse("""{"itemName":"캔","steps":["헹궈요", null]}""")
        assertEquals(listOf("헹궈요"), r.steps)
    }

    // ---------- sanitize: 답변 정리 ----------

    @Test
    fun `앞뒤 공백을 정리하고 빈 단계는 버린다`() {
        val r = GeminiParser.sanitize(resp(itemName = "  캔  ", steps = listOf(" 헹궈요 ", "", "   ")))
        assertEquals("캔", r.itemName)
        assertEquals(listOf("헹궈요"), r.steps)
    }

    @Test
    fun `단계는 8개까지만`() {
        val r = GeminiParser.sanitize(resp(steps = (1..10).map { "단계 $it" }))
        assertEquals(8, r.steps!!.size)
    }

    @Test
    fun `너무 긴 글은 자른다`() {
        val r = GeminiParser.sanitize(resp(itemName = "가".repeat(100), steps = listOf("나".repeat(300))))
        assertEquals(60, r.itemName!!.length)
        assertEquals(200, r.steps!![0].length)
    }

    @Test
    fun `yoloClass는 허용된 값만 남긴다`() {
        assertEquals("pet", GeminiParser.sanitize(resp(yoloClass = "PET")).yoloClass)
        assertEquals("other", GeminiParser.sanitize(resp(yoloClass = "other")).yoloClass)
        assertNull(GeminiParser.sanitize(resp(yoloClass = "metal")).yoloClass)
        assertNull(GeminiParser.sanitize(resp(yoloClass = null)).yoloClass)
    }

    @Test
    fun `이름만 있어도 통과한다`() {
        val r = GeminiParser.sanitize(resp(steps = null))
        assertEquals(emptyList<String>(), r.steps)
    }

    @Test
    fun `이름도 단계도 없으면 오류`() {
        assertThrows(IllegalStateException::class.java) {
            GeminiParser.sanitize(resp(itemName = " ", steps = listOf("")))
        }
    }
}