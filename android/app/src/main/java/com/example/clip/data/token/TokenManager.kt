package com.example.clip.data.token

/**
 * 内存里的当前 token。OkHttp 拦截器同步读它（避免在拦截器线程里 runBlocking DataStore）。
 * 持久化在 [TokenStore]，进程启动时从这里恢复。
 */
object TokenManager {
    @Volatile
    var token: String? = null
}
