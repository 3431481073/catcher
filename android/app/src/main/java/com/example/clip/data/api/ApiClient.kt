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
private class TokenInterceptor : Interceptor {
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

    val api: ApiService by lazy {
        Retrofit.Builder()
            .baseUrl(ApiConfig.BASE_URL)
            .client(okHttp)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(ApiService::class.java)
    }
}
