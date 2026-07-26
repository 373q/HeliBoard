// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.macro

import android.content.Context
import android.net.Uri
import helium314.keyboard.latin.settings.Defaults
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.Log
import helium314.keyboard.latin.utils.prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.Executors
import kotlin.random.Random

/**
 * DumeMacroManager — modul macro "Dume".
 *
 * Format fișier — simplu, plain text:
 *   ma pis
 *   pe tn
 *   de fan
 *              ← linie goală = separator între grupe
 *   sa te fut
 *   in gat de jeg
 *   care esti
 *
 * Comportament:
 * - Grupurile sunt alese în ordine random (shuffle), ca la Shift.
 * - Mesajele din fiecare grupă sunt trimise liniar, în ordine (nu shuffle).
 * - Delay-ul între mesaje este msg_delay, între caractere char_delay.
 * - Legit Mode: activabil din setări — simulează greșeli umane de tastare.
 * - Auto-stop: dacă getCurrentInputText() returnează null → keyboard a pierdut
 *   focusul → macro se oprește automat.
 */
object DumeMacroManager {

    private const val TAG = "DumeMacroManager"
    const val DUME_FILE_NAME = "dume_messages.txt"

    private var typingJob: Job? = null
    @Volatile
    private var isRunning = false

    /** Preset de folosit la next start(); null = citește din SharedPreferences normal. */
    var pendingPreset: MacroPreset? = null

    /** Preset setat opțional în fereastra start delay. Înlocuiește selectedPreset dacă non-null. */
    @Volatile var latePreset: MacroPreset? = null

    /** True între start() și expirarea start delay-ului. */
    @Volatile var inStartDelayWindow = false

    private val macroExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "DumeTypingThread").apply {
            priority = Thread.MAX_PRIORITY
        }
    }
    private val macroDispatcher = macroExecutor.asCoroutineDispatcher()
    private val scope = CoroutineScope(macroDispatcher)

    // Listener independent — setat de keyboard service la fel ca MacroManager.listener
    var listener: MacroManager.MacroListener? = null

    // Prefix — text deja scris in campul de input inainte de pornirea macro-ului.
    // Se lipeste (paste) inaintea fiecarui mesaj in afara de primul,
    // DAR NUMAI daca toolbar-ul era EXPANDABLE (on) la momentul pornirii.
    // Daca toolbar-ul era off, prefixul se aplica doar la primul mesaj.
    private var inputPrefix: String? = null

    // Starea toolbar-ului la momentul pornirii macro-ului.
    // true  = toolbar era EXPANDABLE (on) → prefix pe fiecare mesaj după primul + auto-expand toolbar
    // false = toolbar era off → prefix doar pe primul mesaj
    private var toolbarWasOn: Boolean = false

    // Cache persistent al fisierului parsat, valabil intre porniri succesive (vezi comentariul
    // echivalent din MacroManager — fara el, fiecare start() re-parseaza fisierul de pe disc,
    // ceea ce cauzeaza intarzierea de 2-3s la pornire indiferent de "Start delay").
    private var groupsFileCache: List<List<String>>? = null
    private var groupsFileCacheMtime: Long = -1L

    fun isRunning() = isRunning

    fun toggle(context: Context) {
        if (isRunning) stop() else start(context)
    }

    fun startWithPreset(context: Context, preset: MacroPreset) {
        pendingPreset = preset
        start(context)
    }

    fun start(context: Context) {
        if (isRunning) return
        isRunning = true
        val selectedPreset = pendingPreset
        pendingPreset = null
        latePreset = null
        inStartDelayWindow = true  // fereastra se deschide imediat, înainte de coroutine

        // Ca la MacroManager: distingem caps lock de one-shot shift.
        // capsOn pe tot mesajul doar dacă userul era în CAPS LOCK, nu în one-shot.
        val startedCapsLocked = listener?.isCapsLocked() ?: false

        // Captureaza textul deja scris in input (trebuie pe Main thread, inainte de coroutine) —
        // devine prefixul lipit inaintea fiecarui mesaj urmator, la fel ca la Shift mode.
        val rawInput = listener?.getCurrentInputText()?.takeIf { it.isNotEmpty() }
        inputPrefix = rawInput

        // Verifică dacă toolbar-ul e efectiv vizibil/deschis ACUM (nu doar modul din settings).
        // isToolbarExpanded() citește toolbarContainer.isVisible din SuggestionStripView.
        toolbarWasOn = listener?.isToolbarExpanded() ?: false

        // Copiaza prefixul in clipboard DOAR daca toolbar-ul era on (EXPANDABLE).
        // Cand toolbar e off, nu avem nevoie de clipboard — prefixul nu se mai lipeste pe mesajele urmatoare.
        if (toolbarWasOn) {
            inputPrefix?.let { prefix ->
                val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("dume_macro_prefix", prefix))
            }
        }

        // onMacroStart(hasPrefix=true) → LatinIME va apela setToolbarVisibility(true)
        // doar dacă toolbar-ul era EXPANDABLE (logica e în listener din LatinIME.java)
        listener?.onMacroStart(inputPrefix != null && toolbarWasOn)

        typingJob = scope.launch {
            val startDelay = (selectedPreset?.startDelay
                ?: context.prefs().getInt(Settings.PREF_DUME_START_DELAY, 800)).toLong()
            val startDelayDeferred = async { delay(startDelay) }

            val groups = withContext(Dispatchers.IO) { loadGroupsCached(context) }
            if (groups.isEmpty()) {
                Log.w(TAG, "No dume message groups to send")
                isRunning = false
                startDelayDeferred.cancel()
                return@launch
            }
            startDelayDeferred.await()
            val effectivePreset = latePreset ?: selectedPreset
            latePreset = null
            inStartDelayWindow = false
            runDumeMacro(context, groups.toMutableList(), startedCapsLocked, toolbarWasOn, effectivePreset)
        }
    }

    fun stop() {
        isRunning = false
        inStartDelayWindow = false
        latePreset = null
        typingJob?.cancel()
        typingJob = null
        inputPrefix = null
        toolbarWasOn = false
    }

    private suspend fun runDumeMacro(
        context: Context,
        groups: MutableList<List<String>>,
        capsOn: Boolean,
        toolbarWasOn: Boolean,
        preset: MacroPreset?
    ) {
        val prefs = context.prefs()
        val charDelay = (preset?.charDelay?.toLong()) ?: prefs.getInt(Settings.PREF_DUME_CHAR_DELAY, 80).toLong()
        val msgDelay = (preset?.msgDelay?.toLong()) ?: prefs.getInt(Settings.PREF_DUME_MSG_DELAY, 3000).toLong()
        val legitMode = preset?.legitMode ?: prefs.getBoolean(Settings.PREF_DUME_LEGIT_MODE, false)
        val legitDeleteDelay = (preset?.deleteDelay?.toLong()) ?: prefs.getInt(Settings.PREF_DUME_LEGIT_DELETE_DELAY, 120).toLong()
        val legitPauseActions = (preset?.pauseDelay?.toLong()) ?: prefs.getInt(Settings.PREF_DUME_LEGIT_PAUSE_ACTIONS, 40).toLong()
        val legitWriteDelay = (preset?.writeDelay?.toLong()) ?: prefs.getInt(Settings.PREF_DUME_LEGIT_WRITE_DELAY, 100).toLong()
        val legitTypos = preset?.maxTypos ?: prefs.getInt(Settings.PREF_DUME_LEGIT_TYPOS, 2)
        val legitLettersPerTypo = preset?.lettersPerTypo ?: prefs.getInt(Settings.PREF_DUME_LEGIT_LETTERS_PER_TYPO, 1)
        val randomPauseEnabled = preset?.randomPauseEnabled ?: prefs.getBoolean(Settings.PREF_DUME_RANDOM_PAUSE_ENABLED, Defaults.PREF_DUME_RANDOM_PAUSE_ENABLED)
        val randomPauseMaxMs = (preset?.randomPauseMaxMs?.toLong()) ?: prefs.getInt(Settings.PREF_DUME_RANDOM_PAUSE_MAX_MS, Defaults.PREF_DUME_RANDOM_PAUSE_MAX_MS).toLong()
        val randomPauseCount = preset?.randomPauseCount ?: prefs.getInt(Settings.PREF_DUME_RANDOM_PAUSE_COUNT, Defaults.PREF_DUME_RANDOM_PAUSE_COUNT)

        // Shuffle grupurile
        groups.shuffle()
        var groupIndex = 0
        var lineIndexInGroup = 0
        var totalSent = 0

        // Sincronizăm connection-ul înainte de prima tastă (fără wait extra — macro pornește
        // la long-press când IC-ul e deja activ, nu la release când poate fi null/stale).
        withContext(Dispatchers.Main) { listener?.onMacroPrimeConnection() }

        // Flag pentru a sări null-check-ul redundant la tranziția de grup.
        // La trecerea dintre grupe, null-check-ul s-a executat deja în iterația anterioară
        // (după after-send wait + msgDelay, IC e garantat valid). Un al doilea check imediat
        // crește expunerea la IC tranzitoriu null → risc de oprire aleatorie suplimentar.
        var skipNullCheckThisIteration = false

        while (isRunning) {
            if (!isRunning) return

            // Auto-stop: dacă tastatura a dispărut / nu mai există câmp de input activ.
            // Nu oprim la primul null — IC-ul poate fi tranzitoriu null după send (app-ul
            // procesează trimiterea și resetează câmpul). Așteptăm max 480ms cu retry-uri
            // înainte să declarăm că s-a pierdut focusul cu adevărat.
            // skipNullCheckThisIteration: sări check-ul la tranziția de grup (IC tocmai validat).
            if (!skipNullCheckThisIteration) {
                var nullRetries = 0
                val maxNullRetries = 8 // 8 × 60ms = 480ms fereastră de grație
                while (isRunning) {
                    val txt = withContext(Dispatchers.Main) { listener?.getCurrentInputText() }
                    if (txt != null) break
                    nullRetries++
                    if (nullRetries >= maxNullRetries) {
                        Log.w(TAG, "Dume: input unavailable after retries, stopping macro")
                        isRunning = false
                        return
                    }
                    delay(60)
                }
                if (!isRunning) return
            }
            skipNullCheckThisIteration = false

            // Dacă am terminat toate grupurile, re-shuffle și reîncepem
            if (groupIndex >= groups.size) {
                groups.shuffle()
                groupIndex = 0
                lineIndexInGroup = 0
            }

            val group = groups[groupIndex]

            // Dacă am terminat liniile din grupul curent, trecem la următorul grup.
            // Setăm skipNullCheckThisIteration = true: IC a fost validat în iterația
            // curentă (după after-send wait + msgDelay), nu are rost să-l verificăm din nou.
            if (lineIndexInGroup >= group.size) {
                groupIndex++
                lineIndexInGroup = 0
                skipNullCheckThisIteration = true
                continue
            }

            var line = group[lineIndexInGroup]
            lineIndexInGroup++

            if (capsOn) line = line.uppercase()

            val isFirstMsg = totalSent == 0
            totalSent++
            val prefix = inputPrefix
            // Logică prefix:
            // - toolbar ON  → prefix pe fiecare mesaj după primul (comportament normal)
            // - toolbar OFF → prefix DOAR pe primul mesaj; restul fără prefix
            val shouldPastePrefix = !isFirstMsg && !prefix.isNullOrEmpty() && toolbarWasOn
            if (shouldPastePrefix) {
                val p = if (capsOn) prefix.uppercase() else prefix
                withContext(Dispatchers.Main) { listener?.onMacroPasteText(p) }
                delay(250) // mai mult timp pentru paste să se așeze în câmp înainte să înceapă tastarea
                if (!isRunning) return
            }

            // Tipărește linia caracter cu caracter
            // Budget nou per mesaj — max 1-2 greșeli pe tot mesajul, nu pe fiecare literă
            val typoBudget = LegitMode.TypoBudget(legitTypos)
            // Pre-alege pozițiile unde se inserează pauze mid-typing (random în mesaj)
            val midTypingPausePositions = if (randomPauseEnabled && randomPauseCount > 0 && randomPauseMaxMs > 0 && line.length > 1) {
                (1 until line.length).shuffled().take(randomPauseCount).toSet()
            } else emptySet()

            // Citim starea caps/shift O SINGURĂ DATĂ pe Main thread, înainte de bucla de tastare.
            // Motivele: thread-safety (isShifted()/isCapsLocked() accesează keyboard state din Main
            // thread) + one-shot se aplică la ÎNTREG mesajul (indicatorul de shift rămâne activ
            // pe toată durata mesajului, nu doar 80ms). onMacroResetShift() apelat o singură dată
            // după mesaj, nu după fiecare caracter — identic cu fix-ul din MacroManager.
            val (capsNowForMsg, shiftedNowForMsg) = withContext(Dispatchers.Main) {
                Pair(listener?.isCapsLocked() ?: false, listener?.isShifted() ?: false)
            }

            for ((charIndex, char) in line.withIndex()) {
                if (!isRunning) return
                val charToType = if (capsOn || capsNowForMsg || shiftedNowForMsg) char.uppercaseChar() else char.lowercaseChar()

                if (legitMode && char.isLetter()) {
                    LegitMode.typeCharWithPossibleTypo(
                        correctChar = charToType,
                        charDelay = charDelay,
                        budget = typoBudget,
                        pauseDelay = legitPauseActions,
                        deleteDelay = legitDeleteDelay,
                        writeDelay = legitWriteDelay,
                        lettersPerTypo = legitLettersPerTypo,
                        isRunning = { isRunning },
                        typeChar = { c -> listener?.onMacroTypeChar(c) },
                        deleteChar = { listener?.onMacroDeleteChar() }
                    )
                } else {
                    withContext(Dispatchers.Main) { listener?.onMacroTypeChar(charToType) }
                }

                val d = when (charIndex) {
                    0 -> 120L
                    1 -> 100L
                    2 -> 90L
                    else -> charDelay
                }
                delay(d)

                // Pauză mid-typing — simulează că omul s-a oprit din scris
                if (charIndex in midTypingPausePositions) {
                    if (!isRunning) return
                    delay(Random.nextLong(100L, randomPauseMaxMs + 1L))
                }
                if (!isRunning) return
            }

            // One-shot shift: resetăm keyboard-ul O SINGURĂ DATĂ după linia completă.
            // Identic cu fix-ul din MacroManager — indicatorul de shift rămâne vizibil
            // pe toată durata tastării, nu dispare după primul caracter.
            if (shiftedNowForMsg && !capsNowForMsg) {
                withContext(Dispatchers.Main) { listener?.onMacroResetShift() }
            }

            if (!isRunning) return
            delay(200)

            withContext(Dispatchers.Main) {
                listener?.onMacroSendMessage()
            }

            // Așteptăm ca aplicația să proceseze trimiterea și să golească câmpul de input.
            // Fără asta, pe Discord/Instagram (care au latență de send de 200-400ms), prefixul
            // mesajului următor ajunge să fie lipit în câmpul anterior înainte ca acesta să fie
            // golit, rezultând text sudat + primele litere tăiate la mesajul următor.
            delay(150) // timp minim pentru ca Enter-ul să fie dispatched la app
            val maxWaitAfterSend = 3000L
            val pollInterval = 40L
            var waitedAfterSend = 0L
            var consecutiveNulls = 0
            val maxConsecutiveNulls = 10 // 10 × 40ms = 400ms toleranță pentru IC tranzitoriu null
            // după send (app-ul procesează trimiterea și poate nulifica IC temporar — nu e
            // pierdere de focus, e latența normală de procesare a mesajului trimis)
            // (Identic cu logica din MacroManager — fix pentru oprirea random a Dume.)
            while (isRunning && waitedAfterSend < maxWaitAfterSend) {
                delay(pollInterval)
                waitedAfterSend += pollInterval
                val txt = withContext(Dispatchers.Main) { listener?.getCurrentInputText() }
                if (txt == null) {
                    consecutiveNulls++
                    if (consecutiveNulls >= maxConsecutiveNulls) {
                        // null persistent — focus pierdut cu adevărat
                        Log.w(TAG, "Dume: IC null after send for ${consecutiveNulls * pollInterval}ms, stopping macro")
                        isRunning = false
                        return
                    }
                    continue // null tranzitoriu — mai așteptăm
                }
                consecutiveNulls = 0 // IC valid din nou, resetăm contorul
                if (txt.isEmpty()) break // câmpul e golit, safe să continuăm
            }

            if (!isRunning) return
            delay(msgDelay)
        }
    }

    /**
     * Parsează fișierul dume în grupe de mesaje.
     * Fiecare linie non-goală = un mesaj trimis verbatim.
     * Grupele sunt separate de linii goale.
     */
    /**
     * Ca loadGroups(), dar reutilizeaza rezultatul parsat anterior daca fisierul nu s-a
     * schimbat (lastModified()) de la ultima citire — evita I/O + parsing la fiecare start().
     */
    @Synchronized
    fun loadGroupsCached(context: Context): List<List<String>> {
        val file = getDumeFile(context)
        if (!file.exists()) {
            groupsFileCache = null
            groupsFileCacheMtime = -1L
            return emptyList()
        }
        val mtime = file.lastModified()
        val cached = groupsFileCache
        if (cached != null && mtime == groupsFileCacheMtime) return cached
        val loaded = loadGroups(context)
        groupsFileCache = loaded
        groupsFileCacheMtime = mtime
        return loaded
    }

    fun loadGroups(context: Context): List<List<String>> {
        val file = getDumeFile(context)
        if (!file.exists()) return emptyList()
        return try {
            val groups  = ArrayList<List<String>>()
            val current = ArrayList<String>()

            file.bufferedReader().useLines { lines ->
                for (line in lines) {
                    if (line.isBlank()) {
                        if (current.isNotEmpty()) {
                            groups.add(current.toList())
                            current.clear()
                        }
                    } else {
                        current.add(line)
                    }
                }
            }
            if (current.isNotEmpty()) groups.add(current.toList())
            groups
        } catch (e: Exception) {
            Log.e(TAG, "Error reading dume file", e)
            emptyList()
        }
    }

    fun importFile(context: Context, uri: Uri): Boolean {
        return try {
            val content = context.contentResolver.openInputStream(uri)?.use { it.reader().readText() }
                ?: return false
            val file = getDumeFile(context)
            file.writeText(content)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error importing dume file", e)
            false
        }
    }

    fun getDumeFile(context: Context): File = File(context.filesDir, DUME_FILE_NAME)

    fun getGroupCount(context: Context): Int = loadGroupsCached(context).size

    fun getMessageCount(context: Context): Int = loadGroupsCached(context).sumOf { it.size }
}
