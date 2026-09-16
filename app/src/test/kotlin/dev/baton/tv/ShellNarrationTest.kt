package dev.baton.tv

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.isFocusable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performSemanticsAction
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotBeBlank
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The shell's narration, asserted over the composition tree (issue #6, PRD "Testing
 * Decisions"): every focusable node carries non-empty narration text and a role.
 *
 * The tree is the one the test framework walks, which is not filtered by
 * `invisibleToUser` (TestOwner.kt:97-102, ui-test 1.6.8), so hiding the buttons from
 * VoiceView does not hide them from this check.
 *
 * `sdk = [28]` is the Fire TV Stick's API level, and also the only way to run here:
 * Robolectric 4.13 knows SDKs up to 34 (DefaultSdkProvider.java:64-77) and the app
 * targets 35.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ShellNarrationTest {

    @get:Rule
    val compose = createComposeRule()

    private val heard = mutableListOf<String>()

    private fun showTransportBar() {
        compose.setContent {
            TransportBar(
                playing = false,
                onRewind = {},
                onPlayPause = {},
                onFastForward = {},
                announce = { heard += it },
            )
        }
    }

    @Test
    fun everyFocusableNodeExposesNarrationTextAndARole() {
        showTransportBar()

        // The three transport buttons, plus the access-services row the bar now
        // carries one press below them (issue #7). The row's own tree is asserted
        // in AccessServicesRowTest; what is checked here is that adding it did not
        // put an unlabelled or roleless node in the shell.
        val focusable = compose.onAllNodes(isFocusable()).fetchSemanticsNodes()
        focusable.size shouldBe 3 + AccessService.entries.size
        for (node in focusable) {
            node.config.getOrElseNullable(SemanticsProperties.ContentDescription) { null }
                .orEmpty().joinToString(" ").shouldNotBeBlank()
            node.config.getOrElseNullable(SemanticsProperties.Role) { null }.shouldNotBeNull()
        }
    }

    @Test
    fun focusLandingOnAButtonAnnouncesItsLabelAndRole() {
        showTransportBar()

        compose.onNodeWithContentDescription(TransportNarration.FAST_FORWARD)
            .performSemanticsAction(SemanticsActions.RequestFocus)

        compose.runOnIdle { heard.last() shouldBe "Fast forward, button" }
    }
}
