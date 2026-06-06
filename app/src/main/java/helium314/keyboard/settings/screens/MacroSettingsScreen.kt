// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings.screens

import android.app.Activity
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import helium314.keyboard.latin.macro.MacroManager
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.settings.SearchSettingsScreen
import helium314.keyboard.settings.preferences.Preference
import helium314.keyboard.settings.preferences.SliderPreference

@Composable
fun MacroSettingsScreen(onClickBack: () -> Unit) {
    val ctx = LocalContext.current
    var messageCount by remember { mutableIntStateOf(MacroManager.getMessageCount(ctx)) }
    var isRunning by remember { mutableStateOf(MacroManager.isRunning()) }

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode != Activity.RESULT_OK) return@rememberLauncherForActivityResult
        val uri = result.data?.data ?: return@rememberLauncherForActivityResult
        if (MacroManager.importFile(ctx, uri)) {
            messageCount = MacroManager.getMessageCount(ctx)
        }
    }

    SearchSettingsScreen(
        onClickBack = onClickBack,
        title = "Macro",
        settings = emptyList(),
    ) {
        Scaffold(contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom)) { innerPadding ->
            Column(
                Modifier.verticalScroll(rememberScrollState()).padding(innerPadding)
            ) {
                Preference(
                    name = "Import messages file (.txt)",
                    description = if (messageCount > 0) "$messageCount messages loaded" else "No file loaded — tap to import",
                    onClick = {
                        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
                            .addCategory(Intent.CATEGORY_OPENABLE)
                            .setType("text/plain")
                        filePicker.launch(intent)
                    }
                )
                SliderPreference(
                    name = "Delay between characters (ms)",
                    key = Settings.PREF_MACRO_CHAR_DELAY,
                    description = { v: Int -> "${v}ms" },
                    default = 80,
                    range = 20f..500f,
                    stepSize = 10,
                )
                SliderPreference(
                    name = "Start delay (ms)",
                    key = Settings.PREF_MACRO_START_DELAY,
                    description = { v: Int -> "${v}ms" },
                    default = 800,
                    range = 0f..3000f,
                    stepSize = 100,
                )
                SliderPreference(
                    name = "Delay between messages (ms)",
                    key = Settings.PREF_MACRO_MSG_DELAY,
                    description = { v: Int -> "${v}ms" },
                    default = 3000,
                    range = 0f..3000f,
                    stepSize = 100,
                )
                Preference(
                    name = if (isRunning) "Stop Macro" else "Start Macro",
                    description = if (isRunning) "Running" else "Stopped",
                    onClick = {
                        MacroManager.toggle(ctx)
                        isRunning = MacroManager.isRunning()
                    }
                )
            }
        }
    }
}
