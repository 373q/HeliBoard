// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.macro

import android.content.Context
import android.net.Uri
import helium314.keyboard.latin.settings.Defaults
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.Log
import helium314.keyboard.latin.utils.ToolbarMode
import helium314.keyboard.latin.utils.prefs
import kotlin.random.Random
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

object MacroManager {

    private const val TAG = "MacroManager"
    const val MACRO_FILE_NAME = "macro_messages.txt"

    private var typingJob: Job? = null
    private var isRunning = false

    // Thread dedicat, separat de pool-ul Dispatchers.Default.
    private val macroExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "MacroTypingThread").apply {
            priority = Thread.MAX_PRIORITY
        }
    }
    private val macroDispatcher = macroExecutor.asCoroutineDispatcher()
    private val scope = CoroutineScope(macroDispatcher)

    private var inputPrefix: String? = null
    private var isBoldMode = false
    // true dacă toolbar-ul era EXPANDABLE (on) la momentul pornirii macro-ului.
    // Controlează: (1) dacă prefixul se copiază în clipboard, (2) dacă se lipește la mesajele 2+.
    private var toolbarWasOn: Boolean = false

    // Cache-ul mesajelor
    private var cachedMessages: List<String> = emptyList()
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
        /**
         * Apasă backspace o singură dată — folosit de Legit Mode pentru a șterge
         * caracterul greșit înainte de corectare.
         */
        fun onMacroDeleteChar() { onMacroTypeChar('\b') }
        /**
         * Mută cursorul cu [offset] poziții (negativ = stânga, pozitiv = dreapta).
         * Folosit de Legit Mode cursor correction. Implementare implicită: no-op.
         */
        fun onMacroMoveCursor(offset: Int) {}
        /**
         * Șterge caracterul DUPĂ cursor (forward delete).
         * Folosit de Legit Mode cursor correction. Implementare implicită: no-op.
         */
        fun onMacroDeleteForward() {}
    }

    fun isRunning() = isRunning

    fun toggle(context: Context) {
        if (isRunning) stop() else start(context)
    }

    fun start(context: Context) {
        if (isRunning) return
        isRunning = true

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

        // Verifică dacă toolbar-ul e EXPANDABLE (on) din setări
        toolbarWasOn = Settings.readToolbarMode(context.prefs()) == ToolbarMode.EXPANDABLE

        // Copiaza prefixul în clipboard DOAR dacă toolbar-ul era on.
        // Când e off, prefixul nu se mai lipeste pe mesajele urmatoare — clipboard-ul nu e necesar.
        if (toolbarWasOn) {
            inputPrefix?.let { prefix ->
                val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("macro_prefix", prefix))
            }
        }

        listener?.onMacroStart((inputPrefix != null || isBoldMode) && toolbarWasOn)
        listener?.onMacroCapsState(startedShifted)

        typingJob = scope.launch {
            val startDelay = context.prefs().getInt(Settings.PREF_MACRO_START_DELAY, 800).toLong()
            val startDelayDeferred = async { delay(startDelay) }
            val messages = withContext(Dispatchers.IO) { loadMessagesCached(context) }
            if (messages.isEmpty()) {
                Log.w(TAG, "No messages to send")
                isRunning = false
                startDelayDeferred.cancel()
                return@launch
            }
            cachedMessages = messages
            startDelayDeferred.await()
            runMacro(context, messages.toMutableList(), startedShifted, toolbarWasOn)
        }
    }

    fun stop() {
        isRunning = false
        typingJob?.cancel()
        typingJob = null
        inputPrefix = null
        isBoldMode = false
        toolbarWasOn = false
        cachedMessages = emptyList()
    }

    private suspend fun runMacro(context: Context, messages: MutableList<String>, capsOn: Boolean, toolbarWasOn: Boolean) {
        val prefs = context.prefs()
        val charDelay = prefs.getInt(Settings.PREF_MACRO_CHAR_DELAY, 80).toLong()
        val msgDelay = prefs.getInt(Settings.PREF_MACRO_MSG_DELAY, 3000).toLong()
        val legitMode = prefs.getBoolean(Settings.PREF_SHIFT_LEGIT_MODE, false)
        val legitDeleteDelay = prefs.getInt(Settings.PREF_LEGIT_DELETE_DELAY, 120).toLong()
        val legitPauseActions = prefs.getInt(Settings.PREF_LEGIT_PAUSE_ACTIONS, 40).toLong()
        val legitWriteDelay = prefs.getInt(Settings.PREF_LEGIT_WRITE_DELAY, 100).toLong()
        val legitTypos = prefs.getInt(Settings.PREF_LEGIT_TYPOS, 2)
        val legitCursorMode = prefs.getInt(Settings.PREF_LEGIT_CURSOR_MODE, Defaults.PREF_LEGIT_CURSOR_MODE)
        val legitCursorSpeed = prefs.getInt(Settings.PREF_LEGIT_CURSOR_SPEED, Defaults.PREF_LEGIT_CURSOR_SPEED).toLong()
        val randomPauseEnabled = prefs.getBoolean(Settings.PREF_MACRO_RANDOM_PAUSE_ENABLED, Defaults.PREF_MACRO_RANDOM_PAUSE_ENABLED)
        val randomPauseMaxMs = prefs.getInt(Settings.PREF_MACRO_RANDOM_PAUSE_MAX_MS, Defaults.PREF_MACRO_RANDOM_PAUSE_MAX_MS).toLong()
        val randomPauseCount = prefs.getInt(Settings.PREF_MACRO_RANDOM_PAUSE_COUNT, Defaults.PREF_MACRO_RANDOM_PAUSE_COUNT)

        messages.shuffle()
        var index = 0

        while (isRunning) {
            if (!isRunning) return

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
            // Lipeste prefixul pe mesajele 2, 3, 4... DOAR dacă toolbar-ul era on la start.
            // Când e off: primul mesaj are deja prefixul în câmp (tastat de user), restul fără.
            val needsPrefix = !prefix.isNullOrEmpty() && !isFirstMsg && toolbarWasOn

            if (!isFirstMsg) {
                if (needsPrefix) {
                    val p = if (capsOn) prefix!!.uppercase() else prefix!!
                    withContext(Dispatchers.Main) { listener?.onMacroPasteText(p) }
                    delay(250)
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

            // Tipărește mesajul caracter cu caracter
            val typoBudget = LegitMode.TypoBudget(legitTypos)
            val pausePositions: Set<Int> = if (randomPauseEnabled && randomPauseCount > 0 && randomPauseMaxMs > 0 && msg.isNotEmpty()) {
                (0 until msg.length).shuffled().take(randomPauseCount).toHashSet()
            } else emptySet()

            val msgPrefix = StringBuilder() // textul tastat pana la caracterul curent (pentru RETYPE_LINE)
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
                        cursorMode = legitCursorMode,
                        cursorSpeedDelay = legitCursorSpeed,
                        messagePrefix = msgPrefix.toString(),
                        isRunning = { isRunning },
                        typeChar = { c -> listener?.onMacroTypeChar(c) },
                        deleteChar = { listener?.onMacroDeleteChar() },
                        moveCursor = { offset -> listener?.onMacroMoveCursor(offset) },
                        deleteForward = { listener?.onMacroDeleteForward() }
                    )
                } else {
                    withContext(Dispatchers.Main) { listener?.onMacroTypeChar(charToType) }
                }
                msgPrefix.append(charToType)

                val d = when (charIndex) {
                    0 -> 120L
                    1 -> 100L
                    2 -> 90L
                    else -> charDelay
                }
                delay(d)
                if (charIndex in pausePositions) {
                    if (!isRunning) return
                    delay(Random.nextLong(0L, randomPauseMaxMs + 1L))
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

            delay(150)
            val maxWaitAfterSend = 3000L
            val pollInterval = 40L
            var waitedAfterSend = 0L
            while (isRunning && waitedAfterSend < maxWaitAfterSend) {
                delay(pollInterval)
                waitedAfterSend += pollInterval
                val txt = withContext(Dispatchers.Main) { listener?.getCurrentInputText() }
                if (txt == null) { isRunning = false; return }
                if (txt.isEmpty()) break
            }

            if (!isRunning) return
            delay(msgDelay)
        }
    }

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
            // Invalidăm cache-ul ca la pornirea următoare să se citească fișierul nou
            messagesFileCache = null
            messagesFileCacheMtime = -1L
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
