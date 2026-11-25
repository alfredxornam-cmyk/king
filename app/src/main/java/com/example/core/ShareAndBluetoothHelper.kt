package com.example.core

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.example.BuildConfig
import com.example.data.UiTask
import com.example.data.UiVoiceNote
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class PairedBtDevice(
    val name: String,
    val address: String
)

object ShareAndBluetoothHelper {

    fun hasBluetoothConnectPermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.BLUETOOTH_CONNECT
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    @SuppressLint("MissingPermission")
    fun getPairedBluetoothDevices(context: Context): List<PairedBtDevice> {
        if (!hasBluetoothConnectPermission(context)) return emptyList()
        return try {
            val btManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            val adapter = btManager?.adapter ?: return emptyList()
            adapter.bondedDevices?.map { device ->
                PairedBtDevice(
                    name = device.name ?: "Android Device",
                    address = device.address ?: "Unknown"
                )
            } ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun exportAndShareBundle(
        context: Context,
        tasks: List<UiTask>,
        notes: List<UiVoiceNote>,
        cryptoManager: CryptoManager,
        passphrase: String,
        salt: String,
        preferBluetooth: Boolean
    ): Result<Unit> {
        return try {
            val bundle = JSONObject().apply {
                put("app", "Alfred's Gallery")
                put("formatVersion", 1)
                put("exportedAt", System.currentTimeMillis())
                put("contactPhone", "0553862266")
                put("contactEmail", "alfredxornam@gmail.com")
                put("encrypted", true)

                val tasksArr = JSONArray()
                tasks.forEach { task ->
                    tasksArr.put(
                        JSONObject().apply {
                            put("titleCipher", cryptoManager.encryptText(task.title, passphrase, salt))
                            put("noteCipher", cryptoManager.encryptText(task.note, passphrase, salt))
                            put("lane", task.lane.name)
                            put("priority", task.priority.name)
                            put("completed", task.completed)
                            put("dueLabel", task.dueLabel)
                        }
                    )
                }
                put("tasks", tasksArr)

                val notesArr = JSONArray()
                notes.forEach { note ->
                    notesArr.put(
                        JSONObject().apply {
                            put("titleCipher", cryptoManager.encryptText(note.title, passphrase, salt))
                            put("contentCipher", cryptoManager.encryptText(note.content, passphrase, salt))
                            put("durationSec", note.durationSec)
                        }
                    )
                }
                put("notes", notesArr)
            }

            val file = File(context.cacheDir, "alfreds_gallery_sync_bundle.json")
            file.writeText(bundle.toString(2))
            shareFile(
                context = context,
                file = file,
                mimeType = "application/json",
                chooserTitle = if (preferBluetooth) "Send Encrypted Bundle via Bluetooth" else "Share Alfred's Gallery Bundle",
                preferBluetooth = preferBluetooth
            )
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun shareWallpaperBitmap(
        context: Context,
        bitmap: Bitmap,
        title: String,
        preferBluetooth: Boolean
    ): Result<Unit> {
        return try {
            val safeName = title.lowercase().replace(Regex("[^a-z0-9]+"), "_").take(32)
            val file = WallpaperHelper.saveBitmapToCacheFile(
                context,
                bitmap,
                "alfred_wallpaper_${safeName.ifBlank { "art" }}.png"
            )
            shareFile(
                context = context,
                file = file,
                mimeType = "image/png",
                chooserTitle = if (preferBluetooth) "Send Wallpaper via Bluetooth" else "Share Wallpaper via Apps",
                preferBluetooth = preferBluetooth
            )
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun shareFile(
        context: Context,
        file: File,
        mimeType: String,
        chooserTitle: String,
        preferBluetooth: Boolean
    ) {
        val uri = FileProvider.getUriForFile(
            context,
            "${BuildConfig.APPLICATION_ID}.fileprovider",
            file
        )
        val baseIntent = Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "Shared from Alfred's Gallery")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        if (preferBluetooth) {
            val btPackages = listOf("com.android.bluetooth", "com.mediatek.bluetooth", "com.google.android.bluetooth")
            val pm = context.packageManager
            val resolved = pm.queryIntentActivities(baseIntent, 0)
            val btMatch = resolved.firstOrNull { info ->
                btPackages.any { pkg -> info.activityInfo.packageName.contains("bluetooth", ignoreCase = true) || info.activityInfo.packageName == pkg }
            }
            if (btMatch != null) {
                val targeted = Intent(baseIntent).apply {
                    setClassName(btMatch.activityInfo.packageName, btMatch.activityInfo.name)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(targeted)
                return
            }
        }

        val chooser = Intent.createChooser(baseIntent, chooserTitle).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(chooser)
    }

    fun dialAlfred(context: Context) {
        val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:0553862266")).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    fun emailAlfred(context: Context) {
        val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:alfredxornam@gmail.com")).apply {
            putExtra(Intent.EXTRA_SUBJECT, "Alfred's Gallery — Feedback & Sync")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }
}
