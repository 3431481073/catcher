package com.example.clip.util

/** 从链接域名推断平台（中文展示名）；识别不了返回 null，由用户手动选。 */
object Platform {
    fun infer(url: String): String? {
        val u = url.lowercase()
        return when {
            u.contains("bilibili.com") || u.contains("b23.tv") -> "B站"
            u.contains("douyin.com") || u.contains("iesdouyin.com") || u.contains("v.douyin.com") -> "抖音"
            u.contains("kuaishou.com") || u.contains("v.kuaishou.com") || u.contains("gifshow.com") -> "快手"
            u.contains("xiaohongshu.com") || u.contains("xhslink.com") -> "小红书"
            else -> null
        }
    }
}
