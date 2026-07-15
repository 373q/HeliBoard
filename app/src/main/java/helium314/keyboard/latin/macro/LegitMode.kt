// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.macro

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.random.Random

/**
 * Legit Mode — simulează greșeli umane de tastare.
 * Uneori introduce o literă greșită (adiacentă pe tastatură), o șterge cu backspace,
 * apoi scrie litera corectă — exact ca un om care observă greșeala și o corectează.
 */
object LegitMode {

    // Probabilitate per caracter că se face o greșeală
    private const val TYPO_PROBABILITY = 0.06f

    /**
     * Limitează câte greșeli se fac într-un singur mesaj (max 1-2), altfel la mesaje
     * lungi TYPO_PROBABILITY pe literă tot ar produce prea multe greșeli.
     * O instanță nouă trebuie creată pentru fiecare mesaj tipărit.
     */
    class TypoBudget(maxTypos: Int = 2) {
        private val max = Random.nextInt(1, maxTypos + 1)
        private var used = 0
        fun tryConsume(): Boolean {
            if (used >= max) return false
            used++
            return true
        }
    }

    // Taste adiacente pentru fiecare literă (layout QWERTY)
    private val adjacentKeys: Map<Char, String> = mapOf(
        'a' to "qwsz",   'b' to "vghn",   'c' to "xdfv",   'd' to "erfcxs",
        'e' to "wrsdf",  'f' to "rtgvcd", 'g' to "tyhbvf", 'h' to "yujnbg",
        'i' to "uojkl",  'j' to "uikmnh", 'k' to "iolmj",  'l' to "opk",
        'm' to "njk",    'n' to "bhjm",   'o' to "iplk",   'p' to "ol",
        'q' to "wa",     'r' to "etfd",   's' to "wedxza", 't' to "rygfe",
        'u' to "yihj",   'v' to "cfgb",   'w' to "qase",   'x' to "zsdc",
        'y' to "tugh",   'z' to "asx",
        'A' to "QWSZ",   'B' to "VGHN",   'C' to "XDFV",   'D' to "ERFCXS",
        'E' to "WRSDF",  'F' to "RTGVCD", 'G' to "TYHBVF", 'H' to "YUJNBG",
        'I' to "UOJKL",  'J' to "UIKMNH", 'K' to "IOLMJ",  'L' to "OPK",
        'M' to "NJK",    'N' to "BHJM",   'O' to "IPLK",   'P' to "OL",
        'Q' to "WA",     'R' to "ETFD",   'S' to "WEDXZA", 'T' to "RYGFE",
        'U' to "YIHJ",   'V' to "CFGB",   'W' to "QASE",   'X' to "ZSDC",
        'Y' to "TUGH",   'Z' to "ASX"
    )

    /**
     * Returnează un caracter greșit adiacent cu [correctChar], sau null dacă nu există adiacent.
     */
    private fun getWrongChar(correctChar: Char): Char? {
        val adjacent = adjacentKeys[correctChar]
        return if (!adjacent.isNullOrEmpty()) adjacent[Random.nextInt(adjacent.length)] else null
    }

    /**
     * Tipărește un caracter cu posibilă greșeală (typo + backspace + corect).
     *
     * @param correctChar  Caracterul corect de tipărit
     * @param charDelay    Delay-ul normal între caractere (ms)
     * @param isRunning    Funcție care verifică dacă macro-ul mai rulează
     * @param typeChar     Callback (Main thread) pentru a trimite un caracter
     * @param deleteChar   Callback (Main thread) pentru a apăsa backspace o dată
     */
    suspend fun typeCharWithPossibleTypo(
        correctChar: Char,
        charDelay: Long,
        budget: TypoBudget,
        pauseDelay: Long,
        deleteDelay: Long,
        writeDelay: Long,
        isRunning: () -> Boolean,
        typeChar: (Char) -> Unit,
        deleteChar: () -> Unit
    ) {
        if (!isRunning()) return

        val shouldMakeTypo = correctChar.isLetter() && Random.nextFloat() < TYPO_PROBABILITY && budget.tryConsume()
        val wrongChar = if (shouldMakeTypo) getWrongChar(correctChar) else null

        if (wrongChar != null) {
            // 1. Tipărește caracterul greșit
            withContext(Dispatchers.Main) { typeChar(wrongChar) }
            delay(pauseDelay)
            if (!isRunning()) return
            // 2. Șterge cu backspace (apasă vizual tasta de delete, ca un tap real)
            withContext(Dispatchers.Main) { deleteChar() }
            delay(deleteDelay)
            if (!isRunning()) return
            // 3. Tipărește caracterul corect, apoi bucla apelantă revine la charDelay normal
            withContext(Dispatchers.Main) { typeChar(correctChar) }
            delay(writeDelay)
            return
        }

        // Fără greșeală — tipărește direct caracterul corect
        withContext(Dispatchers.Main) { typeChar(correctChar) }
    }
}
