package com.jiwoo.wastesorting

import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.lifecycle.lifecycleScope
import com.google.android.material.chip.Chip
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 아래쪽 결과 카드: 로딩, 안내 표시, 별표, 실패 시 버튼
 * UI 스레드에서만 사용 (showing만 카메라 스레드에서도 읽음)
 * setContentView 다음에 만들어야 함
 */
class ResultCard(
    private val activity: AppCompatActivity,
    onClose: () -> Unit,
    onRetry: () -> Unit,
    onSearch: () -> Unit
) {
    val panel: View = activity.findViewById(R.id.resultPanel)
    private val scroll: ScrollView = activity.findViewById(R.id.resultScroll)
    private val cropImage: ImageView = activity.findViewById(R.id.cropImage)
    private val itemNameText: TextView = activity.findViewById(R.id.itemNameText)
    private val categoryChip: Chip = activity.findViewById(R.id.categoryChip)
    private val statusChip: Chip = activity.findViewById(R.id.statusChip)
    private val closeButton: ImageButton = activity.findViewById(R.id.closeButton)
    private val starButton: ImageButton = activity.findViewById(R.id.starButton)
    private val loadingBar: ProgressBar = activity.findViewById(R.id.loadingBar)
    private val messageText: TextView = activity.findViewById(R.id.messageText)
    private val stepsContainer: LinearLayout = activity.findViewById(R.id.stepsContainer)
    private val cautionText: TextView = activity.findViewById(R.id.cautionText)
    private val noteText: TextView = activity.findViewById(R.id.noteText)
    private val stepsTitle: TextView = activity.findViewById(R.id.stepsTitle)
    private val failActions: View = activity.findViewById(R.id.failActions)

    /** 카드가 떠 있는지 (카메라 스레드에서도 읽음) */
    @Volatile var showing = false
        private set

    /** 화면에 보이는지 (UI 스레드) */
    val isVisible: Boolean get() = panel.visibility == View.VISIBLE

    private var currentHistoryId: Long? = null   // 지금 카드가 어떤 기록인지

    init {
        closeButton.setOnClickListener { onClose() }
        starButton.setOnClickListener { toggleStar() }
        activity.findViewById<View>(R.id.failRetryButton).setOnClickListener {
            failActions.visibility = View.GONE
            onRetry()
        }
        activity.findViewById<View>(R.id.failSearchButton).setOnClickListener {
            failActions.visibility = View.GONE
            onSearch()
        }
    }

    fun hide() = setVisible(false)

    /** 실패했을 때 [검색하기] [다시 시도] 버튼 보이기 */
    fun showFailActions() {
        failActions.visibility = View.VISIBLE
    }

    private fun setVisible(visible: Boolean) {
        panel.visibility = if (visible) View.VISIBLE else View.GONE
        showing = visible
    }

    // ---------------- 로딩 ----------------

    fun showLoading(det: Detection, crop: Bitmap) {
        failActions.visibility = View.GONE
        cropImage.scaleType = ImageView.ScaleType.CENTER_CROP
        cropImage.setImageBitmap(crop)
        itemNameText.text = "분석 중..."
        setCategoryChip("${WasteClasses.koName(det.label)} ${(det.score * 100).toInt()}%", colorFor(det.classId))
        statusChip.visibility = View.GONE
        loadingBar.visibility = View.VISIBLE
        closeButton.visibility = View.INVISIBLE
        messageText.visibility = View.GONE
        stepsContainer.removeAllViews()
        stepsTitle.visibility = View.GONE
        cautionText.visibility = View.GONE
        noteText.visibility = View.GONE
        starButton.visibility = View.GONE
        setVisible(true)
        scroll.scrollTo(0, 0)
    }

    fun showSearchLoading(query: String) {
        failActions.visibility = View.GONE
        cropImage.scaleType = ImageView.ScaleType.CENTER_INSIDE
        cropImage.setImageResource(android.R.drawable.ic_menu_search)
        itemNameText.text = "'$query' 찾는 중..."
        categoryChip.visibility = View.GONE
        statusChip.visibility = View.GONE
        loadingBar.visibility = View.VISIBLE
        closeButton.visibility = View.INVISIBLE
        messageText.visibility = View.GONE
        stepsContainer.removeAllViews()
        stepsTitle.visibility = View.GONE
        cautionText.visibility = View.GONE
        noteText.visibility = View.GONE
        starButton.visibility = View.GONE
        setVisible(true)
        scroll.scrollTo(0, 0)
    }

    fun showWholeLoading(frame: Bitmap) {
        failActions.visibility = View.GONE
        cropImage.scaleType = ImageView.ScaleType.CENTER_CROP
        cropImage.setImageBitmap(frame)
        itemNameText.text = "화면 전체 분석 중..."
        categoryChip.visibility = View.GONE
        statusChip.visibility = View.GONE
        starButton.visibility = View.GONE
        loadingBar.visibility = View.VISIBLE
        closeButton.visibility = View.INVISIBLE
        messageText.visibility = View.GONE
        stepsContainer.removeAllViews()
        stepsTitle.visibility = View.GONE
        cautionText.visibility = View.GONE
        noteText.visibility = View.GONE
        setVisible(true)
        scroll.scrollTo(0, 0)
    }

    // ---------------- 안내 ----------------

    fun showGuide(
        r: DisposalResponse,
        crop: Bitmap?,
        classIdHint: Int,
        status: String,
        message: String? = null,
        isWarning: Boolean = false,
        historyId: Long? = null
    ) {
        failActions.visibility = View.GONE
        loadingBar.visibility = View.GONE
        closeButton.visibility = View.VISIBLE
        if (crop != null) {
            cropImage.scaleType = ImageView.ScaleType.CENTER_CROP
            cropImage.setImageBitmap(crop)
        } else {
            cropImage.scaleType = ImageView.ScaleType.CENTER_INSIDE
            cropImage.setImageResource(android.R.drawable.ic_menu_search)
        }
        itemNameText.text = r.itemName ?: "알 수 없는 물건"

        // 분류 배지 색: Gemini 판단 분류 → 없으면 힌트 분류, other면 회색
        val g = r.yoloClass?.trim()?.lowercase()
        val classId = when {
            g == null -> classIdHint
            g == WasteClasses.OTHER -> -1
            else -> WasteClasses.indexOf(g).takeIf { it >= 0 } ?: classIdHint
        }
        setCategoryChip(r.category ?: "-", colorFor(classId))
        setStatusChip(status, isWarning)

        if (message.isNullOrBlank()) {
            messageText.visibility = View.GONE
        } else {
            messageText.text = message
            messageText.setTextColor(color(if (isWarning) R.color.brand_caution_text else R.color.brand_green_text))
            messageText.setBackgroundResource(if (isWarning) R.drawable.bg_message_warn else R.drawable.bg_message_ok)
            messageText.visibility = View.VISIBLE
        }

        stepsContainer.removeAllViews()
        stepsTitle.visibility = View.GONE
        r.steps.orEmpty().forEachIndexed { i, s -> addStep(i + 1, s) }
        stepsTitle.visibility = if (r.steps.isNullOrEmpty()) View.GONE else View.VISIBLE

        if (r.caution.isNullOrBlank()) {
            cautionText.visibility = View.GONE
        } else {
            cautionText.text = r.caution
            cautionText.visibility = View.VISIBLE
        }

        if (r.note.isNullOrBlank()) {
            noteText.visibility = View.GONE
        } else {
            noteText.text = "참고: ${r.note}"
            noteText.visibility = View.VISIBLE
        }

        setVisible(true)
        currentHistoryId = historyId
        updateStarButton()
        scroll.scrollTo(0, 0)
    }

    // ---------------- 별표 ----------------

    private fun updateStarButton() {
        val id = currentHistoryId
        if (id == null) {
            starButton.visibility = View.GONE
            return
        }
        val starred = HistoryStore.isStarred(activity, id)
        starButton.setImageResource(
            if (starred) R.drawable.ic_star_on else R.drawable.ic_star_off
        )
        starButton.visibility = View.VISIBLE
    }

    private fun toggleStar() {
        val id = currentHistoryId ?: return
        activity.lifecycleScope.launch {
            val starred = withContext(Dispatchers.IO) { HistoryStore.toggleStar(activity, id) }
            if (currentHistoryId == id) updateStarButton()
            Toast.makeText(
                activity,
                if (starred) "별표를 붙였어요" else "별표를 뗐어요",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    // ---------------- 그리기 도우미 ----------------

    private fun colorFor(classId: Int): Int {
        val c = OverlayView.CLASS_COLORS
        return if (classId < 0) Color.GRAY else c[classId % c.size]
    }

    private fun addStep(index: Int, text: String) {
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(7), 0, dp(7))
        }
        val num = TextView(activity).apply {
            this.text = index.toString()
            setTextColor(Color.WHITE)
            textSize = 13f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER
            setBackgroundResource(R.drawable.bg_step_number)
            layoutParams = LinearLayout.LayoutParams(dp(26), dp(26)).apply { marginEnd = dp(12) }
        }
        val body = TextView(activity).apply {
            this.text = text
            setTextColor(color(R.color.brand_ink))
            textSize = 15f
            setLineSpacing(0f, 1.25f)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        row.addView(num)
        row.addView(body)
        stepsContainer.addView(row)
    }

    /** 분류 칩: 분류 색을 옅게 깐 바탕 + 진한 같은 색 글자 */
    private fun setCategoryChip(text: String, classColor: Int) {
        categoryChip.text = text
        categoryChip.chipBackgroundColor =
            ColorStateList.valueOf(ColorUtils.blendARGB(classColor, Color.WHITE, 0.85f))
        categoryChip.setTextColor(ColorUtils.blendARGB(classColor, Color.BLACK, 0.4f))
        categoryChip.chipStrokeWidth = 0f
        categoryChip.visibility = View.VISIBLE
    }

    /** 상태 칩: 평소엔 회색 테두리, 경고면 주황 */
    private fun setStatusChip(text: String, isWarning: Boolean) {
        statusChip.text = text
        if (isWarning) {
            statusChip.chipBackgroundColor = ColorStateList.valueOf(color(R.color.brand_caution_bg))
            statusChip.setTextColor(color(R.color.brand_caution_text))
            statusChip.chipStrokeWidth = 0f
        } else {
            statusChip.chipBackgroundColor = ColorStateList.valueOf(Color.WHITE)
            statusChip.setTextColor(color(R.color.brand_gray))
            statusChip.chipStrokeColor = ColorStateList.valueOf(color(R.color.brand_outline))
            statusChip.chipStrokeWidth = dp(1).toFloat()
        }
        statusChip.visibility = View.VISIBLE
    }

    private fun color(id: Int) = ContextCompat.getColor(activity, id)

    private fun dp(v: Int) = (v * activity.resources.displayMetrics.density).toInt()
}