package com.jiwoo.wastesorting

/** LabelStabilizer의 계산 부분 (안드로이드 기능 없이 PC에서 테스트 가능) */
object StabilizerMath {

    data class Judgement(
        val classId: Int,
        val avgScore: Float,
        val agreement: Float,
        val settling: Boolean,
        val uncertain: Boolean
    )

    /** votes: (분류 번호, 점수) 목록 */
    fun judge(votes: List<Pair<Int, Float>>, minVotesForJudge: Int, minAgreement: Float): Judgement {
        require(votes.isNotEmpty()) { "투표가 없어요" }
        val total = votes.sumOf { it.second.toDouble() }
        val (winId, winVotes) = votes.groupBy { it.first }
            .maxByOrNull { e -> e.value.sumOf { it.second.toDouble() } }!!
        val winSum = winVotes.sumOf { it.second.toDouble() }
        val agreement = if (total > 0) (winSum / total).toFloat() else 1f
        val settling = votes.size < minVotesForJudge
        return Judgement(
            classId = winId,
            avgScore = winVotes.map { it.second }.average().toFloat(),
            agreement = agreement,
            settling = settling,
            // 표가 부족하거나, 표가 갈렸으면 확실하지 않음
            uncertain = settling || agreement < minAgreement
        )
    }

    /** 두 박스의 겹침 비율 (0 ~ 1) */
    fun iou(
        al: Float, at: Float, ar: Float, ab: Float,
        bl: Float, bt: Float, br: Float, bb: Float
    ): Float {
        val inter = maxOf(0f, minOf(ar, br) - maxOf(al, bl)) *
                maxOf(0f, minOf(ab, bb) - maxOf(at, bt))
        val union = (ar - al) * (ab - at) + (br - bl) * (bb - bt) - inter
        return if (union <= 0f) 0f else inter / union
    }
}