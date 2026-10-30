package com.jiwoo.wastesorting

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 학습 사진 자동 정리 규칙: 확정은 남기고, 검토 대기·제외는 15일 뒤 삭제 */
class FeedbackCleanupTest {

    private val day = 24L * 60 * 60 * 1000
    private val now = 100 * day

    @Test
    fun `검토 대기는 15일 지나면 삭제`() {
        assertTrue(FeedbackManager.shouldAutoDelete(now - 16 * day, "", now))
    }

    @Test
    fun `제외도 15일 지나면 삭제`() {
        assertTrue(FeedbackManager.shouldAutoDelete(now - 16 * day, FeedbackManager.REVIEW_EXCLUDED, now))
    }

    @Test
    fun `15일이 안 지났으면 남김`() {
        assertFalse(FeedbackManager.shouldAutoDelete(now - 14 * day, "", now))
    }

    @Test
    fun `확정은 오래돼도 남김`() {
        assertFalse(FeedbackManager.shouldAutoDelete(now - 365 * day, FeedbackManager.REVIEW_ACCEPTED, now))
    }
}
