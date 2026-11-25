package com.example.ui.viewmodel

import android.content.Context
import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.core.CryptoManager
import com.example.core.LocalPrefs
import com.example.core.NetworkMonitor
import com.example.core.PreferencesManager
import com.example.core.WallpaperHelper
import com.example.data.AiStudioRepository
import com.example.data.AiThemePalette
import com.example.data.AlfredRepository
import com.example.data.GalleryItemType
import com.example.data.TaskLane
import com.example.data.TaskPriority
import com.example.data.ThemeMode
import com.example.data.UiGalleryItem
import com.example.data.UiState
import com.example.data.UiTask
import com.example.data.UiVaultMedia
import com.example.data.UiVoiceNote
import com.example.data.UserProfile
import com.example.widget.AlfredGoalsWidgetProvider
import com.google.firebase.Firebase
import com.google.firebase.auth.auth
import com.google.firebase.firestore.FirebaseFirestoreException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface AiActionState<out T> {
    data object Idle : AiActionState<Nothing>
    data object Loading : AiActionState<Nothing>
    data class Success<T>(val data: T) : AiActionState<T>
    data class Error(val message: String) : AiActionState<Nothing>
}

class MainViewModel(
    private val repository: AlfredRepository,
    private val aiRepository: AiStudioRepository,
    private val cryptoManager: CryptoManager,
    private val preferencesManager: PreferencesManager,
    networkMonitor: NetworkMonitor,
    val currentUserId: String
) : ViewModel() {

    val isOnline: StateFlow<Boolean> = networkMonitor.isOnlineFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), networkMonitor.isCurrentlyOnline())

    val localPrefs: StateFlow<LocalPrefs> = preferencesManager.prefsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), LocalPrefs())

    private val _hasPendingWrites = MutableStateFlow(false)
    val hasPendingWrites: StateFlow<Boolean> = _hasPendingWrites.asStateFlow()

    val userProfile: StateFlow<UserProfile?> = repository.observeUserProfile(currentUserId)
        .catch { error ->
            if (isPureCancellation(error)) throw error
            emit(null)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), null)

    val themeMode: StateFlow<ThemeMode> = combine(localPrefs, userProfile) { local, remote ->
        if (remote != null) ThemeMode.fromRaw(remote.themeMode) else local.themeMode
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), ThemeMode.DARK)

    val activeAiTheme: StateFlow<AiThemePalette?> = userProfile
        .map { AiThemePalette.fromJsonOrNull(it?.activeAiThemeJson) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), null)

    val tasksState: StateFlow<UiState<List<UiTask>>> = combine(
        repository.observeTasks(currentUserId),
        userProfile,
        localPrefs
    ) { snap, profile, prefs ->
        _hasPendingWrites.value = snap.hasPendingWrites
        val salt = profile?.vaultSalt?.ifBlank { "default_salt_$currentUserId" } ?: "default_salt_$currentUserId"
        val pass = cryptoManager.resolvePassphrase(currentUserId, prefs.vaultPassphrase)
        val decrypted = snap.items.map { item ->
            UiTask(
                id = item.id,
                title = if (item.encrypted) cryptoManager.decryptText(item.titleCipher, pass, salt) else item.titleCipher,
                note = if (item.encrypted) cryptoManager.decryptText(item.noteCipher, pass, salt) else item.noteCipher,
                encrypted = item.encrypted,
                completed = item.completed,
                lane = TaskLane.fromRaw(item.lane),
                priority = TaskPriority.fromRaw(item.priority),
                category = item.category,
                sortOrder = item.sortOrder,
                voiceCaptured = item.voiceCaptured,
                dueLabel = item.dueLabel,
                updatedAt = item.updatedAt ?: item.createdAt
            )
        }
        UiState.Success(decrypted) as UiState<List<UiTask>>
    }.catch { error ->
        val firestoreError = unwrapFirestoreError(error)
        if (firestoreError == null && error is CancellationException) throw error
        emit(UiState.Error((firestoreError ?: error).message ?: "Failed to sync daily goals"))
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), UiState.Loading)

    val notesState: StateFlow<UiState<List<UiVoiceNote>>> = combine(
        repository.observeNotes(currentUserId),
        userProfile,
        localPrefs
    ) { snap, profile, prefs ->
        val salt = profile?.vaultSalt?.ifBlank { "default_salt_$currentUserId" } ?: "default_salt_$currentUserId"
        val pass = cryptoManager.resolvePassphrase(currentUserId, prefs.vaultPassphrase)
        val decrypted = snap.items.map { item ->
            UiVoiceNote(
                id = item.id,
                title = if (item.encrypted) cryptoManager.decryptText(item.titleCipher, pass, salt) else item.titleCipher,
                content = if (item.encrypted) cryptoManager.decryptText(item.contentCipher, pass, salt) else item.contentCipher,
                encrypted = item.encrypted,
                tags = item.tags,
                durationSec = item.durationSec,
                aiSummary = item.aiSummaryCipher?.let {
                    if (item.encrypted) cryptoManager.decryptText(it, pass, salt) else it
                },
                updatedAt = item.updatedAt ?: item.createdAt
            )
        }
        UiState.Success(decrypted) as UiState<List<UiVoiceNote>>
    }.catch { error ->
        val firestoreError = unwrapFirestoreError(error)
        if (firestoreError == null && error is CancellationException) throw error
        emit(UiState.Error((firestoreError ?: error).message ?: "Failed to sync voice notes"))
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), UiState.Loading)

    val galleryState: StateFlow<UiState<List<UiGalleryItem>>> = repository.observeGalleryItems(currentUserId)
        .map<_, UiState<List<UiGalleryItem>>> { items ->
            UiState.Success(
                items.map { item ->
                    UiGalleryItem(
                        id = item.id,
                        title = item.title,
                        prompt = item.prompt,
                        itemType = GalleryItemType.fromRaw(item.itemType),
                        palette = AiThemePalette.fromJsonOrNull(item.paletteJson),
                        imageBase64 = item.imageBase64,
                        deviceProfile = item.deviceProfile,
                        updatedAt = item.updatedAt ?: item.createdAt
                    )
                }
            )
        }
        .catch { error ->
            val firestoreError = unwrapFirestoreError(error)
            if (firestoreError == null && error is CancellationException) throw error
            emit(UiState.Error((firestoreError ?: error).message ?: "Failed to load gallery"))
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), UiState.Loading)

    val vaultMediaState: StateFlow<UiState<List<UiVaultMedia>>> = combine(
        repository.observeVaultMedia(currentUserId),
        userProfile,
        localPrefs
    ) { items, profile, prefs ->
        val salt = profile?.vaultSalt?.ifBlank { "default_salt_$currentUserId" } ?: "default_salt_$currentUserId"
        val pass = cryptoManager.resolvePassphrase(currentUserId, prefs.vaultPassphrase)
        val decrypted = withContext(Dispatchers.Default) {
            items.map { item ->
                UiVaultMedia(
                    id = item.id,
                    fileName = item.fileName,
                    mimeType = item.mimeType,
                    sizeBytes = item.sizeBytes,
                    decryptedBytes = if (item.encrypted) {
                        cryptoManager.decryptPayloadToBytes(item.cipherPayload, pass, salt)
                    } else null,
                    sha256Checksum = item.sha256Checksum,
                    encrypted = item.encrypted,
                    updatedAt = item.updatedAt ?: item.createdAt
                )
            }
        }
        UiState.Success(decrypted) as UiState<List<UiVaultMedia>>
    }.catch { error ->
        val firestoreError = unwrapFirestoreError(error)
        if (firestoreError == null && error is CancellationException) throw error
        emit(UiState.Error((firestoreError ?: error).message ?: "Failed to load encrypted vault"))
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), UiState.Loading)

    private val _aiWallpaperState = MutableStateFlow<AiActionState<Bitmap>>(AiActionState.Idle)
    val aiWallpaperState: StateFlow<AiActionState<Bitmap>> = _aiWallpaperState.asStateFlow()

    private val _aiThemeState = MutableStateFlow<AiActionState<AiThemePalette>>(AiActionState.Idle)
    val aiThemeState: StateFlow<AiActionState<AiThemePalette>> = _aiThemeState.asStateFlow()

    private val _aiCoachState = MutableStateFlow<AiActionState<Pair<String, List<String>>>>(AiActionState.Idle)
    val aiCoachState: StateFlow<AiActionState<Pair<String, List<String>>>> = _aiCoachState.asStateFlow()

    private val _events = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val events: SharedFlow<String> = _events.asSharedFlow()

    init {
        viewModelScope.launch {
            val user = Firebase.auth.currentUser
            repository.ensureUserProfile(
                userId = currentUserId,
                displayName = user?.displayName ?: "Alfred",
                email = user?.email ?: "alfredxornam@gmail.com"
            )
        }
    }

    private fun activeSalt(): String =
        userProfile.value?.vaultSalt?.ifBlank { "default_salt_$currentUserId" } ?: "default_salt_$currentUserId"

    private fun activePassphrase(): String =
        cryptoManager.resolvePassphrase(currentUserId, localPrefs.value.vaultPassphrase)

    private fun encryptionEnabled(): Boolean = userProfile.value?.encryptionEnabled ?: true

    fun syncWidgetCache(context: Context, tasks: List<UiTask>) {
        val syncLabel = if (isOnline.value) "E2EE Sync • Online" else "Offline Cache • Protected"
        preferencesManager.updateWidgetGoalsSnapshot(
            goals = tasks.map { it.title to it.completed },
            syncLabel = syncLabel
        )
        AlfredGoalsWidgetProvider.updateAllWidgets(context)
    }

    fun addTask(
        title: String,
        note: String,
        lane: TaskLane,
        priority: TaskPriority,
        category: String,
        dueLabel: String,
        voiceCaptured: Boolean = false
    ) {
        val cleanTitle = title.trim()
        if (cleanTitle.isEmpty()) {
            _events.tryEmit("Please enter a goal title.")
            return
        }
        viewModelScope.launch {
            val enc = encryptionEnabled()
            val salt = activeSalt()
            val pass = activePassphrase()
            val currentCount = (tasksState.value as? UiState.Success)?.data?.size?.toLong() ?: 0L
            val result = repository.addTask(
                userId = currentUserId,
                titleCipher = if (enc) cryptoManager.encryptText(cleanTitle, pass, salt) else cleanTitle,
                noteCipher = if (enc) cryptoManager.encryptText(note.trim(), pass, salt) else note.trim(),
                encrypted = enc,
                lane = lane,
                priority = priority,
                category = category.ifBlank { "Daily Goal" },
                sortOrder = currentCount,
                voiceCaptured = voiceCaptured,
                dueLabel = dueLabel.ifBlank { "Today" }
            )
            result.onFailure { _events.tryEmit(it.localizedMessage ?: "Failed to save goal") }
        }
    }

    fun toggleTaskCompleted(task: UiTask) {
        viewModelScope.launch {
            val newCompleted = !task.completed
            val newLane = if (newCompleted) TaskLane.DONE else TaskLane.FOCUS
            repository.updateTask(
                userId = currentUserId,
                taskId = task.id,
                completed = newCompleted,
                lane = newLane
            ).onFailure { _events.tryEmit(it.localizedMessage ?: "Update failed") }
        }
    }

    fun moveTaskToLane(task: UiTask, lane: TaskLane) {
        viewModelScope.launch {
            repository.updateTask(
                userId = currentUserId,
                taskId = task.id,
                completed = (lane == TaskLane.DONE),
                lane = lane
            ).onFailure { _events.tryEmit(it.localizedMessage ?: "Move failed") }
        }
    }

    fun commitReorderedTasks(reordered: List<UiTask>) {
        viewModelScope.launch {
            val payload = reordered.mapIndexed { idx, task ->
                Triple(task.id, idx.toLong(), task.lane)
            }
            repository.reorderTasksBatch(currentUserId, payload)
                .onFailure { _events.tryEmit(it.localizedMessage ?: "Reorder sync failed") }
        }
    }

    fun deleteTask(taskId: String) {
        viewModelScope.launch {
            repository.deleteTask(currentUserId, taskId)
                .onFailure { _events.tryEmit(it.localizedMessage ?: "Delete failed") }
        }
    }

    fun saveVoiceNote(
        title: String,
        transcript: String,
        durationSec: Long,
        useAiStructuring: Boolean
    ) {
        val cleanContent = transcript.trim()
        if (cleanContent.isEmpty()) {
            _events.tryEmit("Voice note is empty.")
            return
        }
        viewModelScope.launch {
            val enc = encryptionEnabled()
            val salt = activeSalt()
            val pass = activePassphrase()
            val profile = userProfile.value
            val modelName = profile?.aiModelName ?: localPrefs.value.aiModelName
            val temp = (profile?.aiTemperature ?: localPrefs.value.aiTemperature.toDouble()).toFloat()

            var finalTitle = title.ifBlank { "Voice Note" }
            var finalSummary: String? = null
            var finalTags = listOf("voice")

            if (useAiStructuring) {
                val structured = aiRepository.structureVoiceNote(cleanContent, modelName, temp)
                structured.onSuccess { res ->
                    finalTitle = res.title
                    finalSummary = res.summary
                    finalTags = res.tags.ifEmpty { listOf("voice") }
                    res.actionTasks.forEachIndexed { idx, action ->
                        val baseOrder = (tasksState.value as? UiState.Success)?.data?.size?.toLong() ?: 0L
                        repository.addTask(
                            userId = currentUserId,
                            titleCipher = if (enc) cryptoManager.encryptText(action, pass, salt) else action,
                            noteCipher = if (enc) cryptoManager.encryptText("From voice note: $finalTitle", pass, salt) else "From voice note: $finalTitle",
                            encrypted = enc,
                            lane = TaskLane.FOCUS,
                            priority = TaskPriority.MEDIUM,
                            category = "Voice Goal",
                            sortOrder = baseOrder + idx,
                            voiceCaptured = true,
                            dueLabel = "Today"
                        )
                    }
                }.onFailure {
                    _events.tryEmit("Note saved locally (AI structuring offline/unavailable: ${it.localizedMessage})")
                }
            }

            repository.addNote(
                userId = currentUserId,
                titleCipher = if (enc) cryptoManager.encryptText(finalTitle, pass, salt) else finalTitle,
                contentCipher = if (enc) cryptoManager.encryptText(cleanContent, pass, salt) else cleanContent,
                encrypted = enc,
                tags = finalTags,
                durationSec = durationSec,
                aiSummaryCipher = finalSummary?.let { if (enc) cryptoManager.encryptText(it, pass, salt) else it }
            ).onSuccess {
                _events.tryEmit("Encrypted voice note saved & synced.")
            }.onFailure {
                _events.tryEmit(it.localizedMessage ?: "Failed to save note")
            }
        }
    }

    fun deleteNote(noteId: String) {
        viewModelScope.launch {
            repository.deleteNote(currentUserId, noteId)
                .onFailure { _events.tryEmit(it.localizedMessage ?: "Delete failed") }
        }
    }

    fun generateAiWallpaper(prompt: String, stylePreset: String, deviceProfile: String) {
        val clean = prompt.trim()
        if (clean.isEmpty()) {
            _events.tryEmit("Enter a wallpaper prompt first.")
            return
        }
        viewModelScope.launch {
            _aiWallpaperState.value = AiActionState.Loading
            val res = aiRepository.generateWallpaper(clean, stylePreset, deviceProfile)
            res.onSuccess { bmp ->
                _aiWallpaperState.value = AiActionState.Success(bmp)
                val base64 = WallpaperHelper.bitmapToBase64Jpeg(bmp)
                repository.saveGalleryItem(
                    userId = currentUserId,
                    title = clean.take(48),
                    prompt = "$stylePreset: $clean",
                    itemType = GalleryItemType.WALLPAPER,
                    paletteJson = null,
                    imageBase64 = base64,
                    deviceProfile = deviceProfile
                )
            }.onFailure { err ->
                _aiWallpaperState.value = AiActionState.Error(err.localizedMessage ?: "Wallpaper generation failed.")
            }
        }
    }

    fun generateAiTheme(prompt: String) {
        val clean = prompt.trim()
        if (clean.isEmpty()) {
            _events.tryEmit("Enter a theme idea first.")
            return
        }
        viewModelScope.launch {
            _aiThemeState.value = AiActionState.Loading
            val profile = userProfile.value
            val modelName = profile?.aiModelName ?: localPrefs.value.aiModelName
            val temp = (profile?.aiTemperature ?: localPrefs.value.aiTemperature.toDouble()).toFloat()
            val res = aiRepository.generateThemePalette(clean, modelName, temp)
            res.onSuccess { palette ->
                _aiThemeState.value = AiActionState.Success(palette)
                repository.saveGalleryItem(
                    userId = currentUserId,
                    title = palette.name,
                    prompt = clean,
                    itemType = GalleryItemType.THEME,
                    paletteJson = palette.toJsonString(),
                    imageBase64 = null,
                    deviceProfile = "Universal Material 3"
                )
            }.onFailure { err ->
                _aiThemeState.value = AiActionState.Error(err.localizedMessage ?: "Theme generation failed.")
            }
        }
    }

    fun applyAiTheme(palette: AiThemePalette) {
        viewModelScope.launch {
            val targetMode = if (themeMode.value == ThemeMode.HACKER) ThemeMode.DARK else themeMode.value
            preferencesManager.setThemeMode(targetMode)
            repository.updateUserPreferences(
                userId = currentUserId,
                themeMode = targetMode,
                activeAiThemeJson = palette.toJsonString()
            ).onSuccess {
                _events.tryEmit("Applied AI theme: ${palette.name}")
            }
        }
    }

    fun clearAiTheme() {
        viewModelScope.launch {
            repository.updateUserPreferences(
                userId = currentUserId,
                activeAiThemeJson = ""
            ).onSuccess {
                _events.tryEmit("Restored default studio palette")
            }
        }
    }

    fun requestAiGoalCoaching() {
        val currentTasks = (tasksState.value as? UiState.Success)?.data.orEmpty()
        val summary = if (currentTasks.isEmpty()) {
            "No goals added yet; user wants a productive day."
        } else {
            currentTasks.take(8).joinToString("; ") { "${it.title} (${it.lane.label})" }
        }
        viewModelScope.launch {
            _aiCoachState.value = AiActionState.Loading
            val profile = userProfile.value
            val modelName = profile?.aiModelName ?: localPrefs.value.aiModelName
            val temp = (profile?.aiTemperature ?: localPrefs.value.aiTemperature.toDouble()).toFloat()
            aiRepository.suggestDailyGoals(summary, modelName, temp)
                .onSuccess { _aiCoachState.value = AiActionState.Success(it) }
                .onFailure { _aiCoachState.value = AiActionState.Error(it.localizedMessage ?: "Coach unavailable") }
        }
    }

    fun uploadEncryptedMediaBytes(rawBytes: ByteArray, fileName: String) {
        viewModelScope.launch {
            val salt = activeSalt()
            val pass = activePassphrase()
            val cipherPayload = withContext(Dispatchers.Default) {
                cryptoManager.encryptBytesToPayload(rawBytes, pass, salt)
            }
            val checksum = cryptoManager.sha256Hex(rawBytes)
            repository.uploadEncryptedMedia(
                userId = currentUserId,
                fileName = fileName,
                mimeType = "image/jpeg",
                sizeBytes = rawBytes.size.toLong(),
                cipherPayload = cipherPayload,
                sha256Checksum = checksum
            ).onSuccess {
                _events.tryEmit("Media encrypted (AES-256) & backed up to cloud vault.")
            }.onFailure {
                _events.tryEmit(it.localizedMessage ?: "Vault backup failed.")
            }
        }
    }

    fun deleteVaultMedia(mediaId: String) {
        viewModelScope.launch {
            repository.deleteVaultMedia(currentUserId, mediaId)
        }
    }

    fun deleteGalleryItem(itemId: String) {
        viewModelScope.launch {
            repository.deleteGalleryItem(currentUserId, itemId)
        }
    }

    fun setThemeMode(mode: ThemeMode) {
        viewModelScope.launch {
            preferencesManager.setThemeMode(mode)
            repository.updateUserPreferences(userId = currentUserId, themeMode = mode)
        }
    }

    fun cycleThemeMode() {
        val next = when (themeMode.value) {
            ThemeMode.DARK -> ThemeMode.HACKER
            ThemeMode.HACKER -> ThemeMode.LIGHT
            ThemeMode.LIGHT -> ThemeMode.DARK
        }
        setThemeMode(next)
    }

    fun updateVaultPassphrase(newPassphrase: String) {
        viewModelScope.launch {
            preferencesManager.setVaultPassphrase(newPassphrase.trim())
            _events.tryEmit(
                if (newPassphrase.isBlank()) "Using Google-account default vault key"
                else "Custom E2EE vault passphrase updated on this device"
            )
        }
    }

    fun updateAiSettings(modelName: String, temperature: Float, autoStructureVoice: Boolean) {
        viewModelScope.launch {
            preferencesManager.setAiSettings(modelName, temperature)
            repository.updateUserPreferences(
                userId = currentUserId,
                aiModelName = modelName,
                aiTemperature = temperature.toDouble(),
                autoStructureVoice = autoStructureVoice
            )
            _events.tryEmit("AI settings updated ($modelName)")
        }
    }

    fun saveWidgetSettings(context: Context, title: String, theme: String, showCompleted: Boolean) {
        viewModelScope.launch {
            preferencesManager.saveWidgetConfig(title, theme, showCompleted)
            AlfredGoalsWidgetProvider.updateAllWidgets(context)
            _events.tryEmit("Home screen widget updated")
        }
    }

    private fun unwrapFirestoreError(error: Throwable): FirebaseFirestoreException? =
        generateSequence(error) { it.cause }
            .filterIsInstance<FirebaseFirestoreException>()
            .firstOrNull()

    private fun isPureCancellation(error: Throwable): Boolean =
        unwrapFirestoreError(error) == null && error is CancellationException

    companion object {
        fun provideFactory(currentUserId: String): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = checkNotNull(this[APPLICATION_KEY]) {
                    "APPLICATION_KEY missing from CreationExtras"
                }
                val crypto = CryptoManager()
                MainViewModel(
                    repository = AlfredRepository(app, crypto),
                    aiRepository = AiStudioRepository(),
                    cryptoManager = crypto,
                    preferencesManager = PreferencesManager(app),
                    networkMonitor = NetworkMonitor(app),
                    currentUserId = currentUserId
                )
            }
        }
    }
}
