package com.example.clip.data.token

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "settings")

object TokenStore {

    private val KEY_TOKEN = stringPreferencesKey("token")

    fun tokenFlow(context: Context): Flow<String?> =
        context.dataStore.data.map { it[KEY_TOKEN] }

    suspend fun save(context: Context, token: String) {
        context.dataStore.edit { it[KEY_TOKEN] = token }
        TokenManager.token = token
        TokenGate.resume()  // 更新 token 后恢复队列
    }

    suspend fun clear(context: Context) {
        context.dataStore.edit { it.remove(KEY_TOKEN) }
        TokenManager.token = null
    }

    suspend fun load(context: Context): String? {
        val token = tokenFlow(context).first()
        TokenManager.token = token
        return token
    }
}
