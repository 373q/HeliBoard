// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.macro

import android.content.Context
import helium314.keyboard.latin.utils.prefs
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Gestionează preseturile macro salvate în SharedPreferences ca JSON.
 * Preseturile Shift și Dume sunt stocate separat.
 */
object PresetManager {

    private const val PREF_SHIFT_PRESETS = "shift_macro_presets"
    private const val PREF_DUME_PRESETS  = "dume_macro_presets"

    private val json = Json { ignoreUnknownKeys = true }

    // ── Shift ──────────────────────────────────────────────────────────────

    fun loadShiftPresets(context: Context): List<MacroPreset> =
        load(context, PREF_SHIFT_PRESETS)

    fun saveShiftPreset(context: Context, preset: MacroPreset) =
        save(context, PREF_SHIFT_PRESETS, preset)

    fun deleteShiftPreset(context: Context, name: String) =
        delete(context, PREF_SHIFT_PRESETS, name)

    /** Găsește un preset Shift după litera shortcut (case-insensitive). */
    fun findShiftPreset(context: Context, key: Char): MacroPreset? =
        loadShiftPresets(context).firstOrNull {
            it.shortcutKey.firstOrNull()?.lowercaseChar() == key.lowercaseChar()
        }

    // ── Dume ───────────────────────────────────────────────────────────────

    fun loadDumePresets(context: Context): List<MacroPreset> =
        load(context, PREF_DUME_PRESETS)

    fun saveDumePreset(context: Context, preset: MacroPreset) =
        save(context, PREF_DUME_PRESETS, preset)

    fun deleteDumePreset(context: Context, name: String) =
        delete(context, PREF_DUME_PRESETS, name)

    /** Găsește un preset Dume după litera shortcut (case-insensitive). */
    fun findDumePreset(context: Context, key: Char): MacroPreset? =
        loadDumePresets(context).firstOrNull {
            it.shortcutKey.firstOrNull()?.lowercaseChar() == key.lowercaseChar()
        }

    // ── Internal ───────────────────────────────────────────────────────────

    private fun load(context: Context, prefKey: String): List<MacroPreset> {
        val raw = context.prefs().getString(prefKey, "[]") ?: "[]"
        return try { json.decodeFromString(raw) } catch (_: Exception) { emptyList() }
    }

    private fun save(context: Context, prefKey: String, preset: MacroPreset) {
        val list = load(context, prefKey).toMutableList()
        list.removeAll { it.name == preset.name }
        list.add(preset)
        context.prefs().edit().putString(prefKey, json.encodeToString(list)).apply()
    }

    private fun delete(context: Context, prefKey: String, name: String) {
        val list = load(context, prefKey).toMutableList()
        list.removeAll { it.name == name }
        context.prefs().edit().putString(prefKey, json.encodeToString(list)).apply()
    }
}
