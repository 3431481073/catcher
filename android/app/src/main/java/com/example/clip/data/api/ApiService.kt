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

    // App 使用时长上报：一批原始事件进去，后端配对/去重后写 usage_records。
    @POST("api/v1/usage")
    suspend fun reportUsage(@Body body: UsageReportRequest): ApiResponse<UsageReportData>

    // ── Lattice 远程（转发到电脑上的 Lattice 数据口）──────────────
    // 这些是剪藏后端的转发接口（要 X-Token，拦截器自动带）。ref 只传裸 pane-N。

    @GET("api/v1/lattice/health")
    suspend fun latticeHealth(): ApiResponse<LatticeHealthDto>

    @GET("api/v1/lattice/panes")
    suspend fun latticePanes(): ApiResponse<LatticePanesDto>

    @GET("api/v1/lattice/panes/{ref}/status")
    suspend fun latticeStatus(@Path("ref") ref: String): ApiResponse<LatticeStatusDto>

    // mode / lines / maxBytes 传 null 时 Retrofit 不会带这个 query ——
    // 必须这样：Lattice 的默认值在它自己那边，替它补一个假值会被它的 clamp 拉成最小值。
    @GET("api/v1/lattice/panes/{ref}/text")
    suspend fun latticeText(
        @Path("ref") ref: String,
        @Query("mode") mode: String? = "screen",
        @Query("lines") lines: Int? = null,
        @Query("maxBytes") maxBytes: Int? = null
    ): ApiResponse<LatticeScreenDto>

    @GET("api/v1/lattice/panes/{ref}/last-turn")
    suspend fun latticeLastTurn(
        @Path("ref") ref: String,
        @Query("maxBytes") maxBytes: Int? = null
    ): ApiResponse<LatticeLastTurnDto>

    @POST("api/v1/lattice/panes/{ref}/input")
    suspend fun latticeInput(
        @Path("ref") ref: String,
        @Body body: LatticeInputRequest
    ): ApiResponse<LatticeInputDto>

    @POST("api/v1/lattice/panes/{ref}/answer")
    suspend fun latticeAnswer(
        @Path("ref") ref: String,
        @Body body: LatticeAnswerRequest
    ): ApiResponse<LatticeAnswerDto>

    @POST("api/v1/lattice/watch")
    suspend fun latticeWatch(@Body body: LatticeWatchRequest): ApiResponse<LatticeWatchDto>
}
