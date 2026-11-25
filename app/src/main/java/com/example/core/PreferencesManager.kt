package com.example.core

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.data.ThemeMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.json.JSONArray

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "alfred_gallery_prefs")

data class LocalPrefs(
    val themeMode: ThemeMode = ThemeMode.DARK,
    val vaultPassphrase: String = "",
    val widgetTitle: String = "Alfred's Daily Goals",
    val widgetTheme: String = "DARK",
    val widgetShowCompleted: Boolean = false,
    val aiTemperature: Float = 0.7f,
    val aiModelName: String = "gemini-3.8-flash"
)

class PreferencesManager(private val context: Context) {

    private val appContext = context.applicationContext
    private val widgetPrefs = appContext.getSharedPreferences(WIDGET_PREFS_NAME, Context.MODE_PRIVATE)

    val prefsFlow: Flow<LocalPrefs> = appContext.dataStore.data.map { prefs ->
        LocalPrefs(
            themeMode = ThemeMode.fromRaw(prefs[KEY_THEME_MODE]),
            vaultPassphrase = prefs[KEY_VAULT_PASSPHRASE].orEmpty(),
            widgetTitle = prefs[KEY_WIDGET_TITLE] ?: "Alfred's Daily Goals",
            widgetTheme = prefs[KEY_WIDGET_THEME] ?: "DARK",
            widgetShowCompleted = prefs[KEY_WIDGET_SHOW_COMPLETED] ?: false,
            aiTemperature = prefs[KEY_AI_TEMPERATURE] ?: 0.7f,
            aiModelName = prefs[KEY_AI_MODEL] ?: "gemini-3.8-flash"
        )
    }

    suspend fun setThemeMode(mode: ThemeMode) {
        appContext.dataStore.edit { it[KEY_THEME_MODE] = mode.name }
    }

    suspend fun setVaultPassphrase(passphrase: String) {
        appContext.dataStore.edit { it[KEY_VAULT_PASSPHRASE] = passphrase }
    }

    suspend fun setAiSettings(modelName: String, temperature: Float) {
        appContext.dataStore.edit {
            it[KEY_AI_MODEL] = modelName
            it[KEY_AI_TEMPERATURE] = temperature
        }
    }

    suspend fun saveWidgetConfig(title: String, theme: String, showCompleted: Boolean) {
        appContext.dataStore.edit {
            it[KEY_WIDGET_TITLE] = title
            it[KEY_WIDGET_THEME] = theme
            it[KEY_WIDGET_SHOW_COMPLETED] = showCompleted
        }
        widgetPrefs.edit()
            .putString(W_TITLE, title)
            .putString(W_THEME, theme)
            .putBoolean(W_SHOW_COMPLETED, showCompleted)
            .apply()
    }

    fun updateWidgetGoalsSnapshot(goals: List<Pair<String, Boolean>>, syncLabel: String) {
        val arr = JSONArray()
        goals.forEach { (title, completed) ->
            arr.put("${if (completed) "✓" else "○"} $title")
        }
        widgetPrefs.edit()
            .putString(W_GOALS_JSON, arr.toString())
            .putString(W_SYNC_LABEL, syncLabel)
            .apply()
    }

    fun readWidgetTitle(): String = widgetPrefs.getString(W_TITLE, "Alfred's Daily Goals") ?: "Alfred's Daily Goals"
    fun readWidgetTheme(): String = widgetPrefs.getString(W_THEME, "DARK") ?: "DARK"
    fun readWidgetShowCompleted(): Boolean = widgetPrefs.getBoolean(W_SHOW_COMPLETED, false)
    fun readWidgetSyncLabel(): String = widgetPrefs.getString(W_SYNC_LABEL, "E2EE Sync • Ready") ?: "E2EE Sync • Ready"

    fun readWidgetGoals(): List<String> {
        val raw = widgetPrefs.getString(W_GOALS_JSON, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { arr.getString(it) }
        } catch (_: Exception) {
            emptyList()
        }
    }

    companion object {
        private const val WIDGET_PREFS_NAME = "alfred_widget_prefs"
        private val KEY_THEME_MODE = stringPreferencesKey("theme_mode")
        private val KEY_VAULT_PASSPHRASE = stringPreferencesKey("vault_passphrase")
        private val KEY_WIDGET_TITLE = stringPreferencesKey("widget_title")
        private val KEY_WIDGET_THEME = stringPreferencesKey("widget_theme")
        private val KEY_WIDGET_SHOW_COMPLETED = booleanPreferencesKey("widget_show_completed")
        private val KEY_AI_TEMPERATURE = floatPreferencesKey("ai_temperature")
        private val KEY_AI_MODEL = stringPreferencesKey("ai_model")

        private const val W_TITLE = "w_title"
        private const val W_THEME = "w_theme"
        private const val W_SHOW_COMPLETED = "w_show_completed"
        private const val W_GOALS_JSON = "w_goals_json"
        private const val W_SYNC_LABEL = "w_sync_label"
    }
}
