package br.com.movingdots

import android.content.Context
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.view.Display
import android.view.View
import android.view.WindowManager

/**
 * Android 15+ only lets an app start a foreground service from the background if it holds the
 * overlay permission *and* shows an overlay window. Activity samples may not carry the exemption
 * that transition events have, so when a start is refused we show an invisible 1x1 overlay first,
 * then retry. The service removes it once its own overlay is up (or [hide] runs on a timeout).
 */
object OverlayBootstrap {
    private var view: View? = null
    private var windowManager: WindowManager? = null

    fun show(context: Context): Boolean {
        if (view != null) return true
        return try {
            val display = context.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
            val windowContext = context.createDisplayContext(display)
                .createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null)
            val wm = windowContext.getSystemService(WindowManager::class.java)
            val v = View(windowContext)
            wm.addView(v, WindowManager.LayoutParams(
                1, 1, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT,
            ))
            view = v
            windowManager = wm
            true
        } catch (e: RuntimeException) {
            DetectionLog(context).add("Overlay bootstrap failed: $e")
            false
        }
    }

    fun hide() {
        view?.let { runCatching { windowManager?.removeView(it) } }
        view = null
        windowManager = null
    }
}
