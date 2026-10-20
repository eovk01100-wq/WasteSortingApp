package com.jiwoo.wastesorting

import android.content.ClipData
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.Log
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "WasteSorting"

/**
 * 학습 데이터 창: 통계, 검토, ZIP 내보내기, 비우기
 * launchShare: 공유 화면 열기. 돌아오면 MainActivity가 confirmClear(true)를 부른다
 */
class FeedbackDialogs(
    private val activity: AppCompatActivity,
    private val launchShare: (Intent) -> Unit
) {

    // ---------------- 학습 데이터 관리 ----------------

    fun show() {
        activity.lifecycleScope.launch {
            val s = withContext(Dispatchers.IO) { FeedbackManager.stats(activity) }
            val builder = MaterialAlertDialogBuilder(activity).setTitle("학습 데이터")

            if (s.total == 0) {
                builder.setMessage(
                    "아직 수집된 사례가 없어요.\n\n" +
                            "YOLO 탐지 결과와 AI 판단이 다를 때 자동으로 모여요."
                ).setPositiveButton("닫기", null).show()
                return@launch
            }

            val mb = s.bytes / 1024.0 / 1024.0
            builder.setMessage(
                "수집된 사례: ${s.total}개 (약 ${"%.1f".format(mb)}MB)\n" +
                        "· 검토 대기: ${s.pending}개\n" +
                        "· 정답 확정: ${s.accepted}개 (ZIP에 들어감)\n" +
                        "· 제외: ${s.excluded}개\n\n" +
                        "AI 판단도 틀릴 수 있어서, 검토해서 정답을 확정한 사례만 내보내요.\n" +
                        "사진에 개인정보가 찍혀 있으면 '제외'를 눌러 주세요."
            )
            if (s.pending > 0) {
                builder.setPositiveButton("검토하기 (${s.pending})") { _, _ -> startReview() }
            } else {
                builder.setPositiveButton("ZIP으로 내보내기") { _, _ -> exportFeedback() }
            }
            builder
                .setNeutralButton("더보기") { _, _ -> showMore(s) }
                .setNegativeButton("닫기", null)
                .show()
        }
    }

    private fun showMore(s: FeedbackManager.Stats) {
        MaterialAlertDialogBuilder(activity)
            .setTitle("학습 데이터")
            .setItems(arrayOf("ZIP으로 내보내기 (확정 ${s.accepted}개)", "모두 비우기")) { _, which ->
                when (which) {
                    0 -> exportFeedback()
                    1 -> confirmClear(afterExport = false)
                }
            }
            .setNegativeButton("닫기", null)
            .show()
    }

    private fun exportFeedback() {
        activity.lifecycleScope.launch {
            val s = withContext(Dispatchers.IO) { FeedbackManager.stats(activity) }
            if (s.accepted == 0) {
                Toast.makeText(activity, "먼저 검토해서 정답을 확정해 주세요", Toast.LENGTH_SHORT).show()
                return@launch
            }
            Toast.makeText(activity, "압축 중...", Toast.LENGTH_SHORT).show()
            val zip = withContext(Dispatchers.IO) {
                try {
                    FeedbackManager.createZip(activity)
                } catch (e: Exception) {
                    Log.e(TAG, "ZIP 생성 실패", e)
                    null
                }
            }
            if (zip == null) {
                Toast.makeText(activity, "압축에 실패했어요", Toast.LENGTH_SHORT).show()
                return@launch
            }

            val uri = FileProvider.getUriForFile(
                activity, "${activity.packageName}.fileprovider", zip
            )
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "application/zip"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, zip.name)
                clipData = ClipData.newRawUri(zip.name, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            launchShare(Intent.createChooser(send, "학습 데이터 내보내기"))
        }
    }

    fun confirmClear(afterExport: Boolean) {
        val message = if (afterExport)
            "보낸 곳에서 ZIP 파일을 확인했다면, 검토를 마친 사례를 폰에서 비울까요?\n(검토 대기 사례는 남아요)"
        else
            "수집된 학습 데이터를 모두 삭제할까요?\n삭제하면 되돌릴 수 없어요."

        MaterialAlertDialogBuilder(activity)
            .setTitle("학습 데이터 비우기")
            .setMessage(message)
            .setPositiveButton("비우기") { _, _ ->
                activity.lifecycleScope.launch {
                    val n = withContext(Dispatchers.IO) {
                        if (afterExport) FeedbackManager.clearReviewed(activity)
                        else FeedbackManager.clearAll(activity)
                    }
                    Toast.makeText(activity, "파일 ${n}개를 삭제했어요", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(if (afterExport) "나중에" else "취소", null)
            .show()
    }

    // ---------------- 학습 데이터 검토 ----------------

    private fun className(c: String) =
        if (c == WasteClasses.OTHER) "분류 밖(배경)" else OfflineGuide.get(c).category ?: c

    private fun startReview() {
        activity.lifecycleScope.launch {
            val cases = withContext(Dispatchers.IO) { FeedbackManager.pendingCases(activity) }
            if (cases.isEmpty()) {
                Toast.makeText(activity, "검토할 사례가 없어요", Toast.LENGTH_SHORT).show()
                return@launch
            }
            showReviewCase(cases, 0)
        }
    }

    private fun showReviewCase(cases: List<FeedbackManager.Case>, i: Int) {
        if (i >= cases.size) {
            Toast.makeText(activity, "검토를 마쳤어요", Toast.LENGTH_SHORT).show()
            show()
            return
        }
        val c = cases[i]
        activity.lifecycleScope.launch {
            val bmp = withContext(Dispatchers.IO) { loadReviewImage(c) }

            val metrics = activity.resources.displayMetrics
            val pad = (16 * metrics.density).toInt()
            val image = ImageView(activity).apply {
                adjustViewBounds = true
                maxHeight = (metrics.heightPixels * 0.45f).toInt()
                setImageBitmap(bmp)
            }
            val info = TextView(activity).apply {
                setPadding(0, pad / 2, 0, 0)
                text = "YOLO: ${className(c.yoloLabel)}\n" +
                        "AI: ${className(c.geminiClass)}" +
                        (if (c.geminiItemName.isNotBlank()) " · ${c.geminiItemName}" else "") +
                        if (c.box != null) "\n\n노란 박스 안 물체의 정답을 골라 주세요."
                        else "\n\n(위치 정보가 없는 예전 사례예요)"
            }
            val content = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(pad, pad / 2, pad, 0)
                addView(image)
                addView(info)
            }

            val builder = MaterialAlertDialogBuilder(activity)
                .setTitle("학습 데이터 검토 (${i + 1}/${cases.size})")
                .setView(content)
                .setPositiveButton("AI 판단이 맞음") { _, _ -> decide(cases, i, c.geminiClass) }
                .setNegativeButton("제외") { _, _ -> decide(cases, i, null) }
            if (c.box != null) {
                builder.setNeutralButton("다른 분류…") { _, _ -> pickClass(cases, i) }
            }
            builder.show()
        }
    }

    private fun pickClass(cases: List<FeedbackManager.Case>, i: Int) {
        val options = WasteClasses.NAMES + WasteClasses.OTHER
        MaterialAlertDialogBuilder(activity)
            .setTitle("정답 분류 고르기")
            .setItems(options.map { className(it) }.toTypedArray()) { _, which ->
                decide(cases, i, options[which])
            }
            .setNegativeButton("뒤로") { _, _ -> showReviewCase(cases, i) }
            .show()
    }

    /** finalClass가 null이면 제외 */
    private fun decide(cases: List<FeedbackManager.Case>, i: Int, finalClass: String?) {
        val c = cases[i]
        activity.lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) {
                try {
                    if (finalClass == null) {
                        FeedbackManager.exclude(activity, c.base)
                        true
                    } else {
                        FeedbackManager.accept(activity, c.base, finalClass)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "검토 저장 실패", e)
                    false
                }
            }
            if (!ok) {
                Toast.makeText(activity, "저장하지 못했어요", Toast.LENGTH_SHORT).show()
                showReviewCase(cases, i)
                return@launch
            }
            showReviewCase(cases, i + 1)
        }
    }

    /** 검토용 사진 불러오기 (크면 줄이고, 누른 물체에 노란 박스) */
    private fun loadReviewImage(c: FeedbackManager.Case): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(c.image.path, bounds)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 1280) sample *= 2

        val src = BitmapFactory.decodeFile(
            c.image.path, BitmapFactory.Options().apply { inSampleSize = sample }
        ) ?: return null
        val b = c.box ?: return src
        val bmp = src.copy(Bitmap.Config.ARGB_8888, true) ?: return src
        if (bmp !== src) src.recycle()

        val w = bmp.width
        val h = bmp.height
        val paint = Paint().apply {
            style = Paint.Style.STROKE
            color = Color.YELLOW
            strokeWidth = maxOf(w, h) / 150f
        }
        Canvas(bmp).drawRect(
            ((b[0] - b[2] / 2) * w).toFloat(), ((b[1] - b[3] / 2) * h).toFloat(),
            ((b[0] + b[2] / 2) * w).toFloat(), ((b[1] + b[3] / 2) * h).toFloat(),
            paint
        )
        return bmp
    }
}