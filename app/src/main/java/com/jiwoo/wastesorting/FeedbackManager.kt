package com.jiwoo.wastesorting

import android.content.Context
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** FeedbackSaver가 모은 학습 자료의 통계, 검토, ZIP 내보내기, 삭제 */
object FeedbackManager {

    const val REVIEW_ACCEPTED = "accepted"
    const val REVIEW_EXCLUDED = "excluded"

    /** 검토 대기·제외 사례를 폰에 남겨 두는 기간. 확정 사례는 자동 삭제하지 않음 */
    const val KEEP_DAYS = 15

    data class Stats(
        val total: Int,
        val labeled: Int,
        val background: Int,
        val bytes: Long,
        val pending: Int,    // 검토 대기
        val accepted: Int,   // 정답 확정 (ZIP에 들어감)
        val excluded: Int    // 제외
    )

    /** 검토 화면에 보여 줄 사례 1개 */

    data class Case(
        val base: String,
        val image: File,
        val yoloLabel: String,
        val geminiClass: String,
        val geminiItemName: String,
        val box: DoubleArray?   // 중심x, 중심y, 너비, 높이 (0~1). 없으면 다른 분류로 못 바꿈
    )

    private fun feedbackDir(context: Context) =
        File(context.getExternalFilesDir(null), "feedback")

    private fun exportDir(context: Context) =
        File(context.cacheDir, "export")

    private fun readJson(file: File): JSONObject? =
        try { JSONObject(file.readText()) } catch (e: Exception) { null }

    /** 사례 1개 = json 1개. txt가 있으면 학습용, 없으면 배경(other) */
    fun stats(context: Context): Stats {
        val dir = feedbackDir(context)
        val files = dir.listFiles().orEmpty()
        val jsons = files.filter { it.extension == "json" }
        val labeled = jsons.count { File(dir, "${it.nameWithoutExtension}.txt").exists() }
        val reviews = jsons.map { readJson(it)?.optString("review", "") ?: "" }
        return Stats(
            total = jsons.size,
            labeled = labeled,
            background = jsons.size - labeled,
            bytes = files.sumOf { it.length() },
            pending = reviews.count { it.isEmpty() },
            accepted = reviews.count { it == REVIEW_ACCEPTED },
            excluded = reviews.count { it == REVIEW_EXCLUDED }
        )
    }

    /** 아직 검토하지 않은 사례 (오래된 것부터, 백그라운드에서 호출) */
    fun pendingCases(context: Context): List<Case> {
        val dir = feedbackDir(context)
        return dir.listFiles().orEmpty()
            .filter { it.extension == "json" }
            .sortedBy { it.name }
            .mapNotNull { json ->
                val o = readJson(json) ?: return@mapNotNull null
                if (o.optString("review", "").isNotEmpty()) return@mapNotNull null
                val base = json.nameWithoutExtension
                val jpg = File(dir, "$base.jpg")
                if (!jpg.exists()) return@mapNotNull null
                val txt = File(dir, "$base.txt")
                val box = o.optJSONArray("box")?.let { a -> DoubleArray(4) { a.getDouble(it) } }
                    ?: boxFromLabel(txt)
                Case(
                    base = base,
                    image = jpg,
                    yoloLabel = o.optString("yoloLabel"),
                    geminiClass = o.optString("geminiClass"),
                    geminiItemName = o.optString("geminiItemName"),
                    box = box
                )
            }
    }

    /**
     * 정답 분류를 확정한다. 라벨 파일의 클래스 번호를 맞추고 json에 검토 결과를 적는다.
     * finalClass가 other면 라벨을 지워 배경 사진으로 만든다.
     * 박스 정보가 없어 라벨을 만들 수 없으면 false (백그라운드에서 호출)
     */
    fun accept(context: Context, base: String, finalClass: String): Boolean {
        val dir = feedbackDir(context)
        val jsonFile = File(dir, "$base.json")
        val o = readJson(jsonFile) ?: return false
        val txt = File(dir, "$base.txt")
        val classId = WasteClasses.indexOf(finalClass)

        if (classId < 0) {
            if (txt.exists()) txt.delete()   // other → 배경
        } else {
            val line = when {
                txt.exists() -> relabel(txt.readText(), classId)
                o.has("box") -> {
                    val b = o.getJSONArray("box")
                    labelLine(classId, b.getDouble(0), b.getDouble(1), b.getDouble(2), b.getDouble(3))
                }
                else -> null
            } ?: return false
            txt.writeText(line)
        }

        o.put("review", REVIEW_ACCEPTED)
        o.put("finalClass", finalClass)
        jsonFile.writeText(o.toString(2))
        return true
    }

    /** 학습에 쓰지 않도록 제외 표시 (파일은 남김, 백그라운드에서 호출) */
    fun exclude(context: Context, base: String) {
        val jsonFile = File(feedbackDir(context), "$base.json")
        val o = readJson(jsonFile) ?: return
        o.put("review", REVIEW_EXCLUDED)
        jsonFile.writeText(o.toString(2))
    }

    /** YOLO 라벨 한 줄 만들기 */
    fun labelLine(classId: Int, cx: Double, cy: Double, w: Double, h: Double): String =
        String.format(Locale.US, "%d %.6f %.6f %.6f %.6f\n", classId, cx, cy, w, h)

    /** "3 0.5 0.5 0.2 0.2" 에서 클래스 번호만 바꿈. 형식이 이상하면 null */
    fun relabel(line: String, classId: Int): String? {
        val parts = line.trim().split(Regex("\\s+"))
        if (parts.size != 5) return null
        return (listOf(classId.toString()) + parts.drop(1)).joinToString(" ") + "\n"
    }

    /** 검토에서 정답이 확정된 사례만 ZIP으로 (백그라운드에서 호출) */
    fun createZip(context: Context): File {
        val dir = feedbackDir(context)
        val outDir = exportDir(context).apply {
            mkdirs()
            listFiles()?.forEach { it.delete() }   // 이전 ZIP 정리
        }
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val zip = File(outDir, "feedback_$stamp.zip")

        ZipOutputStream(BufferedOutputStream(FileOutputStream(zip))).use { zos ->
            zos.putText("classes.txt", WasteClasses.NAMES.joinToString("\n") + "\n")
            zos.putText("README.txt", README)

            val jsons = dir.listFiles().orEmpty()
                .filter { it.extension == "json" }
                .sortedBy { it.name }

            for (json in jsons) {
                val o = readJson(json) ?: continue
                if (o.optString("review") != REVIEW_ACCEPTED) continue   // 검토 안 했거나 제외

                val base = json.nameWithoutExtension
                val jpg = File(dir, "$base.jpg")
                val txt = File(dir, "$base.txt")

                zos.putFile("meta/$base.json", json)
                if (!jpg.exists()) continue

                if (txt.exists()) {
                    zos.putFile("images/$base.jpg", jpg)
                    zos.putFile("labels/$base.txt", txt)
                } else {
                    zos.putFile("background/$base.jpg", jpg)
                }
            }
        }
        return zip
    }
    /** 예전 사례처럼 json에 box가 없으면 라벨 파일에서 위치를 읽음 */
    private fun boxFromLabel(txt: File): DoubleArray? {
        if (!txt.exists()) return null
        val parts = txt.readText().trim().split(Regex("\\s+"))
        if (parts.size != 5) return null
        return parts.drop(1).mapNotNull { it.toDoubleOrNull() }
            .takeIf { it.size == 4 }?.toDoubleArray()
    }

    /**
     * 자동 정리 대상인지 판단한다 (테스트용으로 분리).
     * 확정(accepted) 사례는 재학습 데이터라 남기고, 검토 대기·제외는 KEEP_DAYS가 지나면 지운다.
     */
    fun shouldAutoDelete(savedAtMs: Long, review: String, nowMs: Long): Boolean {
        if (review == REVIEW_ACCEPTED) return false
        return nowMs - savedAtMs > KEEP_DAYS * 24L * 60 * 60 * 1000
    }

    /**
     * 15일 지난 검토 대기·제외 사례(사진·라벨·기록)를 삭제. 지운 파일 수를 돌려줌.
     * 저장 시각은 사진 파일 시각을 쓴다 (json은 검토할 때 다시 써져서 시각이 바뀜).
     * 앱 시작 때 백그라운드에서 호출
     */
    fun cleanupOld(context: Context, nowMs: Long = System.currentTimeMillis()): Int {
        val dir = feedbackDir(context)
        var count = 0
        dir.listFiles().orEmpty().filter { it.extension == "json" }.forEach { json ->
            val base = json.nameWithoutExtension
            val jpg = File(dir, "$base.jpg")
            val savedAt = if (jpg.exists()) jpg.lastModified() else json.lastModified()
            // 읽을 수 없는 기록은 검토 대기로 보고 기간이 지나면 정리
            val review = readJson(json)?.optString("review", "") ?: ""
            if (!shouldAutoDelete(savedAt, review, nowMs)) return@forEach
            listOf("jpg", "txt", "json").forEach { ext ->
                if (File(dir, "$base.$ext").delete()) count++
            }
        }
        return count
    }

    /** 검토를 마친 사례(확정·제외)만 삭제. 검토 대기는 남김 */
    fun clearReviewed(context: Context): Int {
        val dir = feedbackDir(context)
        var count = 0
        dir.listFiles().orEmpty().filter { it.extension == "json" }.forEach { json ->
            val review = readJson(json)?.optString("review", "") ?: return@forEach
            if (review.isEmpty()) return@forEach
            val base = json.nameWithoutExtension
            listOf("jpg", "txt", "json").forEach { ext ->
                if (File(dir, "$base.$ext").delete()) count++
            }
        }
        exportDir(context).listFiles()?.forEach { it.delete() }
        return count
    }
    /** 학습 자료와 내보낸 ZIP을 모두 삭제. 지운 파일 수를 돌려줌 */
    fun clearAll(context: Context): Int {
        var count = 0
        feedbackDir(context).listFiles()?.forEach { if (it.delete()) count++ }
        exportDir(context).listFiles()?.forEach { it.delete() }
        return count
    }

    private fun ZipOutputStream.putFile(name: String, file: File) {
        putNextEntry(ZipEntry(name))
        file.inputStream().use { it.copyTo(this) }
        closeEntry()
    }

    private fun ZipOutputStream.putText(name: String, text: String) {
        putNextEntry(ZipEntry(name))
        write(text.toByteArray(Charsets.UTF_8))
        closeEntry()
    }

    private val README = """
        분리배출 앱 학습 데이터 (YOLO 형식)

        앱에서 사람이 직접 검토해 정답을 확정한 사례만 들어 있어요.

        images/      정답이 7개 분류 중 하나인 사진
        labels/      images와 같은 이름의 YOLO 라벨 (클래스 번호는 검토한 정답 기준)
        background/  7개 분류에 해당하지 않는다고 확정한 사진 (라벨 없음)
        meta/        사례별 기록 (YOLO 라벨, AI 판단, 최종 정답 finalClass 등)
        classes.txt  클래스 순서 (0번부터)

        학습에 넣기 전에 확인할 것
        1. 라벨에는 사용자가 누른 물체 하나만 들어 있어요.
           사진에 다른 쓰레기가 함께 있으면 라벨링 도구로 추가하세요.
        2. background 사진은 빈 라벨 파일과 함께 넣으면 오탐지를 줄일 수 있어요.
           전체 데이터의 10% 이하로 넣는 것을 권장해요.
        3. 이름, 카드 번호 같은 개인정보가 찍힌 사진은 빼거나 가리세요.
    """.trimIndent() + "\n"
}