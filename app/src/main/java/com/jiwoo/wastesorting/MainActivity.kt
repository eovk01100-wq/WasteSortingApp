package com.jiwoo.wastesorting

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.RectF
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.ImageButton
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.TorchState
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import retrofit2.HttpException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {
    private lateinit var previewView: PreviewView
    private lateinit var photoView: ImageView
    private lateinit var overlayView: OverlayView
    private lateinit var infoText: TextView
    private lateinit var hintText: TextView
    private lateinit var torchButton: ImageButton
    private lateinit var galleryButton: ImageButton
    private lateinit var searchButton: ImageButton
    private lateinit var resultCard: ResultCard   // 아래쪽 결과 카드

    // 모델은 이 스레드에서만 사용 (GPU 가속은 만든 스레드에서만 동작)
    private val cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private var detector: YoloDetector? = null
    private var loadFailed = false
    private val stabilizer = LabelStabilizer()
    private val resultCache = ResultCache()
    // 검색 결과 재사용 (최근 30개)
    private val searchCache = object : LinkedHashMap<String, DisposalResponse>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, DisposalResponse>?) = size > 30
    }
    private var camera: Camera? = null
    @Volatile private var photoMode = false
    private var lastInferenceAt = 0L
    @Volatile private var showTiming = false   // 상태 표시를 누르면 구간별 시간도 표시

    // 구간별 평균 시간 (카메라 스레드에서만 사용)
    private var avgConv = 0f
    private var avgPre = 0f
    private var avgInfer = 0f
    private var avgPost = 0f
    private var avgCount = 0

    private fun avg(old: Float, v: Float) = if (avgCount <= 1) v else old * 0.9f + v * 0.1f

    private var zoomTarget = 1f   // 현재 목표 줌 배율 (UI 스레드에서만 사용)

    // 화면에 그려진 박스와 같은 이미지 (UI 스레드에서만 사용)
    private var shownBitmap: Bitmap? = null
    private var requesting = false
    // 요청 번호. 화면 모드가 바뀌면 올려서, 그 전에 보낸 요청의 결과는 표시하지 않음
    private var requestSeq = 0
    private var currentJob: Job? = null   // 진행 중인 Gemini 요청

    private fun cancelPendingRequest() {
        requestSeq++
        currentJob?.cancel()   // 네트워크 요청까지 실제로 중단
        currentJob = null
        requesting = false
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startCamera()
        else Toast.makeText(this, "카메라 권한이 필요합니다", Toast.LENGTH_LONG).show()
    }

    // 갤러리 사진 선택 (별도 저장소 권한 필요 없음)
    private val pickPhoto = registerForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri -> if (uri != null) openPhoto(uri) }

    // 사진 모드에서 뒤로 가기 → 카메라로 복귀
    private val backToCamera = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = exitPhotoMode()
    }

    private val exportLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { feedbackDialogs.confirmClear(afterExport = true) }

    // 학습 데이터 창 (공유 화면은 Activity에서만 열 수 있어서 연결선만 넘김)
    private val feedbackDialogs: FeedbackDialogs by lazy {
        FeedbackDialogs(this) { intent -> exportLauncher.launch(intent) }
    }

    // 최근 기록 창 (항목을 누르면 결과 카드로 열기)
    private val historyDialog: HistoryDialog by lazy {
        HistoryDialog(this) { e -> openHistory(e) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        GeminiClient.init(this)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT)
        )
        setContentView(R.layout.activity_main)
        previewView = findViewById(R.id.previewView)
        photoView = findViewById(R.id.photoView)
        overlayView = findViewById(R.id.overlayView)
        infoText = findViewById(R.id.infoText)
        hintText = findViewById(R.id.hintText)
        torchButton = findViewById(R.id.torchButton)
        galleryButton = findViewById(R.id.galleryButton)
        searchButton = findViewById(R.id.searchButton)

        resultCard = ResultCard(
            this,
            onClose = { hideResult() },
            onRetry = { analyzeWholeFrame() },
            onSearch = { showSearchDialog() }
        )

        searchButton.setOnClickListener { showSearchDialog() }
        overlayView.onBoxClick = { det -> onDetectionSelected(det) }
        overlayView.cameraControlsEnabled = {
            camera != null && !photoMode && !resultCard.isVisible
        }
        overlayView.onPinch = { factor -> zoomBy(factor) }
        overlayView.onEmptyTap = { x, y -> focusAt(x, y) }
        overlayView.onDoubleTapEmpty = { resetZoom() }
        // 안내 글자를 누르면 화면 전체 분석
        hintText.setOnClickListener { confirmWholeFrameOnce { analyzeWholeFrame() } }
        // 왼쪽 위 상태 글자를 길게 누르면 학습 데이터 관리
        // 누르면 구간별 시간 보이기/숨기기
        infoText.setOnClickListener { showTiming = !showTiming }
        infoText.setOnLongClickListener {
            feedbackDialogs.show()
            true
        }
        torchButton.setOnClickListener { toggleTorch() }
        galleryButton.setOnClickListener {
            if (photoMode) exitPhotoMode()
            else pickPhoto.launch(
                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
            )
        }
        onBackPressedDispatcher.addCallback(this, backToCamera)

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) {
            startCamera()
        } else {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
        applySystemBarInsets()
    }

    // ---------------- 카메라 ----------------

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                val provider = future.get()
                val selector = ResolutionSelector.Builder()
                    .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                    .build()

                val preview = Preview.Builder()
                    .setResolutionSelector(selector)
                    .build()
                    .also { it.setSurfaceProvider(previewView.surfaceProvider) }

                val analysis = ImageAnalysis.Builder()
                    .setResolutionSelector(selector)
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                    .build()
                analysis.setAnalyzer(cameraExecutor) { proxy -> analyze(proxy) }

                provider.unbindAll()
                camera = provider.bindToLifecycle(
                    this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis
                )

                // 플래시 상태를 버튼 아이콘에 반영
                camera?.cameraInfo?.torchState?.observe(this) { state ->
                    val on = state == TorchState.ON
                    torchButton.setImageResource(if (on) R.drawable.ic_flash_on else R.drawable.ic_flash)
                    torchButton.contentDescription = if (on) "플래시 끄기" else "플래시 켜기"
                }
                updateTorchButton()
            } catch (e: Exception) {
                Log.e(TAG, "카메라 시작 실패", e)
                Toast.makeText(this, "카메라를 시작할 수 없습니다", Toast.LENGTH_LONG).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    // 상태 표시줄·내비게이션 바·카메라 구멍 높이만큼 여백 추가
    private fun applySystemBarInsets() {
        val topArea = findViewById<View>(R.id.topArea)
        val panel = resultCard.panel
        val baseTop = topArea.paddingTop
        val baseHint = (hintText.layoutParams as ViewGroup.MarginLayoutParams).bottomMargin
        val basePanel = (panel.layoutParams as ViewGroup.MarginLayoutParams).bottomMargin

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(android.R.id.content)) { _, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            topArea.updatePadding(top = baseTop + bars.top)
            hintText.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                bottomMargin = baseHint + bars.bottom
            }
            panel.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                bottomMargin = basePanel + bars.bottom
            }
            insets
        }
    }

    private fun hasFlash() = camera?.cameraInfo?.hasFlashUnit() == true

    private fun updateTorchButton() {
        torchButton.visibility = if (hasFlash() && !photoMode) View.VISIBLE else View.GONE
    }

    private fun toggleTorch() {
        val cam = camera ?: return
        if (!hasFlash()) {
            Toast.makeText(this, "이 기기에는 플래시가 없어요", Toast.LENGTH_SHORT).show()
            return
        }
        val on = cam.cameraInfo.torchState.value == TorchState.ON
        cam.cameraControl.enableTorch(!on)
    }

    private fun turnOffTorch() {
        if (camera?.cameraInfo?.torchState?.value == TorchState.ON) {
            camera?.cameraControl?.enableTorch(false)
        }
    }

    // ---------------- 줌 / 포커스 ----------------

    private fun zoomBy(factor: Float) {
        val cam = camera ?: return
        val zs = cam.cameraInfo.zoomState.value ?: return
        val max = minOf(zs.maxZoomRatio, MAX_ZOOM)
        zoomTarget = (zoomTarget * factor).coerceIn(zs.minZoomRatio, max)
        cam.cameraControl.setZoomRatio(zoomTarget)
    }

    private fun resetZoom() {
        val cam = camera ?: return
        val min = cam.cameraInfo.zoomState.value?.minZoomRatio ?: 1f
        zoomTarget = maxOf(1f, min)
        cam.cameraControl.setZoomRatio(zoomTarget)
    }

    private fun focusAt(x: Float, y: Float) {
        val cam = camera ?: return
        // OverlayView와 PreviewView는 같은 크기·위치라 좌표를 그대로 사용
        val point = previewView.meteringPointFactory.createPoint(x, y)
        val action = FocusMeteringAction.Builder(
            point, FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE
        ).setAutoCancelDuration(3, TimeUnit.SECONDS).build()
        cam.cameraControl.startFocusAndMetering(action)
    }

    // ---------------- 모델 ----------------

    // 카메라 스레드에서만 호출
    private fun ensureDetector(): YoloDetector? {
        if (loadFailed) return null
        detector?.let { return it }

        val d = try {
            YoloDetector(this)
        } catch (e: Exception) {
            failLoad("모델을 불러오지 못했어요: ${e.message}", e)
            return null
        }

        // 분류 순서와 개수가 앱과 다르면 틀린 이름을 표시하게 되므로 실행하지 않음
        val problem = when {
            d.numClasses != WasteClasses.NAMES.size ->
                "모델의 분류 개수(${d.numClasses})가 앱(${WasteClasses.NAMES.size})과 달라요"
            !WasteClasses.matches(d.labels) ->
                "labels.txt 순서가 앱의 분류 목록과 달라요: ${d.labels}"
            else -> null
        }
        if (problem != null) {
            d.close()
            failLoad(problem, null)
            return null
        }
        detector = d
        return d
    }

    private fun failLoad(msg: String, e: Throwable?) {
        loadFailed = true
        Log.e(TAG, msg, e)
        runOnUiThread { infoText.text = "모델을 사용할 수 없어요\n$msg" }
    }

    // 카메라 분석 스레드
    private fun analyze(proxy: ImageProxy) {
        // 사진 모드이거나 결과 카드를 보는 중이면 추론하지 않음
        if (photoMode || resultCard.showing) { proxy.close(); return }

        // 최소 간격보다 빨리 들어온 프레임은 건너뜀
        val now = SystemClock.uptimeMillis()
        if (now - lastInferenceAt < MIN_INTERVAL_MS) { proxy.close(); return }
        lastInferenceAt = now
        val det = ensureDetector() ?: run { proxy.close(); return }

        val convStart = System.nanoTime()
        // 변환 중 오류가 나도 프레임은 반드시 닫음 (안 닫으면 카메라가 멈춤)
        val (rotation, raw) = try {
            proxy.imageInfo.rotationDegrees to proxy.toBitmap()
        } catch (e: Exception) {
            Log.e(TAG, "프레임 변환 실패", e)
            return
        } finally {
            proxy.close()
        }

        val bitmap = if (rotation != 0) {
            val m = Matrix().apply { postRotate(rotation.toFloat()) }
            Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, m, true)
                .also { if (it !== raw) raw.recycle() }   // 회전본만 쓰므로 원본은 바로 정리
        } else raw
        val convMs = (System.nanoTime() - convStart) / 1e6f

        val start = SystemClock.uptimeMillis()
        val results = try {
            det.detect(bitmap)
        } catch (e: Exception) {
            Log.e(TAG, "추론 실패", e)
            runOnUiThread { infoText.text = "추론 실패: ${e.javaClass.simpleName}\n${e.message}" }
            return
        }
        val ms = SystemClock.uptimeMillis() - start
        val stable = stabilizer.update(results)
        avgCount++
        avgConv = avg(avgConv, convMs)
        avgPre = avg(avgPre, det.lastPreMs)
        avgInfer = avg(avgInfer, det.lastInferMs)
        avgPost = avg(avgPost, det.lastPostMs)
        val timing = "변환 %.0f · 전처리 %.0f · 추론 %.0f · 후처리 %.0f ms"
            .format(avgConv, avgPre, avgInfer, avgPost)

        runOnUiThread {
            // 사진 모드이거나 결과를 보고 있는 동안에는 화면 고정
            if (photoMode || resultCard.isVisible) return@runOnUiThread
            shownBitmap = bitmap
            overlayView.setResults(stable, bitmap.width, bitmap.height)
            updateHint(stable.size)
            val zoomText = if (zoomTarget > 1.05f) " · ${"%.1f".format(zoomTarget)}x" else ""
            infoText.text = "${det.accelerator} · ${ms}ms · 물체 ${stable.size}개$zoomText" +
                    if (showTiming) "\n$timing" else ""
        }
    }

    // ---------------- 갤러리 사진 모드 ----------------

    private fun openPhoto(uri: Uri) {
        cancelPendingRequest()
        photoMode = true
        backToCamera.isEnabled = true
        turnOffTorch()
        updateTorchButton()
        galleryButton.setImageResource(R.drawable.ic_camera)
        galleryButton.contentDescription = "카메라로 돌아가기"
        resultCard.hide()
        hintText.visibility = View.VISIBLE
        overlayView.setResults(emptyList(), 1, 1)
        infoText.text = "사진 불러오는 중..."

        lifecycleScope.launch {
            val photo = withContext(Dispatchers.IO) {
                try {
                    loadBitmap(uri)
                } catch (e: Exception) {
                    Log.e(TAG, "사진 불러오기 실패", e)
                    null
                }
            }
            if (!photoMode) return@launch
            if (photo == null) {
                Toast.makeText(this@MainActivity, "사진을 불러오지 못했어요", Toast.LENGTH_SHORT).show()
                exitPhotoMode()
                return@launch
            }
            photoView.scaleType = ImageView.ScaleType.FIT_CENTER
            photoView.setImageBitmap(photo)
            photoView.visibility = View.VISIBLE
            overlayView.fitCenter = true
            shownBitmap = photo
            infoText.text = "사진 분석 중..."

            cameraExecutor.execute {
                val det = ensureDetector() ?: return@execute
                val start = SystemClock.uptimeMillis()
                val results = try {
                    det.detect(photo)
                } catch (e: Exception) {
                    Log.e(TAG, "사진 추론 실패", e)
                    emptyList()
                }
                val ms = SystemClock.uptimeMillis() - start

                runOnUiThread {
                    if (!photoMode || shownBitmap !== photo) return@runOnUiThread
                    overlayView.setResults(results, photo.width, photo.height)
                    updateHint(results.size)
                    infoText.text = "사진 · ${det.accelerator} · ${ms}ms · 물체 ${results.size}개" +
                            if (results.isEmpty()) "\n탐지된 물체가 없어요" else ""
                }
            }
        }
    }

    private fun exitPhotoMode() {
        cancelPendingRequest()
        photoMode = false
        backToCamera.isEnabled = false
        photoView.setImageDrawable(null)
        photoView.visibility = View.GONE
        overlayView.fitCenter = false
        overlayView.setResults(emptyList(), 1, 1)
        resultCard.hide()
        hintText.visibility = View.VISIBLE
        galleryButton.setImageResource(R.drawable.ic_image)
        galleryButton.contentDescription = "사진 불러오기"
        shownBitmap = null
        updateTorchButton()
        cameraExecutor.execute { stabilizer.reset() }  // 안정화는 카메라 스레드에서만 사용
    }

    // 큰 사진은 긴 변 1280px로 줄여서 불러옴 (사진 방향 자동 보정)
    private fun loadBitmap(uri: Uri): Bitmap {
        val maxSide = 1280
        return if (Build.VERSION.SDK_INT >= 28) {
            val source = ImageDecoder.createSource(contentResolver, uri)
            ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE  // 픽셀을 읽으려면 필요
                val w = info.size.width
                val h = info.size.height
                val scale = minOf(1f, maxSide.toFloat() / maxOf(w, h))
                if (scale < 1f) decoder.setTargetSize((w * scale).toInt(), (h * scale).toInt())
            }
        } else {
            @Suppress("DEPRECATION")
            val bmp = MediaStore.Images.Media.getBitmap(contentResolver, uri)
            val scale = minOf(1f, maxSide.toFloat() / maxOf(bmp.width, bmp.height))
            if (scale < 1f) Bitmap.createScaledBitmap(
                bmp, (bmp.width * scale).toInt(), (bmp.height * scale).toInt(), true
            ) else bmp
        }
    }

    // ---------------- 박스 선택 → 안내 ----------------

    // 박스를 눌렀을 때 (UI 스레드)
    private fun onDetectionSelected(det: Detection) {
        if (requesting) return
        val frame = shownBitmap ?: return
        val crop = cropDetection(frame, det.box)
        val source = if (photoMode) "photo" else "camera"   // 요청 시점에 출처 고정
        hintText.visibility = View.GONE
        freezeFrame(frame)

        // 1) 라벨이 확실하면 저장된 결과부터 확인
        if (!det.uncertain) {
            resultCache.find(det.label, det.trackId, crop)?.let { cached ->
                resultCard.showGuide(cached, crop, det.classId, status = "저장된 결과")
                return
            }
        }

        // 2) 인터넷이 없으면 기본 안내
        if (!isOnline()) {
            val warn = if (det.uncertain) "탐지 결과가 확실하지 않아요. 아래 안내가 맞지 않을 수 있어요." else null
            resultCard.showGuide(OfflineGuide.get(det.label), crop, det.classId,
                status = "오프라인 기본 안내", message = warn, isWarning = true)
            return
        }

        // 3) Gemini에 질의
        requesting = true
        val myReq = ++requestSeq
        resultCard.showLoading(det, crop)

        currentJob = lifecycleScope.launch {
            try {
                val r = withContext(Dispatchers.IO) {
                    GeminiClient.ask(DetectedObject(det.label, det.score), crop)
                }
                if (myReq != requestSeq) return@launch   // 취소된 요청은 아무것도 저장하지 않음

                if (!det.uncertain) resultCache.put(det.label, det.trackId, crop, r)
                val savedAs = withContext(Dispatchers.IO) {
                    try {
                        FeedbackSaver.saveIfNeeded(this@MainActivity, frame, det, r)
                    } catch (e: Exception) {
                        Log.e(TAG, "학습 자료 저장 실패", e)
                        null
                    }
                }
                val entry = withContext(Dispatchers.IO) {
                    HistoryStore.add(this@MainActivity, source, det.label, r, crop)
                }
                if (myReq != requestSeq) return@launch

                val msg = if (savedAs != null)
                    "탐지 결과(${WasteClasses.koName(det.label)})와 AI 판단(${WasteClasses.koName(savedAs)})이 달라 학습 자료로 저장했어요." else null
                resultCard.showGuide(r, crop, det.classId, status = "AI 안내", message = msg, historyId = entry.id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Gemini 요청 실패", e)
                if (myReq != requestSeq) return@launch
                resultCard.showGuide(OfflineGuide.get(det.label), crop, det.classId,
                    status = "기본 안내", message = friendlyError(e), isWarning = true)
            } finally {
                if (myReq == requestSeq) requesting = false
            }
        }
    }

    // ---------------- 화면 전체 분석 ----------------

    private fun updateHint(count: Int) {
        hintText.text = if (count == 0)
            "물체가 안 잡히면 여기를 눌러 화면 전체 분석"
        else
            "박스를 누르거나, 여기를 눌러 화면 전체 분석"
    }

    private val prefs by lazy { getSharedPreferences("settings", MODE_PRIVATE) }

    // 화면 전체 분석: 처음 한 번만 안내하고 동의를 받는다
    private fun confirmWholeFrameOnce(onOk: () -> Unit) {
        if (prefs.getBoolean(KEY_WHOLE_NOTICE, false)) {
            onOk()
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("화면 전체 분석")
            .setMessage(
                "지금 보이는 카메라 화면 전체를 Google Gemini로 보내 분석해요.\n\n" +
                        "사람 얼굴, 문서, 주소 같은 개인정보가 화면에 나오지 않게 해 주세요.\n" +
                        "분석한 사진은 이 기기의 '기록'에도 저장돼요."
            )
            .setPositiveButton("확인하고 분석") { _, _ ->
                prefs.edit().putBoolean(KEY_WHOLE_NOTICE, true).apply()
                onOk()
            }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun analyzeWholeFrame() {
        if (requesting) return
        val frame = shownBitmap ?: return
        val source = if (photoMode) "photo" else "camera"   // 요청 시점에 출처 고정
        hintText.visibility = View.GONE
        freezeFrame(frame)
        if (!photoMode) overlayView.setResults(emptyList(), 1, 1)

        if (!isOnline()) {
            resultCard.showGuide(
                DisposalResponse("화면 전체 분석", "확인 필요", emptyList(), null, null), frame, -1,
                status = "오프라인",
                message = "화면 전체 분석은 인터넷이 필요해요. 연결한 뒤 다시 시도하거나, 박스가 잡히면 박스를 눌러 기본 안내를 볼 수 있어요.",
                isWarning = true
            )
            resultCard.showFailActions()
            return
        }

        requesting = true
        val myReq = ++requestSeq
        resultCard.showWholeLoading(frame)

        currentJob = lifecycleScope.launch {
            try {
                val r = withContext(Dispatchers.IO) { GeminiClient.askWhole(frame) }
                if (myReq != requestSeq) return@launch
                val entry = withContext(Dispatchers.IO) {
                    HistoryStore.add(this@MainActivity, source, "화면 전체", r, frame)
                }
                if (myReq != requestSeq) return@launch
                resultCard.showGuide(r, frame, -1, status = "AI 안내 · 화면 전체", historyId = entry.id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "화면 전체 분석 실패", e)
                if (myReq != requestSeq) return@launch
                resultCard.showGuide(
                    DisposalResponse("화면 전체 분석", "확인 필요", emptyList(), null, null), frame, -1,
                    status = "분석 실패", message = friendlyError(e), isWarning = true
                )
                resultCard.showFailActions()
            } finally {
                if (myReq == requestSeq) requesting = false
            }
        }
    }

    // ---------------- 최근 기록 ----------------

    private fun openHistory(e: HistoryEntry) {
        if (requesting) return
        hintText.visibility = View.GONE
        if (!photoMode) overlayView.setResults(emptyList(), 1, 1)
        val thumb = e.thumbPath?.let { BitmapFactory.decodeFile(it) }
        resultCard.showGuide(
            e.response, thumb, -1,
            status = "기록 · ${HistoryDialog.formatTime(e.time)}",
            historyId = e.id
        )
    }

    // ---------------- 텍스트 검색 ----------------

    private fun showSearchDialog() {
        if (requesting) return
        val input = EditText(this).apply {
            hint = "예: 건전지, 아이스팩, 깨진 유리"
            isSingleLine = true
            imeOptions = EditorInfo.IME_ACTION_SEARCH
        }
        val box = FrameLayout(this).apply {
            setPadding(dp(20), dp(8), dp(20), 0)
            addView(input)
        }
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle("물건 이름으로 검색")
            .setView(box)
            .setPositiveButton("검색") { _, _ -> runSearch(input.text.toString()) }
            .setNegativeButton("취소", null)
            .setNeutralButton("최근 기록") { _, _ -> historyDialog.show() }
            .create()

        // 키보드의 검색 버튼으로도 실행
        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                dialog.dismiss()
                runSearch(input.text.toString())
                true
            } else false
        }
        dialog.setOnShowListener {
            input.requestFocus()
            dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        }
        dialog.show()
    }

    private fun runSearch(raw: String) {
        val query = raw.trim().take(40)
        if (query.isEmpty() || requesting) return
        val key = query.replace(" ", "").lowercase()
        hintText.visibility = View.GONE
        if (!photoMode) overlayView.setResults(emptyList(), 1, 1)

        // 1) 같은 검색어면 저장된 결과
        searchCache[key]?.let {
            resultCard.showGuide(it, null, -1, status = "저장된 결과")
            return
        }

        // 2) 기본 분류 이름과 똑같으면 바로 기본 안내
        OfflineGuide.exactMatch(query)?.let { label ->
            resultCard.showGuide(OfflineGuide.get(label), null, WasteClasses.indexOf(label), status = "기본 안내")
            return
        }

        // 3) 인터넷이 없으면 가까운 기본 분류로 대체
        if (!isOnline()) {
            val label = OfflineGuide.partialMatch(query)
            if (label != null) {
                resultCard.showGuide(
                    OfflineGuide.get(label), null, WasteClasses.indexOf(label),
                    status = "오프라인 기본 안내",
                    message = "인터넷이 없어 '$query'와 비슷한 분류의 기본 안내를 보여 드려요. 실제 배출법은 다를 수 있으니 연결 후 다시 확인하세요.",
                    isWarning = true
                )
            } else {
                resultCard.showGuide(
                    DisposalResponse(query, "확인 필요", emptyList(), null, null), null, -1,
                    status = "오프라인",
                    message = "인터넷에 연결된 뒤 다시 검색해 주세요.",
                    isWarning = true
                )
            }
            return
        }

        // 4) Gemini에 텍스트로 질의
        requesting = true
        val myReq = ++requestSeq
        resultCard.showSearchLoading(query)
        currentJob = lifecycleScope.launch {
            try {
                val r = withContext(Dispatchers.IO) { GeminiClient.askText(query) }
                if (myReq != requestSeq) return@launch
                // 검색은 YOLO 분류와 상관없으니 분류 색을 쓰지 않음
                val result = r.copy(yoloClass = null)
                searchCache[key] = result
                val entry = withContext(Dispatchers.IO) {
                    HistoryStore.add(this@MainActivity, "search", query, result, null)
                }
                if (myReq != requestSeq) return@launch
                resultCard.showGuide(result, null, -1, status = "AI 안내", historyId = entry.id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "검색 실패", e)
                if (myReq != requestSeq) return@launch
                val label = OfflineGuide.partialMatch(query)
                if (label != null) {
                    resultCard.showGuide(OfflineGuide.get(label), null, WasteClasses.indexOf(label),
                        status = "기본 안내", message = friendlyError(e), isWarning = true)
                } else {
                    resultCard.showGuide(DisposalResponse(query, "확인 필요", emptyList(), null, null), null, -1,
                        status = "검색 실패", message = friendlyError(e), isWarning = true)
                }
            } finally {
                if (myReq == requestSeq) requesting = false
            }
        }
    }

    // ---------------- 카드 닫기 / 화면 고정 ----------------

    private fun hideResult() {
        if (requesting) return
        resultCard.hide()
        hintText.visibility = View.VISIBLE
        unfreezeFrame()
    }

    // 카메라 모드에서 결과를 보는 동안 누른 순간의 화면을 고정
    private fun freezeFrame(frame: Bitmap) {
        if (photoMode) return
        photoView.scaleType = ImageView.ScaleType.CENTER_CROP   // 카메라 미리보기와 같은 맞춤
        photoView.setImageBitmap(frame)
        photoView.visibility = View.VISIBLE
    }

    private fun unfreezeFrame() {
        if (photoMode) return
        photoView.setImageDrawable(null)
        photoView.visibility = View.GONE
    }

    // ---------------- 도우미 ----------------

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    // 박스 주변을 10% 여유 있게 잘라내기
    private fun cropDetection(src: Bitmap, box: RectF): Bitmap {
        val padW = box.width() * 0.1f
        val padH = box.height() * 0.1f
        val l = (box.left - padW).toInt().coerceIn(0, src.width - 1)
        val t = (box.top - padH).toInt().coerceIn(0, src.height - 1)
        val r = (box.right + padW).toInt().coerceIn(l + 1, src.width)
        val b = (box.bottom + padH).toInt().coerceIn(t + 1, src.height)
        return Bitmap.createBitmap(src, l, t, r - l, b - t)
    }

    private fun isOnline(): Boolean {
        val cm = getSystemService(android.net.ConnectivityManager::class.java) ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun friendlyError(e: Exception): String = when (e) {
        is ServerException -> e.message ?: "안내를 가져오지 못했어요. 다시 시도해 주세요."
        is HttpException -> when (e.code()) {
            400 -> "요청 형식에 문제가 있어요. 앱을 업데이트해 주세요."
            401, 403 -> "앱 인증에 실패했어요. 앱을 다시 실행해 주세요."
            404 -> "서버 주소를 찾을 수 없어요. 앱을 업데이트해 주세요."
            429 -> "잠시 사용량이 많아요. 조금 뒤에 다시 눌러 주세요."
            in 500..599 -> "서버에 일시적인 문제가 있어요. 잠시 후 다시 시도해 주세요."
            else -> "안내를 가져오지 못했어요. (오류 ${e.code()})"
        }
        is java.net.UnknownHostException,
        is java.net.ConnectException -> "인터넷 연결을 확인해 주세요."
        is java.io.InterruptedIOException -> "응답이 늦어지고 있어요. 다시 시도해 주세요."
        is com.google.gson.JsonSyntaxException -> "AI 답변을 읽지 못했어요. 다시 눌러 주세요."
        else -> "안내를 가져오지 못했어요. 다시 시도해 주세요."
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.execute { detector?.close() }
        cameraExecutor.shutdown()
    }

    companion object {
        private const val MIN_INTERVAL_MS = 120L
        private const val TAG = "WasteSorting"
        private const val KEY_WHOLE_NOTICE = "whole_frame_notice_seen"
        private const val MAX_ZOOM = 4f
    }
}