package com.example.data

import androidx.annotation.Keep
import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.IgnoreExtraProperties
import com.google.firebase.firestore.ServerTimestamp
import org.json.JSONObject

enum class ThemeMode {
    DARK,
    LIGHT,
    HACKER;

    companion object {
        fun fromRaw(raw: String?): ThemeMode =
            entries.firstOrNull { it.name.equals(raw, ignoreCase = true) } ?: DARK
    }
}

enum class TaskLane(val label: String) {
    FOCUS("Today's Focus"),
    PROGRESS("In Progress"),
    DONE("Completed");

    companion object {
        fun fromRaw(raw: String?): TaskLane =
            entries.firstOrNull { it.name.equals(raw, ignoreCase = true) } ?: FOCUS
    }
}

enum class TaskPriority(val label: String) {
    HIGH("High"),
    MEDIUM("Medium"),
    LOW("Low");

    companion object {
        fun fromRaw(raw: String?): TaskPriority =
            entries.firstOrNull { it.name.equals(raw, ignoreCase = true) } ?: MEDIUM
    }
}

enum class GalleryItemType {
    WALLPAPER,
    THEME;

    companion object {
        fun fromRaw(raw: String?): GalleryItemType =
            entries.firstOrNull { it.name.equals(raw, ignoreCase = true) } ?: WALLPAPER
    }
}

sealed interface UiState<out T> {
    data object Loading : UiState<Nothing>
    data class Success<T>(val data: T) : UiState<T>
    data class Error(val message: String) : UiState<Nothing>
}

@Keep
@IgnoreExtraProperties
data class UserProfile(
    @DocumentId val id: String = "",
    val userId: String = "",
    val displayName: String = "",
    val email: String = "",
    val vaultSalt: String = "",
    val encryptionEnabled: Boolean = true,
    val themeMode: String = ThemeMode.DARK.name,
    val activeAiThemeJson: String? = null,
    val aiModelName: String = "gemini-3.8-flash",
    val aiTemperature: Double = 0.7,
    val autoStructureVoice: Boolean = true,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@Keep
@IgnoreExtraProperties
data class DailyTask(
    @DocumentId val id: String = "",
    val userId: String = "",
    val titleCipher: String = "",
    val noteCipher: String = "",
    val encrypted: Boolean = true,
    val completed: Boolean = false,
    val lane: String = TaskLane.FOCUS.name,
    val priority: String = TaskPriority.MEDIUM.name,
    val category: String = "Daily Goal",
    val sortOrder: Long = 0L,
    val voiceCaptured: Boolean = false,
    val dueLabel: String = "Today",
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@Keep
@IgnoreExtraProperties
data class VoiceNote(
    @DocumentId val id: String = "",
    val userId: String = "",
    val titleCipher: String = "",
    val contentCipher: String = "",
    val encrypted: Boolean = true,
    val tags: List<String> = emptyList(),
    val durationSec: Long = 0L,
    val aiSummaryCipher: String? = null,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@Keep
@IgnoreExtraProperties
data class GalleryItem(
    @DocumentId val id: String = "",
    val userId: String = "",
    val title: String = "",
    val prompt: String = "",
    val itemType: String = GalleryItemType.WALLPAPER.name,
    val paletteJson: String? = null,
    val imageBase64: String? = null,
    val encrypted: Boolean = false,
    val deviceProfile: String = "Tecno Camon 20 AMOLED",
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@Keep
@IgnoreExtraProperties
data class VaultMedia(
    @DocumentId val id: String = "",
    val userId: String = "",
    val fileName: String = "",
    val mimeType: String = "image/jpeg",
    val sizeBytes: Long = 0L,
    val cipherPayload: String = "",
    val sha256Checksum: String = "",
    val encrypted: Boolean = true,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

// Decrypted UI Models
data class UiTask(
    val id: String,
    val title: String,
    val note: String,
    val encrypted: Boolean,
    val completed: Boolean,
    val lane: TaskLane,
    val priority: TaskPriority,
    val category: String,
    val sortOrder: Long,
    val voiceCaptured: Boolean,
    val dueLabel: String,
    val updatedAt: Timestamp?
)

data class UiVoiceNote(
    val id: String,
    val title: String,
    val content: String,
    val encrypted: Boolean,
    val tags: List<String>,
    val durationSec: Long,
    val aiSummary: String?,
    val updatedAt: Timestamp?
)

data class UiGalleryItem(
    val id: String,
    val title: String,
    val prompt: String,
    val itemType: GalleryItemType,
    val palette: AiThemePalette?,
    val imageBase64: String?,
    val deviceProfile: String,
    val updatedAt: Timestamp?
)

data class UiVaultMedia(
    val id: String,
    val fileName: String,
    val mimeType: String,
    val sizeBytes: Long,
    val decryptedBytes: ByteArray?,
    val sha256Checksum: String,
    val encrypted: Boolean,
    val updatedAt: Timestamp?
)

data class AiThemePalette(
    val name: String,
    val mood: String,
    val primaryHex: String,
    val secondaryHex: String,
    val tertiaryHex: String,
    val backgroundHex: String,
    val surfaceHex: String
) {
    fun toJsonString(): String = JSONObject().apply {
        put("name", name)
        put("mood", mood)
        put("primaryHex", primaryHex)
        put("secondaryHex", secondaryHex)
        put("tertiaryHex", tertiaryHex)
        put("backgroundHex", backgroundHex)
        put("surfaceHex", surfaceHex)
    }.toString()

    companion object {
        fun fromJsonOrNull(raw: String?): AiThemePalette? {
            if (raw.isNullOrBlank()) return null
            return try {
                val obj = JSONObject(raw)
                AiThemePalette(
                    name = obj.optString("name", "AI Theme"),
                    mood = obj.optString("mood", "Custom"),
                    primaryHex = obj.optString("primaryHex", "#00F5D4"),
                    secondaryHex = obj.optString("secondaryHex", "#3A86FF"),
                    tertiaryHex = obj.optString("tertiaryHex", "#FFBE0B"),
                    backgroundHex = obj.optString("backgroundHex", "#090E1A"),
                    surfaceHex = obj.optString("surfaceHex", "#121A2F")
                )
            } catch (_: Exception) {
                null
            }
        }
    }
}

data class StructuredVoiceResult(
    val title: String,
    val summary: String,
    val tags: List<String>,
    val actionTasks: List<String>
)
