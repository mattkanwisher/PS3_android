package nu.hyperworks.cellstation

import android.view.Surface

/**
 * JNI surface over the RPCS3 core (libcellstation.so). Keep in sync with
 * native/bridge/bridge.cpp. CellStation is based on RPCS3 (GPL-2.0).
 */
object EmuBridge {
    init {
        System.loadLibrary("cellstation")
    }

    const val SURFACE_READY = 0
    const val SURFACE_DESTROYED = 2

    /**
     * Selects the GPU driver for this process; must be called before
     * [initialize] (driver changes take effect on the next app start).
     * Null [driverDir] means the system driver.
     */
    external fun setGpuDriver(driverDir: String?, driverName: String?, hookDir: String?, tmpDir: String?)

    external fun initialize(rootDir: String, user: String): Boolean

    /** Blocks forever, draining the core's main-thread queue. Call from a dedicated thread. */
    external fun runMainLoop()

    external fun installFirmware(fd: Int): Boolean

    /** Installs firmware from a path the app can open directly (see MainActivity: SAF fds can't be reopened). */
    external fun installFirmwarePath(path: String): Boolean

    external fun firmwareVersion(): String

    /** Boots a game (path on the local filesystem). Returns game_boot_result (0 = ok). */
    external fun boot(path: String): Int

    external fun surfaceEvent(surface: Surface?, event: Int): Boolean

    /** Pushes a full pad snapshot (values 0..255, indexed by PadButton). */
    external fun setPadState(values: ByteArray)

    external fun setPadConnected(connected: Boolean)

    external fun kill()
    external fun pause()
    external fun resume()
    external fun getState(): Int
    external fun getVersion(): String
}
