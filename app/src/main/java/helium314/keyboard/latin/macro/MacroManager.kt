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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

object MacroManager {

    private const val TAG = "MacroManager"
    const val MACRO_FILE_NAME = "macro_messages.txt"

    private var typingJob: Job? = null
    private var isRunning = false
    private val scope = CoroutineScope(Dispatchers.Default)

    // Text captured from input field at start time; cleared on stop
    private var inputPrefix: String? = null

    var listener: MacroListener? = null

    interface MacroListener {
        fun onMacroTypeChar(char: Char)
        fun onMacroSendMessage()
        fun onMacroPasteText(text: String)
        fun isShifted(): Boolean
        /** Returns current text in the input field, or null if unavailable */
        fun getCurrentInputText(): String?
    }

    fun isRunning() = isRunning

    fun toggle(context: Context) {
        if (isRunning) stop() else start(context)
    }

    fun start(context: Context) {
        if (isRunning) return
        val messages = loadMessages(context)
        if (messages.isEmpty()) {
            Log.w(TAG, "No messages to send")
            return
        }
        isRunning = true
        val startedShifted = listener?.isShifted() ?: false

        // Capture current input field text as prefix (replaces old clipboard approach)
        inputPrefix = listener?.getCurrentInputText()
            ?.trim()
            ?.takeIf { it.isNotEmpty() }

        typingJob = scope.launch {
            runMacro(context, messages.toMutableList(), startedShifted)
        }
    }

    fun stop() {
        isRunning = false
        typingJob?.cancel()
        typingJob = null
        inputPrefix = null
    }

    private suspend fun runMacro(context: Context, messages: MutableList<String>, capsOn: Boolean) {
        val prefs = context.prefs()
        messages.shuffle()
        var index = 0
        var isFirst = true

        while (isRunning) {
            val charDelay = prefs.getInt(Settings.PREF_MACRO_CHAR_DELAY, 80).toLong()
            val msgDelay = prefs.getInt(Settings.PREF_MACRO_MSG_DELAY, 3000).toLong()
            val startDelay = prefs.getInt(Settings.PREF_MACRO_START_DELAY, 800).toLong()

            if (isFirst) {
                delay(startDelay)
                isFirst = false
            }

            if (!isRunning) return

            // Only add prefix from the second message onwards (first message already has it in the field)
            val prefix = inputPrefix
            if (!prefix.isNullOrEmpty() && index > 0) {
                val textToSend = if (capsOn) prefix.uppercase() else prefix
                withContext(Dispatchers.Main) {
                    listener?.onMacroPasteText(textToSend)
                }
                if (!isRunning) return
                delay(200)
                withContext(Dispatchers.Main) {
                    listener?.onMacroSendMessage()
                }
                delay(msgDelay)
                if (!isRunning) return
            }

            if (index >= messages.size) {
                messages.shuffle()
                index = 0
            }

            var msg = messages[index]
            if (capsOn) msg = msg.uppercase()
            index++

            for (char in msg) {
                if (!isRunning) return
                withContext(Dispatchers.Main) {
                    listener?.onMacroTypeChar(char)
                }
                delay(charDelay)
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
            file.readText()
                .split(Regex("\n\n+|\r\n\r\n+"))
                .map { it.trim() }
                .filter { it.isNotEmpty() }
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
}
