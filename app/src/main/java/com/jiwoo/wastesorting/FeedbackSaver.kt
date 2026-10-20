package com.jiwoo.wastesorting

import android.content.Context
import android.graphics.Bitmap
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * YOLO 라벨과 Gemini 판단이 다르거나, YOLO가 확신하지 못한 경우
 * 화면 전체 사진 + YOLO 형식 라벨 + 기록(json)을 저장한다.
 * 저장 위치: /sdcard/Android/data/com.jiwoo.wastesorting/files/feedback/
 */
object FeedbackSaver {

    /** 저장했으면 Gemini가 판단한 분류 이름, 저장하지 않았으면 null */
    fun saveIfNeeded(context: Context, frame: Bitmap, det: Detection, r: DisposalResponse): String? {
        // 허용된 값(7개 분류 + other)만 사용. 그 외 값은 저장하지 않음
        val geminiClass = WasteClasses.normalize(r.yoloClass) ?: return null
        val disagree = geminiClass != det.label
        // 판단이 같으면 저장 안 함 (단, 표가 충분히 모였는데도 갈린 경우는 저장)
        if (!disagree && !(det.uncertain && !det.settling)) return null

        val dir = File(context.getExternalFilesDir(null), "feedback").apply { mkdirs() }
        val time = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
        val name = "${time}_${det.label}_to_$geminiClass"

        // 박스 위치 (0~1 비율, YOLO 형식: 중심 x, 중심 y, 너비, 높이)
        val w = frame.width.toDouble()
        val h = frame.height.toDouble()
        val b = det.box
        val cx = b.centerX() / w
        val cy = b.centerY() / h
        val bw = b.width() / w
        val bh = b.height() / h

        // 1) 화면 전체 사진
        File(dir, "$name.jpg").outputStream().use {
            frame.compress(Bitmap.CompressFormat.JPEG, 92, it)
        }

        // 2) YOLO 라벨 (Gemini 분류 기준, other면 생략)
        val classId = WasteClasses.indexOf(geminiClass)
        if (classId >= 0) {
            File(dir, "$name.txt").writeText(FeedbackManager.labelLine(classId, cx, cy, bw, bh))
        }

        // 3) 기록 (검토 전이라 review 없음)
        val meta = JSONObject().apply {
            put("time", time)
            put("yoloLabel", det.label)
            put("yoloScore", det.score.toDouble())
            put("yoloAgreement", det.agreement.toDouble())
            put("yoloUncertain", det.uncertain)
            put("geminiClass", geminiClass)
            put("geminiItemName", r.itemName ?: "")
            put("geminiCategory", r.category ?: "")
            put("imageWidth", frame.width)
            put("imageHeight", frame.height)
            put("box", JSONArray().apply { put(cx); put(cy); put(bw); put(bh) })
        }
        File(dir, "$name.json").writeText(meta.toString(2))

        return geminiClass
    }
}