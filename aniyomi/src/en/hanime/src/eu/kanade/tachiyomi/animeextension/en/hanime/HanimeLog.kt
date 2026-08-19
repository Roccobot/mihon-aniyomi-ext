package eu.kanade.tachiyomi.animeextension.en.hanime

import android.app.Application
import android.util.Log
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The trace of what actually happens, because the interesting failure is the silent one: a
 * player that sits still without an error says nothing, and from outside the device there is
 * no way to watch this source work.
 *
 * Three ways out, on purpose, from the most to the least convenient on a phone:
 *  1. searching `debug` in this source returns one entry whose description is the whole log,
 *     so it is readable with no cable, no file manager and no permission;
 *  2. a file under the app's own external files dir, when the preference is on, so a trace
 *     survives an app restart;
 *  3. logcat, for whoever has a cable attached.
 */
object HanimeLog {

    private const val TAG = "HanimeRoccobot"
    private const val MAX_LINES = 400
    const val FILE_NAME = "hanime-debug.log"

    private val lines = ArrayDeque<String>()
    private val stamp = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    /** Set by the source from its preferences: file mirroring is off unless asked for. */
    @Volatile
    var toFile = false

    fun log(message: String) {
        val line = "${stamp.format(Date())}  $message"
        synchronized(lines) {
            lines.addLast(line)
            // A ring buffer, not a growing list: this object outlives a single playback
            // attempt, and an unbounded log in a long-running app is a leak.
            while (lines.size > MAX_LINES) lines.removeFirst()
        }
        Log.d(TAG, message)
        if (toFile) appendToFile(line)
    }

    fun dump(): String = synchronized(lines) {
        if (lines.isEmpty()) "Nothing logged yet. Open an episode and press play, then read this again." else lines.joinToString("\n")
    }

    fun clear() {
        synchronized(lines) { lines.clear() }
        runCatching { logFile()?.delete() }
    }

    fun filePath(): String = logFile()?.absolutePath ?: "(no external files dir)"

    private fun appendToFile(line: String) {
        // Failing to write must never break playback: a diagnostic that can take the source
        // down with it is worse than no diagnostic.
        runCatching { logFile()?.appendText(line + "\n") }
    }

    private fun logFile(): File? =
        Injekt.get<Application>().getExternalFilesDir(null)?.let { File(it, FILE_NAME) }
}
