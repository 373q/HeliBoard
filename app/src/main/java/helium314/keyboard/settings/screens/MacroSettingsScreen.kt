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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import helium314.keyboard.latin.macro.DumeMacroManager
import helium314.keyboard.latin.macro.MacroManager
import helium314.keyboard.latin.settings.Defaults
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.settings.SearchSettingsScreen
import helium314.keyboard.settings.preferences.Preference
import helium314.keyboard.settings.preferences.SliderPreference
import helium314.keyboard.settings.preferences.SwitchPreference

@Composable
fun MacroSettingsScreen(onClickBack: () -> Unit) {
    var selectedTab by remember { mutableIntStateOf(0) }
    val tabs = listOf("Shift", "Dume")

    SearchSettingsScreen(
        onClickBack = onClickBack,
        title = "Macro",
        settings = emptyList(),
    ) {
        Column {
            TabRow(selectedTabIndex = selectedTab) {
                tabs.forEachIndexed { index, title ->
                    Tab(
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        text = { Text(title) }
                    )
                }
            }
            when (selectedTab) {
                0 -> ShiftMacroTab()
                1 -> DumeMacroTab()
            }
        }
    }
}

// ─────────────────────────────────────────────────────────
// Tab 1: Shift (comportamentul original)
// ─────────────────────────────────────────────────────────
@Composable
private fun ShiftMacroTab() {
    val ctx = LocalContext.current
    var messageCount by remember { mutableIntStateOf(MacroManager.getMessageCount(ctx)) }
    var isRunning by remember { mutableStateOf(MacroManager.isRunning()) }

    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode != Activity.RESULT_OK) return@rememberLauncherForActivityResult
        val uri = result.data?.data ?: return@rememberLauncherForActivityResult
        if (MacroManager.importFile(ctx, uri)) {
            messageCount = MacroManager.getMessageCount(ctx)
        }
    }

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
                default = Defaults.PREF_MACRO_CHAR_DELAY,
                range = 20f..500f,
                stepSize = 10,
            )
            SliderPreference(
                name = "Start delay (ms)",
                key = Settings.PREF_MACRO_START_DELAY,
                description = { v: Int -> "${v}ms" },
                default = Defaults.PREF_MACRO_START_DELAY,
                range = 0f..3000f,
                stepSize = 100,
            )
            SliderPreference(
                name = "Delay between messages (ms)",
                key = Settings.PREF_MACRO_MSG_DELAY,
                description = { v: Int -> "${v}ms" },
                default = Defaults.PREF_MACRO_MSG_DELAY,
                range = 0f..3000f,
                stepSize = 100,
            )
            SwitchPreference(
                name = "Legit Mode",
                key = Settings.PREF_SHIFT_LEGIT_MODE,
                default = Defaults.PREF_SHIFT_LEGIT_MODE,
                description = "Simulează greșeli umane de tastare: scrie o literă greșită, o șterge și corectează",
            )
            Preference(
                name = if (isRunning) "Stop Macro (Shift)" else "Start Macro (Shift)",
                description = if (isRunning) "Running" else "Stopped",
                onClick = {
                    MacroManager.toggle(ctx)
                    isRunning = MacroManager.isRunning()
                }
            )
        }
    }
}

// ─────────────────────────────────────────────────────────
// Tab 2: Dume (grupuri de mesaje, trimise liniar per grup)
// ─────────────────────────────────────────────────────────
@Composable
private fun DumeMacroTab() {
    val ctx = LocalContext.current
    var groupCount by remember { mutableIntStateOf(DumeMacroManager.getGroupCount(ctx)) }
    var messageCount by remember { mutableIntStateOf(DumeMacroManager.getMessageCount(ctx)) }
    var isRunning by remember { mutableStateOf(DumeMacroManager.isRunning()) }

    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode != Activity.RESULT_OK) return@rememberLauncherForActivityResult
        val uri = result.data?.data ?: return@rememberLauncherForActivityResult
        if (DumeMacroManager.importFile(ctx, uri)) {
            groupCount = DumeMacroManager.getGroupCount(ctx)
            messageCount = DumeMacroManager.getMessageCount(ctx)
        }
    }

    Scaffold(contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom)) { innerPadding ->
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(innerPadding)
        ) {
            Preference(
                name = "Import Dume messages file (.txt)",
                description = buildString {
                    if (groupCount > 0) {
                        append("$groupCount groups, $messageCount lines loaded")
                    } else {
                        append("No file loaded — tap to import")
                        append("\n\nFormat fișier:\nmesaj1\nce faci\nsalut\n\nmesaj2\nce mai\nzici")
                    }
                },
                onClick = {
                    val intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
                        .addCategory(Intent.CATEGORY_OPENABLE)
                        .setType("text/plain")
                    filePicker.launch(intent)
                }
            )
            SliderPreference(
                name = "Delay between characters (ms)",
                key = Settings.PREF_DUME_CHAR_DELAY,
                description = { v: Int -> "${v}ms" },
                default = Defaults.PREF_DUME_CHAR_DELAY,
                range = 20f..500f,
                stepSize = 10,
            )
            SliderPreference(
                name = "Start delay (ms)",
                key = Settings.PREF_DUME_START_DELAY,
                description = { v: Int -> "${v}ms" },
                default = Defaults.PREF_DUME_START_DELAY,
                range = 0f..3000f,
                stepSize = 100,
            )
            SliderPreference(
                name = "Delay between messages (ms)",
                key = Settings.PREF_DUME_MSG_DELAY,
                description = { v: Int -> "${v}ms" },
                default = Defaults.PREF_DUME_MSG_DELAY,
                range = 0f..3000f,
                stepSize = 100,
            )
            SwitchPreference(
                name = "Legit Mode",
                key = Settings.PREF_DUME_LEGIT_MODE,
                default = Defaults.PREF_DUME_LEGIT_MODE,
                description = "Simulează greșeli umane de tastare: scrie o literă greșită, o șterge și corectează",
            )
            Preference(
                name = if (isRunning) "Stop Macro (Dume)" else "Start Macro (Dume)",
                description = if (isRunning) "Running" else "Stopped",
                onClick = {
                    DumeMacroManager.toggle(ctx)
                    isRunning = DumeMacroManager.isRunning()
                }
            )
        }
    }
}
