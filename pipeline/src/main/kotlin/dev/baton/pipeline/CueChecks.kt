package dev.baton.pipeline

import dev.baton.director.Cue
import dev.baton.director.Gap

/**
 * The description style rules a machine can check, run over every cue the builder
 * is about to emit (issue #12). The rest of the rules live in
 * [DraftingPrompt.STYLE_RULES] and are the evaluation harness's to judge.
 *
 * A violation throws, through `check`, rather than becoming an [AuthoringDrop].
 * A drop says "nothing could be written here" and the build still succeeds; these
 * say "something was written that must not ship", which is the same kind of fault
 * as the runtime-admission contract the builder already enforces with `check`, and
 * a track carrying it should not exist to be copied onto a device. Every message
 * names the cue, the rule, and the offending text or span.
 */
object CueChecks {

    /**
     * Rule (2): a cue's playable span -- its start plus its longest variant's
     * measured length, the variant the runtime is free to choose -- must not
     * intersect a span of dialogue.
     *
     * The builder's placement already guarantees this: a cue is placed inside a
     * window of the gap map, which is the complement of these same spans, and
     * `assertRuntimeWillAdmit` holds its end inside that window. So this is a guard
     * against a regression in either, stated against the dialogue itself rather
     * than against the map derived from it.
     */
    fun checkNoDialogueOverlap(cues: List<Cue>, dialogue: List<Gap>) {
        for (cue in cues) {
            val endMs = cue.startMs + cue.variants.maxOf { it.measuredDuration.millis }
            val span = dialogue.firstOrNull { cue.startMs < it.endMs && endMs > it.startMs } ?: continue
            error("cue ${cue.id} breaks never-over-dialogue: it plays ${cue.startMs}-$endMs ms over dialogue at ${span.startMs}-${span.endMs} ms")
        }
    }

    /**
     * Rule (3): no variant refers to another cue, a description number, or the
     * prompt and the process that generated it. Matched case-insensitively, whole
     * words only.
     *
     * Plain `\b`, `\s`, `\d` and alternation, nothing engine-specific. `model` and
     * `generated` can be innocent in a film ("a model ship"); a false positive here
     * fails loudly and gets reworded, which is the cheap direction to be wrong in.
     */
    val SELF_REFERENCE_PATTERNS: List<Regex> = listOf(
        // "cue 2", "description 3", "description #4"
        """\b(cue|description)\s*#?\d+""",
        // "#4"
        """#\d+""",
        // "as described above", "mentioned earlier"
        """\b(described|mentioned) (above|below|earlier|before|previously)\b""",
        // "previous description", "next cue"
        """\b(previous|last|next|earlier|prior|above) (cue|description)s?\b""",
        // The generation process.
        """\bas an ai\b""",
        """\bprompts?\b""",
        """\bgenerated\b""",
        """\bmodels?\b""",
        """\bi cannot\b""",
    ).map { Regex(it, RegexOption.IGNORE_CASE) }

    fun checkNoSelfReference(cues: List<Cue>) {
        for (cue in cues) {
            for (variant in cue.variants) {
                val hit = SELF_REFERENCE_PATTERNS.firstNotNullOfOrNull { it.find(variant.text) } ?: continue
                error("cue ${cue.id} breaks no-self-reference: \"${hit.value}\" in \"${variant.text}\"")
            }
        }
    }

    /** Capitalised words that are never a character's name. */
    val NAME_STOPLIST: Set<String> = setOf("I")

    /**
     * Rule (4): characters stay unnamed until the film names them. A capitalised
     * word that does not open a sentence and is not in [NAME_STOPLIST] is taken to
     * be a name, and the transcript must have finished saying it, spelled the same
     * way, before the cue starts.
     *
     * ponytail: capitalisation-as-name heuristic. Ceiling: a proper noun that is not
     * a character (a place, "Christmas") fails unless the film says it first; a name
     * that opens a sentence is never seen; a name the transcriber spells or cases
     * differently fails. Upgrade path: have the drafter return the characters it
     * named alongside the text, and match those against a speaker/entity list from
     * the transcript instead of guessing from case.
     */
    fun checkNamesAfterTranscript(cues: List<Cue>, transcript: Transcript) {
        val firstSpokenEndMs = mutableMapOf<String, Long>()
        for (word in transcript.words.sortedBy { it.startMs }) {
            firstSpokenEndMs.putIfAbsent(bareWord(word.text), word.endMs)
        }
        for (cue in cues) {
            for (variant in cue.variants) {
                for (name in namesIn(variant.text)) {
                    val spokenEndMs = firstSpokenEndMs[name]
                    if (spokenEndMs != null && spokenEndMs <= cue.startMs) continue
                    val said = spokenEndMs?.let { "not until $it ms" } ?: "never"
                    error("cue ${cue.id} breaks unnamed-until-named: \"$name\" at ${cue.startMs} ms, which the transcript says $said, in \"${variant.text}\"")
                }
            }
        }
    }

    private fun namesIn(text: String): List<String> {
        val names = mutableListOf<String>()
        var sentenceStart = true
        for (raw in text.split(WHITESPACE)) {
            if (raw.isEmpty()) continue
            val word = bareWord(raw)
            if (word.isNotEmpty() && !sentenceStart && word.first().isUpperCase() && word !in NAME_STOPLIST) {
                names += word
            }
            sentenceStart = raw.trimEnd('"', '\'', ')', '’', '”').lastOrNull().let { it != null && it in SENTENCE_ENDINGS }
        }
        return names
    }

    /** Punctuation off both ends, and a possessive off the tail: `"Sintel's,` is `Sintel`. */
    private fun bareWord(raw: String): String =
        raw.trim { !it.isLetterOrDigit() }.removeSuffix("'s").removeSuffix("’s")

    private val WHITESPACE = Regex("\\s+")
    private const val SENTENCE_ENDINGS = ".!?"
}
