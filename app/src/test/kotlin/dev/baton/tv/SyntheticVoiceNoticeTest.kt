package dev.baton.tv

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import dev.baton.director.DescriptionTrackJson
import io.kotest.matchers.shouldBe
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The on-screen notice for the bundled title (issue #12), asserted over the composition
 * tree: both synthetic voices are named, the dub's from the track asset, and the node
 * that carries each line's text is the node marked invisible to the user -- the one
 * VoiceView's `isVisibleToUser` is computed from.
 *
 * Unmerged tree, because that is the tree the accessibility delegate walks and whose
 * per-node config it reads. `sdk = [28]` for the reason `ShellNarrationTest` gives.
 */
@OptIn(ExperimentalComposeUiApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class SyntheticVoiceNoticeTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun theBundledTitleNamesBothSyntheticVoicesAndHidesThemFromVoiceView() {
        val asset = sequenceOf(
            File("src/main/assets/sample_clip.track.json"),
            File("app/src/main/assets/sample_clip.track.json"),
        ).first { it.isFile }
        val track = DescriptionTrackJson.decode(asset.readText())

        compose.setContent { SyntheticVoiceNotice(track) }

        for (line in listOf(
            "Descriptions in this title use a synthetic voice.",
            "The German dub in this title uses synthetic voices (Amazon Polly).",
        )) {
            val nodes = compose.onAllNodes(hasText(line), useUnmergedTree = true).fetchSemanticsNodes()
            nodes.size shouldBe 1
            (SemanticsProperties.InvisibleToUser in nodes.single().config) shouldBe true
        }
    }
}
