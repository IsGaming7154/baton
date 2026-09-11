package dev.baton.director

import kotlin.math.abs

/**
 * How far to pull the content down so that a description can be understood over it.
 *
 * ## Why a static number was always wrong
 *
 * The old answer was -12 dB, everywhere, for everything. It is the number sound
 * engineers reach for first, and the research is blunt about what it is: an
 * initial position that a professional then rides the fader away from, cue by
 * cue, according to how loud the film happens to be underneath. Left static it is
 * wrong in both directions at once. Under an explosion, 12 dB is nowhere near
 * enough and the description is masked. Under a quiet room tone, 12 dB tears an
 * unnatural hole in the soundscape, and the viewer hears the mechanism instead of
 * the film.
 *
 * ## What actually has to be held constant
 *
 * Not the dip. The **loudness difference** between the narration and the bed it
 * is heard against -- the quantity intelligibility genuinely depends on.
 * Broadcast research on commentary over music puts the minimum at 10 to 15 LU,
 * and over broadband ambience at 15 LU or more, ambience being harder to hear
 * through because it competes across the whole spectrum rather than at
 * particular pitches.
 *
 * Since the narration is now anchored at a known level -- `SpeechClarity` renders
 * every utterance to -27 LKFS, so it is a constant and not a variable -- the dip
 * that achieves a given difference is arithmetic:
 *
 * ```
 * dip = narrationLkfs + monoToStereoOffsetDb - targetLoudnessDifferenceLu - programmeLufs
 * ```
 *
 * The narration enters that sum as it is *heard*, not as it is measured. It is
 * rendered and measured as mono, but a mono `AudioTrack` on a stereo output is
 * duplicated to both channels at full level, which a BS.1770 meter reads
 * `10*log10(2)` LU hotter than the mono measurement -- see
 * [DirectorConfig.narrationMonoToStereoOffsetDb] for the AOSP citations. Adding
 * the offset is what makes the voice sit the configured distance above the bed
 * as actually reproduced; without it every dip is about 3 dB too deep.
 *
 * A loud passage gets a deep dip and a quiet one gets a shallow dip, from one
 * rule, with no bands and no boundaries to jump across mid-scene.
 *
 * ## How this sits against the published dip matrix
 *
 * The same body of research also publishes a three-band table, derived by
 * quantile regression against listener testing: -19 dB under loud programme
 * audio, -9 dB under normal, -3 dB under quiet, and no ducking at all below
 * -48 LUFS. This rule is deliberately not that table.
 *
 * The two agree on range and on shape. At the default 12.5 LU -- the midpoint of
 * the recommended band -- the rule returns -19 dB at a programme loudness of
 * about -17.49 LUFS, -9 dB at about -27.49, and -3 dB at about -33.49: the
 * same three values, spread across the range real programme material occupies,
 * either side of the -26.6 LUFS mean the study reports. That is a consistency
 * check and not a reproduction, because the study does not publish where its
 * band boundaries fall, so there is no way to confirm that a given programme
 * lands in the band whose value the rule matches.
 *
 * What decides it is that the loudness difference is the causal quantity and the
 * table is a discretisation of it. Three fixed dips have to assume a distribution
 * of programme loudness; the rule assumes nothing, has no discontinuity at a
 * band edge, and degrades sensibly on content the bands never anticipated. It
 * also reaches zero dip on its own, at about -36.49 LUFS, without needing the
 * near-silent threshold as a separate constant -- near where the study puts it,
 * and for the same reason: there is nothing there to duck.
 *
 * ## Where it stops
 *
 * Two clamps, at both ends.
 *
 * It never boosts. A rule that returns a positive number under quiet content
 * would be turning the film *up* to sit under the narration, which is not
 * ducking and is not what anyone asked for. [ContentDuck.Ducked] rejects a
 * non-negative value outright, so this is enforced by the type as well as here.
 *
 * And it never digs deeper than [DirectorConfig.maxDuckDb]. Netflix's own
 * instruction is to dip as far as the narration needs rather than lift the
 * narration, so there is an argument for no floor at all; the counter-argument is
 * that past about 19 dB the film is not quiet, it is gone, and a viewer who
 * cannot see the screen is relying on that soundtrack for most of what is
 * happening. 19 dB is also the deepest value the listener testing actually
 * covered, so beyond it the rule would be extrapolating.
 *
 * ## Why it is told where the duck already is
 *
 * The rule above is a subtraction, so it reproduces every wobble of the meter it
 * subtracts from. Asked once per tick against a live short-term measurement it
 * returns a slightly different number every time, and downstream a different
 * number is a different fade: on hardware this restarted the ramp about thirteen
 * times a second for the whole of every utterance. [DirectorConfig.duckDeadbandDb]
 * is the answer, and applying it needs to know what is already applied -- so
 * [current] is a parameter rather than state kept in here. The function stays
 * pure and the state stays in [AudioDirector], which is where the rest of the
 * director's state already lives.
 *
 * The deadband holds only between one dip and the next. It never holds a return
 * to [ContentDuck.Unity]: coming off a duck is decided by the caller, which stops
 * asking this function entirely once nothing is speaking. That matters more than
 * it looks -- a deadband able to swallow the release could leave the film
 * permanently quiet after a description ended, which is a far worse failure than
 * the pumping it exists to prevent.
 *
 * Nor does it hold the *first* dip of an utterance, where [current] is Unity.
 * There is nothing to be steady about yet, and the duck has to arrive at full
 * depth before the narrator's first word.
 */
fun duckFor(
    programmeLufs: Double?,
    config: DirectorConfig,
    current: ContentDuck = ContentDuck.Unity,
): ContentDuck {
    // Nothing has been measured yet -- the meter has not filled its first window,
    // or the content is paused. Fall back to the static number, which is the
    // professional's default starting position and the right thing to guess in
    // the absence of the measurement that would let us do better.
    if (programmeLufs == null) return settle(ContentDuck.Ducked(config.duckDb), current, config)

    val wanted =
        config.narrationLkfs + config.narrationMonoToStereoOffsetDb -
            config.targetLoudnessDifferenceLu - programmeLufs
    if (wanted >= 0.0) return ContentDuck.Unity
    return settle(ContentDuck.Ducked(maxOf(wanted, config.maxDuckDb)), current, config)
}

/**
 * Hold [current] unless [wanted] has moved far enough from it to be worth hearing.
 *
 * Only ever compares one dip against another. Arriving from Unity is not a move
 * the deadband is allowed to damp -- see the note on [duckFor].
 */
private fun settle(wanted: ContentDuck, current: ContentDuck, config: DirectorConfig): ContentDuck {
    if (current !is ContentDuck.Ducked) return wanted
    return if (abs(wanted.db - current.db) < config.duckDeadbandDb) current else wanted
}
