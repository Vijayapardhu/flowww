package com.flowww.android.data.local

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.flowww.android.domain.models.FlowwwSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException

private val Context.flowwwDataStore by preferencesDataStore(name = "flowww_settings")

class FlowwwSettingsDataSource(private val context: Context) {
    private object Keys {
        val deviceName = stringPreferencesKey("device_display_name")
        val notifications = booleanPreferencesKey("transfer_notifications")
        val gestures = booleanPreferencesKey("motion_gestures")
        val sensitivity = floatPreferencesKey("gesture_sensitivity")
        val incomingApproval = booleanPreferencesKey("incoming_approval")
        val defaultSaveUri = stringPreferencesKey("default_save_uri")
        val autoConnect = booleanPreferencesKey("auto_connect_last_device")
    }

    val settings: Flow<Result<FlowwwSettings>> = context.flowwwDataStore.data
        .map<Preferences, Result<FlowwwSettings>> { prefs ->
            Result.success(
                FlowwwSettings(
                    deviceDisplayName = prefs[Keys.deviceName] ?: FlowwwSettings.DEFAULT_DEVICE_NAME,
                    transferNotifications = prefs[Keys.notifications] ?: true,
                    motionGesturesEnabled = prefs[Keys.gestures] ?: false,
                    gestureSensitivity = prefs[Keys.sensitivity] ?: 0.7f,
                    incomingApprovalRequired = prefs[Keys.incomingApproval] ?: true,
                    defaultSaveLocationUri = prefs[Keys.defaultSaveUri],
                    autoConnectLastDevice = prefs[Keys.autoConnect] ?: false
                )
            )
        }
        .catch { e ->
            emit(Result.failure(if (e is IOException) e else IOException(e.message, e)))
        }

    suspend fun update(transform: (FlowwwSettings) -> FlowwwSettings): Result<Unit> = try {
        context.flowwwDataStore.edit { prefs ->
            val current = FlowwwSettings(
                deviceDisplayName = prefs[Keys.deviceName] ?: FlowwwSettings.DEFAULT_DEVICE_NAME,
                transferNotifications = prefs[Keys.notifications] ?: true,
                motionGesturesEnabled = prefs[Keys.gestures] ?: false,
                gestureSensitivity = prefs[Keys.sensitivity] ?: 0.7f,
                incomingApprovalRequired = prefs[Keys.incomingApproval] ?: true,
                defaultSaveLocationUri = prefs[Keys.defaultSaveUri],
                autoConnectLastDevice = prefs[Keys.autoConnect] ?: false
            )
            val next = transform(current)
            prefs[Keys.deviceName] = next.deviceDisplayName
            prefs[Keys.notifications] = next.transferNotifications
            prefs[Keys.gestures] = next.motionGesturesEnabled
            prefs[Keys.sensitivity] = next.gestureSensitivity.coerceIn(0.3f, 1.0f)
            prefs[Keys.incomingApproval] = next.incomingApprovalRequired
            if (next.defaultSaveLocationUri.isNullOrBlank()) prefs.remove(Keys.defaultSaveUri) else prefs[Keys.defaultSaveUri] = next.defaultSaveLocationUri
            prefs[Keys.autoConnect] = next.autoConnectLastDevice
        }
        Result.success(Unit)
    } catch (e: Exception) {
        Result.failure(e)
    }
}
