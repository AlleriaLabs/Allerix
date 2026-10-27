package com.example.myjelly

data class SubCue(val startMs: Long, val endMs: Long, val text: String)

/** Reads text subtitle files into timed lines. Supports SRT, WebVTT, ASS/SSA and MicroDVD (.sub). */
object SubParser {
    private val timeRe = Regex("""(?:(\d+):)?(\d{1,2}):(\d{1,2})[.,](\d{1,3})""")
    private val assTimeRe = Regex("""(\d+):(\d{1,2}):(\d{1,2})[.](\d{1,2})""")
    private val tagRe = Regex("""<[^>]*>|\{\\[^}]*\}""")
    private val microRe = Regex("""^\{(\d+)\}\{(\d+)\}(.*)$""")

    /** Returns null when the text isn't a supported text format (for example image-based VobSub). */
    fun parse(raw: String, defaultFps: Float = 23.976f): List<SubCue>? {
        val text = raw.removePrefix("\uFEFF").replace("\r\n", "\n").replace('\r', '\n')
        val head = text.take(800)
        val cues = when {
            head.contains("[Script Info]", ignoreCase = true) || text.contains("\nDialogue:") -> parseAss(text)
            head.trimStart().startsWith("WEBVTT") -> parseTimed(text)
            microRe.containsMatchIn(head.lineSequence().firstOrNull { it.isNotBlank() } ?: "") -> parseMicroDvd(text, defaultFps)
            text.contains("-->") -> parseTimed(text)
            else -> return null
        }
        return if (cues.isEmpty()) null else cues.sortedBy { it.startMs }
    }

    private fun ms(m: MatchResult): Long {
        val h = m.groupValues[1].ifEmpty { "0" }.toLong()
        val mi = m.groupValues[2].toLong()
        val s = m.groupValues[3].toLong()
        val frac = m.groupValues[4].padEnd(3, '0').take(3).toLong()
        return ((h * 60 + mi) * 60 + s) * 1000 + frac
    }

    private fun clean(s: String): String =
        s.replace(tagRe, "").replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").trim()

    private fun parseTimed(text: String): List<SubCue> {
        val out = ArrayList<SubCue>()
        for (block in text.split(Regex("\n\\s*\n"))) {
            val lines = block.split("\n")
            val ai = lines.indexOfFirst { it.contains("-->") }
            if (ai < 0) continue
            val parts = lines[ai].split("-->")
            if (parts.size < 2) continue
            val a = timeRe.find(parts[0]) ?: continue
            val b = timeRe.find(parts[1]) ?: continue
            val body = clean(lines.drop(ai + 1).joinToString("\n"))
            if (body.isNotEmpty()) out.add(SubCue(ms(a), ms(b), body))
        }
        return out
    }

    private fun assMs(s: String): Long {
        val m = assTimeRe.find(s.trim()) ?: return -1L
        val h = m.groupValues[1].toLong(); val mi = m.groupValues[2].toLong(); val sec = m.groupValues[3].toLong()
        val cs = m.groupValues[4].padEnd(2, '0').take(2).toLong()
        return ((h * 60 + mi) * 60 + sec) * 1000 + cs * 10
    }

    private fun parseAss(text: String): List<SubCue> {
        val out = ArrayList<SubCue>()
        var fields = listOf("Layer", "Start", "End", "Style", "Name", "MarginL", "MarginR", "MarginV", "Effect", "Text")
        for (line in text.split("\n")) {
            val t = line.trim()
            if (t.startsWith("Format:", ignoreCase = true) && t.contains("Start") && t.contains("Text")) {
                fields = t.substringAfter(":").split(",").map { it.trim() }
            } else if (t.startsWith("Dialogue:", ignoreCase = true)) {
                val parts = t.substringAfter(":").trim().split(",", limit = fields.size)
                val si = fields.indexOf("Start"); val ei = fields.indexOf("End"); val ti = fields.indexOf("Text")
                if (si < 0 || ei < 0 || ti < 0 || parts.size <= maxOf(si, ei, ti)) continue
                val start = assMs(parts[si]); val end = assMs(parts[ei])
                if (start < 0 || end < 0) continue
                val body = parts[ti].replace("\\N", "\n").replace("\\n", "\n").replace("\\h", " ")
                val cleaned = clean(body)
                if (cleaned.isNotEmpty()) out.add(SubCue(start, end, cleaned))
            }
        }
        return out
    }

    private fun parseMicroDvd(text: String, defaultFps: Float): List<SubCue> {
        val out = ArrayList<SubCue>()
        var fps = defaultFps
        var first = true
        for (line in text.split("\n")) {
            val m = microRe.find(line.trim()) ?: continue
            val a = m.groupValues[1].toLong(); val b = m.groupValues[2].toLong(); val body = m.groupValues[3]
            if (first) {
                first = false
                val f = body.trim().toFloatOrNull()
                if (f != null && f in 5f..120f) { fps = f; continue }
            }
            val cleaned = clean(body.replace("|", "\n").replace(Regex("""\{[^}]*\}"""), ""))
            if (cleaned.isNotEmpty()) out.add(SubCue((a * 1000f / fps).toLong(), (b * 1000f / fps).toLong(), cleaned))
        }
        return out
    }

    /** Index of the last cue whose start is at or before [t], or -1. */
    fun indexAt(cues: List<SubCue>, t: Long): Int {
        var lo = 0; var hi = cues.size - 1; var ans = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (cues[mid].startMs <= t) { ans = mid; lo = mid + 1 } else hi = mid - 1
        }
        return ans
    }

    fun activeText(cues: List<SubCue>, t: Long): String {
        val i = indexAt(cues, t)
        if (i < 0) return ""
        val sb = ArrayList<String>()
        if (i >= 1 && t <= cues[i - 1].endMs) sb.add(cues[i - 1].text)
        if (t <= cues[i].endMs) sb.add(cues[i].text)
        return sb.joinToString("\n")
    }
}
