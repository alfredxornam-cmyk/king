package com.example.widget

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.core.PreferencesManager
import com.example.data.ThemeMode
import com.example.ui.theme.AlfredsGalleryTheme
import kotlinx.coroutines.launch

class WidgetConfigActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setResult(Activity.RESULT_CANCELED)

        val appWidgetId = intent?.extras?.getInt(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID
        ) ?: AppWidgetManager.INVALID_APPWIDGET_ID

        val prefsManager = PreferencesManager(this)

        setContent {
            var title by remember { mutableStateOf(prefsManager.readWidgetTitle()) }
            var theme by remember { mutableStateOf(prefsManager.readWidgetTheme()) }
            var showCompleted by remember { mutableStateOf(prefsManager.readWidgetShowCompleted()) }
            val scope = rememberCoroutineScope()

            val mode = when (theme) {
                "HACKER" -> ThemeMode.HACKER
                "LIGHT" -> ThemeMode.LIGHT
                else -> ThemeMode.DARK
            }

            AlfredsGalleryTheme(themeMode = mode) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .statusBarsPadding()
                            .navigationBarsPadding()
                            .padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Text(
                            text = "Customize Alfred's Widget",
                            style = MaterialTheme.typography.headlineSmall
                        )
                        Text(
                            text = "Choose how your encrypted daily goals appear on the home screen.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        OutlinedTextField(
                            value = title,
                            onValueChange = { title = it.take(40) },
                            label = { Text("Widget Header Title") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )

                        Text("Widget Style", style = MaterialTheme.typography.titleMedium)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf("DARK", "HACKER", "LIGHT").forEach { option ->
                                FilterChip(
                                    selected = theme == option,
                                    onClick = { theme = option },
                                    label = { Text(option) }
                                )
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Show completed goals", style = MaterialTheme.typography.bodyLarge)
                            Switch(
                                checked = showCompleted,
                                onCheckedChange = { showCompleted = it }
                            )
                        }

                        Spacer(modifier = Modifier.weight(1f))

                        Button(
                            onClick = {
                                scope.launch {
                                    prefsManager.saveWidgetConfig(
                                        title = title.ifBlank { "Alfred's Daily Goals" },
                                        theme = theme,
                                        showCompleted = showCompleted
                                    )
                                    val manager = AppWidgetManager.getInstance(this@WidgetConfigActivity)
                                    if (appWidgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
                                        AlfredGoalsWidgetProvider.updateAppWidget(
                                            this@WidgetConfigActivity,
                                            manager,
                                            appWidgetId
                                        )
                                        val resultValue = Intent().putExtra(
                                            AppWidgetManager.EXTRA_APPWIDGET_ID,
                                            appWidgetId
                                        )
                                        setResult(Activity.RESULT_OK, resultValue)
                                    } else {
                                        AlfredGoalsWidgetProvider.updateAllWidgets(this@WidgetConfigActivity)
                                        setResult(Activity.RESULT_OK)
                                    }
                                    finish()
                                }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(52.dp)
                        ) {
                            Text("Save & Apply Widget")
                        }
                    }
                }
            }
        }
    }
}
