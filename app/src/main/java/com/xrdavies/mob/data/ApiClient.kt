package com.xrdavies.mob.data

import okhttp3.OkHttpClient
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
    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
    
    private val retrofit = Retrofit.Builder()
        .baseUrl(baseUrl.trimEnd('/') + "/")
        .client(okHttpClient)
        .addConverterFactory(GsonConverterFactory.create())
        .build()
    
    val api: MobApi = retrofit.create(MobApi::class.java)
    
    fun updateBaseUrl(newUrl: String): ApiClient {
        return ApiClient(newUrl)
    }
}
