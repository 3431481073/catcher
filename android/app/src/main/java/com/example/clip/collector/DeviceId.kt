package com.example.clip.collector

import android.annotation.SuppressLint
import android.content.Context
import android.provider.Settings
import java.security.MessageDigest

/**
 * 设备标识 = `Settings.Secure.ANDROID_ID` 的 **SHA256 前 16 位**。
 *
 * 为什么要哈希：`ANDROID_ID` 本身在**同一台设备上所有 App 看到的都一样**，
 * 直接上报等于把设备指纹明文发出去。取前 16 位十六进制 = 64 bit，
 * 两台设备撞上的概率可以忽略，而后端只需要"能区分设备"，不需要认得出是谁。
 *
 * ⚠️ 它是**按「App 签名 + 用户 + 设备」**算的，所以：换签名（比如换台机器重签 debug 包）
 * 会得到不同的 id；同一台机器上卸载重装（签名不变）**不变**。
 * 对"同一个人的手机"这个用途是对的。
 */
object DeviceId {

    @Volatile
    private var cached: String? = null

    /** 拿不到 ANDROID_ID 时的兜底值。**是常量而不是随机的** —— 随机的话每次上报都换设备，
     *  后端会把它当成一群新设备，去重全废。 */
    private val fallback: String by lazy { sha256Hex("no-android-id").take(16) }

    fun get(context: Context): String {
        cached?.let { return it }
        val androidId = readAndroidId(context) ?: return fallback
        return sha256Hex(androidId).take(16).also { cached = it }
    }

    /** 读失败（极少数 ROM）返回 null，**不缓存**，下次再试一遍。 */
    @SuppressLint("HardwareIds")
    private fun readAndroidId(context: Context): String? =
        runCatching {
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
        }.getOrNull()?.takeIf { it.isNotBlank() && it != "9774d56d682e549c" }   // 后者是已知的出厂脏值

    private fun sha256Hex(s: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(s.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}
