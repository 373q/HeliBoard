// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.macro

import kotlinx.serialization.Serializable

/**
 * Un preset de setări macro.
 * [shortcutKey] — litera custom (ex: "q", "f") apăsată în timp ce ții held Space (Shift) sau Comma (Dume).
 * Câmpurile Dume-only au default-uri pentru a fi compatibile cu preseturile Shift.
 */
@Serializable
data class MacroPreset(
    val name: String,
    val shortcutKey: String,          // un singur caracter ca String (ex: "q")
    val charDelay: Int,
    val startDelay: Int,
    val msgDelay: Int,
    val legitMode: Boolean,
    val pauseDelay: Int,
    val deleteDelay: Int,
    val writeDelay: Int,
    val maxTypos: Int,
    val lettersPerTypo: Int,
    // Dume-only (cu default-uri pentru compatibilitate cu preseturile Shift)
    val randomPauseEnabled: Boolean = false,
    val randomPauseMaxMs: Int = 1500,
    val randomPauseCount: Int = 3,
)
