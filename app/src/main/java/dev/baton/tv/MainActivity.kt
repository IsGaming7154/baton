package dev.baton.tv

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.tv.material3.Surface
import dev.baton.player.BatonPlayer
import dev.baton.player.BatonPlayerSurface

/**
 * Tracer slice (issue #2): play a bundled clip end to end on Fire TV, driven
 * by the D-pad. No audio description, no arbitration, no cloud -- this exists
 * to prove the toolchain.
 */
class MainActivity : ComponentActivity() {

    private lateinit var player: BatonPlayer

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        player = BatonPlayer(this)
        player.setSource(Uri.parse("android.resource://$packageName/${R.raw.sample_clip}"))
        player.play()

        setContent {
            Surface(modifier = Modifier.fillMaxSize()) {
                BatonPlayerSurface(
                    exoPlayer = player.exoPlayer,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }

    override fun onDestroy() {
        player.release()
        super.onDestroy()
    }
}
