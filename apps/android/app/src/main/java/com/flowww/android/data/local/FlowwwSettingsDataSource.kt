package com.flowww.android.data.local

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.flowww.android.domain.models.FlowwwSettings
import com.flowww.android.domain.models.ThemeMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException

private val Context.flowwwDataStore by preferencesDataStore(name = "flowww_settings")

/**
 * DataStore-backed settings.
 *
 * Every value the UI exposes is persisted here, and read back through the
 * same [FlowwwSettings] projection, so a setting can never drift between the
 * screen and the store.
 */
class FlowwwSettingsDataSource(private val context: Context) {

    private object Keys {
        val deviceName = stringPreferencesKey("device_display_name")
        val themeMode = stringPreferencesKey("theme_mode")
        val notifications = booleanPreferencesKey("transfer_notifications")
        val notificationProgress = booleanPreferencesKey("notification_progress")
        val notificationCompletion = booleanPreferencesKey("notification_completion")
        val gestures = booleanPreferencesKey("motion_gestures")
        val sensitivity = floatPreferencesKey("gesture_sensitivity")
        val gestureFeedback = booleanPreferencesKey("gesture_feedback")
        val incomingApproval = booleanPreferencesKey("incoming_approval")
        val defaultSaveUri = stringPreferencesKey("default_save_uri")
        val autoConnect = booleanPreferencesKey("auto_connect_last_device")
        val maxStagedBytes = longPreferencesKey("max_staged_bytes")
        val autoExpire = booleanPreferencesKey("auto_expire")
        val expireHours = intPreferencesKey("expire_hours")
    }

    val settings: Flow<Result<FlowwwSettings>> = context.flowwwDataStore.data
        .map<Preferences, Result<FlowwwSettings>> { prefs -> Result.success(prefs.toSettings()) }
        .catch { e ->
            emit(Result.failure(if (e is IOException) e else IOException(e.message, e)))
        }

    suspend fun update(transform: (FlowwwSettings) -> FlowwwSettings): Result<Unit> = try {
        context.flowwwDataStore.edit { prefs ->
            val next = transform(prefs.toSettings())
            prefs[Keys.deviceName] = next.deviceDisplayName
            prefs[Keys.themeMode] = next.themeMode.name
            prefs[Keys.notifications] = next.transferNotifications
            prefs[Keys.notificationProgress] = next.notificationProgressEnabled
            prefs[Keys.notificationCompletion] = next.notificationCompletionEnabled
            prefs[Keys.gestures] = next.motionGesturesEnabled
            prefs[Keys.sensitivity] = next.gestureSensitivity.coerceIn(
                FlowwwSettings.MIN_SENSITIVITY,
                FlowwwSettings.MAX_SENSITIVITY
            )
            prefs[Keys.gestureFeedback] = next.gestureFeedbackEnabled
            prefs[Keys.incomingApproval] = next.incomingApprovalRequired
            if (next.defaultSaveLocationUri.isNullOrBlank()) {
                prefs.remove(Keys.defaultSaveUri)
            } else {
                prefs[Keys.defaultSaveUri] = next.defaultSaveLocationUri
            }
            prefs[Keys.autoConnect] = next.autoConnectLastDevice
            prefs[Keys.maxStagedBytes] = next.maxStagedBytes.coerceIn(
                FlowwwSettings.MIN_MAX_STAGED_BYTES,
                FlowwwSettings.MAX_MAX_STAGED_BYTES
            )
            prefs[Keys.autoExpire] = next.autoExpireEnabled
            prefs[Keys.expireHours] = next.expireAfterHours.coerceIn(
                FlowwwSettings.MIN_EXPIRE_HOURS,
                FlowwwSettings.MAX_EXPIRE_HOURS
            )
        }
        Result.success(Unit)
    } catch (e: Exception) {
        Result.failure(e)
    }

    private fun Preferences.toSettings(): FlowwwSettings = FlowwwSettings(
        deviceDisplayName = this[Keys.deviceName] ?: FlowwwSettings.DEFAULT_DEVICE_NAME,
        themeMode = ThemeMode.fromName(this[Keys.themeMode]),
        transferNotifications = this[Keys.notifications] ?: true,
        notificationProgressEnabled = this[Keys.notificationProgress] ?: true,
        notificationCompletionEnabled = this[Keys.notificationCompletion] ?: true,
        motionGesturesEnabled = this[Keys.gestures] ?: false,
        gestureSensitivity = this[Keys.sensitivity] ?: 0.7f,
        gestureFeedbackEnabled = this[Keys.gestureFeedback] ?: true,
        incomingApprovalRequired = this[Keys.incomingApproval] ?: true,
        defaultSaveLocationUri = this[Keys.defaultSaveUri],
        autoConnectLastDevice = this[Keys.autoConnect] ?: false,
        maxStagedBytes = this[Keys.maxStagedBytes] ?: FlowwwSettings.DEFAULT_MAX_STAGED_BYTES,
        autoExpireEnabled = this[Keys.autoExpire] ?: true,
        expireAfterHours = this[Keys.expireHours] ?: FlowwwSettings.DEFAULT_EXPIRE_HOURS
    )
}
