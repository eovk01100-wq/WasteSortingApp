package com.jiwoo.wastesorting

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 오탐 원본 화면을 서버로 보내기 (YOLO 재학습용).
 * - 사용자가 '학습 자료 서버로 보내기'를 켠 경우에만 (기본값: 꺼짐)
 * - 서버가 오탐(misdetection=true)으로 판정한 박스 분석만
 * - 인터넷이 없으면 WorkManager가 연결될 때 대신 보낸다 (앱을 꺼도 이어서)
 */
object FrameUploader {

    private const val TAG = "FrameUploader"
    private const val PREFS = "frame_upload"
    private const val KEY_CONSENT = "consent"

    fun isConsented(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_CONSENT, false)

    fun setConsented(context: Context, on: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_CONSENT, on).apply()
        if (!on) WorkManager.getInstance(context).cancelAllWorkByTag(TAG) // 끄면 아직 안 보낸 것도 취소
    }

    /** 조건이 맞으면 업로드를 예약하고 true */
    fun enqueueIfAllowed(context: Context, r: DisposalResponse, saved: FeedbackSaver.Saved): Boolean {
        val analysisId = r.analysisId ?: return false
        if (r.misdetection != true || !isConsented(context)) return false

        val request = OneTimeWorkRequestBuilder<UploadWorker>()
            .setInputData(
                workDataOf(
                    UploadWorker.KEY_ANALYSIS_ID to analysisId,
                    UploadWorker.KEY_PATH to saved.imageFile.absolutePath,
                    UploadWorker.KEY_BOX to saved.box
                )
            )
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .addTag(TAG)
            .build()
        WorkManager.getInstance(context)
            .enqueueUniqueWork("frame-$analysisId", ExistingWorkPolicy.KEEP, request)
        return true
    }

    class UploadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

        override suspend fun doWork(): Result {
            if (!isConsented(applicationContext)) return Result.success()   // 그사이 동의를 끈 경우
            val id = inputData.getLong(KEY_ANALYSIS_ID, -1)
            val path = inputData.getString(KEY_PATH)
            val box = inputData.getDoubleArray(KEY_BOX)
            if (id < 0 || path == null || box == null || box.size != 4) return Result.failure()
            val file = File(path)
            if (!file.exists()) return Result.failure()   // 사용자가 학습 자료를 비운 경우

            GeminiClient.init(applicationContext)
            return try {
                GeminiClient.uploadFrame(id, shrink(file), box)
                Log.i(TAG, "오탐 업로드 완료: 분석 #$id")
                Result.success()
            } catch (e: ServerException) {
                // 이미 올렸거나(409) 조건이 안 맞는 경우(4xx)는 다시 보내도 같으므로 끝낸다
                Log.w(TAG, "오탐 업로드 거절: ${e.status} ${e.code}")
                if (e.status >= 500) Result.retry() else Result.success()
            } catch (e: Exception) {
                Log.w(TAG, "오탐 업로드 실패, 나중에 다시 시도", e)
                if (runAttemptCount < 5) Result.retry() else Result.failure()
            }
        }

        /** 서버 제한(2MB)보다 작게: 긴 변 1280px, JPEG 85 */
        private fun shrink(file: File): ByteArray {
            val src = BitmapFactory.decodeFile(file.absolutePath) ?: error("사진을 읽지 못했어요")
            val scale = minOf(1f, 1280f / maxOf(src.width, src.height))
            val bmp = if (scale < 1f) {
                Bitmap.createScaledBitmap(src, (src.width * scale).toInt(), (src.height * scale).toInt(), true)
            } else src
            val out = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.JPEG, 85, out)
            if (bmp !== src) bmp.recycle()
            src.recycle()
            return out.toByteArray()
        }

        companion object {
            const val KEY_ANALYSIS_ID = "analysisId"
            const val KEY_PATH = "path"
            const val KEY_BOX = "box"
        }
    }
}
