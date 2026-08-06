package nu.hyperworks.cellstation

import android.content.Context
import android.os.Environment
import java.io.File
import java.util.Locale

/**
 * Finds bootable PS3 content on external storage.
 *
 * The core accepts three shapes, so the library keeps them distinct rather than
 * guessing from the extension alone:
 *  - a disc image (`.iso`), booted directly;
 *  - a game folder, where the actual payload is `PS3_GAME/USRDIR/EBOOT.BIN`
 *    (a disc dump) or `USRDIR/EBOOT.BIN` (an installed title);
 *  - a bare homebrew executable (`.elf` / `.self`).
 */
object GameLibrary {

    data class Entry(
        val title: String,
        /** Path handed to the core. */
        val bootPath: String,
        val kind: Kind,
        val sizeBytes: Long,
        /** TITLE_ID from PARAM.SFO (e.g. BLUS30443), when the game carries one. */
        val serial: String? = null,
        /** Path of the game's own ICON0.PNG (320x176), when present. */
        val iconPath: String? = null
    )

    enum class Kind { DISC_IMAGE, GAME_FOLDER, HOMEBREW }

    /** Directories scanned for content, in order. */
    fun searchRoots(): List<File> {
        val ext = Environment.getExternalStorageDirectory()
        return listOf(
            File(ext, "CellStation/games"),
            File(ext, "PS3"),
            File(ext, "Games/PS3"),
            File(ext, "roms/ps3"),
            File(ext, "ROMs/PS3")
        )
    }

    fun scan(extraRoots: List<File> = emptyList()): List<Entry> {
        val found = LinkedHashMap<String, Entry>()

        for (root in extraRoots + searchRoots()) {
            if (!root.isDirectory) continue
            val children = root.listFiles() ?: continue
            for (child in children.sortedBy { it.name.lowercase(Locale.ROOT) }) {
                classify(child)?.let { found.putIfAbsent(it.bootPath, it) }
            }
        }
        return found.values.toList()
    }

    /** Returns an entry if this file/folder is bootable, else null. */
    fun classify(file: File): Entry? {
        if (file.isDirectory) {
            val eboot = ebootIn(file) ?: return null
            // Games describe themselves: PARAM.SFO carries the display title
            // and serial, ICON0.PNG the 320x176 tile art. Disc dumps keep them
            // under PS3_GAME/, installed titles at the folder root.
            val meta = File(file, "PS3_GAME").takeIf { it.isDirectory } ?: file
            val sfo = ParamSfo.parse(File(meta, "PARAM.SFO"))
            val icon = File(meta, "ICON0.PNG").takeIf { it.isFile }
            return Entry(
                title = sfo?.get("TITLE")?.lineSequence()?.first()?.trim().takeUnless { it.isNullOrEmpty() } ?: file.name,
                bootPath = eboot.absolutePath,
                kind = Kind.GAME_FOLDER,
                sizeBytes = folderSize(file),
                serial = sfo?.get("TITLE_ID"),
                iconPath = icon?.absolutePath
            )
        }

        val name = file.name.lowercase(Locale.ROOT)
        return when {
            name.endsWith(".iso") -> {
                // Disc images carry PARAM.SFO/ICON0.PNG inside the image; the
                // bridge extracts them once into an app-private cache keyed by
                // (path, size, mtime) so a re-copied image refreshes.
                val meta = isoMetaDir(file)
                val sfo = meta?.let { ParamSfo.parse(File(it, "PARAM.SFO")) }
                val icon = meta?.let { m -> File(m, "ICON0.PNG").takeIf { it.isFile } }
                Entry(
                    title = sfo?.get("TITLE")?.lineSequence()?.first()?.trim().takeUnless { it.isNullOrEmpty() }
                        ?: file.nameWithoutExtension,
                    bootPath = file.absolutePath,
                    kind = Kind.DISC_IMAGE,
                    sizeBytes = file.length(),
                    serial = sfo?.get("TITLE_ID"),
                    iconPath = icon?.absolutePath
                )
            }
            name.endsWith(".elf") || name.endsWith(".self") || name.endsWith(".bin") ->
                Entry(file.name, file.absolutePath, Kind.HOMEBREW, file.length())
            else -> null
        }
    }

    /**
     * App context for the ISO metadata cache; set once at startup. Kept as a
     * nullable so `classify` degrades to filename titles if scanning happens
     * before init (it doesn't today).
     */
    @Volatile var appContext: Context? = null

    private fun isoMetaDir(iso: File): File? {
        val context = appContext ?: return null
        val key = "${iso.absolutePath}|${iso.length()}|${iso.lastModified()}".hashCode().toUInt().toString(16)
        val dir = File(File(context.filesDir, "iso_meta"), key)
        val stamp = File(dir, ".done")
        if (stamp.isFile) return dir
        dir.mkdirs()
        // One attempt per (path,size,mtime); a stamp with no PARAM.SFO next to
        // it means "tried, nothing extractable" and stops rescan churn.
        EmuBridge.extractIsoAssets(iso.absolutePath, dir.absolutePath)
        stamp.writeText("")
        return dir
    }

    private fun ebootIn(dir: File): File? {
        val candidates = listOf(
            File(dir, "PS3_GAME/USRDIR/EBOOT.BIN"),
            File(dir, "USRDIR/EBOOT.BIN"),
            File(dir, "PS3_GAME/USRDIR/EBOOT.SELF")
        )
        return candidates.firstOrNull { it.isFile }
    }

    private fun folderSize(dir: File): Long =
        dir.walkTopDown().filter { it.isFile }.map { it.length() }.sum()

    fun humanSize(bytes: Long): String = when {
        bytes >= 1L shl 30 -> String.format(Locale.ROOT, "%.1f GB", bytes.toDouble() / (1L shl 30))
        bytes >= 1L shl 20 -> String.format(Locale.ROOT, "%.0f MB", bytes.toDouble() / (1L shl 20))
        else -> String.format(Locale.ROOT, "%.0f KB", bytes.toDouble() / 1024.0)
    }
}
