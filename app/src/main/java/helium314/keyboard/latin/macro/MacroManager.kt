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

object MacroManager {

    private const val TAG = "MacroManager"
    const val MACRO_FILE_NAME = "macro_messages.txt"

    private var typingJob: Job? = null
    @Volatile
    private var isRunning = false

    /** Preset de folosit la next start(); null = citește din SharedPreferences normal. */
    var pendingPreset: MacroPreset? = null

    /**
     * Preset setat în timpul ferestrei start delay (apăsare literă shortcut în timp ce macro
     * rulează deja start delay-ul). Dacă non-null la expirarea start delay-ului, înlocuiește
     * selectedPreset pentru toate setările (charDelay, msgDelay, etc.).
     */
    @Volatile var latePreset: MacroPreset? = null

    /** True între start() și expirarea start delay-ului — fereastra în care latePreset poate fi setat. */
    @Volatile var inStartDelayWindow = false

    // Thread dedicat, separat de pool-ul Dispatchers.Default (care e shared cu restul app-ului).
    // Ridicam prioritatea threadului ca timing-ul macro-ului sa nu fie afectat de alte task-uri
    // sau de GC pauses cauzate de alte coroutine care ruleaza pe acelasi pool.
    private val macroExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "MacroTypingThread").apply {
            priority = Thread.MAX_PRIORITY
        }
    }
    private val macroDispatcher = macroExecutor.asCoroutineDispatcher()
    private val scope = CoroutineScope(macroDispatcher)

    private var inputPrefix: String? = null
    private var isBoldMode = false
    private var toolbarWasOn: Boolean = false

    // Cache-ul mesajelor — incarcate o singura data la start, nu la fiecare iteratie din loop
    private var cachedMessages: List<String> = emptyList()

    // Cache persistent al fisierului parsat, valabil intre porniri succesive ale macro-ului.
    // Fara asta, fiecare start() re-citeste si re-parseaza fisierul de pe disc (poate fi 15MB+),
    // ceea ce introduce o intarziere de 2-3s la pornire INDIFERENT de "Start delay" (care e 0
    // doar in paralel cu incarcarea, nu o elimina). Cache-ul e invalidat doar cand fisierul e
    // re-importat (vezi importFile), nu la fiecare stop().
    private var messagesFileCache: List<String>? = null
    private var messagesFileCacheMtime: Long = -1L

    var listener: MacroListener? = null

    interface MacroListener {
        fun onMacroTypeChar(char: Char)
        fun onMacroSendMessage()
        fun onMacroPasteText(text: String)
        fun onMacroStart(hasPrefix: Boolean)
        fun onMacroSwitchKeyboard(toSymbols: Boolean)
        fun onMacroCapsState(capsOn: Boolean)
        fun isShifted(): Boolean
        fun isCapsLocked(): Boolean
        /** Returns current text in the input field, or null if unavailable */
        fun getCurrentInputText(): String?
        /** Returns true dacă toolbar-ul e efectiv vizibil/deschis chiar acum */
        fun isToolbarExpanded(): Boolean
        /**
         * Apasă backspace o singură dată — folosit de Legit Mode pentru a șterge
         * caracterul greșit înainte de corectare. Implementarea implicită trimite
         * caracterul BS (0x08) prin onMacroTypeChar; suprascrie în keyboard service
         * dacă ai nevoie de KeyCode.DELETE direct.
         */
        fun onMacroDeleteChar() { onMacroTypeChar('\b') }

        /**
         * Sincronizează starea internă a input connection-ului cu app-ul.
         * Chemat o singură dată la pornirea macro-ului, înainte de prima tastă.
         * Rezolvă bug-ul unde prima tastă e silently dropped după long-press +
         * keyboard layout switch (cursorul InputLogic e out-of-sync cu app-ul).
         * Default no-op — suprascris în keyboard service.
         */
        fun onMacroPrimeConnection() {}
    }

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
        // Rezolvăm presetul în momentul pornirii, nu după start delay. Astfel:
        // - fără shortcut, macro-ul folosește setările active acum;
        // - cu shortcut, toate setările presetului (inclusiv start delay) se aplică de la început;
        // - un preset rămas accidental în pendingPreset nu poate contamina o pornire ulterioară.
        val selectedPreset = pendingPreset
        pendingPreset = null
        latePreset = null
        inStartDelayWindow = true  // fereastra se deschide imediat, înainte de coroutine

        val startedShifted = listener?.isShifted() ?: false

        // Capture current input field text (trebuie pe Main thread, inainte de coroutine)
        val rawInput = listener?.getCurrentInputText()?.takeIf { it.isNotEmpty() }

        // Detect bold mode: input ends with **
        isBoldMode = rawInput?.endsWith("**") == true

        // Prefix is everything before the trailing ** (if bold), or the full text
        inputPrefix = if (isBoldMode && rawInput != null) {
            rawInput.dropLast(2).takeIf { it.isNotEmpty() }
        } else {
            rawInput
        }

        // Verifica daca toolbar-ul e efectiv deschis/vizibil acum (nu doar modul din settings)
        toolbarWasOn = listener?.isToolbarExpanded() ?: false

        // Copiaza prefixul in clipboard DOAR daca toolbar-ul era deschis.
        // Cand e inchis, prefixul nu se mai lipeste pe mesajele urmatoare, deci clipboard-ul nu e necesar.
        if (toolbarWasOn) {
            inputPrefix?.let { prefix ->
                val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("macro_prefix", prefix))
            }
        }

        listener?.onMacroStart((inputPrefix != null || isBoldMode) && toolbarWasOn)
        listener?.onMacroCapsState(startedShifted)

        typingJob = scope.launch {
            // Pornim delay-ul de start in paralel cu incarcarea fisierului — daca userul a pus
            // 0ms, nu vrem ca parse-ul unui fisier de 15MB+ sa adauge un delay "ascuns" la start.
            val startDelay = (selectedPreset?.startDelay
                ?: context.prefs().getInt(Settings.PREF_MACRO_START_DELAY, 800)).toLong()
            val startDelayDeferred = async { delay(startDelay) }
            // Foloseste cache-ul persistent daca exista si fisierul nu s-a schimbat — evita
            // re-citirea + re-parse-ul de pe disc la fiecare start (asta e sursa reala a
            // "start delay"-ului de 2-3s vazut chiar cu Start delay = 0ms).
            val messages = withContext(Dispatchers.IO) { loadMessagesCached(context) }
            if (messages.isEmpty()) {
                Log.w(TAG, "No messages to send")
                isRunning = false
                startDelayDeferred.cancel()
                return@launch
            }
            cachedMessages = messages
            startDelayDeferred.await()
            // Consumă latePreset setat opțional în fereastra start delay (apăsare literă shortcut)
            val effectivePreset = latePreset ?: selectedPreset
            latePreset = null
            inStartDelayWindow = false
            runMacro(context, messages.toMutableList(), startedShifted, toolbarWasOn, effectivePreset)
        }
    }

    fun stop() {
        isRunning = false
        inStartDelayWindow = false
        latePreset = null
        typingJob?.cancel()
        typingJob = null
        inputPrefix = null
        isBoldMode = false
        toolbarWasOn = false
        cachedMessages = emptyList()
    }

    private suspend fun runMacro(
        context: Context,
        messages: MutableList<String>,
        capsOn: Boolean,
        toolbarWasOn: Boolean,
        preset: MacroPreset?
    ) {
        val prefs = context.prefs()
        // Citim delay-urile o data la start — daca userul le schimba in timp ce ruleaza, se aplica la urmatoarea pornire
        val charDelay = (preset?.charDelay?.toLong()) ?: prefs.getInt(Settings.PREF_MACRO_CHAR_DELAY, 80).toLong()
        val msgDelay = (preset?.msgDelay?.toLong()) ?: prefs.getInt(Settings.PREF_MACRO_MSG_DELAY, 3000).toLong()
        val legitMode = preset?.legitMode ?: prefs.getBoolean(Settings.PREF_SHIFT_LEGIT_MODE, false)
        val legitDeleteDelay = (preset?.deleteDelay?.toLong()) ?: prefs.getInt(Settings.PREF_LEGIT_DELETE_DELAY, 120).toLong()
        val legitPauseActions = (preset?.pauseDelay?.toLong()) ?: prefs.getInt(Settings.PREF_LEGIT_PAUSE_ACTIONS, 40).toLong()
        val legitWriteDelay = (preset?.writeDelay?.toLong()) ?: prefs.getInt(Settings.PREF_LEGIT_WRITE_DELAY, 100).toLong()
        val legitTypos = preset?.maxTypos ?: prefs.getInt(Settings.PREF_LEGIT_TYPOS, 2)
        val legitLettersPerTypo = preset?.lettersPerTypo ?: prefs.getInt(Settings.PREF_LEGIT_LETTERS_PER_TYPO, 1)
        val randomPauseEnabled = preset?.randomPauseEnabled ?: prefs.getBoolean(Settings.PREF_MACRO_RANDOM_PAUSE_ENABLED, Defaults.PREF_MACRO_RANDOM_PAUSE_ENABLED)
        val randomPauseMaxMs = (preset?.randomPauseMaxMs?.toLong()) ?: prefs.getInt(Settings.PREF_MACRO_RANDOM_PAUSE_MAX_MS, Defaults.PREF_MACRO_RANDOM_PAUSE_MAX_MS).toLong()
        val randomPauseCount = preset?.randomPauseCount ?: prefs.getInt(Settings.PREF_MACRO_RANDOM_PAUSE_COUNT, Defaults.PREF_MACRO_RANDOM_PAUSE_COUNT)
        // startDelay e deja aplicat in start(), in paralel cu incarcarea fisierului

        messages.shuffle()
        var index = 0

        // Sincronizăm connection-ul înainte de prima tastă (fără wait extra — macro pornește
        // la long-press când IC-ul e deja activ, nu la release când poate fi null/stale).
        withContext(Dispatchers.Main) { listener?.onMacroPrimeConnection() }

        while (isRunning) {
            if (!isRunning) return

            // Auto-stop: dacă tastatura a dispărut / nu mai există câmp de input activ
            val inputAvailable = withContext(Dispatchers.Main) {
                listener?.getCurrentInputText() != null
            }
            if (!inputAvailable) {
                Log.w(TAG, "Shift: input unavailable, stopping macro")
                isRunning = false
                return
            }

            if (index >= messages.size) {
                messages.shuffle()
                index = 0
            }

            var msg = messages[index]
            if (capsOn) msg = msg.uppercase()
            index++

            val isFirstMsg = index == 1
            val prefix = inputPrefix
            // Lipeste prefixul pe mesajele 2, 3, 4... DOAR daca toolbar-ul era deschis la start.
            // Cand e inchis: primul mesaj are deja prefixul in camp (tastat de user), restul fara.
            val needsPrefix = !prefix.isNullOrEmpty() && !isFirstMsg && toolbarWasOn

            // Build what to type before the message content
            if (!isFirstMsg) {
                if (needsPrefix) {
                    val p = if (capsOn) prefix!!.uppercase() else prefix!!
                    withContext(Dispatchers.Main) { listener?.onMacroPasteText(p) }
                    delay(250) // mai mult timp pentru paste să se așeze în câmp înainte să înceapă tastarea
                    if (!isRunning) return
                }
                if (isBoldMode) {
                    val capsBeforeOpen = listener?.isCapsLocked() ?: false
                    withContext(Dispatchers.Main) { listener?.onMacroSwitchKeyboard(true) }
                    delay(charDelay)
                    for (char in "**") {
                        if (!isRunning) return
                        withContext(Dispatchers.Main) { listener?.onMacroTypeChar(char) }
                        delay(charDelay)
                    }
                    withContext(Dispatchers.Main) { listener?.onMacroSwitchKeyboard(false) }
                    delay(charDelay)
                    if (capsBeforeOpen) {
                        withContext(Dispatchers.Main) { listener?.onMacroCapsState(true) }
                        delay(charDelay)
                    }
                }
            }

            // Tipărește mesajul caracter cu caracter (cu Legit Mode dacă e activat)
            // Budget nou per mesaj — max 1-2 greșeli pe tot mesajul, nu pe fiecare literă
            val typoBudget = LegitMode.TypoBudget(legitTypos)
            // Pre-alege pozițiile unde se inserează pauze mid-typing (random în mesaj)
            val midTypingPausePositions = if (randomPauseEnabled && randomPauseCount > 0 && randomPauseMaxMs > 0 && msg.length > 1) {
                (1 until msg.length).shuffled().take(randomPauseCount).toSet()
            } else emptySet()
            for ((charIndex, char) in msg.withIndex()) {
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
            }

            // Inchide ** la sfarsit (bold mode)
            if (isBoldMode) {
                val capsBeforeClose = listener?.isCapsLocked() ?: false
                withContext(Dispatchers.Main) { listener?.onMacroSwitchKeyboard(true) }
                delay(charDelay)
                for (char in "**") {
                    if (!isRunning) return
                    withContext(Dispatchers.Main) { listener?.onMacroTypeChar(char) }
                    delay(charDelay)
                }
                withContext(Dispatchers.Main) { listener?.onMacroSwitchKeyboard(false) }
                delay(charDelay)
                if (capsBeforeClose) {
                    withContext(Dispatchers.Main) { listener?.onMacroCapsState(true) }
                    delay(charDelay)
                }
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
        }
    }

    /**
     * Ca loadMessages(), dar reutilizeaza rezultatul parsat anterior daca fisierul
     * (marcat prin lastModified()) nu s-a schimbat de la ultima citire. Evita costul de
     * I/O + parsing la fiecare pornire a macro-ului pentru fisiere mari (15MB+).
     */
    @Synchronized
    fun loadMessagesCached(context: Context): List<String> {
        val file = getMacroFile(context)
        if (!file.exists()) {
            messagesFileCache = null
            messagesFileCacheMtime = -1L
            return emptyList()
        }
        val mtime = file.lastModified()
        val cached = messagesFileCache
        if (cached != null && mtime == messagesFileCacheMtime) return cached
        val loaded = loadMessages(context)
        messagesFileCache = loaded
        messagesFileCacheMtime = mtime
        return loaded
    }

    fun loadMessages(context: Context): List<String> {
        val file = getMacroFile(context)
        if (!file.exists()) return emptyList()
        return try {
            // Citim linie cu linie si grupam pe linii goale, in loc sa tinem tot fisierul
            // ca un singur String urias + un array de rezultate Regex.split (foarte costisitor
            // pe heap pentru fisiere de 15MB+, declanseaza GC frecvent care intarzie delay()-urile
            // din macro chiar daca delay-ul setat e mic).
            val messages = ArrayList<String>()
            val current = StringBuilder()
            file.bufferedReader().useLines { lines ->
                for (line in lines) {
                    if (line.isBlank()) {
                        if (current.isNotEmpty()) {
                            messages.add(current.toString().trim())
                            current.setLength(0)
                        }
                    } else {
                        if (current.isNotEmpty()) current.append('\n')
                        current.append(line)
                    }
                }
            }
            if (current.isNotEmpty()) messages.add(current.toString().trim())
            messages.removeAll { it.isEmpty() }
            messages
        } catch (e: Exception) {
            Log.e(TAG, "Error reading macro file", e)
            emptyList()
        }
    }

    fun importFile(context: Context, uri: Uri): Boolean {
        return try {
            val content = context.contentResolver.openInputStream(uri)?.use { it.reader().readText() }
                ?: return false
            val file = getMacroFile(context)
            file.writeText(content)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error importing macro file", e)
            false
        }
    }

    fun getMacroFile(context: Context): File = File(context.filesDir, MACRO_FILE_NAME)

    fun getMessageCount(context: Context): Int = loadMessagesCached(context).size

    fun getMacroKeyword(context: Context): String =
        context.prefs().getString(Settings.PREF_MACRO_KEYWORD, "macro1") ?: "macro1"
}
