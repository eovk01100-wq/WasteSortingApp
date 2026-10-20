package com.jiwoo.wastesorting

import android.graphics.RectF

/**
 * 프레임마다 흔들리는 YOLO 라벨을 안정화한다.
 * - 박스가 많이 겹치면(IoU) 같은 물체로 보고 따라간다.
 * - 최근 historySize 프레임의 라벨을 점수 가중치로 투표한다.
 * - 1등 라벨의 표 비율이 minAgreement보다 낮으면 uncertain으로 표시한다.
 */
class LabelStabilizer(
    private val historySize: Int = 7,
    private val iouMatch: Float = 0.3f,
    private val maxMissed: Int = 5,
    private val minAgreement: Float = 0.6f,
    private val minVotesForJudge: Int = 3
) {
    private class Vote(val classId: Int, val label: String, val score: Float)

    private class Track(val id: Int, var box: RectF) {
        val history = ArrayDeque<Vote>()
        var missed = 0
    }

    private var nextId = 0

    private val tracks = mutableListOf<Track>()

    /** 카메라 분석 스레드에서만 호출 */
    fun update(dets: List<Detection>): List<Detection> {
        val unmatched = tracks.toMutableSet()
        val out = ArrayList<Detection>()

        for (d in dets.sortedByDescending { it.score }) {
            // 겹침이 가장 큰 기존 물체 찾기 (라벨은 흔들리므로 라벨 무관하게 비교)
            val best = unmatched.maxByOrNull { iou(it.box, d.box) }
            val track = if (best != null && iou(best.box, d.box) >= iouMatch) {
                unmatched.remove(best)
                best
            } else {
                Track(nextId++, RectF(d.box)).also { tracks.add(it) }
            }

            track.box = RectF(d.box)
            track.missed = 0
            track.history.addLast(Vote(d.classId, d.label, d.score))
            if (track.history.size > historySize) track.history.removeFirst()

            out.add(vote(track, d))
        }

        // 이번 프레임에 안 보인 물체는 잠시 기억했다가 지움
        for (t in unmatched) t.missed++
        tracks.removeAll { it.missed > maxMissed }
        return out
    }

    fun reset() = tracks.clear()

    private fun vote(t: Track, d: Detection): Detection {
        val j = StabilizerMath.judge(
            t.history.map { it.classId to it.score },
            minVotesForJudge,
            minAgreement
        )
        return Detection(
            box = RectF(d.box),
            label = t.history.first { it.classId == j.classId }.label,
            score = j.avgScore,
            classId = j.classId,
            uncertain = j.uncertain,
            agreement = j.agreement,
            trackId = t.id,
            settling = j.settling
        )
    }

    private fun iou(a: RectF, b: RectF): Float =
        StabilizerMath.iou(a.left, a.top, a.right, a.bottom, b.left, b.top, b.right, b.bottom)
}