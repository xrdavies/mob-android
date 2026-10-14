package com.xrdavies.mob.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.*
import java.util.concurrent.TimeUnit

interface MobApi {
    @GET("health")
    suspend fun getHealth(): Health
    
    @POST("tasks")
    suspend fun createTask(@Body request: CreateTaskRequest): Task
    
    @GET("tasks/{id}")
    suspend fun getTask(@Path("id") id: String): Task
    
    @POST("tasks/{id}/cancel")
    suspend fun cancelTask(@Path("id") id: String)
    
    @POST("tasks/{id}/steer")
    suspend fun steerTask(@Path("id") id: String, @Body request: SteerRequest)
    
    @GET("sessions")
    suspend fun listSessions(): SessionsResponse
    
    @GET("sessions/{id}")
    suspend fun getSession(@Path("id") id: String): SessionDetail
    
    @POST("tasks/{taskId}/questions/{questionId}/answer")
    suspend fun answerQuestion(
        @Path("taskId") taskId: String,
        @Path("questionId") questionId: String,
        @Body request: AnswerQuestionRequest
    )
}

class ApiClient(baseUrl: String) {
    val okHttpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    val baseUrl: HttpUrl = normalize(baseUrl)

    private val retrofit = Retrofit.Builder()
        .baseUrl(this.baseUrl)
        .client(okHttpClient)
        .addConverterFactory(GsonConverterFactory.create())
        .build()
    
    val api: MobApi = retrofit.create(MobApi::class.java)
    
    fun updateBaseUrl(newUrl: String): ApiClient {
        return ApiClient(newUrl)
    }

    /** mobd's VNC relay: ws://host:port/vnc (wss for https). */
    val vncUrl: String
        get() = baseUrl.resolve("vnc").toString().replaceFirst("http", "ws")

    /** A desktop snapshot from GET /screen, scaled down to [maxWidth]. */
    suspend fun screen(maxWidth: Int): Bitmap = withContext(Dispatchers.IO) {
        val url = baseUrl.newBuilder().addPathSegment("screen")
            .addQueryParameter("maxWidth", maxWidth.toString())
            .addQueryParameter("quality", "70")
            .build()
        okHttpClient.newCall(Request.Builder().url(url).build()).execute().use { r ->
            val body = r.body ?: throw IOException("Empty response")
            if (!r.isSuccessful) {
                val text = body.string()
                throw IOException(runCatching { JSONObject(text).getString("error") }.getOrDefault("HTTP ${r.code}"))
            }
            BitmapFactory.decodeStream(body.byteStream()) ?: throw IOException("Not an image")
        }
    }

    companion object {
        // Used until the user enters a mobd URL; AppState makes no requests then.
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
