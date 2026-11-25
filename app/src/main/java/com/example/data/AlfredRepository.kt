package com.example.data

import android.content.Context
import com.example.R
import com.example.core.CryptoManager
import com.google.firebase.Firebase
import com.google.firebase.auth.auth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.FirebaseFirestoreSettings
import com.google.firebase.firestore.PersistentCacheSettings
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.snapshots
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await

data class SyncSnapshot<T>(
    val items: List<T>,
    val hasPendingWrites: Boolean,
    val isFromCache: Boolean
)

class AlfredRepository(
    private val db: FirebaseFirestore,
    private val cryptoManager: CryptoManager
) {
    constructor(context: Context, cryptoManager: CryptoManager) : this(
        createConfiguredFirestore(context),
        cryptoManager
    )

    companion object {
        fun createConfiguredFirestore(context: Context): FirebaseFirestore {
            val dbId = context.applicationContext.getString(R.string.firestore_database_id)
            val instance = FirebaseFirestore.getInstance(dbId)
            try {
                val settings = FirebaseFirestoreSettings.Builder()
                    .setLocalCacheSettings(
                        PersistentCacheSettings.newBuilder()
                            .setSizeBytes(FirebaseFirestoreSettings.CACHE_SIZE_UNLIMITED)
                            .build()
                    )
                    .build()
                instance.firestoreSettings = settings
            } catch (_: IllegalStateException) {
                // Settings already applied on existing instance
            }
            return instance
        }
    }

    private val auth = Firebase.auth

    private fun requireUserId(): String {
        return auth.currentUser?.uid
            ?: throw IllegalStateException("User must be signed in with Google before accessing Firestore.")
    }

    private fun userDoc(uid: String) = db.collection("users").document(uid)

    fun observeUserProfile(userId: String): Flow<UserProfile?> = flow {
        require(userId.isNotBlank())
        val path = "users/$userId"
        emitAll(
            userDoc(userId).snapshots()
                .map { snap ->
                    snap.toObject(
                        UserProfile::class.java,
                        DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                    )
                }
                .catch { error ->
                    logStreamError(error, OperationType.GET, path, userId)
                    throw error
                }
        )
    }

    suspend fun ensureUserProfile(
        userId: String,
        displayName: String,
        email: String
    ): Result<UserProfile> {
        val docPath = "users/$userId"
        return try {
            val uid = requireUserId()
            check(uid == userId) { "Signed-in user mismatch" }
            val snap = userDoc(uid).get().await()
            val existing = snap.toObject(
                UserProfile::class.java,
                DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
            )
            if (existing != null) {
                return Result.success(existing)
            }
            val salt = cryptoManager.generateSaltBase64()
            val safeName = displayName.ifBlank { "Alfred User" }.take(100)
            val safeEmail = email.take(150)
            val payload = mapOf(
                "userId" to uid,
                "displayName" to safeName,
                "email" to safeEmail,
                "vaultSalt" to salt,
                "encryptionEnabled" to true,
                "themeMode" to ThemeMode.DARK.name,
                "aiModelName" to "gemini-3.8-flash",
                "aiTemperature" to 0.7,
                "autoStructureVoice" to true,
                "createdAt" to FieldValue.serverTimestamp(),
                "updatedAt" to FieldValue.serverTimestamp()
            )
            userDoc(uid).set(payload).await()
            Result.success(
                UserProfile(
                    id = uid,
                    userId = uid,
                    displayName = safeName,
                    email = safeEmail,
                    vaultSalt = salt,
                    encryptionEnabled = true,
                    themeMode = ThemeMode.DARK.name,
                    aiModelName = "gemini-3.8-flash",
                    aiTemperature = 0.7,
                    autoStructureVoice = true
                )
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, docPath)
            Result.failure(e)
        }
    }

    suspend fun updateUserPreferences(
        userId: String,
        themeMode: ThemeMode? = null,
        activeAiThemeJson: String? = null,
        aiModelName: String? = null,
        aiTemperature: Double? = null,
        autoStructureVoice: Boolean? = null,
        encryptionEnabled: Boolean? = null
    ): Result<Unit> {
        val docPath = "users/$userId"
        return try {
            val uid = requireUserId()
            check(uid == userId)
            val updates = mutableMapOf<String, Any>(
                "updatedAt" to FieldValue.serverTimestamp()
            )
            themeMode?.let { updates["themeMode"] = it.name }
            activeAiThemeJson?.let { updates["activeAiThemeJson"] = it.take(2000) }
            aiModelName?.let { updates["aiModelName"] = it }
            aiTemperature?.let { updates["aiTemperature"] = it.coerceIn(0.0, 1.0) }
            autoStructureVoice?.let { updates["autoStructureVoice"] = it }
            encryptionEnabled?.let { updates["encryptionEnabled"] = it }

            userDoc(uid).update(updates).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, docPath)
            Result.failure(e)
        }
    }

    fun observeTasks(userId: String): Flow<SyncSnapshot<DailyTask>> = flow {
        require(userId.isNotBlank())
        val path = "users/$userId/tasks"
        emitAll(
            userDoc(userId).collection("tasks")
                .orderBy("sortOrder", Query.Direction.ASCENDING)
                .snapshots()
                .map { snapshot ->
                    SyncSnapshot(
                        items = snapshot.toObjects(
                            DailyTask::class.java,
                            DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                        ),
                        hasPendingWrites = snapshot.metadata.hasPendingWrites(),
                        isFromCache = snapshot.metadata.isFromCache
                    )
                }
                .catch { error ->
                    logStreamError(error, OperationType.LIST, path, userId)
                    throw error
                }
        )
    }

    suspend fun addTask(
        userId: String,
        titleCipher: String,
        noteCipher: String,
        encrypted: Boolean,
        lane: TaskLane,
        priority: TaskPriority,
        category: String,
        sortOrder: Long,
        voiceCaptured: Boolean,
        dueLabel: String
    ): Result<Unit> {
        var docPath = "users/$userId/tasks"
        return try {
            val uid = requireUserId()
            check(uid == userId)
            val cleanTitle = titleCipher.trim().take(2000)
            require(cleanTitle.isNotEmpty()) { "Task title cannot be empty." }
            val docRef = userDoc(uid).collection("tasks").document()
            docPath = docRef.path
            val payload = mapOf(
                "userId" to uid,
                "titleCipher" to cleanTitle,
                "noteCipher" to noteCipher.take(10000),
                "encrypted" to encrypted,
                "completed" to (lane == TaskLane.DONE),
                "lane" to lane.name,
                "priority" to priority.name,
                "category" to category.take(50),
                "sortOrder" to sortOrder.coerceIn(0L, 1_000_000L),
                "voiceCaptured" to voiceCaptured,
                "dueLabel" to dueLabel.take(60),
                "createdAt" to FieldValue.serverTimestamp(),
                "updatedAt" to FieldValue.serverTimestamp()
            )
            docRef.set(payload).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, docPath)
            Result.failure(e)
        }
    }

    suspend fun updateTask(
        userId: String,
        taskId: String,
        titleCipher: String? = null,
        noteCipher: String? = null,
        encrypted: Boolean? = null,
        completed: Boolean? = null,
        lane: TaskLane? = null,
        priority: TaskPriority? = null,
        category: String? = null,
        sortOrder: Long? = null,
        dueLabel: String? = null
    ): Result<Unit> {
        val docPath = "users/$userId/tasks/$taskId"
        return try {
            val uid = requireUserId()
            check(uid == userId)
            require(taskId.isNotBlank())
            val updates = mutableMapOf<String, Any>(
                "updatedAt" to FieldValue.serverTimestamp()
            )
            titleCipher?.let { updates["titleCipher"] = it.take(2000) }
            noteCipher?.let { updates["noteCipher"] = it.take(10000) }
            encrypted?.let { updates["encrypted"] = it }
            completed?.let { updates["completed"] = it }
            lane?.let { updates["lane"] = it.name }
            priority?.let { updates["priority"] = it.name }
            category?.let { updates["category"] = it.take(50) }
            sortOrder?.let { updates["sortOrder"] = it.coerceIn(0L, 1_000_000L) }
            dueLabel?.let { updates["dueLabel"] = it.take(60) }

            userDoc(uid).collection("tasks").document(taskId).update(updates).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, docPath)
            Result.failure(e)
        }
    }

    suspend fun reorderTasksBatch(
        userId: String,
        orderedTaskIdsAndLanes: List<Triple<String, Long, TaskLane>>
    ): Result<Unit> {
        val path = "users/$userId/tasks"
        return try {
            val uid = requireUserId()
            check(uid == userId)
            val batch = db.batch()
            orderedTaskIdsAndLanes.forEach { (taskId, sortOrder, lane) ->
                if (taskId.isNotBlank()) {
                    val ref = userDoc(uid).collection("tasks").document(taskId)
                    batch.update(
                        ref,
                        mapOf(
                            "sortOrder" to sortOrder.coerceIn(0L, 1_000_000L),
                            "lane" to lane.name,
                            "completed" to (lane == TaskLane.DONE),
                            "updatedAt" to FieldValue.serverTimestamp()
                        )
                    )
                }
            }
            batch.commit().await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, path)
            Result.failure(e)
        }
    }

    suspend fun deleteTask(userId: String, taskId: String): Result<Unit> {
        val docPath = "users/$userId/tasks/$taskId"
        return try {
            val uid = requireUserId()
            check(uid == userId)
            userDoc(uid).collection("tasks").document(taskId).delete().await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.DELETE, docPath)
            Result.failure(e)
        }
    }

    fun observeNotes(userId: String): Flow<SyncSnapshot<VoiceNote>> = flow {
        require(userId.isNotBlank())
        val path = "users/$userId/notes"
        emitAll(
            userDoc(userId).collection("notes")
                .orderBy("updatedAt", Query.Direction.DESCENDING)
                .snapshots()
                .map { snapshot ->
                    SyncSnapshot(
                        items = snapshot.toObjects(
                            VoiceNote::class.java,
                            DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                        ),
                        hasPendingWrites = snapshot.metadata.hasPendingWrites(),
                        isFromCache = snapshot.metadata.isFromCache
                    )
                }
                .catch { error ->
                    logStreamError(error, OperationType.LIST, path, userId)
                    throw error
                }
        )
    }

    suspend fun addNote(
        userId: String,
        titleCipher: String,
        contentCipher: String,
        encrypted: Boolean,
        tags: List<String>,
        durationSec: Long,
        aiSummaryCipher: String?
    ): Result<Unit> {
        var docPath = "users/$userId/notes"
        return try {
            val uid = requireUserId()
            check(uid == userId)
            val cleanTitle = titleCipher.trim().take(1000)
            require(cleanTitle.isNotEmpty())
            val docRef = userDoc(uid).collection("notes").document()
            docPath = docRef.path
            val payload = mutableMapOf<String, Any?>(
                "userId" to uid,
                "titleCipher" to cleanTitle,
                "contentCipher" to contentCipher.take(20000),
                "encrypted" to encrypted,
                "tags" to tags.map { it.take(40) }.take(10),
                "durationSec" to durationSec.coerceIn(0L, 86400L),
                "aiSummaryCipher" to aiSummaryCipher?.take(5000),
                "createdAt" to FieldValue.serverTimestamp(),
                "updatedAt" to FieldValue.serverTimestamp()
            ).filterValues { it != null } as Map<String, Any>
            docRef.set(payload).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, docPath)
            Result.failure(e)
        }
    }

    suspend fun deleteNote(userId: String, noteId: String): Result<Unit> {
        val docPath = "users/$userId/notes/$noteId"
        return try {
            val uid = requireUserId()
            check(uid == userId)
            userDoc(uid).collection("notes").document(noteId).delete().await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.DELETE, docPath)
            Result.failure(e)
        }
    }

    fun observeGalleryItems(userId: String): Flow<List<GalleryItem>> = flow {
        require(userId.isNotBlank())
        val path = "users/$userId/wallpapers"
        emitAll(
            userDoc(userId).collection("wallpapers")
                .orderBy("createdAt", Query.Direction.DESCENDING)
                .snapshots()
                .map { snapshot ->
                    snapshot.toObjects(
                        GalleryItem::class.java,
                        DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                    )
                }
                .catch { error ->
                    logStreamError(error, OperationType.LIST, path, userId)
                    throw error
                }
        )
    }

    suspend fun saveGalleryItem(
        userId: String,
        title: String,
        prompt: String,
        itemType: GalleryItemType,
        paletteJson: String?,
        imageBase64: String?,
        deviceProfile: String
    ): Result<Unit> {
        var docPath = "users/$userId/wallpapers"
        return try {
            val uid = requireUserId()
            check(uid == userId)
            val docRef = userDoc(uid).collection("wallpapers").document()
            docPath = docRef.path
            val payload = mutableMapOf<String, Any?>(
                "userId" to uid,
                "title" to title.ifBlank { "AI Creation" }.take(120),
                "prompt" to prompt.take(1000),
                "itemType" to itemType.name,
                "paletteJson" to paletteJson?.take(3000),
                "imageBase64" to imageBase64?.take(700000),
                "encrypted" to false,
                "deviceProfile" to deviceProfile.take(80),
                "createdAt" to FieldValue.serverTimestamp(),
                "updatedAt" to FieldValue.serverTimestamp()
            ).filterValues { it != null } as Map<String, Any>
            docRef.set(payload).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, docPath)
            Result.failure(e)
        }
    }

    suspend fun deleteGalleryItem(userId: String, itemId: String): Result<Unit> {
        val docPath = "users/$userId/wallpapers/$itemId"
        return try {
            val uid = requireUserId()
            check(uid == userId)
            userDoc(uid).collection("wallpapers").document(itemId).delete().await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.DELETE, docPath)
            Result.failure(e)
        }
    }

    fun observeVaultMedia(userId: String): Flow<List<VaultMedia>> = flow {
        require(userId.isNotBlank())
        val path = "users/$userId/vault_media"
        emitAll(
            userDoc(userId).collection("vault_media")
                .orderBy("createdAt", Query.Direction.DESCENDING)
                .snapshots()
                .map { snapshot ->
                    snapshot.toObjects(
                        VaultMedia::class.java,
                        DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                    )
                }
                .catch { error ->
                    logStreamError(error, OperationType.LIST, path, userId)
                    throw error
                }
        )
    }

    suspend fun uploadEncryptedMedia(
        userId: String,
        fileName: String,
        mimeType: String,
        sizeBytes: Long,
        cipherPayload: String,
        sha256Checksum: String
    ): Result<Unit> {
        var docPath = "users/$userId/vault_media"
        return try {
            val uid = requireUserId()
            check(uid == userId)
            require(cipherPayload.length <= 850_000) {
                "Media file exceeds 850KB encrypted document limit after compression."
            }
            val docRef = userDoc(uid).collection("vault_media").document()
            docPath = docRef.path
            val payload = mapOf(
                "userId" to uid,
                "fileName" to fileName.take(160),
                "mimeType" to mimeType.take(80),
                "sizeBytes" to sizeBytes.coerceIn(0L, 2_000_000L),
                "cipherPayload" to cipherPayload,
                "sha256Checksum" to sha256Checksum.take(128),
                "encrypted" to true,
                "createdAt" to FieldValue.serverTimestamp(),
                "updatedAt" to FieldValue.serverTimestamp()
            )
            docRef.set(payload).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, docPath)
            Result.failure(e)
        }
    }

    suspend fun deleteVaultMedia(userId: String, mediaId: String): Result<Unit> {
        val docPath = "users/$userId/vault_media/$mediaId"
        return try {
            val uid = requireUserId()
            check(uid == userId)
            userDoc(uid).collection("vault_media").document(mediaId).delete().await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.DELETE, docPath)
            Result.failure(e)
        }
    }

    private fun logStreamError(
        error: Throwable,
        operationType: OperationType,
        path: String,
        userId: String
    ) {
        val firestoreError = generateSequence(error) { it.cause }
            .filterIsInstance<FirebaseFirestoreException>()
            .firstOrNull()
        if (firestoreError == null && error is CancellationException) return
        val exceptionToLog = firestoreError ?: (error as? Exception)
        if (exceptionToLog != null && auth.currentUser?.uid == userId) {
            handleFirestoreError(exceptionToLog, operationType, path)
        }
    }
}
