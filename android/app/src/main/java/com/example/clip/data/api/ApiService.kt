package com.example.clip.data.api

import okhttp3.MultipartBody
import okhttp3.RequestBody
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Multipart
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.Part
import retrofit2.http.Path
import retrofit2.http.Query

interface ApiService {

    @GET("api/v1/health")
    suspend fun health(): ApiResponse<HealthDto>

    @GET("api/v1/records")
    suspend fun records(
        @Query("limit") limit: Int = 100,
        @Query("offset") offset: Int = 0
    ): ApiResponse<List<RecordDto>>

    // Token 验证：显式带 token。健康检查免 token 验不出真假，改打带鉴权的 records。
    // 拦截器见请求已带 X-Token 时会跳过，避免双 header。
    @GET("api/v1/records")
    suspend fun validateToken(
        @Header("X-Token") token: String,
        @Query("limit") limit: Int = 1,
        @Query("offset") offset: Int = 0
    ): ApiResponse<List<RecordDto>>

    @Multipart
    @POST("api/v1/transcribe")
    suspend fun transcribe(
        @Part audio: MultipartBody.Part,
        @Part("local_id") localId: RequestBody
    ): ApiResponse<RecordDto>

    @GET("api/v1/tasks/{taskId}")
    suspend fun task(@Path("taskId") taskId: String): ApiResponse<RecordDto>

    // M3：文本/视频新建（text/video），无耗时任务，接口立刻返回最终态
    @POST("api/v1/records")
    suspend fun createRecord(@Body body: CreateRecordRequest): ApiResponse<RecordDto>

    @GET("api/v1/records/{recordId}")
    suspend fun getRecord(@Path("recordId") recordId: String): ApiResponse<RecordDto>

    @PATCH("api/v1/records/{recordId}")
    suspend fun patchRecord(
        @Path("recordId") recordId: String,
        @Body body: PatchRecordRequest
    ): ApiResponse<RecordDto>
}
