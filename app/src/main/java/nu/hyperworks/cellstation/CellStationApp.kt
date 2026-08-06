package nu.hyperworks.cellstation

import android.app.Application
import kotlin.concurrent.thread

class CellStationApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Prefer Turnip out of the box: if no driver is selected yet but a
        // Turnip build is installed, make it the default (better performance
        // and far lower GPU memory use than the stock blob on Adreno).
        if (Settings.gpuDriver(this).isEmpty()) {
            GpuDriver.installed(this).firstOrNull { it.name.contains("turnip", ignoreCase = true) }
                ?.let { Settings.setGpuDriver(this, it.dir.name) }
        }
        GameLibrary.appContext = this
        GpuDriver.apply(this) // must precede initialize; needs a restart to change
        EmuBridge.initialize(filesDir.absolutePath, "00000001")
        thread(name = "EmuMainLoop", isDaemon = true) {
            EmuBridge.runMainLoop()
        }
    }
}
