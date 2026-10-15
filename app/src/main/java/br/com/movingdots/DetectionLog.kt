package br.com.movingdots

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Diagnostic log of automatic detection: what Play services reported and what the app decided.
 * Shown in the app (with a copy button) so a ride can be analysed without adb; also mirrored to
 * logcat under [TAG]. One line per entry: loggedMillis<TAB>message.
 */
class DetectionLog(context: Context) {
    private val file = File(context.filesDir, "detection_log.txt")

    fun add(message: String) {
        Log.i(TAG, message)
        file.appendText("${System.currentTimeMillis()}\t${message.replace('\n', ' ')}\n")
        // Appending is cheap; trimming rewrites the file, so only do it once in a while.
        if (file.length() > TRIM_BYTES) file.writeText(file.readLines().takeLast(MAX_ENTRIES).joinToString("\n", postfix = "\n"))
    }

    /** Changes whenever an entry is added or the log is cleared (0 when empty). */
    fun lastModified(): Long = file.lastModified()

    /** Formatted lines, newest first. */
    fun lines(): List<String> {
        if (!file.exists()) return emptyList()
        return file.readLines().mapNotNull { line ->
            val tab = line.indexOf('\t')
            val millis = line.substring(0, maxOf(tab, 0)).toLongOrNull() ?: return@mapNotNull null
            "${time(millis)}  ${line.substring(tab + 1)}"
        }.reversed()
    }

    fun clear() {
        file.delete()
    }

    companion object {
        const val TAG = "MotionCueAuto"
        private const val MAX_ENTRIES = 1000
        /** About 1.5x the size of [MAX_ENTRIES] typical lines. */
        private const val TRIM_BYTES = 150_000L

        fun time(millis: Long): String = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(Date(millis))
    }
}
