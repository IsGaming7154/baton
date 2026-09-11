package dev.baton.director

/**
 * One line of subtitle text, covering `[startMs, endMs)` of the content timeline.
 */
data class SubtitleLine(val startMs: Long, val endMs: Long, val text: String)

/**
 * Parses a subtitle file -- SubRip (.srt) or WebVTT (.vtt) -- into the lines a
 * spoken-subtitle track is booked from (issue #5): each line is spoken later, in
 * the silence after the line it translates, so the booking needs every line known
 * in advance rather than delivered as they appear.
 *
 * Hand-written rather than borrowed: `:director` is a pure JVM module, and Media3's
 * own `SubripParser` imports `android.text.Html`, which is to say it needs Android,
 * which this module deliberately does not.
 *
 * WebVTT is detected by its `WEBVTT` header (after an optional BOM); anything else
 * is treated as SubRip. The two formats differ only in timestamp punctuation and
 * in the ornaments WebVTT allows -- cue identifiers, cue settings, `NOTE`/`STYLE`/
 * `REGION` blocks -- none of which survive into a [SubtitleLine]. A cue whose text
 * is empty once markup is stripped is skipped. A malformed timing line, or a cue
 * that ends before it starts, throws [IllegalArgumentException] naming the cue's
 * position in the file; refusing bad input at the boundary is this module's style.
 */
fun parseSubtitles(source: String): List<SubtitleLine> {
    val lines = source.removePrefix("\uFEFF").split(Regex("""\r\n|\r|\n"""))
    val parsed = mutableListOf<SubtitleLine>()
    var block = mutableListOf<Pair<Int, String>>()

    fun flush() {
        if (block.isNotEmpty()) parseCue(block, parsed)
        block = mutableListOf()
    }

    for ((index, line) in lines.withIndex()) {
        if (line.isBlank()) flush() else block += (index + 1) to line
    }
    flush()
    return parsed
}

/** A block is a cue if it carries a `-->` timing line; these first words mean it doesn't. */
private val SKIPPED_BLOCK_PREFIXES = listOf("WEBVTT", "NOTE", "STYLE", "REGION")

/** `start --> end`, SRT commas or VTT dots, VTT hours optional, cue settings ignored. */
private val CUE_TIMING = Regex(
    """^\s*(?:(\d{1,3}):)?(\d{2}):(\d{2})[.,](\d{3})\s*-->\s*(?:(\d{1,3}):)?(\d{2}):(\d{2})[.,](\d{3})""",
)

private fun parseCue(block: List<Pair<Int, String>>, out: MutableList<SubtitleLine>) {
    val firstLine = block.first().second.trim()
    if (SKIPPED_BLOCK_PREFIXES.any(firstLine::startsWith)) return
    val timingIndex = block.indexOfFirst { it.second.contains("-->") }
    if (timingIndex < 0) return

    val (lineNo, timingLine) = block[timingIndex]
    val timing = CUE_TIMING.find(timingLine)
        ?: throw IllegalArgumentException(
            "subtitle cue at line $lineNo has a malformed timing line: '${timingLine.trim()}'",
        )
    val startMs = timing.toMs(firstGroup = 1)
    val endMs = timing.toMs(firstGroup = 5)
    require(endMs > startMs) {
        "subtitle cue at line $lineNo ends at ${endMs}ms, before it starts at ${startMs}ms"
    }

    val spoken = decodeEntities(
        block.subList(timingIndex + 1, block.size)
            .joinToString(" ") { it.second }
            .replace(Regex("""\{[^}]*}"""), "")   // SSA overrides, e.g. {\an8}
            .replace(Regex("""<[^>]*>"""), ""),   // markup tags, e.g. <i>, </b>, <c.x>, <v Name>
    ).trim()
    if (spoken.isNotEmpty()) out += SubtitleLine(startMs, endMs, spoken)
}

private fun MatchResult.toMs(firstGroup: Int): Long {
    val hours = groups[firstGroup]?.value?.toLong() ?: 0L
    val minutes = groups[firstGroup + 1]!!.value.toLong()
    val seconds = groups[firstGroup + 2]!!.value.toLong()
    val millis = groups[firstGroup + 3]!!.value.toLong()
    return (hours * 60 + minutes) * 60_000 + seconds * 1_000 + millis
}

/** `&amp;` last, so `&amp;lt;` decodes to `&lt;` and not `<`. */
private fun decodeEntities(text: String): String = text
    .replace("&lt;", "<")
    .replace("&gt;", ">")
    .replace("&nbsp;", " ")
    .replace("&amp;", "&")
