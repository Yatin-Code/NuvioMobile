package com.nuvio.app.features.player

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Contributor-published tracks, keyed by registry URL.
 *
 * After a bundle upload the contributor re-reads the registry (the server
 * merges same-duration cues into the existing version, first writer wins on
 * overlap) and publishes the refreshed version here. The scrub overlay
 * observes [Snapshot.generation] and swaps the track in, which is what
 * "self-unlock" means: the device that contributed a range can immediately
 * scrub inside it instead of waiting for a cold start.
 *
 * Compose-observable on purpose: the upload lands on the player coroutine
 * minutes after the overlay composed, so a one-shot fetch would never see it.
 */
internal object SeekPreviewTrackBus {
    /** Titles kept; the contributor only ever publishes the one being played. */
    private const val MAX_TRACKS = 8

    /** Immutable state: url -> newest published track, plus a publish counter. */
    data class Snapshot(
        val tracks: Map<String, SeekPreviewTrack> = emptyMap(),
        val generation: Int = 0,
    )

    private val lock = Any()

    var snapshot: Snapshot by mutableStateOf(Snapshot())
        private set

    /** Publishes [track] as the newest version served for [query]. */
    fun publish(query: SeekPreviewQuery, track: SeekPreviewTrack) {
        val url = query.registryUrl
        synchronized(lock) {
            val current = snapshot
            if (current.tracks[url] == track) return
            var tracks = current.tracks + (url to track)
            while (tracks.size > MAX_TRACKS) {
                val eldest = tracks.keys.firstOrNull() ?: break
                tracks = tracks - eldest
            }
            snapshot = current.copy(tracks = tracks, generation = current.generation + 1)
        }
    }

    fun trackFor(url: String): SeekPreviewTrack? = snapshot.tracks[url]
}
