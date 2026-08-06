package nu.hyperworks.cellstation

import android.app.Application
import kotlin.concurrent.thread

class CellStationApp : Application() {
    override fun onCreate() {
        super.onCreate()
        GpuDriver.apply(this) // must precede initialize; needs a restart to change
        EmuBridge.initialize(filesDir.absolutePath, "00000001")
        thread(name = "EmuMainLoop", isDaemon = true) {
            EmuBridge.runMainLoop()
        }
    }
}
