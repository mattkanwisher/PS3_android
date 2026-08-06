package nu.hyperworks.cellstation

import android.content.Context
import android.net.Uri
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipInputStream

/**
 * Custom GPU drivers (adrenotools format: a zip holding meta.json plus the
 * driver .so, e.g. Mesa Turnip builds). Installed under filesDir/gpu_drivers/,
 * which satisfies adrenotools' requirement that the driver lives in
 * app-private storage (dlopen refuses world-writable locations).
 */
object GpuDriver {

    data class Entry(val dir: File, val name: String, val description: String, val libraryName: String)

    private fun driversRoot(context: Context) = File(context.filesDir, "gpu_drivers")

    fun installed(context: Context): List<Entry> =
        driversRoot(context).listFiles { f -> f.isDirectory }.orEmpty().mapNotNull { load(it) }
            .sortedBy { it.name }

    fun find(context: Context, dirName: String): Entry? =
        File(driversRoot(context), dirName).takeIf { it.isDirectory }?.let { load(it) }

    private fun load(dir: File): Entry? = runCatching {
        val meta = JSONObject(File(dir, "meta.json").readText())
        val library = meta.getString("libraryName")
        if (!File(dir, library).isFile) return null
        Entry(
            dir = dir,
            name = meta.optString("name", dir.name),
            description = meta.optString("description", ""),
            libraryName = library
        )
    }.getOrNull()

    /** Unpacks a driver zip; returns the new entry or null if it isn't one. */
    fun install(context: Context, uri: Uri): Entry? {
        val staging = File(driversRoot(context), ".staging-${System.currentTimeMillis()}")
        staging.mkdirs()
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                ZipInputStream(input.buffered()).use { zip ->
                    while (true) {
                        val entry = zip.nextEntry ?: break
                        if (entry.isDirectory) continue
                        // Flatten and reject path traversal; driver zips are flat.
                        val name = File(entry.name).name
                        if (name.isEmpty() || name.startsWith(".")) continue
                        File(staging, name).outputStream().use { out -> zip.copyTo(out) }
                    }
                }
            } ?: return null

            val entry = load(staging) ?: return null

            // Final name from the metadata, so reinstalls overwrite.
            val slug = entry.name.replace(Regex("[^A-Za-z0-9._-]"), "_")
            val target = File(driversRoot(context), slug)
            target.deleteRecursively()
            if (!staging.renameTo(target)) return null
            return load(target)
        } finally {
            staging.deleteRecursively()
        }
    }

    fun uninstall(context: Context, entry: Entry) {
        entry.dir.deleteRecursively()
    }

    /**
     * Applies the persisted selection for this process; call before
     * [EmuBridge.initialize]. Falls back to the system driver when the
     * selected driver is gone.
     */
    fun apply(context: Context) {
        val selected = Settings.gpuDriver(context)
        val entry = if (selected.isEmpty()) null else find(context, selected)
        if (entry == null) {
            EmuBridge.setGpuDriver(null, null, null, null)
        } else {
            EmuBridge.setGpuDriver(
                entry.dir.absolutePath + "/",
                entry.libraryName,
                context.applicationInfo.nativeLibraryDir,
                context.cacheDir.absolutePath
            )
        }
    }
}
