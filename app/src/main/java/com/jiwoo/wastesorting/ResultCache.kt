package com.jiwoo.wastesorting

import android.graphics.Bitmap
import android.graphics.Color
import android.os.SystemClock
import android.util.Log

/**
 * Gemini 결과 재사용. 라벨이 같아야 하고, 아래 둘 중 하나를 만족해야 한다.
 * 1) 화면에서 계속 추적 중인 같은 물체(trackId 동일)
 * 2) 사진 지문(가운데 70% dHash)이 거의 같음
 * UI 스레드에서만 사용.
 */
class ResultCache(
    private val maxEntries: Int = 30,
    private val ttlMs: Long = 10 * 60 * 1000L,   // 10분
    private val maxDistance: Int = 12            // 64비트 중 다른 비트 허용 개수
) {
    private class Entry(
        val label: String,
        val trackId: Int,
        val hash: Long,
        val response: DisposalResponse,
        val time: Long
    )

    private val entries = ArrayDeque<Entry>()

    fun find(label: String, trackId: Int, crop: Bitmap): DisposalResponse? {
        val now = SystemClock.elapsedRealtime()
        entries.removeAll { now - it.time > ttlMs }
        val sameLabel = entries.filter { it.label == label }

        // 1) 같은 물체를 계속 추적 중이면 바로 사용
        if (trackId >= 0) {
            sameLabel.lastOrNull { it.trackId == trackId }?.let {
                Log.d(TAG, "캐시 적중(추적): label=$label track=$trackId")
                return it.response
            }
        }

        // 2) 사진 지문 비교
        val h = dHash(crop)
        val best = sameLabel.minByOrNull { distance(it.hash, h) } ?: return null
        val d = distance(best.hash, h)
        Log.d(TAG, "캐시 비교(지문): label=$label 거리=$d (기준 $maxDistance 이하)")
        return if (d <= maxDistance) best.response else null
    }

    fun put(label: String, trackId: Int, crop: Bitmap, response: DisposalResponse) {
        entries.addLast(Entry(label, trackId, dHash(crop), response, SystemClock.elapsedRealtime()))
        while (entries.size > maxEntries) entries.removeFirst()
    }

    private fun distance(a: Long, b: Long) = java.lang.Long.bitCount(a xor b)

    // 가장자리(배경)를 빼고 가운데 70%만 사용
    private fun dHash(src: Bitmap): Long {
        val mx = (src.width * 0.15f).toInt()
        val my = (src.height * 0.15f).toInt()
        val w = (src.width - 2 * mx).coerceAtLeast(9)
        val h = (src.height - 2 * my).coerceAtLeast(8)
        val center = if (src.width > 20 && src.height > 20)
            Bitmap.createBitmap(src, mx, my, w, h) else src

        val small = Bitmap.createScaledBitmap(center, 9, 8, true)
        var hash = 0L
        for (y in 0 until 8) {
            for (x in 0 until 8) {
                val left = gray(small.getPixel(x, y))
                val right = gray(small.getPixel(x + 1, y))
                hash = (hash shl 1) or (if (left > right) 1L else 0L)
            }
        }
        if (small !== center) small.recycle()
        if (center !== src) center.recycle()
        return hash
    }

    private fun gray(p: Int) =
        (Color.red(p) * 299 + Color.green(p) * 587 + Color.blue(p) * 114) / 1000

    companion object {
        private const val TAG = "WasteSorting"
    }
}