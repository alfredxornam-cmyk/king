package com.example.data

import android.graphics.Bitmap
import android.util.Log
import com.google.firebase.Firebase
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.AspectRatio
import com.google.firebase.ai.type.ImageConfig
import com.google.firebase.ai.type.ImagePart
import com.google.firebase.ai.type.ImageSize
import com.google.firebase.ai.type.QuotaExceededException
import com.google.firebase.ai.type.ResponseModality
import com.google.firebase.ai.type.Schema
import com.google.firebase.ai.type.generationConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import org.json.JSONObject

class AiStudioRepository {

    companion object {
        private const val TAG = "AiStudioRepository"
    }

    private val themeSchema = Schema.obj(
        properties = mapOf(
            "name" to Schema.string(description = "Short 2-3 word theme name"),
            "mood" to Schema.string(description = "Short 2-4 word aesthetic mood"),
            "primaryHex" to Schema.string(description = "6-digit hex color with # prefix for primary accent"),
            "secondaryHex" to Schema.string(description = "6-digit hex color with # prefix for secondary accent"),
            "tertiaryHex" to Schema.string(description = "6-digit hex color with # prefix for tertiary highlight"),
            "backgroundHex" to Schema.string(description = "6-digit hex color with # prefix for dark or rich screen background"),
            "surfaceHex" to Schema.string(description = "6-digit hex color with # prefix for elevated card surface")
        )
    )

    private val voiceStructureSchema = Schema.obj(
        properties = mapOf(
            "title" to Schema.string(description = "Concise 3-6 word note title"),
            "summary" to Schema.string(description = "1-2 sentence clean summary of the voice transcript"),
            "tags" to Schema.array(items = Schema.string(), description = "Up to 4 short lowercase topic tags"),
            "actionTasks" to Schema.array(items = Schema.string(), description = "Up to 4 concrete daily goal tasks extracted from the note")
        )
    )

    private val goalCoachSchema = Schema.obj(
        properties = mapOf(
            "focusHeadline" to Schema.string(description = "Motivating 1-line focus advice"),
            "suggestedGoals" to Schema.array(items = Schema.string(), description = "3 actionable daily goals tailored to the user's current list")
        )
    )

    private val wallpaperPortraitModel by lazy {
        val config = generationConfig {
            responseModalities = listOf(ResponseModality.IMAGE)
            imageConfig = ImageConfig.builder()
                .setAspectRatio(AspectRatio.PORTRAIT_9x16)
                .setImageSize(ImageSize.SIZE_1K)
                .build()
        }
        Firebase.ai.generativeModel(
            modelName = "gemini-nano-banana-2.1",
            generationConfig = config
        )
    }

    suspend fun generateThemePalette(
        prompt: String,
        modelName: String,
        temperature: Float
    ): Result<AiThemePalette> {
        return try {
            val safeModel = if (modelName == "gemini-3.5-flash-lite") modelName else "gemini-3.8-flash"
            val model = Firebase.ai.generativeModel(
                modelName = safeModel,
                generationConfig = generationConfig {
                    this.temperature = temperature.coerceIn(0.1f, 1.0f)
                    responseMimeType = "application/json"
                    responseSchema = themeSchema
                }
            )
            val fullPrompt = """
                Design a cohesive, high-contrast Material 3 Android app color theme optimized for AMOLED phones (such as Tecno Camon 20).
                User theme request: "$prompt"
                Ensure primaryHex, secondaryHex, and tertiaryHex contrast sharply against backgroundHex and surfaceHex.
                Return valid 6-digit hex codes starting with #.
            """.trimIndent()

            val response = withTimeout(45_000L) {
                model.generateContent(fullPrompt)
            }
            val jsonText = response.text ?: throw IllegalStateException("Model returned an empty theme response.")
            val json = JSONObject(jsonText)
            Result.success(
                AiThemePalette(
                    name = json.getString("name"),
                    mood = json.getString("mood"),
                    primaryHex = normalizeHex(json.getString("primaryHex"), "#00F5D4"),
                    secondaryHex = normalizeHex(json.getString("secondaryHex"), "#3A86FF"),
                    tertiaryHex = normalizeHex(json.getString("tertiaryHex"), "#FFBE0B"),
                    backgroundHex = normalizeHex(json.getString("backgroundHex"), "#090E1A"),
                    surfaceHex = normalizeHex(json.getString("surfaceHex"), "#121A2F")
                )
            )
        } catch (e: TimeoutCancellationException) {
            Log.e(TAG, "Theme generation timed out", e)
            Result.failure(Exception("Theme generation timed out. Please try again."))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Theme generation failed", e)
            Result.failure(e)
        }
    }

    suspend fun generateWallpaper(
        prompt: String,
        stylePreset: String,
        deviceProfile: String
    ): Result<Bitmap> {
        return try {
            val enrichedPrompt = """
                Create a crisp, high-contrast smartphone wallpaper tailored for $deviceProfile.
                Visual style: $stylePreset.
                Subject: $prompt.
                No text, no watermarks, clean edge-to-edge vertical mobile wallpaper composition.
            """.trimIndent()

            val response = withTimeout(60_000L) {
                wallpaperPortraitModel.generateContent(enrichedPrompt)
            }
            val imagePart = response.candidates.firstOrNull()
                ?.content?.parts?.filterIsInstance<ImagePart>()
                ?.firstOrNull()
            val bitmap = imagePart?.image
                ?: return Result.failure(IllegalStateException("No wallpaper image was returned by Gemini."))
            Result.success(bitmap)
        } catch (e: TimeoutCancellationException) {
            Log.e(TAG, "Wallpaper generation timed out", e)
            Result.failure(Exception("Wallpaper generation timed out after 60 seconds."))
        } catch (e: QuotaExceededException) {
            Log.e(TAG, "Wallpaper quota exceeded", e)
            Result.failure(Exception("AI image generation quota exceeded. Please try again later or check Firebase Blaze billing."))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Wallpaper generation failed", e)
            Result.failure(e)
        }
    }

    suspend fun structureVoiceNote(
        transcript: String,
        modelName: String,
        temperature: Float
    ): Result<StructuredVoiceResult> {
        return try {
            val safeModel = if (modelName == "gemini-3.5-flash-lite") modelName else "gemini-3.8-flash"
            val model = Firebase.ai.generativeModel(
                modelName = safeModel,
                generationConfig = generationConfig {
                    this.temperature = temperature.coerceIn(0.1f, 1.0f)
                    responseMimeType = "application/json"
                    responseSchema = voiceStructureSchema
                }
            )
            val response = withTimeout(45_000L) {
                model.generateContent(
                    "Turn this spoken voice memo into a clean title, brief summary, short tags, and actionable daily tasks:\n\n\"$transcript\""
                )
            }
            val jsonText = response.text ?: throw IllegalStateException("Empty AI voice structure response.")
            val obj = JSONObject(jsonText)
            val tagsArr = obj.getJSONArray("tags")
            val tasksArr = obj.getJSONArray("actionTasks")
            Result.success(
                StructuredVoiceResult(
                    title = obj.getString("title"),
                    summary = obj.getString("summary"),
                    tags = (0 until tagsArr.length()).map { tagsArr.getString(it) }.take(6),
                    actionTasks = (0 until tasksArr.length()).map { tasksArr.getString(it) }.take(4)
                )
            )
        } catch (e: TimeoutCancellationException) {
            Result.failure(Exception("Voice note AI structuring timed out."))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Voice structuring failed", e)
            Result.failure(e)
        }
    }

    suspend fun suggestDailyGoals(
        currentGoalsSummary: String,
        modelName: String,
        temperature: Float
    ): Result<Pair<String, List<String>>> {
        return try {
            val safeModel = if (modelName == "gemini-3.5-flash-lite") modelName else "gemini-3.8-flash"
            val model = Firebase.ai.generativeModel(
                modelName = safeModel,
                generationConfig = generationConfig {
                    this.temperature = temperature.coerceIn(0.1f, 1.0f)
                    responseMimeType = "application/json"
                    responseSchema = goalCoachSchema
                }
            )
            val response = withTimeout(45_000L) {
                model.generateContent(
                    "Based on these current daily goals ($currentGoalsSummary), suggest 3 crisp, high-impact next daily goals and a short focus headline."
                )
            }
            val jsonText = response.text ?: throw IllegalStateException("Empty AI goal coach response.")
            val obj = JSONObject(jsonText)
            val headline = obj.getString("focusHeadline")
            val arr = obj.getJSONArray("suggestedGoals")
            val suggestions = (0 until arr.length()).map { arr.getString(it) }.take(3)
            Result.success(headline to suggestions)
        } catch (e: TimeoutCancellationException) {
            Result.failure(Exception("AI Goal Coach timed out."))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "AI goal coaching failed", e)
            Result.failure(e)
        }
    }

    private fun normalizeHex(raw: String, fallback: String): String {
        val cleaned = raw.trim().let { if (it.startsWith("#")) it else "#$it" }
        return if (Regex("^#[0-9A-Fa-f]{6}$").matches(cleaned)) cleaned.uppercase() else fallback
    }
}
