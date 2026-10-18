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

    @GET("sessions/{id}")
    suspend fun getSession(@Path("id") id: String): SessionDetail

    @PATCH("sessions/{id}")
    suspend fun patchSession(@Path("id") id: String, @Body request: PatchSessionRequest): Session

    @DELETE("sessions/{id}")
    suspend fun deleteSession(@Path("id") id: String): Response<Unit>

    @POST("tasks/{taskId}/questions/{questionId}/answer")
    suspend fun answerQuestion(
        @Path("taskId") taskId: String,
        @Path("questionId") questionId: String,
        @Body request: AnswerQuestionRequest
    ): Response<Unit>

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

data class Snapshot(val bitmap: Bitmap, val etag: String)

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

    /** mobd's VNC relay: ws://host:port/vnc (wss for https). */
    val vncUrl: String
        get() = baseUrl.resolve("vnc").toString().replaceFirst("http", "ws")

    /** A desktop snapshot from GET /screen, scaled down to [maxWidth]; null on 304 not modified. */
    suspend fun screen(maxWidth: Int, quality: Int = 65, etag: String? = null): Snapshot? = withContext(Dispatchers.IO) {
        val url = baseUrl.newBuilder().addPathSegment("screen")
            .addQueryParameter("maxWidth", maxWidth.toString())
            .addQueryParameter("quality", quality.toString())
            .build()
        val req = Request.Builder().url(url)
        if (etag != null) req.header("If-None-Match", etag)
        okHttpClient.newCall(req.build()).execute().use { r ->
            if (r.code == 304) return@withContext null
            val body = r.body ?: throw IOException("Empty response")
            if (!r.isSuccessful) {
                val text = body.string()
                throw IOException(runCatching { JSONObject(text).getString("error") }.getOrDefault("HTTP ${r.code}"))
            }
            val bm = BitmapFactory.decodeStream(body.byteStream()) ?: throw IOException("Not an image")
            Snapshot(bm, r.header("ETag").orEmpty())
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
