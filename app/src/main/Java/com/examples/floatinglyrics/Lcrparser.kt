package com.example.floatinglyrics

data class LyricLine(
    val startTimeMs: Long, 
    val text: String
)

object LrcParser {
    fun parse(lrcString: String): List<LyricLine> {
        // Matches the format [MM:SS.xx] or [MM:SS.xxx]
        val regex = Regex("\\[(\\d{2}):(\\d{2})\\.(\\d{2,3})\\] (.*)")
        val lines = mutableListOf<LyricLine>()
        
        lrcString.lines().forEach { line ->
            val match = regex.find(line)
            if (match != null) {
                val mins = match.groupValues[1].toLong()
                val secs = match.groupValues[2].toLong()
                // Pad with zeros (e.g., .12 becomes 120ms)
                val millis = match.groupValues[3].padEnd(3, '0').toLong()
                val text = match.groupValues[4]
                
                val totalMillis = (mins * 60 * 1000) + (secs * 1000) + millis
                lines.add(LyricLine(totalMillis, text))
            }
        }
        return lines
    }
}
