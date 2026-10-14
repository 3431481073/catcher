package com.example.clip.data.api

import com.example.clip.config.ApiConfig
import com.example.clip.data.token.TokenGate
import com.example.clip.data.token.TokenManager
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

/**
 * 请求已显式带 X-Token 时（如 validateToken）不重复加。
 * 401（业务请求）→ pause；2xx（非 /health）→ resume；暂停超 5 分钟 → 自动恢复。
 */
internal class TokenInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        // 兜底：暂停超 5 分钟自动恢复（防 Doze 死锁）
        TokenGate.resumeIfExpired()

        val original = chain.request()
        val token = TokenManager.token
        val explicit = original.header("X-Token") != null
        val request = if (token != null && !explicit) {
            original.newBuilder().header("X-Token", token).build()
        } else {
            original
        }
        val response = chain.proceed(request)

        if (response.code == 401 && token != null && !explicit) {
            TokenGate.pause()
        } else if (response.isSuccessful && !isHealth(original)) {
            // 2xx 且非 /health（免 token，200 不代表 token 有效）→ token 有效，幂等 resume
            TokenGate.resume()
        }
        return response
    }

    private fun isHealth(request: Request): Boolean =
        request.url.encodedPath == "/api/v1/health"
}

object ApiClient {

    private val okHttp: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(120, TimeUnit.SECONDS)
            .addInterceptor(TokenInterceptor())
            .addInterceptor(HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BASIC })
            .build()
    }

    /**
     * 远程页那条 WebSocket 专用的 client。**不能复用 [okHttp]**。
     *
     * 三个值都是为长连接定的：
     * - `readTimeout(45s)`：Lattice（经后端）每 20 秒一个心跳，45 秒 = 连丢两次才认死。
     *   注意这里**不能写 0**（永不超时）—— 那样一条半死的连接会永远挂着，
     *   手机看着"连着"却什么都收不到，正是最难查的那种状态。
     * - `pingInterval(20s)`：OkHttp 自己发 WS ping 帧，20 秒内收不到 pong 就判连接已死。
     *   比 readTimeout 更早发现"socket 还在、对端没了"，这是 SSE 时期没有的能力。
     * - `connectTimeout(15s)`：隧道首次建连实测要 5~9 秒（TLS + 隧道 + 上游），
     *   10 秒会误杀。
     */
    val webSocketClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(45, TimeUnit.SECONDS)
            .pingInterval(20, TimeUnit.SECONDS)
            .addInterceptor(TokenInterceptor()) // X-Token 自动带；401 仍会触发 TokenGate.pause
            .build()
    }

    val api: ApiService by lazy {
        Retrofit.Builder()
            .baseUrl(ApiConfig.BASE_URL)
            .client(okHttp)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(ApiService::class.java)
    }
}
