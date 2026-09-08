package com.hazbu.xcam.core.engine

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.Surface
import androidx.core.net.toUri
import androidx.media3.common.C
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.effect.ScaleAndRotateTransformation
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Handles Media3/ExoPlayer lifecycle and state management for video injection.
 *
 * For M3U8/HLS streams, an explicit [HlsMediaSource.Factory] backed by
 * [OkHttpDataSource] is used so that ExoPlayer can resolve the HLS playlist
 * and download TS segments reliably — even when running inside a hooked app's
 * process via Xposed (where classloader auto-discovery may fail).
 */
@UnstableApi
class MediaEngine(private val logAction: (String) -> Unit) {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var player: ExoPlayer? = null

    /** Shared OkHttpClient – reused across player rebuilds. */
    private val okHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    @Volatile
    private var isPlayingInternal = false

    @Volatile
    private var currentPositionInternal = 0L

    @Volatile
    var videoWidth = 0
        private set

    @Volatile
    var videoHeight = 0
        private set

    val isPlaying: Boolean
        get() = isPlayingInternal

    val currentPosition: Long
        get() = if (Looper.myLooper() == Looper.getMainLooper()) {
            player?.currentPosition ?: 0L
        } else {
            currentPositionInternal
        }

    private fun log(tag: String, msg: String) {
        logAction("[$tag] $msg")
    }

    // ── Internal helper: release old player (must be called on main thread) ──

    private fun releasePlayerInternal() {
        try {
            player?.apply {
                stop()
                clearVideoSurface()
                release()
            }
        } catch (e: Throwable) {
            log("MEDIA-ENGINE", "Release failed: ${e.message}")
        } finally {
            player = null
            isPlayingInternal = false
            currentPositionInternal = 0L
            videoWidth = 0
            videoHeight = 0
        }
    }

    // ── Public API ───────────────────────────────────────────────────────────

    fun stop() {
        mainHandler.post { releasePlayerInternal() }
    }

    fun play(
        context: Context,
        path: String,
        surface: Surface,
        tag: String,
        isMirrored: Boolean = false,
        rotationAngle: Int = 0,
        onPrepared: ((ExoPlayer?) -> Unit)? = null
    ) {
        mainHandler.post {
            // Atomically release old player, then build + start new one.
            releasePlayerInternal()

            try {
                val uri = path.toUri()
                val isHls = path.lowercase().let {
                    it.contains(".m3u8") || it.contains("m3u8")
                }
                log(tag, "Loading media: $path (HLS=$isHls)")

                // ── Build MediaSource factory ────────────────────────────────
                val appContext = context.applicationContext
                val okDataSourceFactory = OkHttpDataSource.Factory(okHttpClient)

                val mediaSourceFactory: MediaSource.Factory = if (isHls) {
                    // Explicit HLS factory — bypasses classloader auto-discovery
                    HlsMediaSource.Factory(okDataSourceFactory)
                } else {
                    DefaultMediaSourceFactory(appContext)
                }

                // ── Build renderers (video only — no audio renderer needed) ──
                val renderersFactory = DefaultRenderersFactory(appContext)
                    .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)

                // ── Build ExoPlayer ──────────────────────────────────────────
                val exoPlayer = ExoPlayer.Builder(appContext, renderersFactory)
                    .setMediaSourceFactory(mediaSourceFactory)
                    .build()
                player = exoPlayer

                // Disable audio track selection — we only need video frames.
                exoPlayer.trackSelectionParameters = exoPlayer.trackSelectionParameters
                    .buildUpon()
                    .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true)
                    .build()

                // ── Build MediaItem ──────────────────────────────────────────
                val mediaItem = if (isHls) {
                    MediaItem.Builder()
                        .setUri(uri)
                        .setMimeType(MimeTypes.APPLICATION_M3U8)
                        .build()
                } else {
                    MediaItem.fromUri(uri)
                }

                // ── Video effects (mirror / rotate) ──────────────────────────
                val effects = mutableListOf<Effect>()
                if (isMirrored || rotationAngle != 0) {
                    val scaleX = if (isMirrored) -1f else 1f
                    effects.add(
                        ScaleAndRotateTransformation.Builder()
                            .setScale(scaleX, 1f)
                            .setRotationDegrees(rotationAngle.toFloat())
                            .build()
                    )
                }
                if (effects.isNotEmpty()) {
                    exoPlayer.setVideoEffects(effects)
                }

                // ── Wire up and prepare ──────────────────────────────────────
                exoPlayer.setMediaItem(mediaItem)
                exoPlayer.setVideoSurface(surface)
                exoPlayer.repeatMode = Player.REPEAT_MODE_ONE
                exoPlayer.playWhenReady = true

                exoPlayer.addListener(object : Player.Listener {
                    override fun onIsPlayingChanged(playing: Boolean) {
                        isPlayingInternal = playing
                        if (playing) startPositionPolling() else stopPositionPolling()
                    }

                    override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) {
                        if (videoSize.width > 0) {
                            videoWidth = videoSize.width
                            videoHeight = videoSize.height
                        }
                    }

                    override fun onPlaybackStateChanged(playbackState: Int) {
                        when (playbackState) {
                            Player.STATE_READY -> {
                                if (videoWidth == 0) {
                                    videoWidth = exoPlayer.videoSize.width
                                    videoHeight = exoPlayer.videoSize.height
                                }
                                log(tag, "Player ACTIVE (${videoWidth}x${videoHeight})")
                                try { onPrepared?.invoke(exoPlayer) } catch (_: Throwable) {}
                            }
                            Player.STATE_BUFFERING -> {
                                log(tag, "Buffering…")
                            }
                            Player.STATE_ENDED -> {
                                log(tag, "Playback ended (should loop)")
                            }
                            Player.STATE_IDLE -> { /* no-op */ }
                        }
                    }

                    override fun onPlayerError(error: PlaybackException) {
                        log(tag, "Player Error: ${error.errorCodeName} | ${error.message} | Cause: ${error.cause?.message}")
                        releasePlayerInternal()
                    }
                })

                exoPlayer.prepare()

            } catch (e: Throwable) {
                log(tag, "Prepare failed: ${e.message}")
                releasePlayerInternal()
            }
        }
    }

    // ── Position polling ─────────────────────────────────────────────────────

    private val positionPoller = object : Runnable {
        override fun run() {
            player?.let {
                currentPositionInternal = it.currentPosition
                if (isPlayingInternal) {
                    mainHandler.postDelayed(this, 500)
                }
            }
        }
    }

    private fun startPositionPolling() {
        mainHandler.removeCallbacks(positionPoller)
        mainHandler.post(positionPoller)
    }

    private fun stopPositionPolling() {
        mainHandler.removeCallbacks(positionPoller)
    }
}
