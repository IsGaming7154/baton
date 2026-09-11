package dev.baton.player

import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import android.net.JvmTestUri
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe

/**
 * The one fact about subtitles that can be settled without a device: that a
 * subtitle URI handed to [BatonPlayer.setSource] really is attached to the
 * [androidx.media3.common.MediaItem] it loads -- one SubRip track, language
 * "en", flagged default, which is everything `DefaultTrackSelector` 1.4.1
 * needs to select it without any track-selector code (see `mediaItem`'s doc
 * for the citations).
 *
 * Building a `BatonPlayer` here is not possible -- `ExoPlayer` wants a
 * `Looper` and a real `Context` -- so this tests [mediaItem], the pure
 * function `setSource` delegates to. What that leaves unchecked is the
 * delegation itself, which is one line.
 */
class BatonPlayerMediaItemTest : FunSpec({

    val content = JvmTestUri("android.resource://dev.baton.tv/2131951699")
    val subtitle = JvmTestUri("android.resource://dev.baton.tv/2131951700")

    test("a subtitle URI is attached as exactly one SubRip track, flagged default") {
        val item = mediaItem(content, subtitle)

        val subtitles = item.localConfiguration!!.subtitleConfigurations
        subtitles.size shouldBe 1
        val attached = subtitles.single()
        attached.mimeType shouldBe MimeTypes.APPLICATION_SUBRIP
        attached.language shouldBe "en"
        attached.selectionFlags shouldBe C.SELECTION_FLAG_DEFAULT
        attached.uri shouldBe subtitle
    }

    test("the content URI itself is untouched by the subtitle being attached") {
        mediaItem(content, subtitle).localConfiguration!!.uri shouldBe content
    }

    test("no subtitle URI means no subtitle tracks") {
        mediaItem(content, subtitleUri = null).localConfiguration!!.subtitleConfigurations.shouldBeEmpty()
    }
})
