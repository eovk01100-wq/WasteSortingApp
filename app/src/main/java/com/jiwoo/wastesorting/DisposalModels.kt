package com.jiwoo.wastesorting

import com.google.gson.annotations.SerializedName

// ---- 앱에서 쓰는 데이터 ----
data class DetectedObject(
    val label: String,
    val confidence: Float
)

data class DisposalResponse(
    @SerializedName("itemName") val itemName: String?,
    @SerializedName("category") val category: String?,
    @SerializedName("steps") val steps: List<String>?,
    @SerializedName("caution") val caution: String?,
    @SerializedName("note") val note: String?,
    @SerializedName("yoloClass") val yoloClass: String? = null,
    // 서버가 붙여 주는 값 (오프라인 기본 안내에는 없음)
    @SerializedName("analysisId") val analysisId: Long? = null,
    @SerializedName("misdetection") val misdetection: Boolean? = null
)

// ---- Gemini REST API 요청/응답 ----
data class GeminiRequest(
    @SerializedName("contents") val contents: List<GeminiContent>,
    @SerializedName("generationConfig") val generationConfig: GenerationConfig
)

data class GeminiContent(
    @SerializedName("parts") val parts: List<GeminiPart>,
    @SerializedName("role") val role: String? = "user"
)

data class GeminiPart(
    @SerializedName("text") val text: String? = null,
    @SerializedName("inlineData") val inlineData: InlineData? = null
)

data class InlineData(
    @SerializedName("mimeType") val mimeType: String,
    @SerializedName("data") val data: String
)

data class GenerationConfig(
    @SerializedName("responseMimeType") val responseMimeType: String = "application/json",
    @SerializedName("temperature") val temperature: Float = 0.2f
)

data class GeminiResponse(
    @SerializedName("candidates") val candidates: List<GeminiCandidate>?
)

data class GeminiCandidate(
    @SerializedName("content") val content: GeminiContent?
)