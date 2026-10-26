package com.xrdavies.mob.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import okhttp3.MultipartBody
import retrofit2.HttpException
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.*
import java.util.concurrent.TimeUnit

interface MobApi {
    @GET("health")
    suspend fun getHealth(): Health

    @POST("tasks")
    suspend fun createTask(@Body request: CreateTaskRequest): Task

    @GET("tasks")
    suspend fun listTasks(@Query("limit") limit: Int = 100, @Query("sessionId") sessionId: String? = null): TasksResponse

    @GET("tasks/{id}")
    suspend fun getTask(@Path("id") id: String): Task

    @POST("tasks/{id}/cancel")
    suspend fun cancelTask(@Path("id") id: String): Response<Unit>

    @POST("tasks/{id}/steer")
    suspend fun steerTask(@Path("id") id: String, @Body request: SteerRequest): Response<Unit>

    @GET("queue")
    suspend fun queue(): Queue

    /** archived: "false" (default), "true" or "all". */
    @GET("sessions")
    suspend fun listSessions(@Query("archived") archived: String = "false"): SessionsResponse

    /** With [since], mobd may answer only the turns changed after that revision (see ChatDetail.merged). */
    @GET("sessions/{id}")
    suspend fun getSession(@Path("id") id: String, @Query("since") since: Long? = null): SessionDetail

    @PATCH("sessions/{id}")
    suspend fun patchSession(@Path("id") id: String, @Body request: PatchSessionRequest): Session

    @GET("settings")
    suspend fun settings(): SettingsInfo

    /** Replaces the machine settings; 409 code "settings_changed" when another device saved first. */
    @PUT("settings")
    suspend fun saveSettings(@Body settings: MachineSettings): SettingsInfo

    @DELETE("sessions/{id}")
    suspend fun deleteSession(@Path("id") id: String): Response<Unit>

    @POST("tasks/{taskId}/questions/{questionId}/answer")
    suspend fun answerQuestion(
        @Path("taskId") taskId: String,
        @Path("questionId") questionId: String,
        @Body request: AnswerQuestionRequest
    ): Response<Unit>

    @GET("desktops")
    suspend fun listDesktops(): DesktopsResponse

    /** Stops the conversation's running turn, then closes its desktop. */
    @DELETE("desktops/{sessionId}")
    suspend fun closeDesktop(@Path("sessionId") sessionId: String): Response<Unit>

    /** Makes the conversation's browser profiles the seed for new conversations. */
    @POST("desktops/{sessionId}/seed")
    suspend fun seedDesktop(@Path("sessionId") sessionId: String): Response<Unit>

    @Multipart
    @POST("uploads")
    suspend fun upload(@Part file: MultipartBody.Part): Upload
}

/** mobd answers errors as {"error": "..."}; turns a failed Response into a readable exception. */
fun <T> Response<T>.orThrow(): Response<T> {
    if (isSuccessful) return this
    val text = errorBody()?.string().orEmpty()
    throw IOException(runCatching { JSONObject(text).getString("error") }.getOrDefault("HTTP ${code()}"))
}

const val NEEDS_PAIRING = "This device needs to be paired again"

/** [asleep]: the last frame of a sleeping desktop (`X-Mob-Desktop-State: asleep`), which was not woken. */
data class Snapshot(val bitmap: Bitmap, val etag: String, val asleep: Boolean = false)

/** An HTTP failure outside Retrofit (event streams, the live view). */
open class HttpStatusException(val code: Int, message: String) : IOException(message)

/** mobd refused the device token: pair again, retrying with it is pointless. */
class UnauthorizedException : HttpStatusException(401, NEEDS_PAIRING)

fun Throwable.isUnauthorized(): Boolean =
    (this is HttpStatusException && code == 401) || (this is HttpException && code() == 401)

/** A readable message for any request failure, including Retrofit HTTP errors. */
fun Throwable.readable(): String = when (this) {
    is HttpException if code() == 401 -> NEEDS_PAIRING
    is HttpException -> response()?.errorBody()?.string()
        ?.let { runCatching { JSONObject(it).getString("error") }.getOrNull() } ?: "HTTP ${code()}"
    is java.net.ConnectException, is java.net.UnknownHostException -> "Cannot reach mobd"
    is java.net.SocketTimeoutException -> "mobd did not answer in time"
    else -> message ?: javaClass.simpleName
}

/**
 * Sends the device token to mobd's own origin only (images may point elsewhere) and turns its
 * 401 into [UnauthorizedException], so Retrofit, streams, images and the live view all see it.
 */
class AuthInterceptor(private val base: HttpUrl, private val token: String?) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): okhttp3.Response {
        val req = chain.request()
        val own = req.url.scheme == base.scheme && req.url.host == base.host && req.url.port == base.port
        val res = chain.proceed(if (own && token != null) req.newBuilder().header("Authorization", "Bearer $token").build() else req)
        if (own && res.code == 401) {
            res.close()
            throw UnauthorizedException()
        }
        return res
    }
}

/** Talks to one mobd; [token] is the device credential from pairing, null before pairing. */
class ApiClient(baseUrl: String, val token: String?) {
    val baseUrl: HttpUrl = normalize(baseUrl)

    val okHttpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .addInterceptor(AuthInterceptor(this.baseUrl, token))
        .build()

    /** For event streams: an idle stream sends nothing, so reads must not time out. */
    val streamClient: OkHttpClient = okHttpClient.newBuilder().readTimeout(0, TimeUnit.SECONDS).build()

    fun url(path: String): String = baseUrl.resolve(path).toString()

    private val retrofit = Retrofit.Builder()
        .baseUrl(this.baseUrl)
        .client(okHttpClient)
        .addConverterFactory(GsonConverterFactory.create())
        .build()
    
    val api: MobApi = retrofit.create(MobApi::class.java)
    
    /** Absolute URL of a stored screenshot or upload. */
    fun artifactUrl(id: String): String = baseUrl.resolve("artifacts/$id").toString()

    /** GET /screen; [sessionId] picks the conversation's desktop (servers with `sessionDesktops`). */
    fun screenUrl(maxWidth: Int, quality: Int, sessionId: String? = null): HttpUrl = baseUrl.newBuilder().addPathSegment("screen")
        .addQueryParameter("maxWidth", maxWidth.toString())
        .addQueryParameter("quality", quality.toString())
        .apply { sessionId?.let { addQueryParameter("sessionId", it) } }
        .build()

    /**
     * A desktop snapshot from GET /screen, scaled down to [maxWidth]; null on 304 not modified.
     * Throws [NoDesktopException] when the conversation [sessionId] has no desktop yet.
     */
    suspend fun screen(maxWidth: Int, quality: Int = 65, etag: String? = null, sessionId: String? = null): Snapshot? = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(screenUrl(maxWidth, quality, sessionId))
        if (etag != null) req.header("If-None-Match", etag)
        okHttpClient.newCall(req.build()).execute().use { r ->
            if (r.code == 304) return@withContext null
            val body = r.body ?: throw IOException("Empty response")
            if (!r.isSuccessful) {
                val e = apiError(body.string(), r.code)
                if (e.code == "no_desktop") throw NoDesktopException()
                throw IOException(e.message)
            }
            val bm = BitmapFactory.decodeStream(body.byteStream()) ?: throw IOException("Not an image")
            Snapshot(bm, r.header("ETag").orEmpty(), r.header("X-Mob-Desktop-State") == DesktopState.ASLEEP)
        }
    }

    fun streamUrl() = baseUrl.newBuilder().addPathSegment("stream").build().toString()

    companion object {
        val gson = com.google.gson.Gson()

        // Used until the user adds a machine; AppState makes no requests then.
        private const val PLACEHOLDER = "http://localhost/"

        /** Turns "192.168.1.5:8080" or "http://host:8080/" into a Retrofit base URL. */
        fun normalize(url: String): HttpUrl {
            val trimmed = url.trim().trimEnd('/')
            if (trimmed.isEmpty()) return PLACEHOLDER.toHttpUrlOrNull()!!
            val withScheme = if ("://" in trimmed) trimmed else "http://$trimmed"
            return "$withScheme/".toHttpUrlOrNull() ?: PLACEHOLDER.toHttpUrlOrNull()!!
        }
    }
}
