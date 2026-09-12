package dev.baton.tv

import android.view.KeyEvent.ACTION_DOWN
import android.view.KeyEvent.ACTION_UP
import android.view.KeyEvent.KEYCODE_BACK
import android.view.KeyEvent.KEYCODE_MEDIA_FAST_FORWARD
import android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
import android.view.KeyEvent.KEYCODE_MEDIA_REWIND
import android.view.KeyEvent.KEYCODE_MENU
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * The app's own key bindings against the gestures VoiceView reserves (PRD.md, "Remote
 * control mapping"): Menu long-press, Back+Menu, Menu+Select, Rewind+Fast-Forward,
 * Back+Fast-Forward.
 *
 * Select is left out of the reserved keys below because a single Select press is how
 * tv-material activates the focused button; what VoiceView owns is Select *with* Menu,
 * and Menu is bound to nothing.
 */
class RemoteBindingsTest : FunSpec({

    val reservedGestureKeys = listOf(KEYCODE_MENU, KEYCODE_BACK, KEYCODE_MEDIA_REWIND, KEYCODE_MEDIA_FAST_FORWARD)

    test("a short press of Play/Pause toggles playback") {
        RemoteBindings.togglesPlayback(KEYCODE_MEDIA_PLAY_PAUSE, ACTION_DOWN, repeatCount = 0) shouldBe true
    }

    test("releasing Play/Pause does not toggle a second time") {
        RemoteBindings.togglesPlayback(KEYCODE_MEDIA_PLAY_PAUSE, ACTION_UP, repeatCount = 0) shouldBe false
    }

    test("holding Play/Pause toggles once, not on every repeat") {
        RemoteBindings.togglesPlayback(KEYCODE_MEDIA_PLAY_PAUSE, ACTION_DOWN, repeatCount = 1) shouldBe false
    }

    test("no key a VoiceView gesture is made of is bound, pressed or held") {
        for (key in reservedGestureKeys) {
            for (action in listOf(ACTION_DOWN, ACTION_UP)) {
                for (repeatCount in listOf(0, 1)) {
                    RemoteBindings.togglesPlayback(key, action, repeatCount) shouldBe false
                }
            }
        }
    }
})
