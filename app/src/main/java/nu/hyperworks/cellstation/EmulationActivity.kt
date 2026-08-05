package nu.hyperworks.cellstation

import android.os.Bundle
import android.view.SurfaceHolder
import android.view.SurfaceView
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
    }

    private var booted = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        val surfaceView = SurfaceView(this)
        setContentView(surfaceView)
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
            val path = intent.getStringExtra(EXTRA_BOOT_PATH) ?: intent.data?.path
            if (path.isNullOrEmpty()) {
                Toast.makeText(this, R.string.no_boot_path, Toast.LENGTH_LONG).show()
                finish()
                return
            }
            thread(name = "EmuBoot") {
                val result = EmuBridge.boot(path)
                if (result != 0) {
                    runOnUiThread {
                        Toast.makeText(this, getString(R.string.boot_failed, result), Toast.LENGTH_LONG).show()
                        finish()
                    }
                }
            }
        }
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        EmuBridge.surfaceEvent(holder.surface, EmuBridge.SURFACE_READY)
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        EmuBridge.surfaceEvent(null, EmuBridge.SURFACE_DESTROYED)
    }

    override fun onDestroy() {
        EmuBridge.kill()
        super.onDestroy()
    }
}
