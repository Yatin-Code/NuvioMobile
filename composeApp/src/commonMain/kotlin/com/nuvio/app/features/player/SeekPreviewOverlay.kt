package com.nuvio.app.features.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nuvio.app.core.ui.nuvioTypeScale
import com.nuvio.app.core.ui.themePalette
import kotlinx.coroutines.delay

private val SeekPreviewCardWidth = 160.dp
private val SeekPreviewCardHeight = 90.dp // 16:9 at 160 wide
private const val SeekPreviewLingerMs = 1500L

/**
 * Scrub-seek thumbnail card above the progress bar, adapted from the seekr TV
 * fork's overlay: shows only while scrubbing (plus a 1.5s linger so
 * tap-tap-tap inputs don't flicker), follows the scrub fraction with edge
 * clamp, silent when no cached tile exists for the floor timestamp.
 *
 * Driven by the live scrub position ([SeekPreviewParams.positionMs], fed from
 * onScrubChange); the seek commit still fires only on onScrubFinished.
 */
@Composable
internal fun SeekPreviewScrubOverlay(
    params: SeekPreviewParams?,
    modifier: Modifier = Modifier,
) {
    if (params == null || !params.enabled) return
    val query = params.query ?: return
    val durationMs = params.durationMs
    if (durationMs <= 0L) return

    var track by remember(query) { mutableStateOf<SeekPreviewTrack?>(null) }
    LaunchedEffect(query) {
        SeekPreviewSheetTiles.clear()
        track = SeekPreviewRepository.loadTrack(query)
    }

    // Linger: keep the card visible briefly after scrub ends.
    var lingerVisible by remember { mutableStateOf(false) }
    LaunchedEffect(params.isScrubbing) {
        if (params.isScrubbing) {
            lingerVisible = true
        } else {
            delay(SeekPreviewLingerMs)
            lingerVisible = false
        }
    }

    val positionMs = params.positionMs.coerceIn(0L, durationMs)
    val cue = track?.thumbnailFor(positionMs)
    val tile by produceState<ImageBitmap?>(null, cue) {
        value = if (cue == null) {
            null
        } else {
            SeekPreviewSheetTiles.tile(cue.imageUrl, cue.x, cue.y, cue.w, cue.h)
        }
    }

    AnimatedVisibility(
        visible = lingerVisible && tile != null,
        enter = fadeIn(animationSpec = tween(120)),
        exit = fadeOut(animationSpec = tween(200)),
        modifier = modifier,
    ) {
        val bitmap = tile ?: return@AnimatedVisibility
        val fraction = (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
        val palette = MaterialTheme.themePalette
        BoxWithConstraints(
            modifier = Modifier.fillMaxWidth(),
        ) {
            val left = seekPreviewCardOffset(maxWidth, SeekPreviewCardWidth, fraction)
            Column(
                modifier = Modifier
                    .offset(x = left)
                    .width(SeekPreviewCardWidth)
                    .padding(bottom = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Image(
                    bitmap = bitmap,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(SeekPreviewCardWidth, SeekPreviewCardHeight)
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color.Black)
                        .border(1.dp, palette.secondary, RoundedCornerShape(6.dp)),
                )
                Text(
                    text = formatPlaybackTime(positionMs),
                    style = MaterialTheme.nuvioTypeScale.labelSm.copy(fontSize = 11.sp),
                    color = Color.White,
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color.Black.copy(alpha = 0.5f))
                        .border(1.dp, Color.White.copy(alpha = 0.2f), RoundedCornerShape(12.dp))
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                )
            }
        }
    }
}

private fun seekPreviewCardOffset(trackWidth: Dp, thumbWidth: Dp, fraction: Float): Dp {
    val centerX = trackWidth * fraction
    val leftUnclamped = centerX - thumbWidth / 2
    val maxLeft = (trackWidth - thumbWidth).coerceAtLeast(0.dp)
    return leftUnclamped.coerceIn(0.dp, maxLeft)
}
