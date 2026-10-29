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
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.HTTP
import retrofit2.http.Header
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Part
import retrofit2.http.Path
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

    /** 오탐 원본 화면 + 박스 업로드 (사용자가 동의한 경우에만) */
    @Multipart
    @POST("api/v1/analyses/{id}/frame")
    suspend fun uploadFrame(
        @Header("Authorization") auth: String,
        @Path("id") analysisId: Long,
        @Part image: MultipartBody.Part,
        @Part("meta") meta: RequestBody
    ): FrameUploadResponse

    /** 탈퇴: 계정·기록·오탐 사진 모두 삭제 (이메일 계정은 비밀번호 필요) */
    @HTTP(method = "DELETE", path = "api/v1/users/me", hasBody = true)
    suspend fun deleteMe(
        @Header("Authorization") auth: String,
        @Body body: DeleteRequest
    ): Response<Unit>

    @POST("api/v1/auth/signup")
    suspend fun signup(
        @Header("Authorization") auth: String,
        @Body body: AccountRequest
    ): TokenResponse

    /** 기기 토큰을 같이 보내면 이 기기의 익명 기록을 계정으로 옮긴다 */
    @POST("api/v1/auth/login")
    suspend fun login(
        @Header("Authorization") auth: String?,
        @Body body: AccountRequest
    ): TokenResponse

    @POST("api/v1/auth/refresh")
    suspend fun refresh(@Body body: RefreshRequest): TokenResponse

    @POST("api/v1/auth/logout")
    suspend fun logout(@Body body: RefreshRequest): Response<Unit>

    @GET("api/v1/analyses")
    suspend fun history(
        @Header("Authorization") auth: String,
        @Query("page") page: Int,
        @Query("size") size: Int
    ): HistoryPage

    @GET("api/v1/analyses/{id}")
    suspend fun historyDetail(
        @Header("Authorization") auth: String,
        @Path("id") id: Long
    ): HistoryDetail
}

data class DeviceTokenResponse(@SerializedName("token") val token: String?)

data class FrameUploadResponse(@SerializedName("misdetectionId") val misdetectionId: Long?)

data class AccountRequest(
    @SerializedName("email") val email: String,
    @SerializedName("password") val password: String
)

data class RefreshRequest(@SerializedName("refreshToken") val refreshToken: String)

data class DeleteRequest(@SerializedName("password") val password: String?)

data class TokenResponse(
    @SerializedName("accessToken") val accessToken: String?,
    @SerializedName("refreshToken") val refreshToken: String?,
    @SerializedName("email") val email: String?
)

/** 서버에 쌓인 내 기록 (로그인하면 다른 폰에서도 같은 기록) */
data class HistoryPage(
    @SerializedName("content") val content: List<HistorySummary>?,
    @SerializedName("totalElements") val totalElements: Long = 0
)

data class HistorySummary(
    @SerializedName("id") val id: Long,
    @SerializedName("source") val source: String?,
    @SerializedName("itemName") val itemName: String?,
    @SerializedName("category") val category: String?,
    @SerializedName("query") val query: String?,
    @SerializedName("detectedLabel") val detectedLabel: String?,
    @SerializedName("createdAt") val createdAt: String?
)

data class HistoryDetail(
    @SerializedName("id") val id: Long,
    @SerializedName("itemName") val itemName: String?,
    @SerializedName("category") val category: String?,
    @SerializedName("steps") val steps: List<String>?,
    @SerializedName("caution") val caution: String?,
    @SerializedName("note") val note: String?,
    @SerializedName("yoloClass") val yoloClass: String?,
    @SerializedName("createdAt") val createdAt: String?
) {
    fun toResponse() = DisposalResponse(itemName, category, steps, caution, note, yoloClass)
}

/** 서버가 보낸 오류 { code, message }. message는 사용자에게 그대로 보여 줘도 되는 한국어 */
class ServerException(val status: Int, val code: String?, message: String) : Exception(message)

/**
 * 분리배출 안내 요청 창구. 이름은 예전 그대로지만 이제 Gemini가 아니라 내 서버를 호출한다.
 * MainActivity에서 쓰는 ask / askWhole / askText 모양은 바꾸지 않았다.
 */
object GeminiClient {

    private const val PREFS = "server_auth"
    private const val KEY_TOKEN = "device_token"
    private const val KEY_ACCESS = "access_token"
    private const val KEY_REFRESH = "refresh_token"
    private const val KEY_EMAIL = "account_email"

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
        GeminiParser.sanitize(withAuth { auth -> api.search(auth, query) })

    private suspend fun analyze(bitmap: Bitmap, metaJson: String): DisposalResponse {
        val jpeg = toJpegBytes(bitmap)
        return GeminiParser.sanitize(withAuth { auth ->
            // 다시 시도할 때 같은 본문을 쓸 수 있게 매번 새로 만든다
            val image = MultipartBody.Part.createFormData(
                "image", "photo.jpg", jpeg.toRequestBody("image/jpeg".toMediaType())
            )
            val meta = metaJson.toRequestBody("text/plain; charset=utf-8".toMediaType())
            api.analyze(auth, image, meta)
        })
    }

    /** 오탐 원본 업로드. box = [중심x, 중심y, 너비, 높이] (0~1) */
    suspend fun uploadFrame(analysisId: Long, jpeg: ByteArray, box: DoubleArray) {
        val metaJson = gson.toJson(mapOf("box" to box.toList()))
        withAuth { auth ->
            val image = MultipartBody.Part.createFormData(
                "image", "frame.jpg", jpeg.toRequestBody("image/jpeg".toMediaType())
            )
            api.uploadFrame(auth, analysisId, image, metaJson.toRequestBody("text/plain; charset=utf-8".toMediaType()))
        }
    }

    // ---------------- 계정 (선택 로그인) ----------------

    val isLoggedIn: Boolean get() = prefs?.getString(KEY_ACCESS, null) != null
    val email: String? get() = prefs?.getString(KEY_EMAIL, null)

    /** 가입: 지금 이 기기의 익명 기록이 그대로 계정 기록이 된다 */
    suspend fun signup(email: String, password: String) {
        val req = AccountRequest(email.trim(), password)
        try {
            call { saveAccount(api.signup("Bearer " + deviceToken(), req)) }
        } catch (e: ServerException) {
            // 이 폰의 기기 토큰이 이미 다른 계정이 된 경우(예: 예전 버전에서 로그아웃): 새 익명 토큰으로 한 번 더
            if (e.code != "ALREADY_REGISTERED") throw e
            clearToken()
            call { saveAccount(api.signup("Bearer " + deviceToken(), req)) }
        }
    }

    /** 로그인: 이 기기에서 쌓은 익명 기록도 계정으로 옮긴다 */
    suspend fun login(email: String, password: String) = call {
        val device = prefs?.getString(KEY_TOKEN, null)?.let { "Bearer $it" }
        saveAccount(api.login(device, AccountRequest(email.trim(), password)))
    }

    /**
     * 로그아웃: 서버의 리프레시 토큰을 지우고, 이 폰의 기기 토큰도 버린다.
     * 가입하면 그 기기 토큰의 사용자가 그대로 계정이 되므로, 남겨 두면 로그아웃 후에도 계정으로 동작한다.
     * 다음 요청 때 새 익명 토큰을 받는다.
     */
    suspend fun logout() {
        prefs?.getString(KEY_REFRESH, null)?.let { r ->
            try { api.logout(RefreshRequest(r)) } catch (_: Exception) { /* 오프라인이어도 로컬 로그아웃은 진행 */ }
        }
        clearAccount()
        clearToken()
    }

    /** 탈퇴: 서버의 계정과 기록을 지우고, 이 폰의 토큰도 지운다 (폰 안의 최근 기록·학습 자료는 남음) */
    suspend fun deleteAccount(password: String?) {
        withAuth { auth ->
            val r = api.deleteMe(auth, DeleteRequest(password))
            if (!r.isSuccessful) throw HttpException(r)
        }
        clearAccount()
        clearToken()
    }

    /** 서버에 쌓인 내 기록 (최신순) */
    suspend fun history(page: Int = 0, size: Int = 30): HistoryPage = withAuth { auth -> api.history(auth, page, size) }

    suspend fun historyDetail(id: Long): HistoryDetail = withAuth { auth -> api.historyDetail(auth, id) }

    private fun saveAccount(t: TokenResponse) {
        require(!t.accessToken.isNullOrBlank() && !t.refreshToken.isNullOrBlank()) { "서버에서 토큰을 받지 못했어요" }
        prefs?.edit()
            ?.putString(KEY_ACCESS, t.accessToken)
            ?.putString(KEY_REFRESH, t.refreshToken)
            ?.putString(KEY_EMAIL, t.email)
            ?.apply()
    }

    private fun clearAccount() {
        prefs?.edit()?.remove(KEY_ACCESS)?.remove(KEY_REFRESH)?.remove(KEY_EMAIL)?.apply()
    }

    /** 서버 오류를 사용자에게 보여 줄 문장이 담긴 ServerException으로 바꿔서 던진다 */
    private suspend fun <T> call(block: suspend () -> T): T = try {
        block()
    } catch (e: HttpException) {
        throw toServerException(e)
    }

    // ---------------- 인증 ----------------

    /** 로그인했으면 액세스 토큰(JWT), 아니면 기기 토큰 */
    private suspend fun currentAuth(): String =
        "Bearer " + (prefs?.getString(KEY_ACCESS, null) ?: deviceToken())

    /**
     * 인증을 붙여 요청하고, 401이면 한 번만 복구한 뒤 다시 시도한다.
     * - 로그인 상태: 리프레시 토큰으로 새 토큰을 받는다. 실패하면 로그아웃 처리하고 기기 토큰으로
     * - 익명 상태: 기기 토큰을 새로 받는다
     */
    private suspend fun <T> withAuth(request: suspend (String) -> T): T {
        try {
            return request(currentAuth())
        } catch (e: HttpException) {
            if (e.code() != 401) throw toServerException(e)
            recoverAuth()
        }
        return call { request(currentAuth()) }
    }

    private suspend fun recoverAuth() {
        val refresh = prefs?.getString(KEY_REFRESH, null)
        if (prefs?.getString(KEY_ACCESS, null) != null && refresh != null) {
            try {
                val t = api.refresh(RefreshRequest(refresh))
                saveAccount(t.copy(email = t.email ?: email))
                return
            } catch (_: Exception) {
                clearAccount()   // 리프레시도 만료 → 로그아웃 상태로 (기록은 서버에 남아 있음)
                return
            }
        }
        clearToken()
    }

    private suspend fun deviceToken(): String {
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
