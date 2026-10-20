package com.jiwoo.wastesorting

import android.graphics.Bitmap
import android.util.Base64
import com.google.gson.Gson
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Path
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

interface GeminiApi {
    @POST("v1beta/models/{model}:generateContent")
    suspend fun generate(
        @Path("model") model: String,
        @Header("x-goog-api-key") apiKey: String,
        @Body body: GeminiRequest
    ): GeminiResponse
}

object GeminiClient {

    // 모델 이름이 바뀌어 404가 나면 여기만 고치면 돼요
    private const val MODEL = "gemini-3.1-flash-lite"

    private val gson = Gson()

    private val api: GeminiApi = Retrofit.Builder()
        .baseUrl("https://generativelanguage.googleapis.com/")
        .client(
            OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .callTimeout(20, TimeUnit.SECONDS)   // 요청 전체 상한
                .build()
        )
        .addConverterFactory(GsonConverterFactory.create(gson))
        .build()
        .create(GeminiApi::class.java)

    private val classGuide = """
        can=캔류, glass=유리병, pack=종이팩, paper=종이류,
        pet=페트병, plastic=플라스틱류, vinyl=비닐류
    """.trimIndent()

    private val jsonFormat = """
        반드시 아래 JSON 형식으로만 답해. JSON 밖에 다른 글은 쓰지 마.
        {
          "itemName": "물건 이름",
          "category": "배출 분류 (예: 투명 페트병, 플라스틱, 캔류)",
          "steps": ["배출 단계 1", "배출 단계 2"],
          "caution": "주의사항, 없으면 빈 문자열",
          "note": "추가 정보나 판단 이유, 없으면 빈 문자열",
          "yoloClass": "can, glass, pack, paper, pet, plastic, vinyl 중 물체에 가장 맞는 하나. 어디에도 해당하지 않으면 other"
        }
    """.trimIndent()

    /** 사진 + 탐지 라벨로 질의 */
    suspend fun ask(obj: DetectedObject, crop: Bitmap): DisposalResponse {
        val prompt = """
            너는 대한민국 생활폐기물 분리배출 안내 도우미야.
            사진 속 물체를 보고 한국 기준 분리배출 방법을 알려 줘.

            객체 탐지 모델의 추정: ${obj.label} (신뢰도 ${(obj.confidence * 100).toInt()}%)
            탐지 클래스 의미: $classGuide
            이 추정은 틀릴 수 있어. 사진과 다르면 사진을 기준으로 판단해.
        """.trimIndent() + "\n\n" + jsonFormat

        return request(
            listOf(
                GeminiPart(text = prompt),
                GeminiPart(inlineData = InlineData("image/jpeg", toBase64Jpeg(crop)))
            )

        )
    }

    /** 사진 없이 물건 이름으로 질의 */
    suspend fun askText(query: String): DisposalResponse {
        val prompt = """
            너는 대한민국 생활폐기물 분리배출 안내 도우미야.
            사용자가 사진 없이 물건 이름으로 검색했어: "$query"
            한국 기준으로 이 물건의 분리배출 방법을 알려 줘.

            - 물건 상태(오염, 여러 재질이 섞임 등)에 따라 방법이 달라지면 경우를 나눠서 설명해.
            - 지역마다 다르거나 확실하지 않은 내용은 지어내지 말고 "거주 지역 지자체 안내를 확인하세요"라고 안내해.
            - 물건 이름이 아니거나 무엇인지 알 수 없으면 itemName에 검색어를 그대로 쓰고 category를 "확인 필요"로 해.
        """.trimIndent() + "\n\n" + jsonFormat

        return request(listOf(GeminiPart(text = prompt)))
    }
    /** 탐지 박스 없이 화면 전체로 질의 (사용자가 직접 요청할 때만) */
    suspend fun askWhole(frame: Bitmap): DisposalResponse {
        val prompt = """
            너는 대한민국 생활폐기물 분리배출 안내 도우미야.
            사진은 사용자가 카메라로 비춘 화면 전체야. 물체 탐지 모델이 물건을 찾지 못했거나, 사용자가 화면 전체 분석을 요청했어.

            - 화면 가운데에 있거나 가장 크게 보이는, 버리려는 것으로 보이는 물건 하나를 골라 한국 기준 분리배출 방법을 알려 줘.
            - 사람, 손, 가구, 벽, 바닥, 책상처럼 버릴 물건이 아닌 것은 고르지 마.
            - 버릴 물건을 찾을 수 없거나 무엇인지 확실하지 않으면 지어내지 마. 그때는 itemName을 "물체를 찾지 못했어요", category를 "확인 필요", yoloClass를 "other"로 하고, steps에 "버릴 물건을 화면 가운데에 크게 비춘 뒤 다시 시도하세요"를 넣어.
            - 비슷하게 눈에 띄는 물건이 여러 개면 note에 어떤 물건을 골랐는지 적어.
            - 지역마다 다르거나 확실하지 않은 내용은 "거주 지역 지자체 안내를 확인하세요"라고 안내해.
        """.trimIndent() + "\n\n" + jsonFormat

        return request(
            listOf(
                GeminiPart(text = prompt),
                GeminiPart(inlineData = InlineData("image/jpeg", toBase64Jpeg(frame)))
            )
        )
    }

    private suspend fun request(parts: List<GeminiPart>): DisposalResponse {
        val key = BuildConfig.GEMINI_API_KEY
        require(key.isNotBlank()) { "local.properties에 GEMINI_API_KEY가 없어요" }

        val body = GeminiRequest(
            contents = listOf(GeminiContent(parts = parts)),
            generationConfig = GenerationConfig()
        )
        val response = api.generate(MODEL, key, body)
        val text = response.candidates?.firstOrNull()
            ?.content?.parts?.firstOrNull { it.text != null }?.text
            ?: error("Gemini 응답이 비어 있어요")

        // 혹시 ```json ... ``` 으로 감싸서 오면 벗겨내기
        return GeminiParser.parse(text)
    }



    // 긴 변을 768px 이하로 줄여서 전송량 줄이기
    private fun toBase64Jpeg(src: Bitmap): String {
        val maxSide = 768
        val scale = minOf(1f, maxSide.toFloat() / maxOf(src.width, src.height))
        val bmp = if (scale < 1f) {
            Bitmap.createScaledBitmap(
                src, (src.width * scale).toInt(), (src.height * scale).toInt(), true
            )
        } else src
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 85, out)
        if (bmp !== src) bmp.recycle()   // 축소본은 전송용으로만 쓰고 바로 정리
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }
}