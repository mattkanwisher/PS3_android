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

    /**
     * Extracts PARAM.SFO / ICON0.PNG from a disc image into [outDir] using the
     * core's ISO reader. No-op while a game is running.
     */
    external fun extractIsoAssets(isoPath: String, outDir: String): Boolean

    /**
     * Deletes the compiled PPU/SPU/shader caches for one game (by TITLE_ID).
     * The next boot recompiles from scratch. Returns bytes freed; no-op while
     * a game is running.
     */
    external fun clearGameCache(serial: String): Long

    /**
     * Per-game settings (config/custom_configs/config_<SERIAL>.yml). The core
     * applies these on top of the global config when booting, so a game that
     * needs a compatibility setting doesn't impose it on the whole library.
     * [path] is "Section/Setting" exactly as the names appear in config.yml,
     * e.g. "Core/SPU XFloat Accuracy".
     */
    external fun gameConfigGet(serial: String, path: String): String

    /** Accepted values for [path], newline-separated; empty when unknown. */
    external fun gameConfigOptions(path: String): String

    external fun gameConfigSet(serial: String, path: String, value: String): Boolean

    /** True when this game has an override file at all. */
    external fun gameConfigExists(serial: String): Boolean

    /** Deletes the override file so the game follows the global config again. */
    external fun gameConfigReset(serial: String): Boolean

    /** Boots a game (path on the local filesystem). Returns game_boot_result (0 = ok). */
    external fun boot(path: String): Int

    /** Persisted "Stretch To Display Area" (config.yml). False = aspect-correct pillarboxing. */
    external fun stretchToDisplayArea(): Boolean

    /**
     * Stretches the emulated output to fill the display instead of honoring the
     * game's aspect ratio. Applies immediately (the setting is re-read per frame);
     * [persist] writes it to config.yml, otherwise it lasts for this boot only.
     */
    external fun setStretchToDisplayArea(enabled: Boolean, persist: Boolean)

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
