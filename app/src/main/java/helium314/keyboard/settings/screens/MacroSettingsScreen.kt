// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings.screens

import android.app.Activity
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import helium314.keyboard.latin.macro.DumeMacroManager
import helium314.keyboard.latin.macro.MacroManager
import helium314.keyboard.latin.macro.MacroPreset
import helium314.keyboard.latin.macro.PresetManager
import helium314.keyboard.latin.settings.Defaults
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.prefs
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
// Tab 1: Shift
// ─────────────────────────────────────────────────────────
@Composable
private fun ShiftMacroTab() {
    val ctx = LocalContext.current
    var messageCount by remember { mutableIntStateOf(MacroManager.getMessageCount(ctx)) }
    var isRunning by remember { mutableStateOf(MacroManager.isRunning()) }
    var presets by remember { mutableStateOf(PresetManager.loadShiftPresets(ctx)) }

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
            SliderPreference(
                name = "Legit Mode — pause before delete (ms)",
                key = Settings.PREF_LEGIT_PAUSE_ACTIONS,
                description = { v: Int -> "${v}ms" },
                default = Defaults.PREF_LEGIT_PAUSE_ACTIONS,
                range = 20f..3000f,
                stepSize = 10,
            )
            SliderPreference(
                name = "Legit Mode — delete key delay (ms)",
                key = Settings.PREF_LEGIT_DELETE_DELAY,
                description = { v: Int -> "${v}ms" },
                default = Defaults.PREF_LEGIT_DELETE_DELAY,
                range = 20f..3000f,
                stepSize = 10,
            )
            SliderPreference(
                name = "Legit Mode — retype correct char delay (ms)",
                key = Settings.PREF_LEGIT_WRITE_DELAY,
                description = { v: Int -> "${v}ms" },
                default = Defaults.PREF_LEGIT_WRITE_DELAY,
                range = 20f..3000f,
                stepSize = 10,
            )
            SliderPreference(
                name = "Legit Mode — max typos per message",
                key = Settings.PREF_LEGIT_TYPOS,
                description = { v: Int -> "$v typos" },
                default = Defaults.PREF_LEGIT_TYPOS,
                range = 1f..10f,
                stepSize = 1,
            )
            SliderPreference(
                name = "Legit Mode — litere greșite per typo",
                key = Settings.PREF_LEGIT_LETTERS_PER_TYPO,
                description = { v: Int -> if (v == 1) "1 literă" else "$v litere" },
                default = Defaults.PREF_LEGIT_LETTERS_PER_TYPO,
                range = 1f..10f,
                stepSize = 1,
            )
            SwitchPreference(
                name = "Random Pause",
                key = Settings.PREF_MACRO_RANDOM_PAUSE_ENABLED,
                default = Defaults.PREF_MACRO_RANDOM_PAUSE_ENABLED,
                description = "Adaugă pauze aleatorii între mesaje, ca un om care se distrage",
            )
            SliderPreference(
                name = "Random Pause — durată maximă (ms)",
                key = Settings.PREF_MACRO_RANDOM_PAUSE_MAX_MS,
                description = { v: Int -> "${v}ms" },
                default = Defaults.PREF_MACRO_RANDOM_PAUSE_MAX_MS,
                range = 100f..30000f,
                stepSize = 100,
            )
            SliderPreference(
                name = "Random Pause — câte pauze în timp ce scrie",
                key = Settings.PREF_MACRO_RANDOM_PAUSE_COUNT,
                description = { v: Int -> if (v == 1) "1 pauză / mesaj" else "$v pauze / mesaj" },
                default = Defaults.PREF_MACRO_RANDOM_PAUSE_COUNT,
                range = 1f..10f,
                stepSize = 1,
            )

            // ── Presets ───────────────────────────────────────────────────
            PresetSection(
                isShift = true,
                presets = presets,
                onSave = { name, key ->
                    val prefs = ctx.prefs()
                    val preset = MacroPreset(
                        name = name,
                        shortcutKey = key,
                        charDelay = prefs.getInt(Settings.PREF_MACRO_CHAR_DELAY, Defaults.PREF_MACRO_CHAR_DELAY),
                        startDelay = prefs.getInt(Settings.PREF_MACRO_START_DELAY, Defaults.PREF_MACRO_START_DELAY),
                        msgDelay = prefs.getInt(Settings.PREF_MACRO_MSG_DELAY, Defaults.PREF_MACRO_MSG_DELAY),
                        legitMode = prefs.getBoolean(Settings.PREF_SHIFT_LEGIT_MODE, Defaults.PREF_SHIFT_LEGIT_MODE),
                        pauseDelay = prefs.getInt(Settings.PREF_LEGIT_PAUSE_ACTIONS, Defaults.PREF_LEGIT_PAUSE_ACTIONS),
                        deleteDelay = prefs.getInt(Settings.PREF_LEGIT_DELETE_DELAY, Defaults.PREF_LEGIT_DELETE_DELAY),
                        writeDelay = prefs.getInt(Settings.PREF_LEGIT_WRITE_DELAY, Defaults.PREF_LEGIT_WRITE_DELAY),
                        maxTypos = prefs.getInt(Settings.PREF_LEGIT_TYPOS, Defaults.PREF_LEGIT_TYPOS),
                        lettersPerTypo = prefs.getInt(Settings.PREF_LEGIT_LETTERS_PER_TYPO, Defaults.PREF_LEGIT_LETTERS_PER_TYPO),
                        randomPauseEnabled = prefs.getBoolean(Settings.PREF_MACRO_RANDOM_PAUSE_ENABLED, Defaults.PREF_MACRO_RANDOM_PAUSE_ENABLED),
                        randomPauseMaxMs = prefs.getInt(Settings.PREF_MACRO_RANDOM_PAUSE_MAX_MS, Defaults.PREF_MACRO_RANDOM_PAUSE_MAX_MS),
                        randomPauseCount = prefs.getInt(Settings.PREF_MACRO_RANDOM_PAUSE_COUNT, Defaults.PREF_MACRO_RANDOM_PAUSE_COUNT),
                    )
                    PresetManager.saveShiftPreset(ctx, preset)
                    presets = PresetManager.loadShiftPresets(ctx)
                },
                onDelete = { name ->
                    PresetManager.deleteShiftPreset(ctx, name)
                    presets = PresetManager.loadShiftPresets(ctx)
                },
                onApply = { preset ->
                    ctx.prefs().edit()
                        .putInt(Settings.PREF_MACRO_CHAR_DELAY, preset.charDelay)
                        .putInt(Settings.PREF_MACRO_START_DELAY, preset.startDelay)
                        .putInt(Settings.PREF_MACRO_MSG_DELAY, preset.msgDelay)
                        .putBoolean(Settings.PREF_SHIFT_LEGIT_MODE, preset.legitMode)
                        .putInt(Settings.PREF_LEGIT_PAUSE_ACTIONS, preset.pauseDelay)
                        .putInt(Settings.PREF_LEGIT_DELETE_DELAY, preset.deleteDelay)
                        .putInt(Settings.PREF_LEGIT_WRITE_DELAY, preset.writeDelay)
                        .putInt(Settings.PREF_LEGIT_TYPOS, preset.maxTypos)
                        .putInt(Settings.PREF_LEGIT_LETTERS_PER_TYPO, preset.lettersPerTypo)
                        .putBoolean(Settings.PREF_MACRO_RANDOM_PAUSE_ENABLED, preset.randomPauseEnabled)
                        .putInt(Settings.PREF_MACRO_RANDOM_PAUSE_MAX_MS, preset.randomPauseMaxMs)
                        .putInt(Settings.PREF_MACRO_RANDOM_PAUSE_COUNT, preset.randomPauseCount)
                        .apply()
                },
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
// Tab 2: Dume
// ─────────────────────────────────────────────────────────
@Composable
private fun DumeMacroTab() {
    val ctx = LocalContext.current
    var groupCount by remember { mutableIntStateOf(DumeMacroManager.getGroupCount(ctx)) }
    var messageCount by remember { mutableIntStateOf(DumeMacroManager.getMessageCount(ctx)) }
    var isRunning by remember { mutableStateOf(DumeMacroManager.isRunning()) }
    var presets by remember { mutableStateOf(PresetManager.loadDumePresets(ctx)) }

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
                name = "Random Pause",
                key = Settings.PREF_DUME_RANDOM_PAUSE_ENABLED,
                default = Defaults.PREF_DUME_RANDOM_PAUSE_ENABLED,
                description = "Inserează pauze aleatorii în secvența de mesaje",
            )
            SliderPreference(
                name = "Random Pause — max duration (ms)",
                key = Settings.PREF_DUME_RANDOM_PAUSE_MAX_MS,
                description = { v: Int -> "${v}ms" },
                default = Defaults.PREF_DUME_RANDOM_PAUSE_MAX_MS,
                range = 0f..3000f,
                stepSize = 100,
            )
            SliderPreference(
                name = "Random Pause — câte pauze în timp ce scrie",
                key = Settings.PREF_DUME_RANDOM_PAUSE_COUNT,
                description = { v: Int -> "$v pauses" },
                default = Defaults.PREF_DUME_RANDOM_PAUSE_COUNT,
                range = 0f..10f,
                stepSize = 1,
            )
            SwitchPreference(
                name = "Legit Mode",
                key = Settings.PREF_DUME_LEGIT_MODE,
                default = Defaults.PREF_DUME_LEGIT_MODE,
                description = "Simulează greșeli umane de tastare: scrie o literă greșită, o șterge și corectează",
            )
            SliderPreference(
                name = "Legit Mode — pause before delete (ms)",
                key = Settings.PREF_DUME_LEGIT_PAUSE_ACTIONS,
                description = { v: Int -> "${v}ms" },
                default = Defaults.PREF_DUME_LEGIT_PAUSE_ACTIONS,
                range = 20f..3000f,
                stepSize = 10,
            )
            SliderPreference(
                name = "Legit Mode — delete key delay (ms)",
                key = Settings.PREF_DUME_LEGIT_DELETE_DELAY,
                description = { v: Int -> "${v}ms" },
                default = Defaults.PREF_DUME_LEGIT_DELETE_DELAY,
                range = 20f..3000f,
                stepSize = 10,
            )
            SliderPreference(
                name = "Legit Mode — retype correct char delay (ms)",
                key = Settings.PREF_DUME_LEGIT_WRITE_DELAY,
                description = { v: Int -> "${v}ms" },
                default = Defaults.PREF_DUME_LEGIT_WRITE_DELAY,
                range = 20f..3000f,
                stepSize = 10,
            )
            SliderPreference(
                name = "Legit Mode — max typos per message",
                key = Settings.PREF_DUME_LEGIT_TYPOS,
                description = { v: Int -> "$v typos" },
                default = Defaults.PREF_DUME_LEGIT_TYPOS,
                range = 1f..10f,
                stepSize = 1,
            )
            SliderPreference(
                name = "Legit Mode — litere greșite per typo",
                key = Settings.PREF_DUME_LEGIT_LETTERS_PER_TYPO,
                description = { v: Int -> if (v == 1) "1 literă" else "$v litere" },
                default = Defaults.PREF_DUME_LEGIT_LETTERS_PER_TYPO,
                range = 1f..10f,
                stepSize = 1,
            )

            // ── Presets ───────────────────────────────────────────────────
            PresetSection(
                isShift = false,
                presets = presets,
                onSave = { name, key ->
                    val prefs = ctx.prefs()
                    val preset = MacroPreset(
                        name = name,
                        shortcutKey = key,
                        charDelay = prefs.getInt(Settings.PREF_DUME_CHAR_DELAY, Defaults.PREF_DUME_CHAR_DELAY),
                        startDelay = prefs.getInt(Settings.PREF_DUME_START_DELAY, Defaults.PREF_DUME_START_DELAY),
                        msgDelay = prefs.getInt(Settings.PREF_DUME_MSG_DELAY, Defaults.PREF_DUME_MSG_DELAY),
                        legitMode = prefs.getBoolean(Settings.PREF_DUME_LEGIT_MODE, Defaults.PREF_DUME_LEGIT_MODE),
                        pauseDelay = prefs.getInt(Settings.PREF_DUME_LEGIT_PAUSE_ACTIONS, Defaults.PREF_DUME_LEGIT_PAUSE_ACTIONS),
                        deleteDelay = prefs.getInt(Settings.PREF_DUME_LEGIT_DELETE_DELAY, Defaults.PREF_DUME_LEGIT_DELETE_DELAY),
                        writeDelay = prefs.getInt(Settings.PREF_DUME_LEGIT_WRITE_DELAY, Defaults.PREF_DUME_LEGIT_WRITE_DELAY),
                        maxTypos = prefs.getInt(Settings.PREF_DUME_LEGIT_TYPOS, Defaults.PREF_DUME_LEGIT_TYPOS),
                        lettersPerTypo = prefs.getInt(Settings.PREF_DUME_LEGIT_LETTERS_PER_TYPO, Defaults.PREF_DUME_LEGIT_LETTERS_PER_TYPO),
                        randomPauseEnabled = prefs.getBoolean(Settings.PREF_DUME_RANDOM_PAUSE_ENABLED, Defaults.PREF_DUME_RANDOM_PAUSE_ENABLED),
                        randomPauseMaxMs = prefs.getInt(Settings.PREF_DUME_RANDOM_PAUSE_MAX_MS, Defaults.PREF_DUME_RANDOM_PAUSE_MAX_MS),
                        randomPauseCount = prefs.getInt(Settings.PREF_DUME_RANDOM_PAUSE_COUNT, Defaults.PREF_DUME_RANDOM_PAUSE_COUNT),
                    )
                    PresetManager.saveDumePreset(ctx, preset)
                    presets = PresetManager.loadDumePresets(ctx)
                },
                onDelete = { name ->
                    PresetManager.deleteDumePreset(ctx, name)
                    presets = PresetManager.loadDumePresets(ctx)
                },
                onApply = { preset ->
                    ctx.prefs().edit()
                        .putInt(Settings.PREF_DUME_CHAR_DELAY, preset.charDelay)
                        .putInt(Settings.PREF_DUME_START_DELAY, preset.startDelay)
                        .putInt(Settings.PREF_DUME_MSG_DELAY, preset.msgDelay)
                        .putBoolean(Settings.PREF_DUME_LEGIT_MODE, preset.legitMode)
                        .putInt(Settings.PREF_DUME_LEGIT_PAUSE_ACTIONS, preset.pauseDelay)
                        .putInt(Settings.PREF_DUME_LEGIT_DELETE_DELAY, preset.deleteDelay)
                        .putInt(Settings.PREF_DUME_LEGIT_WRITE_DELAY, preset.writeDelay)
                        .putInt(Settings.PREF_DUME_LEGIT_TYPOS, preset.maxTypos)
                        .putInt(Settings.PREF_DUME_LEGIT_LETTERS_PER_TYPO, preset.lettersPerTypo)
                        .putBoolean(Settings.PREF_DUME_RANDOM_PAUSE_ENABLED, preset.randomPauseEnabled)
                        .putInt(Settings.PREF_DUME_RANDOM_PAUSE_MAX_MS, preset.randomPauseMaxMs)
                        .putInt(Settings.PREF_DUME_RANDOM_PAUSE_COUNT, preset.randomPauseCount)
                        .apply()
                },
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

// ─────────────────────────────────────────────────────────
// Componentă reutilizabilă pentru presets (Shift + Dume)
// ─────────────────────────────────────────────────────────
@Composable
private fun PresetSection(
    isShift: Boolean,
    presets: List<MacroPreset>,
    onSave: (name: String, shortcutKey: String) -> Unit,
    onDelete: (name: String) -> Unit,
    onApply: (MacroPreset) -> Unit,
) {
    var showSaveDialog by remember { mutableStateOf(false) }
    var expandedPreset by remember { mutableStateOf<String?>(null) }

    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "Presets",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
        )
        TextButton(onClick = { showSaveDialog = true }) {
            Text("+ Save current")
        }
    }

    if (presets.isEmpty()) {
        Text(
            text = "Niciun preset salvat. Configurează setările de sus și apasă \"Save current\".",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )
    } else {
        presets.forEach { preset ->
            PresetItem(
                preset = preset,
                isShift = isShift,
                expanded = expandedPreset == preset.name,
                onToggleExpand = {
                    expandedPreset = if (expandedPreset == preset.name) null else preset.name
                },
                onApply = { onApply(preset) },
                onDelete = { onDelete(preset.name) },
            )
        }
    }

    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

    // Dialog salvare preset
    if (showSaveDialog) {
        SavePresetDialog(
            isShift = isShift,
            onConfirm = { name, key ->
                onSave(name, key)
                showSaveDialog = false
            },
            onDismiss = { showSaveDialog = false },
        )
    }
}

@Composable
private fun PresetItem(
    preset: MacroPreset,
    isShift: Boolean,
    expanded: Boolean,
    onToggleExpand: () -> Unit,
    onApply: () -> Unit,
    onDelete: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 2.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
    ) {
        // Header — tap pentru expand
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onToggleExpand() }
                .padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = preset.name,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = if (isShift) "Hold Space + [${preset.shortcutKey.uppercase()}]" else "Hold Comma + [${preset.shortcutKey.uppercase()}]",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontFamily = FontFamily.Monospace,
                )
            }
            Text(
                text = if (expanded) "▲" else "▼",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // Expand: setările salvate + butoane
        AnimatedVisibility(visible = expanded) {
            Column(modifier = Modifier.padding(horizontal = 12.dp).padding(bottom = 10.dp)) {
                HorizontalDivider(modifier = Modifier.padding(bottom = 8.dp))

                PresetSettingRow("Char delay", "${preset.charDelay}ms")
                PresetSettingRow("Start delay", "${preset.startDelay}ms")
                PresetSettingRow("Msg delay", "${preset.msgDelay}ms")
                PresetSettingRow("Legit Mode", if (preset.legitMode) "ON" else "OFF")
                if (preset.legitMode) {
                    PresetSettingRow("Pause before delete", "${preset.pauseDelay}ms")
                    PresetSettingRow("Delete delay", "${preset.deleteDelay}ms")
                    PresetSettingRow("Write delay", "${preset.writeDelay}ms")
                    PresetSettingRow("Max typos / msg", "${preset.maxTypos}")
                    PresetSettingRow("Litere greșite / typo", "${preset.lettersPerTypo}")
                }
                if (preset.randomPauseEnabled) {
                    PresetSettingRow("Random Pause", "ON — max ${preset.randomPauseMaxMs}ms, ${preset.randomPauseCount}x/msg")
                }

                Spacer(modifier = Modifier.height(10.dp))

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = onApply,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("Apply")
                    }
                    Button(
                        onClick = onDelete,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                            contentColor = MaterialTheme.colorScheme.onErrorContainer,
                        ),
                    ) {
                        Text("Delete")
                    }
                }
            }
        }
    }
    Spacer(modifier = Modifier.height(4.dp))
}

@Composable
private fun PresetSettingRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 1.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
        )
    }
}

@Composable
private fun SavePresetDialog(
    isShift: Boolean,
    onConfirm: (name: String, shortcutKey: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var key by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Salvează preset") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it; error = "" },
                    label = { Text("Nume preset") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = key,
                    onValueChange = {
                        // acceptăm doar o singură literă
                        if (it.isEmpty() || (it.length == 1 && it[0].isLetter())) {
                            key = it.lowercase()
                            error = ""
                        }
                    },
                    label = { Text("Litera shortcut (ex: q)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (error.isNotEmpty()) {
                    Text(
                        text = error,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Text(
                    text = if (isShift) "Hold Space + litera shortcut → pornește macro Shift cu acest preset." else "Hold Comma + litera shortcut → pornește macro Dume cu acest preset.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                when {
                    name.isBlank() -> error = "Introdu un nume"
                    key.isBlank() -> error = "Introdu o literă shortcut"
                    else -> onConfirm(name.trim(), key)
                }
            }) { Text("Salvează") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Anulează") }
        },
    )
}
