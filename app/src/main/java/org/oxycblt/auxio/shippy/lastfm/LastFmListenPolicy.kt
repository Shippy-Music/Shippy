package org.oxycblt.auxio.shippy.lastfm

/** Position deltas are accepted only when they look like ordinary advancing playback, never seeks. */
internal class LastFmListenPolicy(private val durationMs: Long?) {
    private var previous: Long? = null
    var listenedMs = 0L; private set
    var scrobbled = false; private set

    fun pause() {
        previous = null
    }

    fun update(positionMs: Long): Boolean {
        val old = previous; previous = positionMs
        if (old == null || positionMs < old || positionMs - old > 5_000L) return false
        listenedMs += positionMs - old
        val eligible = durationMs == null || durationMs > 30_000L
        val threshold = durationMs?.let { minOf(it / 2, 240_000L) } ?: 240_000L
        if (!eligible || scrobbled || listenedMs < threshold) return false
        scrobbled = true
        return true
    }
}
