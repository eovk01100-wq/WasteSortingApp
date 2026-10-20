package com.jiwoo.wastesorting

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.Log
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.CompatibilityList
import org.tensorflow.lite.gpu.GpuDelegate
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

data class Detection(
    val box: RectF,      // 원본 이미지 기준 좌표(px)
    val label: String,
    val score: Float,
    val classId: Int,
    val uncertain: Boolean = false,  // 최근 프레임 투표가 갈렸거나 표가 부족한지
    val agreement: Float = 1f,       // 1등 라벨이 받은 표 비율 (0~1)
    val trackId: Int = -1,           // 같은 물체 추적 번호
    val settling: Boolean = false    // 아직 표가 부족해 판단 중
)

/** 같은 스레드(카메라 스레드)에서만 사용할 것 */
class YoloDetector(
    context: Context,
    modelName: String = "waste_yolo11n_fp32.tflite",
    labelName: String = "labels.txt",
    private val confThreshold: Float = 0.4f,
    private val iouThreshold: Float = 0.45f
) {
    private val interpreter: Interpreter
    private var gpuDelegate: GpuDelegate? = null
    val accelerator: String
    val labels: List<String>

    // 마지막 detect()의 구간별 시간 (ms)
    var lastPreMs = 0f; private set
    var lastInferMs = 0f; private set
    var lastPostMs = 0f; private set

    private val inputSize: Int
    private val isNchw: Boolean
    val inputShapeText: String

    // ---- 재사용 버퍼 (매 프레임 새로 만들지 않음) ----
    private val inputBuffer: ByteBuffer
    private val inputFloats: FloatBuffer
    private val inputArray: FloatArray
    private val pixels: IntArray
    private val letterbox: Bitmap
    private val letterboxCanvas: Canvas
    private val drawPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val dstRect = RectF()

    private val outShape: IntArray
    private val output: Array<Array<FloatArray>>
    private val channelsFirst: Boolean
    private val numBoxes: Int
    val numClasses: Int
    private var coordScale = -1f   // 첫 프레임에서 한 번만 결정

    init {
        // 모델 로드 (assets → 메모리)
        val bytes = context.assets.open(modelName).use { it.readBytes() }
        val model = ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder())
        model.put(bytes)
        model.rewind()

        // GPU 가속 시도 → 실패하면 CPU로
        var interp: Interpreter? = null
        var acc = "CPU"
        try {
            val compat = CompatibilityList()
            if (compat.isDelegateSupportedOnThisDevice) {
                val delegate = GpuDelegate(compat.bestOptionsForThisDevice)
                try {
                    interp = Interpreter(model, Interpreter.Options().addDelegate(delegate))
                    gpuDelegate = delegate
                    acc = "GPU"
                } catch (t: Throwable) {
                    delegate.close()
                    throw t
                }
            }
        } catch (t: Throwable) {
            Log.w("WasteSorting", "GPU 가속 사용 불가, CPU로 실행", t)
        }
        if (interp == null) {
            model.rewind()
            interp = Interpreter(model, Interpreter.Options().apply { setNumThreads(4) })
        }
        interpreter = interp
        accelerator = acc

        labels = context.assets.open(labelName).bufferedReader()
            .readLines().map { it.trim() }.filter { it.isNotEmpty() }

        // 입력: [1, 640, 640, 3](NHWC) 또는 [1, 3, 640, 640](NCHW)
        val inShape = interpreter.getInputTensor(0).shape()
        isNchw = inShape[1] == 3
        inputSize = if (isNchw) inShape[2] else inShape[1]

        val area = inputSize * inputSize
        inputBuffer = ByteBuffer.allocateDirect(4 * area * 3).order(ByteOrder.nativeOrder())
        inputFloats = inputBuffer.asFloatBuffer()
        inputArray = FloatArray(area * 3)
        pixels = IntArray(area)
        letterbox = Bitmap.createBitmap(inputSize, inputSize, Bitmap.Config.ARGB_8888)
        letterboxCanvas = Canvas(letterbox)

        // 출력: [1, 11, 8400] 또는 [1, 8400, 11]
        outShape = interpreter.getOutputTensor(0).shape()
        output = Array(1) { Array(outShape[1]) { FloatArray(outShape[2]) } }
        channelsFirst = outShape[1] < outShape[2]
        numBoxes = if (channelsFirst) outShape[2] else outShape[1]
        numClasses = (if (channelsFirst) outShape[1] else outShape[2]) - 4

        inputShapeText = "in=${inShape.contentToString()} out=${outShape.contentToString()}"
    }

    fun detect(src: Bitmap): List<Detection> {
        val t0 = System.nanoTime()

        // 1) 레터박스: 비율을 유지한 채 640x640에 맞추고 남는 곳은 회색(114)
        val scale = minOf(inputSize.toFloat() / src.width, inputSize.toFloat() / src.height)
        val newW = src.width * scale
        val newH = src.height * scale
        val padX = (inputSize - newW) / 2f
        val padY = (inputSize - newH) / 2f

        letterboxCanvas.drawColor(Color.rgb(114, 114, 114))
        dstRect.set(padX, padY, padX + newW, padY + newH)
        letterboxCanvas.drawBitmap(src, null, dstRect, drawPaint)
        letterbox.getPixels(pixels, 0, inputSize, 0, 0, inputSize, inputSize)

        // 2) 픽셀 → 0~1 float, 배열에 채운 뒤 한 번에 복사
        val inv = 1f / 255f
        val area = pixels.size
        val arr = inputArray
        if (isNchw) {
            val g0 = area
            val b0 = area * 2
            for (i in 0 until area) {
                val p = pixels[i]
                arr[i] = ((p shr 16) and 0xFF) * inv
                arr[g0 + i] = ((p shr 8) and 0xFF) * inv
                arr[b0 + i] = (p and 0xFF) * inv
            }
        } else {
            var j = 0
            for (i in 0 until area) {
                val p = pixels[i]
                arr[j] = ((p shr 16) and 0xFF) * inv
                arr[j + 1] = ((p shr 8) and 0xFF) * inv
                arr[j + 2] = (p and 0xFF) * inv
                j += 3
            }
        }
        inputFloats.rewind()
        inputFloats.put(arr)
        inputBuffer.rewind()

        // 3) 추론
        val t1 = System.nanoTime()
        interpreter.run(inputBuffer, output)
        val t2 = System.nanoTime()

        // 4) 출력 해석
        val o = output[0]
        if (coordScale < 0f) coordScale = detectCoordScale(o)
        val cs = coordScale
        val maxW = src.width.toFloat()
        val maxH = src.height.toFloat()

        val candidates = ArrayList<Detection>()
        for (i in 0 until numBoxes) {
            // 가장 높은 분류 점수 (기준보다 낮으면 바로 건너뜀)
            var best = -1
            var bestScore = confThreshold
            if (channelsFirst) {
                for (c in 0 until numClasses) {
                    val s = o[4 + c][i]
                    if (s > bestScore) { bestScore = s; best = c }
                }
            } else {
                val row = o[i]
                for (c in 0 until numClasses) {
                    val s = row[4 + c]
                    if (s > bestScore) { bestScore = s; best = c }
                }
            }
            if (best < 0) continue

            val cx: Float; val cy: Float; val w: Float; val h: Float
            if (channelsFirst) {
                cx = o[0][i] * cs; cy = o[1][i] * cs; w = o[2][i] * cs; h = o[3][i] * cs
            } else {
                val row = o[i]
                cx = row[0] * cs; cy = row[1] * cs; w = row[2] * cs; h = row[3] * cs
            }

            // 레터박스 좌표 → 원본 이미지 좌표
            val left = ((cx - w / 2 - padX) / scale).coerceIn(0f, maxW)
            val top = ((cy - h / 2 - padY) / scale).coerceIn(0f, maxH)
            val right = ((cx + w / 2 - padX) / scale).coerceIn(0f, maxW)
            val bottom = ((cy + h / 2 - padY) / scale).coerceIn(0f, maxH)

            candidates.add(
                Detection(RectF(left, top, right, bottom),
                    labels.getOrElse(best) { "class$best" }, bestScore, best)
            )
        }

        val result = nms(candidates)
        val t3 = System.nanoTime()
        lastPreMs = (t1 - t0) / 1e6f
        lastInferMs = (t2 - t1) / 1e6f
        lastPostMs = (t3 - t2) / 1e6f
        return result
    }

    // 좌표가 0~1로 정규화돼 있으면 입력 크기를 곱해야 함 (모델마다 고정이라 한 번만 확인)
    private fun detectCoordScale(o: Array<FloatArray>): Float {
        var maxCoord = 0f
        for (i in 0 until numBoxes) {
            val x = if (channelsFirst) o[0][i] else o[i][0]
            val y = if (channelsFirst) o[1][i] else o[i][1]
            if (x > maxCoord) maxCoord = x
            if (y > maxCoord) maxCoord = y
        }
        return if (maxCoord <= 2f) inputSize.toFloat() else 1f
    }

    // 겹치는 박스 제거 (클래스 구분 없이 점수 높은 것만 남김)
    private fun nms(dets: List<Detection>): List<Detection> {
        val sorted = dets.sortedByDescending { it.score }.toMutableList()
        val keep = ArrayList<Detection>()
        while (sorted.isNotEmpty()) {
            val top = sorted.removeAt(0)
            keep.add(top)
            sorted.removeAll { iou(top.box, it.box) > iouThreshold }
        }
        return keep
    }

    private fun iou(a: RectF, b: RectF): Float {
        val l = maxOf(a.left, b.left)
        val t = maxOf(a.top, b.top)
        val r = minOf(a.right, b.right)
        val bt = minOf(a.bottom, b.bottom)
        val inter = maxOf(0f, r - l) * maxOf(0f, bt - t)
        val union = a.width() * a.height() + b.width() * b.height() - inter
        return if (union <= 0f) 0f else inter / union
    }

    fun close() {
        interpreter.close()
        gpuDelegate?.close()
        letterbox.recycle()
    }
}