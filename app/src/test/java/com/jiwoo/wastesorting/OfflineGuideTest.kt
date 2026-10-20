package com.jiwoo.wastesorting

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OfflineGuideTest {

    // ---------- exactMatch: 구체적인 품목 이름만 ----------

    @Test
    fun `정확한 품목 이름은 분류를 돌려준다`() {
        assertEquals("can", OfflineGuide.exactMatch("캔"))
        assertEquals("pack", OfflineGuide.exactMatch("우유팩"))
        assertEquals("pet", OfflineGuide.exactMatch("생수병"))
        assertEquals("vinyl", OfflineGuide.exactMatch("과자봉지"))
    }

    @Test
    fun `띄어쓰기가 달라도 같은 품목으로 본다`() {
        assertEquals("paper", OfflineGuide.exactMatch("택배 상자"))
        assertEquals("pet", OfflineGuide.exactMatch(" 투명 페트병 "))
    }

    @Test
    fun `넓은 말은 exactMatch에서 잡지 않는다`() {
        assertNull(OfflineGuide.exactMatch("병"))
        assertNull(OfflineGuide.exactMatch("플라스틱"))
        assertNull(OfflineGuide.exactMatch("봉지"))
    }

    @Test
    fun `예외 품목은 exactMatch에서 null`() {
        assertNull(OfflineGuide.exactMatch("종이컵"))
        assertNull(OfflineGuide.exactMatch("깨진 유리병"))
        assertNull(OfflineGuide.exactMatch("부탄가스 캔"))
    }

    // ---------- partialMatch: 오프라인용 가까운 분류 ----------

    @Test
    fun `힌트 단어가 들어 있으면 가까운 분류를 돌려준다`() {
        assertEquals("plastic", OfflineGuide.partialMatch("플라스틱 컵"))
        assertEquals("vinyl", OfflineGuide.partialMatch("검정 봉투"))
        assertEquals("paper", OfflineGuide.partialMatch("과자 상자"))
    }

    @Test
    fun `더 긴 힌트가 이긴다`() {
        // "페트"(2글자)가 "병"(1글자)보다 길어서 pet
        assertEquals("pet", OfflineGuide.partialMatch("콜라 페트병"))
    }

    @Test
    fun `예외 품목은 partialMatch에서도 null`() {
        assertNull(OfflineGuide.partialMatch("깨진 병"))
        assertNull(OfflineGuide.partialMatch("아이스팩"))
        assertNull(OfflineGuide.partialMatch("스프레이 캔"))
    }

    @Test
    fun `아무 힌트도 없으면 null`() {
        assertNull(OfflineGuide.partialMatch("의자"))
    }

    @Test
    fun `종이팩은 종이가 아니라 종이팩으로 본다`() {
        assertEquals("pack", OfflineGuide.partialMatch("우유 종이팩"))
    }

    // ---------- get: 안내 내용 ----------

    @Test
    fun `7개 분류 모두 기본 안내가 있다`() {
        WasteClasses.NAMES.forEach { label ->
            assertNotEquals("$label 안내 없음", "확인 필요", OfflineGuide.get(label).category)
        }
    }

    @Test
    fun `모르는 분류는 확인 필요로 돌려준다`() {
        assertEquals("확인 필요", OfflineGuide.get("metal").category)
    }
}