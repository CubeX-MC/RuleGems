package org.cubexmc.manager

import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.logging.Logger

/** Two streaming passes per selected file keep memory proportional to the requested page. */
internal class HistoryFileReader(private val directory: File, private val logger: Logger) {
    fun read(page: Int, size: Int, matches: (String) -> Boolean): HistoryLogger.HistoryPage {
        val offset = (page.coerceAtLeast(1).toLong() - 1L) * size.coerceAtLeast(0)
        val end = offset + size.coerceAtLeast(0)
        var total = 0L
        val entries = ArrayList<String>()
        val files = directory.listFiles { _, name -> name.endsWith(".log") }.orEmpty().sortedByDescending { it.name }
        for (file in files) {
            try {
                val count = Files.newBufferedReader(file.toPath(), StandardCharsets.UTF_8).use { reader ->
                    reader.lineSequence().filter(matches).fold(0L) { count, _ -> count + 1L }
                }
                val startInFile = (offset - total).coerceAtLeast(0L)
                val endInFile = (end - total).coerceAtMost(count)
                if (startInFile < endInFile) {
                    entries.addAll(readWindow(file, count - endInFile, count - startInFile, matches).asReversed())
                }
                total += count
            } catch (failure: IOException) {
                logger.warning("Cannot read history file ${file.name}: ${failure.message}")
            }
        }
        return HistoryLogger.HistoryPage(entries, total.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
    }

    private fun readWindow(file: File, start: Long, end: Long, matches: (String) -> Boolean): List<String> {
        val entries = ArrayList<String>()
        Files.newBufferedReader(file.toPath(), StandardCharsets.UTF_8).use { reader ->
            var index = 0L
            for (line in reader.lineSequence().filter(matches)) {
                if (index >= end) break
                if (index >= start) entries.add(line)
                index++
            }
        }
        return entries
    }
}
