// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.macro

import android.content.Context
import android.net.Uri
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

object MacroManager {

    private const val TAG = "MacroManager"
    const val MACRO_FILE_NAME = "macro_messages.txt"

    private var typingJob: Job? = null
    private var isRunning = false

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

    // Cache-ul mesajelor — incarcate o singura data la start, nu la fiecare iteratie din loop
    private var cachedMessages: List<String> = emptyList()

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

        // Copiaza prefixul in clipboard ca sa poata fi lipit rapid la mesajele urmatoare
        inputPrefix?.let { prefix ->
            val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("macro_prefix", prefix))
        }

        listener?.onMacroStart(inputPrefix != null || isBoldMode)
        listener?.onMacroCapsState(startedShifted)

        typingJob = scope.launch {
            // Pornim delay-ul de start in paralel cu incarcarea fisierului — daca userul a pus
            // 0ms, nu vrem ca parse-ul unui fisier de 15MB+ sa adauge un delay "ascuns" la start.
            val startDelay = context.prefs().getInt(Settings.PREF_MACRO_START_DELAY, 800).toLong()
            val startDelayDeferred = async { delay(startDelay) }
            // Incarca mesajele pe thread IO — evita lag-ul pe fisiere mari (15MB+)
            val messages = withContext(Dispatchers.IO) { loadMessages(context) }
            if (messages.isEmpty()) {
                Log.w(TAG, "No messages to send")
                isRunning = false
                startDelayDeferred.cancel()
                return@launch
            }
            cachedMessages = messages
            startDelayDeferred.await()
            runMacro(context, messages.toMutableList(), startedShifted)
        }
    }

    fun stop() {
        isRunning = false
        typingJob?.cancel()
        typingJob = null
        inputPrefix = null
        isBoldMode = false
        cachedMessages = emptyList()
    }

    private suspend fun runMacro(context: Context, messages: MutableList<String>, capsOn: Boolean) {
        val prefs = context.prefs()
        // Citim delay-urile o data la start — daca userul le schimba in timp ce ruleaza, se aplica la urmatoarea pornire
        val charDelay = prefs.getInt(Settings.PREF_MACRO_CHAR_DELAY, 80).toLong()
        val msgDelay = prefs.getInt(Settings.PREF_MACRO_MSG_DELAY, 3000).toLong()
        // startDelay e deja aplicat in start(), in paralel cu incarcarea fisierului

        messages.shuffle()
        var index = 0

        while (isRunning) {
            if (!isRunning) return

            if (index >= messages.size) {
                messages.shuffle()
                index = 0
            }

            var msg = messages[index]
            if (capsOn) msg = msg.uppercase()
            index++

            val isFirstMsg = index == 1
            val prefix = inputPrefix
            val needsPrefix = !prefix.isNullOrEmpty() && !isFirstMsg

            // Build what to type before the message content
            // First message: prefix is already in field (possibly with **), just type msg + ** (if bold)
            // Later messages: paste prefix (if any), type ** (if bold), type msg, type **
            if (!isFirstMsg) {
                // Paste prefix if exists
                if (needsPrefix) {
                    val p = if (capsOn) prefix!!.uppercase() else prefix!!
                    withContext(Dispatchers.Main) { listener?.onMacroPasteText(p) }
                    delay(150)
                    if (!isRunning) return
                }
                // Type opening ** if bold mode — switch to symbols, type **, switch back
                if (isBoldMode) {
                    // Retine caps state inainte de switch (switch-ul il reseteaza)
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
                    // Re-aplica caps dupa switch back
                    if (capsBeforeOpen) {
                        withContext(Dispatchers.Main) { listener?.onMacroCapsState(true) }
                        delay(charDelay)
                    }
                }
            }

            // Type the message with ramp-up delays
            // isCapsLocked() per caracter — reflecta starea reala a tastaturii
            // daca userul dezactiveaza caps manual, scrie lowercase imediat
            for ((charIndex, char) in msg.withIndex()) {
                if (!isRunning) return
                val capsNow = listener?.isCapsLocked() ?: false
                val shiftedNow = listener?.isShifted() ?: false
                val charToType = if (capsNow || shiftedNow) char.uppercaseChar() else char.lowercaseChar()
                withContext(Dispatchers.Main) { listener?.onMacroTypeChar(charToType) }
                val d = when (charIndex) {
                    0 -> 120L
                    1 -> 100L
                    2 -> 90L
                    else -> charDelay
                }
                delay(d)
            }

            // Type closing ** if bold mode — switch to symbols, type **, switch back
            if (isBoldMode) {
                // Retine caps state inainte de switch la symbols (switch-ul il reseteaza)
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
                // Re-aplica caps dupa switch back — Android/HeliBoard reseteaza shift-ul
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

            delay(msgDelay)
        }
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

    fun getMessageCount(context: Context): Int = loadMessages(context).size

    fun getMacroKeyword(context: Context): String =
        context.prefs().getString(Settings.PREF_MACRO_KEYWORD, "macro1") ?: "macro1"
}
