package nu.hyperworks.cellstation

import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.widget.FrameLayout
import android.view.WindowManager
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlin.concurrent.thread

/**
 * Boots straight into a game. Public intent contract (docs/INTENTS.md):
 *   action  nu.hyperworks.cellstation.EMULATE
 *   extra   bootPath = filesystem path (content:// support comes later)
 */
class EmulationActivity : AppCompatActivity(), SurfaceHolder.Callback {

    companion object {
        const val ACTION_EMULATE = "nu.hyperworks.cellstation.EMULATE"
        const val EXTRA_BOOT_PATH = "bootPath"
        const val EXTRA_GAME_DIR = "gameDir"
        const val EXTRA_STRETCH = "stretch"
    }

    private var booted = false
    private val pad = PadState()
    private var overlay: TouchOverlayView? = null
    private var overlayMode = Settings.TouchOverlayMode.AUTO

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        val surfaceView = SurfaceView(this)
        val frame = FrameLayout(this)
        frame.addView(surfaceView)

        pad.nintendoLayout = Settings.nintendoLayout(this)
        overlayMode = Settings.touchOverlayMode(this)
        if (overlayMode != Settings.TouchOverlayMode.NEVER) {
            overlay = TouchOverlayView(this, pad).also { frame.addView(it) }
        }

        setContentView(frame)
        surfaceView.holder.addCallback(this)

        WindowInsetsControllerCompat(window, surfaceView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        EmuBridge.surfaceEvent(holder.surface, EmuBridge.SURFACE_READY)

        if (!booted) {
            booted = true
            // Accepts bootPath (path or URI), gameDir (folder-format), or a
            // data URI from ACTION_VIEW — see docs/INTENTS.md.
            val path = BootTarget.resolve(this, intent)
            if (path.isNullOrEmpty()) {
                Toast.makeText(this, R.string.no_boot_path, Toast.LENGTH_LONG).show()
                finish()
                return
            }
            EmulationService.start(this, java.io.File(path).nameWithoutExtension)

            val stretch = intentBoolean(EXTRA_STRETCH)
            thread(name = "EmuBoot") {
                val result = EmuBridge.boot(path)
                if (result != 0) {
                    runOnUiThread {
                        Toast.makeText(this, getString(R.string.boot_failed, result), Toast.LENGTH_LONG).show()
                        finish()
                    }
                    return@thread
                }
                // Emu.Load() reloads config.yml, so a per-launch override can only
                // be applied once the boot returns; the renderer re-reads the
                // setting every frame, well before the game presents anything.
                if (stretch != null) {
                    EmuBridge.setStretchToDisplayArea(stretch, persist = false)
                }
            }
        }
    }

    /**
     * Reads an optional boolean extra. `am start -e <name> true` delivers a
     * string, so frontends can pass either form; absent/unparsable means
     * "leave the persisted setting alone".
     */
    @Suppress("DEPRECATION")
    private fun intentBoolean(name: String): Boolean? {
        return when (val value = intent.extras?.get(name)) {
            is Boolean -> value
            is Number -> value.toInt() != 0
            is String -> when (value.lowercase()) {
                "true", "1", "on", "yes" -> true
                "false", "0", "off", "no" -> false
                else -> null
            }
            else -> null
        }
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        EmuBridge.surfaceEvent(holder.surface, EmuBridge.SURFACE_READY)
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        EmuBridge.surfaceEvent(null, EmuBridge.SURFACE_DESTROYED)
    }

    // Controller input: Android delivers gamepad events to the focused activity,
    // so forward them to the native pad handler and consume them. Non-gamepad
    // keys (e.g. volume, back on a phone) fall through to the default handling.
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean =
        handledByPad(pad.onKey(event)) || super.onKeyDown(keyCode, event)

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean =
        handledByPad(pad.onKey(event)) || super.onKeyUp(keyCode, event)

    override fun onGenericMotionEvent(event: MotionEvent): Boolean =
        handledByPad(pad.onMotion(event)) || super.onGenericMotionEvent(event)

    /**
     * In AUTO mode the on-screen controller is redundant once a real pad is in
     * use, so the first physical input retires it for this session.
     */
    private fun handledByPad(consumed: Boolean): Boolean {
        if (consumed && overlayMode == Settings.TouchOverlayMode.AUTO) {
            overlay?.let { view ->
                overlay = null
                view.releaseAll()
                (view.parent as? android.view.ViewGroup)?.removeView(view)
            }
        }
        return consumed
    }

    override fun onResume() {
        super.onResume()
        EmuBridge.setPadConnected(true)
    }

    override fun onPause() {
        // Release every button so a game doesn't see input stuck down while
        // the activity is backgrounded.
        EmuBridge.setPadConnected(false)
        super.onPause()
    }

    override fun onDestroy() {
        EmulationService.stop(this)
        EmuBridge.kill()
        super.onDestroy()
    }
}
