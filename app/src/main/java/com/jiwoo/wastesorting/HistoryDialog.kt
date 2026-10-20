package com.jiwoo.wastesorting

import android.graphics.BitmapFactory
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 최근 기록 창: 목록, 별표 빼고 비우기. 항목을 누르면 onOpen을 부른다 */
class HistoryDialog(
    private val activity: AppCompatActivity,
    private val onOpen: (HistoryEntry) -> Unit
) {

    fun show() {
        activity.lifecycleScope.launch {
            val items = withContext(Dispatchers.IO) { HistoryStore.all(activity) }
            if (items.isEmpty()) {
                MaterialAlertDialogBuilder(activity)
                    .setTitle("최근 기록")
                    .setMessage("아직 기록이 없어요.\nAI 안내를 받으면 자동으로 저장돼요.")
                    .setPositiveButton("닫기", null)
                    .show()
                return@launch
            }

            val adapter = object : ArrayAdapter<HistoryEntry>(activity, 0, items) {
                override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                    val row = (convertView as? LinearLayout) ?: buildRow()
                    val e = getItem(position)!!
                    val thumb = row.getChildAt(0) as ImageView
                    val texts = row.getChildAt(1) as LinearLayout
                    val title = texts.getChildAt(0) as TextView
                    val sub = texts.getChildAt(1) as TextView

                    val bmp = e.thumbPath?.let { BitmapFactory.decodeFile(it) }
                    if (bmp != null) {
                        thumb.scaleType = ImageView.ScaleType.CENTER_CROP
                        thumb.setImageBitmap(bmp)
                    } else {
                        thumb.scaleType = ImageView.ScaleType.CENTER_INSIDE
                        thumb.setImageResource(android.R.drawable.ic_menu_search)
                    }
                    title.text = (if (e.starred) "★ " else "") + (e.response.itemName ?: e.title)
                    sub.text = "${e.response.category ?: "-"} · ${sourceName(e.source)} · ${formatTime(e.time)}"
                    return row
                }
            }

            MaterialAlertDialogBuilder(activity)
                .setTitle("최근 기록")
                .setAdapter(adapter) { _, which -> onOpen(items[which]) }
                .setNeutralButton("별표 빼고 비우기") { _, _ -> confirmClear() }
                .setNegativeButton("닫기", null)
                .show()
        }
    }

    private fun buildRow(): LinearLayout = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(20), dp(10), dp(20), dp(10))

        addView(ImageView(context).apply {
            layoutParams = LinearLayout.LayoutParams(dp(48), dp(48)).apply { marginEnd = dp(12) }
        })
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            addView(TextView(context).apply { textSize = 16f; maxLines = 1 })
            addView(TextView(context).apply { textSize = 13f; alpha = 0.7f; maxLines = 1 })
        })
    }

    private fun confirmClear() {
        MaterialAlertDialogBuilder(activity)
            .setTitle("기록 비우기")
            .setMessage("별표가 없는 기록을 모두 지울까요?\n별표 기록은 남아요.")
            .setPositiveButton("비우기") { _, _ ->
                activity.lifecycleScope.launch {
                    val n = withContext(Dispatchers.IO) { HistoryStore.clearUnstarred(activity) }
                    Toast.makeText(activity, "기록 ${n}개를 지웠어요", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun dp(v: Int) = (v * activity.resources.displayMetrics.density).toInt()

    companion object {
        private val timeFormat = SimpleDateFormat("MM/dd HH:mm", Locale.KOREA)

        /** 기록 시간 표시 (UI 스레드에서만 사용) */
        fun formatTime(time: Long): String = timeFormat.format(Date(time))

        fun sourceName(source: String) = when (source) {
            "camera" -> "카메라"
            "photo" -> "사진"
            "search" -> "검색"
            else -> source
        }
    }
}