package com.example.clip.data.catalog

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.clip.util.FileLogger
import com.example.clip.util.JsonList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** 候选列表专用 DataStore（与 token 的 "settings" 分离）。 */
private val Context.catalogDataStore by preferencesDataStore(name = "catalog")

/** 平台预设：首次读取且从未写入时为默认值（用户可增删，删空后不再回填）。 */
private val DEFAULT_PLATFORMS = listOf("抖音", "视频号", "B站", "快手", "小红书", "其他")

/**
 * 前端维护的三套独立候选列表。内存 StateFlow 是单一来源（读写同步、无滞后），
 * 持久化异步写 DataStore（长生命周期 IO scope，不随卡片取消）。三套互不可见。
 */
object CatalogStore {

    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val KEY_PLATFORMS = stringPreferencesKey("platforms")
    private val KEY_TEXT_TAGS = stringPreferencesKey("text_tags")
    private val KEY_VIDEO_TAGS = stringPreferencesKey("video_tags")

    private val _platforms = MutableStateFlow(DEFAULT_PLATFORMS)
    private val _textTags = MutableStateFlow<List<String>>(emptyList())
    private val _videoTags = MutableStateFlow<List<String>>(emptyList())

    val platforms: StateFlow<List<String>> = _platforms
    val textTags: StateFlow<List<String>> = _textTags
    val videoTags: StateFlow<List<String>> = _videoTags

    /** 启动时载入 DataStore → 内存缓存；自检 textTags/videoTags 是否重叠（旧数据污染）。 */
    suspend fun load(context: Context) {
        val prefs = context.catalogDataStore.data.first()
        _platforms.value = prefs[KEY_PLATFORMS]?.let(JsonList::fromJson) ?: DEFAULT_PLATFORMS
        _textTags.value = JsonList.fromJson(prefs[KEY_TEXT_TAGS])
        _videoTags.value = JsonList.fromJson(prefs[KEY_VIDEO_TAGS])
        val overlap = _textTags.value.toSet() intersect _videoTags.value.toSet()
        if (overlap.isNotEmpty()) {
            FileLogger.w("CatalogStore", "自检：textTags 与 videoTags 重叠 $overlap")
        }
    }

    fun addTextTag(context: Context, tag: String) = add(_textTags, KEY_TEXT_TAGS, context, tag)
    fun removeTextTag(context: Context, tag: String) = remove(_textTags, KEY_TEXT_TAGS, context, tag)
    fun addVideoTag(context: Context, tag: String) = add(_videoTags, KEY_VIDEO_TAGS, context, tag)
    fun removeVideoTag(context: Context, tag: String) = remove(_videoTags, KEY_VIDEO_TAGS, context, tag)
    fun addPlatform(context: Context, name: String) = add(_platforms, KEY_PLATFORMS, context, name)

    private fun add(cache: MutableStateFlow<List<String>>, key: Preferences.Key<String>, context: Context, value: String) {
        val next = (cache.value + value).distinct()
        cache.value = next
        persist(context, key, next)
    }

    private fun remove(cache: MutableStateFlow<List<String>>, key: Preferences.Key<String>, context: Context, value: String) {
        val next = cache.value - value
        cache.value = next
        persist(context, key, next)
    }

    private fun persist(context: Context, key: Preferences.Key<String>, list: List<String>) {
        ioScope.launch {
            runCatching { context.catalogDataStore.edit { it[key] = JsonList.toJson(list) } }
        }
    }
}
