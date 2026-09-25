package com.openfx.core.file

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.bookmarkDataStore by preferencesDataStore(name = "openfx_bookmarks")

/**
 * 書籤 (常用資料夾) — DataStore 持久化
 */
@Singleton
class BookmarkRepository @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val key = stringSetPreferencesKey("paths")

    val bookmarks: Flow<Set<String>> =
        context.bookmarkDataStore.data.map { it[key] ?: emptySet() }

    suspend fun toggle(path: String) {
        context.bookmarkDataStore.edit { prefs ->
            val current = prefs[key] ?: emptySet()
            prefs[key] = if (path in current) current - path else current + path
        }
    }

    suspend fun remove(path: String) {
        context.bookmarkDataStore.edit { prefs ->
            prefs[key] = (prefs[key] ?: emptySet()) - path
        }
    }
}
