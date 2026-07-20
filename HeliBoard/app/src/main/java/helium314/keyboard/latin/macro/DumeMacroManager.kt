// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.macro

import android.content.Context
import android.net.Uri
import helium314.keyboard.latin.settings.Defaults
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.Log
import helium314.keyboard.latin.utils.ToolbarMode
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
    private var isRunning = false

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

    fun start(context: Context) {
        if (isRunning) return
        isRunning = true

        val startedShifted = listener?.isShifted() ?: false

        // Captureaza textul deja scris in input (trebuie pe Main thread, inainte de coroutine) —
        // devine prefixul lipit inaintea fiecarui mesaj urmator, la fel ca la Shift mode.
        val rawInput = listener?.getCurrentInputText()?.takeIf { it.isNotEmpty() }
        inputPrefix = rawInput

        // Verifică starea toolbar-ului ACUM, înainte de coroutine
        toolbarWasOn = Settings.readToolbarMode(context.prefs()) == ToolbarMode.EXPANDABLE

        // Copiaza prefixul in clipboard DOAR daca toolbar-ul e on (va fi lipit inaintea mesajelor)
        // Daca toolbar-ul e off, prefixul e scris manual de user o singura data, nu-l mai atingem
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
            val startDelay = context.prefs().getInt(Settings.PREF_DUME_START_DELAY, 800).toLong()
            val startDelayDeferred = async { delay(startDelay) }

            val groups = withContext(Dispatchers.IO) { loadGroupsCached(context) }
            if (groups.isEmpty()) {
                Log.w(TAG, "No dume message groups to send")
                isRunning = false
                startDelayDeferred.cancel()
                return@launch
            }
            startDelayDeferred.await()
            runDumeMacro(context, groups.toMutableList(), startedShifted, toolbarWasOn)
        }
    }

    fun stop() {
        isRunning = false
        typingJob?.cancel()
        typingJob = null
        inputPrefix = null
        toolbarWasOn = false
    }

    private suspend fun runDumeMacro(
        context: Context,
        groups: MutableList<List<String>>,
        capsOn: Boolean,
        toolbarWasOn: Boolean
    ) {
        val prefs = context.prefs()
        val charDelay = prefs.getInt(Settings.PREF_DUME_CHAR_DELAY, 80).toLong()
        val msgDelay = prefs.getInt(Settings.PREF_DUME_MSG_DELAY, 3000).toLong()
        val legitMode = prefs.getBoolean(Settings.PREF_DUME_LEGIT_MODE, false)
        val legitDeleteDelay = prefs.getInt(Settings.PREF_DUME_LEGIT_DELETE_DELAY, 120).toLong()
        val legitPauseActions = prefs.getInt(Settings.PREF_DUME_LEGIT_PAUSE_ACTIONS, 40).toLong()
        val legitWriteDelay = prefs.getInt(Settings.PREF_DUME_LEGIT_WRITE_DELAY, 100).toLong()
        val legitTypos = prefs.getInt(Settings.PREF_DUME_LEGIT_TYPOS, 2)
        val randomPauseEnabled = prefs.getBoolean(Settings.PREF_DUME_RANDOM_PAUSE_ENABLED, Defaults.PREF_DUME_RANDOM_PAUSE_ENABLED)
        val randomPauseMaxMs = prefs.getInt(Settings.PREF_DUME_RANDOM_PAUSE_MAX_MS, Defaults.PREF_DUME_RANDOM_PAUSE_MAX_MS).toLong()
        val randomPauseCount = prefs.getInt(Settings.PREF_DUME_RANDOM_PAUSE_COUNT, Defaults.PREF_DUME_RANDOM_PAUSE_COUNT)

        // Shuffle grupurile
        groups.shuffle()
        var groupIndex = 0
        var lineIndexInGroup = 0
        var totalSent = 0

        while (isRunning) {
            if (!isRunning) return

            // Auto-stop dacă tastatura a pierdut focusul (getCurrentInputText() == null)
            // La primul mesaj (totalSent == 0) așteptăm până la 3s ca IME-ul să se reconecteze
            // (toolbar-ul tocmai s-a deschis și poate întrerupe temporar conexiunea IME).
            // Mid-macro (totalSent > 0), stop imediat — pierderea focusului e reală.
            val inputAvailable = withContext(Dispatchers.Main) {
                listener?.getCurrentInputText() != null
            }
            if (!inputAvailable) {
                if (totalSent == 0) {
                    // Retry loop — max 3s, poll la 100ms
                    val deadline = System.currentTimeMillis() + 3000L
                    var found = false
                    while (System.currentTimeMillis() < deadline) {
                        delay(100L)
                        if (!isRunning) return
                        found = withContext(Dispatchers.Main) {
                            listener?.getCurrentInputText() != null
                        }
                        if (found) break
                    }
                    if (!found) {
                        Log.w(TAG, "Dume: input unavailable after 3s wait, stopping macro")
                        isRunning = false
                        return
                    }
                } else {
                    Log.w(TAG, "Dume: input unavailable mid-macro, stopping")
                    isRunning = false
                    return
                }
            }

            // Dacă am terminat toate grupurile, re-shuffle și reîncepem
            if (groupIndex >= groups.size) {
                groups.shuffle()
                groupIndex = 0
                lineIndexInGroup = 0
            }

            val group = groups[groupIndex]

            // Dacă am terminat liniile din grupul curent, trecem la următorul grup
            if (lineIndexInGroup >= group.size) {
                groupIndex++
                lineIndexInGroup = 0
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
            for ((charIndex, char) in line.withIndex()) {
                if (!isRunning) return
                val capsNow = listener?.isCapsLocked() ?: false
                val shiftedNow = listener?.isShifted() ?: false
                val charToType = if (capsNow || shiftedNow) char.uppercaseChar() else char.lowercaseChar()

                if (legitMode && char.isLetter()) {
                    LegitMode.typeCharWithPossibleTypo(
                        correctChar = charToType,
                        charDelay = charDelay,
                        budget = typoBudget,
                        pauseDelay = legitPauseActions,
                        deleteDelay = legitDeleteDelay,
                        writeDelay = legitWriteDelay,
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
                if (!isRunning) return
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
            while (isRunning && waitedAfterSend < maxWaitAfterSend) {
                delay(pollInterval)
                waitedAfterSend += pollInterval
                val txt = withContext(Dispatchers.Main) { listener?.getCurrentInputText() }
                if (txt == null) { isRunning = false; return } // am pierdut focusul
                if (txt.isEmpty()) break // câmpul e golit, safe să continuăm
            }

            if (!isRunning) return
            delay(msgDelay)

            // Random pause — inserat după delay-ul normal dintre mesaje
            if (randomPauseEnabled && randomPauseCount > 0 && randomPauseMaxMs > 0) {
                // Fiecare mesaj are o probabilitate de a primi o pauză.
                // randomPauseCount / 10 = probabilitatea aproximativă pe mesaj (max 10 pauze la 10 mesaje = 100%)
                val pauseChance = randomPauseCount.toFloat() / 10f
                if (Random.nextFloat() < pauseChance) {
                    val pauseMs = Random.nextLong(0L, randomPauseMaxMs + 1L)
                    if (!isRunning) return
                    delay(pauseMs)
                }
            }
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
