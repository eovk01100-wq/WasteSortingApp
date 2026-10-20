package com.jiwoo.wastesorting

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View

class OverlayView(context: Context, attrs: AttributeSet?) : View(context, attrs) {

    private var detections: List<Detection> = emptyList()
    private var imageWidth = 1
    private var imageHeight = 1
    // 물체별로 화면에 그리고 있는 박스 (이미지 좌표). 새 위치로 조금씩 따라감
    private val shownBoxes = HashMap<Int, RectF>()

    /** 박스를 탭했을 때 */
    var onBoxClick: ((Detection) -> Unit)? = null
    /** 빈 곳을 탭했을 때 (터치 포커스) */
    var onEmptyTap: ((Float, Float) -> Unit)? = null
    /** 두 손가락 줌 (배율 변화량) */
    var onPinch: ((Float) -> Unit)? = null
    /** 빈 곳 두 번 탭 (줌 초기화) */
    var onDoubleTapEmpty: (() -> Unit)? = null
    /** 지금 줌/포커스를 써도 되는지 (MainActivity가 정함) */
    var cameraControlsEnabled: () -> Boolean = { true }

    /** true: 사진 전체가 보이게 맞춤(사진 모드), false: 화면을 꽉 채움(카메라) */
    var fitCenter = false
        set(value) { field = value; invalidate() }

    // ---- 그리기 ----
    private val boxPaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 6f
    }
    private val textBgPaint = Paint().apply { style = Paint.Style.FILL }
    private val textPaint = Paint().apply {
        color = Color.WHITE
        textSize = 42f
        isAntiAlias = true
    }
    private val focusPaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f
        color = Color.WHITE
        isAntiAlias = true
    }

    // 포커스 링 표시 위치와 종료 시각
    private var focusX = 0f
    private var focusY = 0f
    private var focusUntil = 0L

    fun setResults(results: List<Detection>, imgW: Int, imgH: Int) {
        detections = results
        imageWidth = imgW
        imageHeight = imgH

        // 사라진 물체는 지우고, 새로 나타난 물체는 그 자리에서 시작
        val ids = results.map { it.trackId }.toSet()
        shownBoxes.keys.retainAll(ids)
        for (d in results) {
            if (d.trackId >= 0 && d.trackId !in shownBoxes) shownBoxes[d.trackId] = RectF(d.box)
        }
        invalidate()
    }
    private fun scale() = if (fitCenter)
        minOf(width.toFloat() / imageWidth, height.toFloat() / imageHeight)
    else
        maxOf(width.toFloat() / imageWidth, height.toFloat() / imageHeight)

    private fun dx(s: Float) = (width - imageWidth * s) / 2f
    private fun dy(s: Float) = (height - imageHeight * s) / 2f

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val s = scale()
        val dx = dx(s)
        val dy = dy(s)
        var moving = false

        for (d in detections) {
            // 추적 중인 물체는 이전 위치에서 새 위치로 조금씩 이동
            val b = shownBoxes[d.trackId]?.also { cur ->
                if (approach(cur, d.box)) moving = true
            } ?: d.box

            val color = CLASS_COLORS[d.classId % CLASS_COLORS.size]
            val r = RectF(b.left * s + dx, b.top * s + dy, b.right * s + dx, b.bottom * s + dy)
            boxPaint.color = color
            canvas.drawRect(r, boxPaint)

            val text = when {
                d.settling -> "${WasteClasses.koName(d.label)}…"
                d.uncertain -> "${WasteClasses.koName(d.label)}? (확인 필요)"
                else -> "${WasteClasses.koName(d.label)} ${(d.score * 100).toInt()}%"
            }
            val textW = textPaint.measureText(text)
            val textTop = maxOf(0f, r.top - 52f)
            textBgPaint.color = color
            canvas.drawRect(r.left, textTop, r.left + textW + 20f, textTop + 52f, textBgPaint)
            canvas.drawText(text, r.left + 10f, textTop + 40f, textPaint)
        }

        // 포커스 링
        if (SystemClock.uptimeMillis() < focusUntil) {
            canvas.drawCircle(focusX, focusY, 70f, focusPaint)
            moving = true
        }

        // 아직 따라가는 중이면 다음 화면 프레임에 다시 그림
        if (moving) postInvalidateOnAnimation()
    }

    /** cur를 target 쪽으로 30% 이동. 아직 차이가 남아 있으면 true */
    private fun approach(cur: RectF, target: RectF): Boolean {
        val k = 0.3f
        cur.left += (target.left - cur.left) * k
        cur.top += (target.top - cur.top) * k
        cur.right += (target.right - cur.right) * k
        cur.bottom += (target.bottom - cur.bottom) * k
        return Math.abs(target.left - cur.left) > 0.5f ||
                Math.abs(target.top - cur.top) > 0.5f ||
                Math.abs(target.right - cur.right) > 0.5f ||
                Math.abs(target.bottom - cur.bottom) > 0.5f
    }

    // ---- 터치 ----

    // 화면 좌표의 박스 찾기 (겹치면 가장 작은 박스)
    private fun boxAt(x: Float, y: Float): Detection? {
        val s = scale()
        val ix = (x - dx(s)) / s
        val iy = (y - dy(s)) / s
        return detections
            .filter { it.box.contains(ix, iy) }
            .minByOrNull { it.box.width() * it.box.height() }
    }

    private fun showFocusRing(x: Float, y: Float) {
        focusX = x
        focusY = y
        focusUntil = SystemClock.uptimeMillis() + 800
        invalidate()
    }

    private var scaling = false

    private val scaleDetector = ScaleGestureDetector(context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                scaling = true
                return cameraControlsEnabled()
            }

            override fun onScale(detector: ScaleGestureDetector): Boolean {
                onPinch?.invoke(detector.scaleFactor)
                return true
            }
        })

    private val gestureDetector = GestureDetector(context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean {
                scaling = false   // 새 터치 시작
                return true
            }

            // 박스는 바로 선택
            override fun onSingleTapUp(e: MotionEvent): Boolean {
                if (scaling) return false
                val hit = boxAt(e.x, e.y) ?: return false
                onBoxClick?.invoke(hit)
                return true
            }

            // 빈 곳 한 번 탭 → 포커스 (두 번 탭과 구분하려고 잠깐 기다린 뒤 실행)
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                if (scaling || !cameraControlsEnabled()) return false
                if (boxAt(e.x, e.y) != null) return false
                showFocusRing(e.x, e.y)
                onEmptyTap?.invoke(e.x, e.y)
                return true
            }

            // 빈 곳 두 번 탭 → 줌 초기화
            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (!cameraControlsEnabled()) return false
                if (boxAt(e.x, e.y) != null) return false
                onDoubleTapEmpty?.invoke()
                return true
            }
        })

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)
        return true
    }

    companion object {
        val CLASS_COLORS = intArrayOf(
            Color.rgb(255, 87, 34),   // can
            Color.rgb(0, 188, 212),   // glass
            Color.rgb(255, 193, 7),   // pack
            Color.rgb(121, 85, 72),   // paper
            Color.rgb(33, 150, 243),  // pet
            Color.rgb(76, 175, 80),   // plastic
            Color.rgb(156, 39, 176)   // vinyl
        )
    }
}