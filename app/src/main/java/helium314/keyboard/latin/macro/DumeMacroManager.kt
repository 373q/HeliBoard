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

    fun isRunning() = isRunning

    fun toggle(context: Context) {
        if (isRunning) stop() else start(context)
    }

    fun start(context: Context) {
        if (isRunning) return
        isRunning = true

        val startedShifted = listener?.isShifted() ?: false

        typingJob = scope.launch {
            val startDelay = context.prefs().getInt(Settings.PREF_DUME_START_DELAY, 800).toLong()
            val startDelayDeferred = async { delay(startDelay) }

            val groups = withContext(Dispatchers.IO) { loadGroups(context) }
            if (groups.isEmpty()) {
                Log.w(TAG, "No dume message groups to send")
                isRunning = false
                startDelayDeferred.cancel()
                return@launch
            }
            startDelayDeferred.await()
            runDumeMacro(context, groups.toMutableList(), startedShifted)
        }
    }

    fun stop() {
        isRunning = false
        typingJob?.cancel()
        typingJob = null
    }

    private suspend fun runDumeMacro(
        context: Context,
        groups: MutableList<List<String>>,
        capsOn: Boolean
    ) {
        val prefs = context.prefs()
        val charDelay = prefs.getInt(Settings.PREF_DUME_CHAR_DELAY, 80).toLong()
        val msgDelay = prefs.getInt(Settings.PREF_DUME_MSG_DELAY, 3000).toLong()
        val legitMode = prefs.getBoolean(Settings.PREF_DUME_LEGIT_MODE, false)

        // Shuffle grupurile
        groups.shuffle()
        var groupIndex = 0
        var lineIndexInGroup = 0

        while (isRunning) {
            if (!isRunning) return

            // Auto-stop dacă tastatura a pierdut focusul (getCurrentInputText() == null)
            val inputAvailable = withContext(Dispatchers.Main) {
                listener?.getCurrentInputText() != null
            }
            if (!inputAvailable) {
                Log.w(TAG, "Dume: input unavailable, stopping macro")
                isRunning = false
                return
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

            // Tipărește linia caracter cu caracter
            for ((charIndex, char) in line.withIndex()) {
                if (!isRunning) return
                val capsNow = listener?.isCapsLocked() ?: false
                val shiftedNow = listener?.isShifted() ?: false
                val charToType = if (capsNow || shiftedNow) char.uppercaseChar() else char.lowercaseChar()

                if (legitMode && char.isLetter()) {
                    LegitMode.typeCharWithPossibleTypo(
                        correctChar = charToType,
                        charDelay = charDelay,
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

            delay(msgDelay)
        }
    }

    /**
     * Parsează fișierul dume în grupe de mesaje.
     * Fiecare linie non-goală = un mesaj trimis verbatim.
     * Grupele sunt separate de linii goale.
     */
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

    fun getGroupCount(context: Context): Int = loadGroups(context).size

    fun getMessageCount(context: Context): Int = loadGroups(context).sumOf { it.size }
}
