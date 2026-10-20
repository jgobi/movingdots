package io.github.jgobi.movingdots

import java.io.File

/** Local log of post-ride MISC ratings, one CSV line per ride: epochMillis,score,dotsOn. */
class RideLog(private val file: File) {
    class Entry(val timeMillis: Long, val score: Int, val dotsOn: Boolean)

    /** All entries, newest first. */
    fun entries(): List<Entry> {
        if (!file.exists()) return emptyList()
        val out = ArrayList<Entry>()
        file.forEachLine { line ->
            val parts = line.split(',')
            if (parts.size == 3) {
                val time = parts[0].toLongOrNull() ?: return@forEachLine
                val score = parts[1].toIntOrNull() ?: return@forEachLine
                out += Entry(time, score, parts[2] == "1")
            }
        }
        out.reverse()
        return out
    }

    fun add(score: Int, dotsOn: Boolean) {
        file.appendText("${System.currentTimeMillis()},$score,${if (dotsOn) 1 else 0}\n")
    }

    fun clear() {
        file.delete()
    }
}
