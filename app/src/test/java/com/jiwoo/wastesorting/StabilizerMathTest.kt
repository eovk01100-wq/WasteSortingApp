package com.jiwoo.wastesorting

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class StabilizerMathTest {

    // 앱과 같은 기준: 3표 이상, 60% 이상 일치
    private fun judge(vararg votes: Pair<Int, Float>) =
        StabilizerMath.judge(votes.toList(), minVotesForJudge = 3, minAgreement = 0.6f)

    // ---------- 투표 ----------

    @Test
    fun `모두 같은 분류면 확실하다`() {
        val j = judge(0 to 0.8f, 0 to 0.8f, 0 to 0.8f)
        assertEquals(0, j.classId)
        assertEquals(1f, j.agreement, 0.0001f)
        assertFalse(j.uncertain)
    }

    @Test
    fun `표가 3개 미만이면 아직 판단 중`() {
        val j = judge(0 to 0.9f, 0 to 0.9f)
        assertTrue(j.settling)
        assertTrue(j.uncertain)
    }

    @Test
    fun `표가 갈리면 확인 필요`() {
        // 4 대 3 → 약 57% < 60%
        val j = judge(0 to 0.5f, 0 to 0.5f, 0 to 0.5f, 0 to 0.5f, 1 to 0.5f, 1 to 0.5f, 1 to 0.5f)
        assertEquals(0, j.classId)
        assertFalse(j.settling)
        assertTrue(j.uncertain)
    }

    @Test
    fun `한쪽이 충분히 많으면 확실하다`() {
        // 5 대 2 → 약 71%
        val j = judge(0 to 0.5f, 0 to 0.5f, 0 to 0.5f, 0 to 0.5f, 0 to 0.5f, 1 to 0.5f, 1 to 0.5f)
        assertEquals(0, j.classId)
        assertFalse(j.uncertain)
    }

    @Test
    fun `표 수보다 점수 합이 이긴다`() {
        // 분류 0: 2표지만 점수 합 0.4 / 분류 1: 1표지만 점수 합 0.9
        val j = judge(0 to 0.2f, 0 to 0.2f, 1 to 0.9f)
        assertEquals(1, j.classId)
    }

    @Test
    fun `점수는 이긴 분류의 평균`() {
        val j = judge(0 to 0.6f, 0 to 0.8f, 1 to 0.1f)
        assertEquals(0.7f, j.avgScore, 0.0001f)
    }

    @Test
    fun `표가 없으면 오류`() {
        assertThrows(IllegalArgumentException::class.java) { judge() }
    }

    // ---------- IoU ----------

    @Test
    fun `같은 박스는 1`() {
        assertEquals(1f, StabilizerMath.iou(0f, 0f, 10f, 10f, 0f, 0f, 10f, 10f), 0.0001f)
    }

    @Test
    fun `안 겹치면 0`() {
        assertEquals(0f, StabilizerMath.iou(0f, 0f, 10f, 10f, 20f, 20f, 30f, 30f), 0.0001f)
    }

    @Test
    fun `절반 겹치면 3분의 1`() {
        // 겹친 넓이 50, 합친 넓이 150
        assertEquals(1f / 3f, StabilizerMath.iou(0f, 0f, 10f, 10f, 5f, 0f, 15f, 10f), 0.0001f)
    }

    @Test
    fun `크기가 0인 박스는 0`() {
        assertEquals(0f, StabilizerMath.iou(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f), 0.0001f)
    }
}