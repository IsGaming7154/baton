package dev.baton.tv

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.NativeKeyEvent
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.isFocusable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performKeyPress
import androidx.compose.ui.test.performSemanticsAction
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The access-services row (issue #7), asserted over the composition tree the way
 * `ShellNarrationTest` asserts the transport bar's.
 *
 * What is checked here is the part of EN 301 549 clause 7.3 that is a fact about
 * the tree: the row is one D-pad press below the transport bar and one press back,
 * every control carries its label, its role and its state as the text the director
 * will speak, and every node is [androidx.compose.ui.semantics.invisibleToUser] so
 * VoiceView does not say it again in a second voice. Whether VoiceView honours
 * that remains a device check, for the reason `TransportBar`'s docstring gives.
 *
 * The press is a real `KEYCODE_DPAD_DOWN` delivered to the focused node rather
 * than a `FocusManager.moveFocus` call, because the claim is about the key the
 * viewer presses: Compose resolves that key to a focus direction itself
 * (`AndroidComposeView.getFocusDirection(KeyEvent)`, ui 1.6.8) and the app binds no
 * key for it. Which toggle it lands on is deliberately not asserted -- that is
 * layout geometry, and the criterion is reachability.
 *
 * `sdk = [28]` for the reason `ShellNarrationTest` gives.
 */
@OptIn(ExperimentalComposeUiApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class AccessServicesRowTest {

    @get:Rule
    val compose = createComposeRule()

    private val heard = mutableListOf<String>()
    private val toggled = mutableListOf<AccessService>()

    /** The shell as `MainActivity` composes it, with the row's state held the way the activity holds it. */
    private fun showShell(initial: Set<AccessService> = DEFAULT_ON) {
        compose.setContent {
            var services by remember { mutableStateOf(initial) }
            TransportBar(
                playing = false,
                onRewind = {},
                onPlayPause = {},
                onFastForward = {},
                announce = { heard += it },
                services = services,
                onToggleService = { service ->
                    toggled += service
                    services = if (service in services) services - service else services + service
                },
            )
        }
    }

    /**
     * A remote press on [focusedLabel], down then up: tv-material's `Surface` acts on
     * Select at key up, and focus search acts on key down, so a press that is only
     * half a press works for one of them and not the other.
     */
    private fun press(focusedLabel: String, keyCode: Int) {
        val node = compose.onNodeWithContentDescription(focusedLabel)
        node.performKeyPress(KeyEvent(NativeKeyEvent(NativeKeyEvent.ACTION_DOWN, keyCode)))
        node.performKeyPress(KeyEvent(NativeKeyEvent(NativeKeyEvent.ACTION_UP, keyCode)))
        compose.waitForIdle()
    }

    /** The transport buttons' own labels, which are what they carry as a content description. */
    private fun transportLabels(): List<String> =
        listOf(TransportNarration.REWIND, TransportNarration.PLAY, TransportNarration.FAST_FORWARD)

    private fun togglePhrases(on: Set<AccessService>): List<String> =
        AccessService.entries.map { TransportNarration.toggle(it, it in on) }

    @Test
    fun oneDpadPressDownFromTheTransportBarReachesTheRowAndOnePressBackLeavesIt() {
        showShell()

        compose.onNodeWithContentDescription(TransportNarration.PLAY)
            .performSemanticsAction(SemanticsActions.RequestFocus)
        compose.runOnIdle { heard.last() shouldBe "Play, button" }

        press(TransportNarration.PLAY, NativeKeyEvent.KEYCODE_DPAD_DOWN)
        val landed = heard.last()
        togglePhrases(DEFAULT_ON) shouldContain landed

        press(landed, NativeKeyEvent.KEYCODE_DPAD_UP)
        transportLabels().map(TransportNarration::focused) shouldContain heard.last()
    }

    @Test
    fun everyToggleCarriesItsLabelRoleAndStateAndIsHiddenFromVoiceView() {
        val on = setOf(AccessService.DESCRIPTION, AccessService.SCREEN_CURTAIN)
        showShell(initial = on)

        val focusable = compose.onAllNodes(isFocusable()).fetchSemanticsNodes()
        // Three transport buttons and one control per access service.
        focusable.size shouldBe 3 + AccessService.entries.size

        val spoken = focusable.map {
            it.config.getOrElseNullable(SemanticsProperties.ContentDescription) { null }.orEmpty().joinToString(" ")
        }
        val expected = transportLabels() + togglePhrases(on)
        spoken shouldContainExactlyInAnyOrder expected

        for (node in focusable) {
            (SemanticsProperties.InvisibleToUser in node.config) shouldBe true
        }
    }

    @Test
    fun focusLandingOnAToggleAnnouncesItsLabelAndState() {
        showShell(initial = setOf(AccessService.DESCRIPTION))

        compose.onNodeWithContentDescription("Description, button, on")
            .performSemanticsAction(SemanticsActions.RequestFocus)
        compose.runOnIdle { heard.last() shouldBe "Description, button, on" }

        compose.onNodeWithContentDescription("Suppress description, button, off")
            .performSemanticsAction(SemanticsActions.RequestFocus)
        compose.runOnIdle { heard.last() shouldBe "Suppress description, button, off" }
    }

    /**
     * The curtain toggle reports the curtain and nothing else, which is as much of
     * "audio continues uninterrupted" as is reachable off a device: the row has one
     * callback, the activity's handler for [AccessService.SCREEN_CURTAIN] does
     * nothing but let the composable be drawn (`MainActivity.toggle`), and
     * `ScreenCurtain` is a box of black pixels. There is no path from the button to
     * the player to stop the audio with. Whether the Stick keeps playing under a
     * black window is still a device check.
     *
     * Focus is unchanged by the press, which is the other half of the criterion:
     * the toggle that raised the curtain still holds focus underneath it, so the
     * same button lowers it with no sighted help.
     */
    @Test
    fun raisingTheCurtainTouchesNothingButTheCurtainAndLeavesFocusWhereItWas() {
        showShell(initial = emptySet())

        compose.onNodeWithContentDescription("Screen curtain, button, off")
            .performSemanticsAction(SemanticsActions.RequestFocus)
        press("Screen curtain, button, off", NativeKeyEvent.KEYCODE_DPAD_CENTER)

        toggled shouldContainExactly listOf(AccessService.SCREEN_CURTAIN)
        compose.onNodeWithContentDescription("Screen curtain, button, on").assertIsFocused()
    }

    /** Nothing under the curtain can take focus, so raising it cannot strand the viewer on it. */
    @Test
    fun theCurtainTakesNoFocusAndSaysNothing() {
        compose.setContent { ScreenCurtain() }

        compose.onAllNodes(isFocusable()).fetchSemanticsNodes().shouldBeEmpty()
    }

    /**
     * Every sentence the row can say is pre-rendered, or it is a sentence the viewer
     * never hears: `RenderedAudioSpeaker` refuses an utterance with no `audioRef`,
     * and `TrackPreparation` renders exactly [TransportNarration.PHRASES].
     */
    @Test
    fun everyStateOfEveryControlIsAPreparedPhrase() {
        val required = AccessService.entries.flatMap {
            listOf(TransportNarration.toggle(it, on = true), TransportNarration.toggle(it, on = false))
        }
        (required - TransportNarration.PHRASES.toSet()).shouldBeEmpty()
    }

    /**
     * The length control says its value rather than on or off, on focus and after a
     * press, and both values are rendered with the rest of the row. The press itself
     * is announced by `MainActivity.toggle`, with the same phrase.
     */
    @Test
    fun theLengthControlSaysShortOrLong() {
        showShell(initial = emptySet())

        compose.onNodeWithContentDescription("Description length, button, long")
            .performSemanticsAction(SemanticsActions.RequestFocus)
        compose.runOnIdle { heard.last() shouldBe "Description length, button, long" }

        press("Description length, button, long", NativeKeyEvent.KEYCODE_DPAD_CENTER)

        toggled shouldContainExactly listOf(AccessService.DESCRIPTION_LENGTH)
        compose.onNodeWithContentDescription("Description length, button, short").assertIsFocused()
        TransportNarration.AFTER_START shouldContain "Description length, button, short"
        TransportNarration.AFTER_START shouldContain "Description length, button, long"
    }

    /** A title with nothing stored starts with Description off, and the row says so. */
    @Test
    fun descriptionStartsFromTheTitlesStoredStateAndDefaultsOff() {
        val context = RuntimeEnvironment.getApplication()
        val initial = if (TitleDescriptions.isOn(context, "title-a")) setOf(AccessService.DESCRIPTION) else emptySet()
        showShell(initial = initial)

        compose.onNodeWithContentDescription("Description, button, off")
            .performSemanticsAction(SemanticsActions.RequestFocus)
        compose.runOnIdle { heard.last() shouldBe "Description, button, off" }
    }

    /** On for one title is on for that title only, and read back from storage rather than memory. */
    @Test
    fun twoTitlesDoNotShareDescriptionState() {
        val context = RuntimeEnvironment.getApplication()

        TitleDescriptions.set(context, "title-a", on = true)

        TitleDescriptions.isOn(context, "title-a") shouldBe true
        TitleDescriptions.isOn(context, "title-b") shouldBe false

        TitleDescriptions.set(context, "title-b", on = true)
        TitleDescriptions.set(context, "title-a", on = false)

        TitleDescriptions.isOn(context, "title-a") shouldBe false
        TitleDescriptions.isOn(context, "title-b") shouldBe true
    }

    private companion object {
        val DEFAULT_ON = setOf(
            AccessService.DESCRIPTION,
            AccessService.SPOKEN_SUBTITLES,
            AccessService.CAPTIONS,
        )
    }
}
