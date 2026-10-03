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
 *
 * Also silent past the served coverage: a partial registry version answers
 * with a `covered_until_ms` and the lookup honors it, so the uncovered tail of
 * a title stays blank instead of showing the last tile over and over. When
 * this device contributed that range itself, the contributor publishes the
 * refreshed version on [SeekPreviewTrackBus] and the card starts answering
 * without a restart.
 */
@Composable
internal fun SeekPreviewScrubOverlay(
    params: SeekPreviewParams?,
    modifier: Modifier = Modifier,
) {
    if (params == null || !params.enabled) {
        seekPreviewLog("overlay skip paramsNull=${params == null} enabled=${params?.enabled}")
        return
    }
    val query = params.query
    if (query == null) {
        seekPreviewLog("overlay skip queryNull parentMeta? dur=${params.durationMs}")
        return
    }
    val durationMs = params.durationMs
    if (durationMs <= 0L) {
        seekPreviewLog("overlay skip badDuration query=$query")
        return
    }
    var lastScrubLogged by remember(query) { mutableStateOf<Boolean?>(null) }
    if (lastScrubLogged != params.isScrubbing) {
        lastScrubLogged = params.isScrubbing
        seekPreviewLog("overlay query=$query dur=$durationMs scrubbing=${params.isScrubbing}")
    }
    // No-cue is a per-scrub-frame log; keep it to once per scrub gesture so the
    // debug ring buffer stays readable.
    var noCueLogged by remember(query) { mutableStateOf(false) }
    if (!params.isScrubbing) noCueLogged = false

    var track by remember(query) { mutableStateOf<SeekPreviewTrack?>(null) }
    // Contributor-published refreshes land on the bus minutes into playback;
    // the generation counter is what wakes this overlay up for them.
    val publishedGeneration = SeekPreviewTrackBus.snapshot.generation
    LaunchedEffect(query) {
        SeekPreviewSheetTiles.clear()
        val fetched = SeekPreviewRepository.loadTrack(query)
        track = fetched ?: SeekPreviewTrackBus.trackFor(query.registryUrl)
        seekPreviewLog(
            "overlay track loaded null=${fetched == null} cues=${track?.cues?.size} " +
                "status=${track?.status} covered=${track?.coveredUntilMs}",
        )
    }
    LaunchedEffect(query, publishedGeneration) {
        val published = SeekPreviewTrackBus.trackFor(query.registryUrl) ?: return@LaunchedEffect
        if (published == track) return@LaunchedEffect
        track = published
        // Newly merged cue ranges mean sheets this overlay never fetched.
        SeekPreviewSheetTiles.clear()
        seekPreviewLog(
            "overlay track refreshed cues=${published.cues.size} " +
                "status=${published.status} covered=${published.coveredUntilMs}",
        )
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
    // Floor lookup, bounded by the served coverage: a partial ("pending")
    // version must stay silent past its last cue instead of repeating a stale
    // tail tile (thumbnailFor compares covered_until_ms in source time).
    val cue = track?.thumbnailFor(positionMs)
    if (track != null && cue == null && !noCueLogged) {
        noCueLogged = true
        seekPreviewLog(
            "overlay no cue for pos=$positionMs cues=${track?.cues?.size} " +
                "covered=${track?.coveredUntilMs} status=${track?.status}",
        )
    }
    val tile by produceState<ImageBitmap?>(null, cue) {
        value = if (cue == null) {
            null
        } else {
            try {
                SeekPreviewSheetTiles.tile(cue.imageUrl, cue.x, cue.y, cue.w, cue.h)
            } catch (e: Exception) {
                seekPreviewLog("overlay tile failed url=${cue.imageUrl} err=${e.message}")
                null
            }
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
