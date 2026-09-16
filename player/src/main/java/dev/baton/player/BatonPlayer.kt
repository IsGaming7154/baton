package dev.baton.player

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import dev.baton.director.Playback
import dev.baton.ports.ContentGain
import dev.baton.ports.PlaybackClock

/**
 * The ExoPlayer adapter, and only that.
 *
 * It implements two ports -- [PlaybackClock] and [ContentGain] -- and holds no
 * policy: it cannot tell you whether a cue may speak, only where the content is
 * and how loud it currently is. Everything that decides anything lives in
 * `:director`, on the other side of `:ports`, with no Android on its classpath.
 *
 * Every method here must be called on the thread that built the player, which is
 * ExoPlayer's rule and not ours.
 *
 * ## Two duck implementations, one active
 *
 * [sampleLevelGain] is always installed in the sink's processor chain, so
 * [DuckingAudioProcessor.writtenPositionMs] keeps measuring the sink's queue
 * depth regardless of which implementation is ducking. But it sits upstream of
 * that queue -- several hundred milliseconds deep on the Fire TV Stick -- so a
 * duck applied there is inaudible for most of a second. [contentGain] is the
 * active one: [OutputStageContentGain], which applies at `ExoPlayer.setVolume`,
 * after the queue rather than before it. [sampleLevelGain] stays wired into the
 * chain, at unity, as the fallback if the output-stage ticker's steps turn out
 * to be audible -- switching back is choosing which field callers are handed,
 * not rewriting anything.
 */
@OptIn(UnstableApi::class)
class BatonPlayer(context: Context, log: (String) -> Unit = {}) {

    /**
     * The sample-accurate duck, always in the render chain for its
     * [DuckingAudioProcessor.writtenPositionMs] measurement, but left at unity
     * unless it is the one selected as [contentGain].
     */
    val sampleLevelGain: DuckingAudioProcessor = DuckingAudioProcessor(log = log)

    val exoPlayer: ExoPlayer = ExoPlayer.Builder(context, renderersFactory(context, sampleLevelGain)).build()

    /** The active [ContentGain]: the output-stage duck. See the class doc. */
    val contentGain: ContentGain = OutputStageContentGain(exoPlayer, exoPlayer.applicationLooper, log = log)

    init {
        // sampleLevelGain.writtenPositionMs() counts frames since its last sink
        // flush, and a seek is one of those flushes. Media3 1.4.1's
        // AudioProcessor.flush() takes no arguments, so the processor itself has
        // no way to learn where a seek landed -- this listener is the one place
        // that does, so it hands the position straight over. See
        // DuckingAudioProcessor.seekedTo.
        exoPlayer.addListener(object : Player.Listener {
            override fun onPositionDiscontinuity(
                oldPosition: Player.PositionInfo,
                newPosition: Player.PositionInfo,
                reason: Int,
            ) {
                if (reason == Player.DISCONTINUITY_REASON_SEEK) sampleLevelGain.seekedTo(newPosition.positionMs)
            }
        })
    }

    val clock: PlaybackClock = object : PlaybackClock {
        override fun positionMs(): Long = exoPlayer.currentPosition.coerceAtLeast(0L)

        /**
         * Buffering is not playing -- the content position is not advancing, so a
         * cue admitted on it would be admitted against a clock that has stopped --
         * but it is not a pause either, and this is the one place in the system
         * that can tell the difference.
         *
         * `Player.isPlaying` cannot. It is defined as `playbackState ==
         * STATE_READY && playWhenReady && playbackSuppressionReason ==
         * PLAYBACK_SUPPRESSION_REASON_NONE` (media3-common 1.4.1,
         * `BasePlayer.isPlaying`): a conjunction of three facts that answers "is
         * the position advancing" and then throws away which of the three failed.
         * The branches below are that conjunction pulled back apart, in the order
         * `Player.isPlaying`'s own contract lists the ways it can be false.
         *
         * What makes the answer trustworthy is that Media3 keeps intention and
         * readiness apart on purpose. `playWhenReady` is the intention, and every
         * reason it is allowed to change is somebody deciding something --
         * `PLAY_WHEN_READY_CHANGE_REASON_` `USER_REQUEST`, `AUDIO_FOCUS_LOSS`,
         * `AUDIO_BECOMING_NOISY`, `REMOTE`, `END_OF_MEDIA_ITEM`,
         * `SUPPRESSED_TOO_LONG` (1.4.1). Running out of buffer is not on that
         * list, and cannot be: it moves `playbackState` and leaves the intention
         * exactly where the viewer left it. So "wants to play, cannot yet" is
         * directly readable, and the director never has to guess it from timing.
         *
         * Suppression is reported as a pause rather than a stall. Transient audio
         * focus loss and an unsuitable audio route are both somebody else deciding
         * the film should stop being heard, and a description that talked through
         * them would be talking over whatever took the focus -- which is the one
         * thing this product promises not to do.
         *
         * `STATE_IDLE` and `STATE_ENDED` fall through to the same answer. Neither
         * is an interruption of anything: the first means nothing is loaded yet,
         * the second that the film is over.
         */
        override fun playback(): Playback = when {
            exoPlayer.isPlaying -> Playback.PLAYING
            !exoPlayer.playWhenReady -> Playback.PAUSED
            exoPlayer.playbackSuppressionReason != Player.PLAYBACK_SUPPRESSION_REASON_NONE -> Playback.PAUSED
            exoPlayer.playbackState == Player.STATE_BUFFERING -> Playback.STALLED
            else -> Playback.PAUSED
        }
    }

    fun setSource(uri: Uri, subtitleUri: Uri? = null) {
        // The SELECTION_FLAG_DEFAULT alone makes 1.4.1's stock DefaultTrackSelector
        // pick the track (DefaultTrackSelector.java:4021-4029), and the stock
        // PlayerView layout renders it -- no track-selector code needed.
        val item = MediaItem.Builder()
            .setUri(uri)
            .apply {
                if (subtitleUri != null) setSubtitleConfigurations(
                    listOf(
                        MediaItem.SubtitleConfiguration.Builder(subtitleUri)
                            .setMimeType(MimeTypes.APPLICATION_SUBRIP)
                            .setLanguage("en")
                            .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                            .build(),
                    ),
                )
            }
            .build()
        exoPlayer.setMediaItem(item)
        exoPlayer.prepare()
    }

    fun play() {
        exoPlayer.playWhenReady = true
    }

    /**
     * The captions toggle in the access-services row (issue #7): the on-screen
     * subtitles, which are a different thing from the spoken ones.
     *
     * Disabling the track type rather than hiding `PlayerView`'s subtitle view,
     * because the two are not the same claim: with the type disabled the selector
     * stops choosing a text track at all
     * (`TrackSelectionParameters.Builder.setTrackTypeDisabled(int, boolean)`,
     * media3-common 1.4.1), so no cue is decoded and there is nothing left that
     * could reappear on the next track reselection. Hiding the view would leave a
     * selected track drawing into a hidden surface.
     *
     * Nothing here touches playback: track selection does not stop the audio
     * renderer.
     */
    fun setCaptionsEnabled(enabled: Boolean) {
        exoPlayer.trackSelectionParameters = exoPlayer.trackSelectionParameters
            .buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, !enabled)
            .build()
    }

    /** Notifies [onSeek] whenever playback jumps, which is what the director rebaselines on. */
    fun onSeek(onSeek: () -> Unit) {
        exoPlayer.addListener(object : Player.Listener {
            override fun onPositionDiscontinuity(
                oldPosition: Player.PositionInfo,
                newPosition: Player.PositionInfo,
                reason: Int,
            ) {
                if (reason == Player.DISCONTINUITY_REASON_SEEK) onSeek()
            }
        })
    }

    fun release() {
        exoPlayer.release()
    }

    private companion object {

        /**
         * [sampleLevelGain] rides in the sink's processor chain even while it is
         * not the active duck, for its `writtenPositionMs` measurement. Building
         * the sink by hand is the only way to get a processor into the chain.
         */
        fun renderersFactory(context: Context, gain: DuckingAudioProcessor): DefaultRenderersFactory =
            object : DefaultRenderersFactory(context) {
                override fun buildAudioSink(
                    context: Context,
                    enableFloatOutput: Boolean,
                    enableAudioTrackPlaybackParams: Boolean,
                ): AudioSink = DefaultAudioSink.Builder(context)
                    .setAudioProcessors(arrayOf(gain))
                    .setEnableFloatOutput(enableFloatOutput)
                    .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                    .build()
            }
    }
}
