package com.jiwoo.wastesorting

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WasteClassesTest {

    @Test
    fun `분류는 7개다`() {
        assertEquals(7, WasteClasses.NAMES.size)
    }

    @Test
    fun `대소문자와 공백을 정리한다`() {
        assertEquals("can", WasteClasses.normalize("  CAN "))
        assertEquals("pet", WasteClasses.normalize("Pet"))
    }

    @Test
    fun `other는 허용한다`() {
        assertEquals("other", WasteClasses.normalize("Other"))
    }

    @Test
    fun `모르는 값은 null`() {
        assertNull(WasteClasses.normalize("metal"))
        assertNull(WasteClasses.normalize(""))
        assertNull(WasteClasses.normalize(null))
    }

    @Test
    fun `번호 순서가 labels와 같다`() {
        assertEquals(0, WasteClasses.indexOf("can"))
        assertEquals(6, WasteClasses.indexOf("vinyl"))
        assertEquals(-1, WasteClasses.indexOf("other"))
    }

    @Test
    fun `한글 이름으로 바꾼다`() {
        assertEquals("페트병", WasteClasses.koName("pet"))
        assertEquals("종이팩", WasteClasses.koName("pack"))
        assertEquals("분류 밖", WasteClasses.koName("other"))
    }

    @Test
    fun `7개 분류 모두 한글 이름이 있다`() {
        WasteClasses.NAMES.forEach { assertTrue(it, WasteClasses.koName(it) != it) }
    }

    @Test
    fun `모르는 이름은 그대로`() {
        assertEquals("metal", WasteClasses.koName("metal"))
    }

    @Test
    fun `labels 순서까지 같아야 일치`() {
        assertTrue(WasteClasses.matches(listOf("can", "glass", "pack", "paper", "pet", "plastic", "vinyl")))
        assertFalse(WasteClasses.matches(listOf("glass", "can", "pack", "paper", "pet", "plastic", "vinyl")))
        assertFalse(WasteClasses.matches(listOf("can", "glass")))
    }
}