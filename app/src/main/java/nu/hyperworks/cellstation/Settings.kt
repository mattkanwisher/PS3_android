package nu.hyperworks.cellstation

import android.content.Context

/** App-side preferences (emulator settings live in the core's config.yml). */
object Settings {

    /** When the on-screen controller is shown. */
    enum class TouchOverlayMode {
        /** Shown until a physical controller is used, then hidden. */
        AUTO,
        ALWAYS,
        NEVER;

        companion object {
            fun from(name: String?): TouchOverlayMode =
                entries.firstOrNull { it.name == name } ?: AUTO
        }
    }

    private const val PREFS = "cellstation"
    private const val KEY_TOUCH_OVERLAY = "touch_overlay_mode"
    private const val KEY_SCAN_FOLDERS = "scan_folders"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun touchOverlayMode(context: Context): TouchOverlayMode =
        TouchOverlayMode.from(prefs(context).getString(KEY_TOUCH_OVERLAY, null))

    fun setTouchOverlayMode(context: Context, mode: TouchOverlayMode) {
        prefs(context).edit().putString(KEY_TOUCH_OVERLAY, mode.name).apply()
    }

    /** Extra folders the user pointed us at, on top of the conventional roots. */
    fun scanFolders(context: Context): Set<String> =
        prefs(context).getStringSet(KEY_SCAN_FOLDERS, emptySet()).orEmpty()

    fun addScanFolder(context: Context, path: String) {
        val updated = scanFolders(context) + path
        prefs(context).edit().putStringSet(KEY_SCAN_FOLDERS, updated).apply()
    }

    fun removeScanFolder(context: Context, path: String) {
        val updated = scanFolders(context) - path
        prefs(context).edit().putStringSet(KEY_SCAN_FOLDERS, updated).apply()
    }

    /**
     * Positional face-button mapping for Nintendo-layout pads (the Thor's
     * built-in controller): bottom = Cross, right = Circle, top = Triangle,
     * left = Square, regardless of the printed A/B/X/Y labels.
     */
    fun nintendoLayout(context: Context): Boolean =
        prefs(context).getBoolean(KEY_NINTENDO_LAYOUT, false)

    fun setNintendoLayout(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_NINTENDO_LAYOUT, enabled).apply()
    }

    private const val KEY_NINTENDO_LAYOUT = "nintendo_layout"

    /** Directory name under gpu_drivers/ of the selected driver; "" = system. */
    fun gpuDriver(context: Context): String =
        prefs(context).getString(KEY_GPU_DRIVER, "").orEmpty()

    fun setGpuDriver(context: Context, dirName: String) {
        prefs(context).edit().putString(KEY_GPU_DRIVER, dirName).apply()
    }

    private const val KEY_GPU_DRIVER = "gpu_driver"
}
