package com.jiwoo.wastesorting

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import retrofit2.HttpException
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Part
import retrofit2.http.Query
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

/** 버림가이드 서버 API (Gemini는 서버가 대신 호출한다. 앱에는 API 키가 없다) */
interface ServerApi {
    @POST("api/v1/auth/device")
    suspend fun issueDeviceToken(): DeviceTokenResponse

    @Multipart
    @POST("api/v1/analyses")
    suspend fun analyze(
        @Header("Authorization") auth: String,
        @Part image: MultipartBody.Part,
        @Part("meta") meta: RequestBody
    ): DisposalResponse

    @GET("api/v1/search")
    suspend fun search(
        @Header("Authorization") auth: String,
        @Query("q") query: String
    ): DisposalResponse
}

data class DeviceTokenResponse(@SerializedName("token") val token: String?)

/** 서버가 보낸 오류 { code, message }. message는 사용자에게 그대로 보여 줘도 되는 한국어 */
class ServerException(val status: Int, val code: String?, message: String) : Exception(message)

/**
 * 분리배출 안내 요청 창구. 이름은 예전 그대로지만 이제 Gemini가 아니라 내 서버를 호출한다.
 * MainActivity에서 쓰는 ask / askWhole / askText 모양은 바꾸지 않았다.
 */
object GeminiClient {

    private const val PREFS = "server_auth"
    private const val KEY_TOKEN = "device_token"

    private val gson = Gson()
    private var prefs: SharedPreferences? = null

    private val api: ServerApi by lazy {
        val baseUrl = BuildConfig.SERVER_URL
        require(baseUrl.isNotBlank()) { "local.properties에 SERVER_URL이 없어요" }
        Retrofit.Builder()
            .baseUrl(if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/")
            .client(
                OkHttpClient.Builder()
                    .connectTimeout(15, TimeUnit.SECONDS)
                    .readTimeout(45, TimeUnit.SECONDS)
                    .callTimeout(50, TimeUnit.SECONDS)   // 서버가 Gemini 혼잡 시 재시도하는 시간까지 포함
                    .build()
            )
            .addConverterFactory(GsonConverterFactory.create(gson))
            .build()
            .create(ServerApi::class.java)
    }

    /** MainActivity.onCreate에서 한 번 호출 (토큰 저장 위치 준비) */
    fun init(context: Context) {
        if (prefs == null) {
            prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        }
    }

    /** 탐지 박스로 잘라낸 사진 + 탐지 라벨 */
    suspend fun ask(obj: DetectedObject, crop: Bitmap): DisposalResponse {
        val meta = gson.toJson(
            mapOf("mode" to "crop", "detectedLabel" to obj.label, "confidence" to obj.confidence)
        )
        return analyze(crop, meta)
    }

    /** 화면 전체 사진 (사용자가 직접 요청할 때만) */
    suspend fun askWhole(frame: Bitmap): DisposalResponse =
        analyze(frame, gson.toJson(mapOf("mode" to "whole")))

    /** 사진 없이 물건 이름으로 검색 */
    suspend fun askText(query: String): DisposalResponse =
        withToken { auth -> api.search(auth, query) }

    private suspend fun analyze(bitmap: Bitmap, metaJson: String): DisposalResponse {
        val jpeg = toJpegBytes(bitmap)
        return withToken { auth ->
            // 다시 시도할 때 같은 본문을 쓸 수 있게 매번 새로 만든다
            val image = MultipartBody.Part.createFormData(
                "image", "photo.jpg", jpeg.toRequestBody("image/jpeg".toMediaType())
            )
            val meta = metaJson.toRequestBody("text/plain; charset=utf-8".toMediaType())
            api.analyze(auth, image, meta)
        }
    }

    /**
     * 저장된 토큰으로 요청. 토큰이 없으면 먼저 받고,
     * 서버가 401(토큰 무효)을 주면 토큰을 새로 받아 한 번만 다시 시도한다.
     */
    private suspend fun withToken(call: suspend (String) -> DisposalResponse): DisposalResponse {
        try {
            return GeminiParser.sanitize(call("Bearer " + token()))
        } catch (e: HttpException) {
            if (e.code() != 401) throw toServerException(e)
            clearToken()
        }
        try {
            return GeminiParser.sanitize(call("Bearer " + token()))
        } catch (e: HttpException) {
            throw toServerException(e)
        }
    }

    private suspend fun token(): String {
        prefs?.getString(KEY_TOKEN, null)?.let { return it }
        val issued = try {
            api.issueDeviceToken().token
        } catch (e: HttpException) {
            throw toServerException(e)
        }
        require(!issued.isNullOrBlank()) { "서버에서 토큰을 받지 못했어요" }
        prefs?.edit()?.putString(KEY_TOKEN, issued)?.apply()
        return issued
    }

    private fun clearToken() {
        prefs?.edit()?.remove(KEY_TOKEN)?.apply()
    }

    /** 서버의 { code, message } 오류를 읽어 ServerException으로 바꾼다 */
    private fun toServerException(e: HttpException): Exception {
        val body = try {
            e.response()?.errorBody()?.string()
        } catch (_: Exception) {
            null
        }
        val err = try {
            gson.fromJson(body, ServerError::class.java)
        } catch (_: Exception) {
            null
        }
        val msg = err?.message
        return if (msg.isNullOrBlank()) e else ServerException(e.code(), err?.code, msg)
    }

    private data class ServerError(
        @SerializedName("code") val code: String?,
        @SerializedName("message") val message: String?
    )

    // 긴 변을 768px 이하로 줄여서 전송량 줄이기 (서버 제한 2MB보다 훨씬 작다)
    private fun toJpegBytes(src: Bitmap): ByteArray {
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
        return out.toByteArray()
    }
}
