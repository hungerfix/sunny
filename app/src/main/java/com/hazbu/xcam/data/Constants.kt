package com.hazbu.xcam.data

object Constants {
    const val PREFS_NAME = "xcam_prefs"
    const val KEY_MEDIA_PATH = "media_path"
    const val KEY_IS_ENABLED = "is_enabled"
    const val KEY_IS_MIRRORED = "is_mirrored"
    const val KEY_ROTATION_ANGLE = "rotation_angle"
    const val AUTHORITY = "com.hazbu.xcam.provider"

    // Engine Configurations
    const val DEFAULT_CAPTURE_WIDTH = 1280
    const val DEFAULT_CAPTURE_HEIGHT = 1280
    const val DUMMY_SURFACE_TEXTURE_ID = 999
    const val MIN_SESSION_DEBOUNCE_MS = 100L
    const val STREAM_FRAME_INTERVAL_MS = 500L
}

// ---------------------------------------------------------------------------
// Extension helpers — used throughout the module to identify media types
// ---------------------------------------------------------------------------

/** Returns true if this path / URL uses an HTTP or HTTPS scheme. */
fun String.isNetworkUrl(): Boolean =
    startsWith("http://", ignoreCase = true) || startsWith("https://", ignoreCase = true)

/**
 * Returns true if this source should be treated as a time-based stream
 * (i.e. frame position matters and periodic extraction is appropriate).
 * Covers local .mp4 files, local/remote .m3u8 playlists, and any raw
 * HTTP/HTTPS URL (which ExoPlayer will handle as HLS/DASH/progressive).
 */
fun String.isStreamable(): Boolean {
    val lower = lowercase()
    return lower.endsWith(".mp4") || lower.endsWith(".m3u8") || isNetworkUrl()
}

