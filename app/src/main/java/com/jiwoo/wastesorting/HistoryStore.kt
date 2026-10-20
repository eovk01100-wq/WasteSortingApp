package com.jiwoo.wastesorting

import android.content.Context
import android.graphics.Bitmap
import com.google.gson.Gson
import java.io.File

data class HistoryEntry(
    val id: Long,
    val time: Long,
    val source: String,             // camera, photo, search
    val title: String,              // 탐지 라벨 또는 검색어
    val response: DisposalResponse,
    val thumbPath: String?,
    var starred: Boolean = false
)

/** 받은 안내를 기기에 저장 (history.json + 썸네일) */
object HistoryStore {

    private const val MAX_UNSTARRED = 50
    private val gson = Gson()
    private var cache: MutableList<HistoryEntry>? = null

    private fun file(ctx: Context) = File(ctx.filesDir, "history.json")
    private fun thumbDir(ctx: Context) = File(ctx.filesDir, "history_thumbs").apply { mkdirs() }

    @Synchronized
    private fun entries(ctx: Context): MutableList<HistoryEntry> {
        cache?.let { return it }
        val f = file(ctx)
        val loaded = if (!f.exists()) null else try {
            gson.fromJson(f.readText(), Array<HistoryEntry>::class.java)?.toMutableList()
        } catch (e: Exception) {
            // 깨진 파일은 덮어쓰지 않고 따로 보관
            f.renameTo(File(f.parentFile, "history.json.broken-${System.currentTimeMillis()}"))
            null
        }
        return (loaded ?: mutableListOf()).also { cache = it }
    }

    @Synchronized
    fun add(ctx: Context, source: String, title: String, response: DisposalResponse, thumb: Bitmap?): HistoryEntry {
        val list = entries(ctx)

        // 같은 검색어는 새로 쌓지 않고 최신으로 교체 (별표는 유지)
        val old = if (source == "search") list.firstOrNull { it.source == "search" && it.title == title } else null
        old?.let {
            list.remove(it)
            it.thumbPath?.let { p -> File(p).delete() }
        }

        val now = System.currentTimeMillis()
        // 같은 밀리초에 저장돼도 번호가 겹치지 않게 항상 기존 번호보다 크게
        val id = maxOf(now, (list.maxOfOrNull { it.id } ?: 0L) + 1)
        val entry = HistoryEntry(
            id = id,
            time = now,
            source = source,
            title = title,
            response = response,
            thumbPath = thumb?.let { saveThumb(ctx, id, it) },
            starred = old?.starred ?: false
        )
        list.add(0, entry)
        trim(list)
        save(ctx)
        return entry
    }

    /** 별표 먼저, 그다음 최신순 */
    @Synchronized
    fun all(ctx: Context): List<HistoryEntry> =
        entries(ctx).sortedWith(compareByDescending<HistoryEntry> { it.starred }.thenByDescending { it.time })

    @Synchronized
    fun isStarred(ctx: Context, id: Long) = entries(ctx).firstOrNull { it.id == id }?.starred == true

    /** 별표를 바꾸고 바뀐 상태를 돌려줌 */
    @Synchronized
    fun toggleStar(ctx: Context, id: Long): Boolean {
        val e = entries(ctx).firstOrNull { it.id == id } ?: return false
        e.starred = !e.starred
        save(ctx)
        return e.starred
    }

    /** 별표 없는 기록 삭제, 지운 개수를 돌려줌 */
    @Synchronized
    fun clearUnstarred(ctx: Context): Int {
        val list = entries(ctx)
        val removed = list.filter { !it.starred }
        removed.forEach { e -> e.thumbPath?.let { File(it).delete() } }
        list.removeAll(removed)
        save(ctx)
        return removed.size
    }

    private fun trim(list: MutableList<HistoryEntry>) {
        val unstarred = list.filter { !it.starred }.sortedByDescending { it.time }
        unstarred.drop(MAX_UNSTARRED).forEach { e ->
            e.thumbPath?.let { File(it).delete() }
            list.remove(e)
        }
    }

    // 임시 파일에 다 쓴 뒤 교체 → 쓰는 도중 앱이 꺼져도 기존 파일은 그대로
    private fun save(ctx: Context) {
        val target = file(ctx)
        val tmp = File(target.parentFile, "history.json.tmp")
        tmp.writeText(gson.toJson(entries(ctx)))
        if (!tmp.renameTo(target)) {
            target.delete()
            tmp.renameTo(target)
        }
    }

    private fun saveThumb(ctx: Context, id: Long, src: Bitmap): String {
        val maxSide = 200
        val scale = minOf(1f, maxSide.toFloat() / maxOf(src.width, src.height))
        val bmp = if (scale < 1f) Bitmap.createScaledBitmap(
            src, (src.width * scale).toInt().coerceAtLeast(1), (src.height * scale).toInt().coerceAtLeast(1), true
        ) else src
        val f = File(thumbDir(ctx), "$id.jpg")
        f.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 80, it) }
        return f.absolutePath
    }
}