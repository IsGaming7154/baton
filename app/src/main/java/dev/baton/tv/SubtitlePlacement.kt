package dev.baton.tv

import dev.baton.director.SilenceWindows
import dev.baton.director.SubtitleLine

/**
 * Where a spoken subtitle's booking starts on the content timeline.
 *
 * Each line is spoken in the silence after the line it translates, so the
 * question is which silence: the line itself was dialogue, and the gap map says
 * where the film stops talking. This is placement, not arbitration -- a cue
 * whose booking cannot hold it is still handed to the director, which drops it
 * and says so. Placement never hides a cue; the director decides and reports.
 *
 * Pure, and deliberately outside `TrackPreparation`: the rule is worth testing
 * without an engine, a context, or a device.
 */
object SubtitlePlacement {

    /** The start booked for every line, in file order. */
    fun startsFor(lines: List<SubtitleLine>, silence: SilenceWindows): List<Long> =
        lines.mapIndexed { index, line -> placedStartMs(line, lines.getOrNull(index + 1), silence) }

    /**
     * The start booked for one line, given the line that follows it (null for
     * the file's last line).
     *
     * [SubtitleLine.endMs] if that instant is already inside a silence window;
     * otherwise the start of the first window that opens after it, provided it
     * opens before the next line starts -- the translation is due when the next
     * line has begun, and a window behind that point is one it cannot use. The
     * last line has no next line, so any later window serves. Otherwise
     * [SubtitleLine.endMs] again: a start over dialogue the director will drop
     * and report as `NoSilenceAtCueStart`, which is what the cue and the viewer
     * are owed rather than a cue that never existed.
     */
    fun placedStartMs(line: SubtitleLine, next: SubtitleLine?, silence: SilenceWindows): Long {
        if (silence.windowEndAt(line.endMs) != null) return line.endMs

        val window = silence.windows.firstOrNull { it.startMs > line.endMs }
        if (window != null && (next == null || window.startMs < next.startMs)) return window.startMs

        return line.endMs
    }
}
