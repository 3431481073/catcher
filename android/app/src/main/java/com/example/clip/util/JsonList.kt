package com.example.clip.util

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/** List<String> 与 JSON 数组互转（Room tags 列 / DataStore 候选列表共用）。空列表序列化为 "[]"。 */
object JsonList {
    private val gson = Gson()
    private val type = object : TypeToken<List<String>>() {}.type

    fun toJson(list: List<String>): String =
        if (list.isEmpty()) "[]" else gson.toJson(list)

    fun fromJson(s: String?): List<String> =
        if (s.isNullOrBlank()) emptyList()
        else runCatching { gson.fromJson<List<String>>(s, type) }.getOrDefault(emptyList())
}
