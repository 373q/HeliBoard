// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.macro

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.random.Random

/**
 * Legit Mode — simulează greșeli umane de tastare.
 *
 * Moduri de corectare:
 *  0 = OFF      — backspace imediat (comportament vechi)
 *  1 = DIRECT   — cursorul se mută la litera greșită, o șterge forward, retastează corect
 *  2 = RETYPE_LINE — cursorul se mută la începutul textului tastat, șterge totul, retastează corect
 *  3 = RANDOM   — alege aleatoriu între DIRECT și RETYPE_LINE
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
        // maxTypos=0 înseamnă dezactivat complet (niciun typo).
        // Altfel, se alege random câte greșeli se fac în mesajul curent (între 1 și maxTypos).
        private val max = if (maxTypos <= 0) 0 else Random.nextInt(1, maxTypos + 1)
        private var used = 0
        fun tryConsume(): Boolean {
            if (max == 0 || used >= max) return false
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

    /** Returnează un caracter greșit adiacent cu [correctChar], sau null dacă nu există. */
    private fun getWrongChar(correctChar: Char): Char? {
        val adjacent = adjacentKeys[correctChar]
        return if (!adjacent.isNullOrEmpty()) adjacent[Random.nextInt(adjacent.length)] else null
    }

    /**
     * Tipărește un caracter cu posibilă greșeală.
     *
     * @param correctChar     Caracterul corect de tipărit
     * @param charDelay       Delay-ul normal între caractere (ms)
     * @param budget          Budget de greșeli pentru mesajul curent
     * @param pauseDelay      Pauză după greșeală (ms) — simulează "observarea" erorii
     * @param deleteDelay     Delay după ștergere (ms)
     * @param writeDelay      Delay după retastare corect (ms)
     * @param cursorMode      0=OFF, 1=DIRECT, 2=RETYPE_LINE, 3=RANDOM
     * @param cursorSpeedDelay  Delay per pas de mutare cursor (ms)
     * @param messagePrefix   Textul deja tastat înainte de acest caracter (pentru RETYPE_LINE)
     * @param isRunning       Verifică dacă macro-ul mai rulează
     * @param typeChar        Callback pentru a trimite un caracter
     * @param deleteChar      Callback pentru backspace (șterge ÎNAINTE de cursor)
     * @param moveCursor      Callback pentru a muta cursorul cu N pași (negativ=stânga, pozitiv=dreapta)
     * @param deleteForward   Callback pentru a șterge caracterul DUPĂ cursor
     */
    suspend fun typeCharWithPossibleTypo(
        correctChar: Char,
        charDelay: Long,
        budget: TypoBudget,
        pauseDelay: Long,
        deleteDelay: Long,
        writeDelay: Long,
        cursorMode: Int = 0,
        cursorSpeedDelay: Long = 150L,
        messagePrefix: String = "",
        isRunning: () -> Boolean,
        typeChar: (Char) -> Unit,
        deleteChar: () -> Unit,
        moveCursor: (Int) -> Unit = {},
        deleteForward: () -> Unit = {}
    ) {
        if (!isRunning()) return

        val shouldMakeTypo = correctChar.isLetter() && Random.nextFloat() < TYPO_PROBABILITY && budget.tryConsume()
        val wrongChar = if (shouldMakeTypo) getWrongChar(correctChar) else null

        if (wrongChar == null) {
            // Fără greșeală — tipărește direct
            withContext(Dispatchers.Main) { typeChar(correctChar) }
            return
        }

        // Determină ce mod de corecție se folosește
        val actualMode = when (cursorMode) {
            3 -> if (Random.nextBoolean()) 1 else 2  // RANDOM → alege 1 sau 2
            else -> cursorMode
        }

        // 1. Tipărește caracterul greșit
        withContext(Dispatchers.Main) { typeChar(wrongChar) }
        delay(pauseDelay)
        if (!isRunning()) return

        when (actualMode) {
            1 -> {
                // DIRECT: mută cursorul la litera greșită, șterge forward, retastează
                // Cursorul e după litera greșită → mutăm stânga 1
                withContext(Dispatchers.Main) { moveCursor(-1) }
                delay(cursorSpeedDelay.coerceAtLeast(10L))
                if (!isRunning()) return

                // Șterge litera greșită (forward delete)
                withContext(Dispatchers.Main) { deleteForward() }
                delay(deleteDelay)
                if (!isRunning()) return

                // Retastează corect
                withContext(Dispatchers.Main) { typeChar(correctChar) }
                delay(writeDelay)
            }

            2 -> {
                // RETYPE_LINE: mută cursorul la început, șterge tot, retastează de la 0
                val totalTyped = messagePrefix.length + 1  // prefix + litera greșită tocmai tastată
                val stepDelay = cursorSpeedDelay.coerceAtLeast(0L)

                // Mută cursorul la stânga pas cu pas până la începutul rândului
                repeat(totalTyped) {
                    if (!isRunning()) return
                    withContext(Dispatchers.Main) { moveCursor(-1) }
                    if (stepDelay > 0L) delay(stepDelay)
                }
                if (!isRunning()) return

                // Șterge tot ce era scris (forward delete)
                val delDelay = (deleteDelay / totalTyped.coerceAtLeast(1)).coerceAtLeast(0L)
                repeat(totalTyped) {
                    if (!isRunning()) return
                    withContext(Dispatchers.Main) { deleteForward() }
                    if (delDelay > 0L) delay(delDelay)
                }
                if (!isRunning()) return

                // Retastează prefixul
                for (c in messagePrefix) {
                    if (!isRunning()) return
                    withContext(Dispatchers.Main) { typeChar(c) }
                    delay(writeDelay)
                }
                if (!isRunning()) return

                // Tastează caracterul corect
                withContext(Dispatchers.Main) { typeChar(correctChar) }
                delay(writeDelay)
            }

            else -> {
                // OFF (0) sau fallback: backspace clasic
                withContext(Dispatchers.Main) { deleteChar() }
                delay(deleteDelay)
                if (!isRunning()) return
                withContext(Dispatchers.Main) { typeChar(correctChar) }
                delay(writeDelay)
            }
        }
    }
}
