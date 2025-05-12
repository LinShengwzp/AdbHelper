package com.anmi.adbhelper.models

import android.content.Context
import androidx.compose.material3.DrawerValue
import androidx.compose.runtime.compositionLocalOf
import androidx.datastore.core.Serializer
import androidx.datastore.dataStore
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.InputStream
import java.io.OutputStream
import javax.inject.Inject

/**
 * App Setting config
 *
 * @constructor Create empty App Setting config
 */
@Serializable
data class AppSettingConfig(
    val drawerState: Boolean = false
)

/**
 * User config view model
 *
 * @constructor
 *
 * @param context
 */
@HiltViewModel
class AppSettingConfigViewModel @Inject constructor(
    @ApplicationContext context: Context
) : GenericStoreViewModel<AppSettingConfig>(
    store = GenericStore(
        context = context,
        name = "app_setting_config",
        serializer = AppSettingConfig.serializer(),
        default = AppSettingConfig()
    )
) {
    fun updateDrawerState(drawerState: Boolean) {
        viewModelScope.launch {
            store.update(state.value.copy(drawerState = drawerState))
        }
    }

    fun getDrawerState(): DrawerValue {
        return if (state.value.drawerState) DrawerValue.Closed else DrawerValue.Open
    }
}

/** 全局共享状态 **/
val LocalAppSettingConfig = compositionLocalOf<StateFlow<AppSettingConfig>> {
    error("LocalAppSettingConfig not provided")
}

/** 全局共享状态 **/
val LocalAppSettingConfigViewModel = compositionLocalOf<AppSettingConfigViewModel> {
    error("LocalAppSettingConfigViewModel not provided")
}


@Serializable
data class AdbCommand(val name: String, val command: List<String>)

object CommandStore {
    private val Context.commandDataStore by dataStore(
        fileName = "adb_commands.json",
        serializer = CommandListSerializer
    )

    suspend fun saveCommands(context: Context, commands: List<AdbCommand>) {
        context.commandDataStore.updateData { commands }
    }

    suspend fun loadCommands(context: Context): List<AdbCommand> {
        return context.commandDataStore.data.first()
    }
}

object CommandListSerializer : Serializer<List<AdbCommand>> {
    override val defaultValue: List<AdbCommand> = emptyList()

    override suspend fun readFrom(input: InputStream): List<AdbCommand> {
        return try {
            Json.decodeFromString(
                ListSerializer(AdbCommand.serializer()),
                input.readBytes().decodeToString()
            )
        } catch (e: Exception) {
            emptyList()
        }
    }

    override suspend fun writeTo(t: List<AdbCommand>, output: OutputStream) {
        output.write(
            Json.encodeToString(ListSerializer(AdbCommand.serializer()), t).encodeToByteArray()
        )
    }
}

