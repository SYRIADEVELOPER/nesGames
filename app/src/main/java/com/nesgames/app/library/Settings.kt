package com.nesgames.app.library

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.store by preferencesDataStore("settings")

class Settings(private val ctx: Context) {
    private val treeKey = stringPreferencesKey("rom_tree_uri")
    private val intScaleKey = booleanPreferencesKey("integer_scale")

    val treeUri = ctx.store.data.map { it[treeKey] }
    suspend fun setTreeUri(v: String) = ctx.store.edit { it[treeKey] = v }
    suspend fun integerScale() = ctx.store.data.first()[intScaleKey] ?: false
    suspend fun setIntegerScale(v: Boolean) = ctx.store.edit { it[intScaleKey] = v }
}
