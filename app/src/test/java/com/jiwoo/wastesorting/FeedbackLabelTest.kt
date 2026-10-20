package com.jiwoo.wastesorting

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FeedbackLabelTest {

    @Test
    fun `라벨 한 줄 형식`() {
        assertEquals(
            "4 0.500000 0.250000 0.100000 0.200000\n",
            FeedbackManager.labelLine(4, 0.5, 0.25, 0.1, 0.2)
        )
    }

    @Test
    fun `클래스 번호만 바뀌고 위치는 그대로`() {
        assertEquals(
            "0 0.500000 0.250000 0.100000 0.200000\n",
            FeedbackManager.relabel("4 0.500000 0.250000 0.100000 0.200000\n", 0)
        )
    }

    @Test
    fun `형식이 이상하면 null`() {
        assertNull(FeedbackManager.relabel("", 0))
        assertNull(FeedbackManager.relabel("4 0.5 0.5", 0))
    }
}