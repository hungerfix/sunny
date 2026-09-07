package com.hazbu.xcam.core.engine

import android.content.Context
import android.graphics.SurfaceTexture
import android.os.Handler
import android.os.Looper
import android.view.Surface
import android.view.SurfaceHolder
import androidx.media3.common.util.UnstableApi
import com.hazbu.xcam.core.settings.SettingsManager
import com.hazbu.xcam.core.surface.SurfaceManager
import com.hazbu.xcam.core.surface.SurfaceProvider

@UnstableApi
class XCamEngine(
    private val contextProvider: () -> Context?,
    private val settingsProvider: () -> SettingsManager,
    private val surfaceManager: SurfaceManager,
    private val mediaEngine: MediaEngine,
    private val surfaceProvider: SurfaceProvider,
    private val logAction: (String) -> Unit,
) {
    private val uiHandler = Handler(Looper.getMainLooper())
    
    private var lastST: SurfaceTexture? = null
    private var lastModernSurface: Surface? = null
    private var lastInjectedGen = -1
    private var lastInjectedSurfaceId = -1L
    private var lastInjectedPath: String? = null
    private var lastInjectedMirrored: Boolean = false
    private var lastInjectedRotation: Int = 0

    private fun log(tag: String, msg: String) = logAction("[$tag] $msg")
    private fun logPipe(msg: String) = log("PIPELINE", msg)

    fun isPlaying() = mediaEngine.isPlaying
    fun getCurrentPosition() = mediaEngine.currentPosition

    fun stop() {
        uiHandler.removeCallbacksAndMessages(null)
        mediaEngine.stop()
        surfaceProvider.release()
        lastST = null
        lastModernSurface = null
        lastInjectedSurfaceId = -1L
        lastInjectedPath = null
    }

    fun handleCamera1Preview(st: SurfaceTexture) {
        val surface = Surface(st)
        if (!surfaceManager.isPreviewSurface(surface)) {
            surface.release()
            return
        }

        val path = settingsProvider().mediaPath ?: return
        val context = contextProvider() ?: return
        val settings = settingsProvider()

        if (st == lastST && 
            lastInjectedGen == surfaceManager.sessionGeneration &&
            path == lastInjectedPath &&
            settings.isMirrored == lastInjectedMirrored &&
            settings.rotationAngle == lastInjectedRotation) return

        lastST = st
        lastInjectedGen = surfaceManager.sessionGeneration
        lastInjectedPath = path
        lastInjectedMirrored = settings.isMirrored
        lastInjectedRotation = settings.rotationAngle

        logPipe("Legacy Hook: Injecting to SurfaceTexture")
        mediaEngine.play(context, path, surface, "Legacy", settings.isMirrored, settings.rotationAngle)
    }

    fun handleModernPreview(surface: Surface) {
        val currentGen = surfaceManager.sessionGeneration
        val path = settingsProvider().mediaPath ?: ""
        val settings = settingsProvider()

        if (surface == lastModernSurface && 
            lastInjectedGen == currentGen &&
            path == lastInjectedPath &&
            settings.isMirrored == lastInjectedMirrored &&
            settings.rotationAngle == lastInjectedRotation) return

        lastModernSurface = surface
        uiHandler.post { processInjection(surface) }
    }

    fun handleSurfaceViewPreview(holder: SurfaceHolder) {
        uiHandler.post {
            if (surfaceManager.isPreviewSurface(holder.surface)) {
                processInjection(holder.surface)
            }
        }
    }

    private fun processInjection(surface: Surface) {
        val context = contextProvider() ?: return
        val path = settingsProvider().mediaPath ?: return
        injectToSurface(surface, context, path)
    }

    fun injectToSurface(surface: Surface, context: Context, path: String) {
        synchronized(this) {
            if (!surface.isValid) return
            
            val id = com.hazbu.xcam.utils.SystemUtils.getSurfaceId(surface)
            val currentGen = surfaceManager.sessionGeneration
            val settings = settingsProvider()

            if (id == lastInjectedSurfaceId && 
                currentGen == lastInjectedGen && 
                path == lastInjectedPath &&
                settings.isMirrored == lastInjectedMirrored &&
                settings.rotationAngle == lastInjectedRotation) return

            logPipe("Injection: ID=$id Gen=$currentGen Path=$path")
            mediaEngine.stop()
            
            lastInjectedGen = currentGen
            lastInjectedSurfaceId = id
            lastInjectedPath = path
            lastInjectedMirrored = settings.isMirrored
            lastInjectedRotation = settings.rotationAngle
            mediaEngine.play(context, path, surface, "Engine", settings.isMirrored, settings.rotationAngle)
        }
    }

    fun getDummySurface(): Surface = surfaceProvider.getDummySurface()
}
