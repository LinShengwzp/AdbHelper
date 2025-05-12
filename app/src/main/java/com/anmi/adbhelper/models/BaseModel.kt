package com.anmi.adbhelper.models

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Persistent state
 *
 * @param T
 * @constructor Create empty Persistent state
 */
interface PersistentState<T> {
    val state: StateFlow<T>
    suspend fun update(value: T)
    suspend fun clear()
}

class GenericStore<T>(
    context: Context,
    name: String,
    private val serializer: KSerializer<T>,
    private val default: T,
) : PersistentState<T> {

    private val _state = MutableStateFlow(default)
    private val valueKey = stringPreferencesKey("value")
    private val dataStore = context.createDataStore(name)
    override val state: StateFlow<T> = _state.asStateFlow()

    init {
        CoroutineScope(Dispatchers.IO).launch {
            dataStore.data.map {
                val raw = it[valueKey] ?: return@map default
                try {
                    Json.decodeFromString(serializer, raw)
                } catch (e: Exception) {
                    default
                }
            }.collect { _state.value = it }
        }
    }

    private fun Context.createDataStore(name: String): DataStore<Preferences> =
        PreferenceDataStoreFactory.create(
            produceFile = { File(this.filesDir, "$name.preferences_pb") })

    override suspend fun update(value: T) {
        dataStore.edit {
            it[valueKey] = Json.encodeToString(serializer, value)
        }
    }

    override suspend fun clear() {
        dataStore.edit { it.clear() }
    }

}

open class GenericStoreViewModel<T>(
    internal val store: GenericStore<T>
) : ViewModel() {

    val state: StateFlow<T> = store.state

    fun update(value: T) {
        viewModelScope.launch {
            store.update(value)
        }
    }

    fun clear() {
        viewModelScope.launch {
            store.clear()
        }
    }
}