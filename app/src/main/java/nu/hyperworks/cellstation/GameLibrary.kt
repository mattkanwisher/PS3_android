package nu.hyperworks.cellstation

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
        val sizeBytes: Long
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
            // The core resolves the rest of the layout from the folder itself.
            return Entry(file.name, eboot.absolutePath, Kind.GAME_FOLDER, folderSize(file))
        }

        val name = file.name.lowercase(Locale.ROOT)
        return when {
            name.endsWith(".iso") ->
                Entry(file.nameWithoutExtension, file.absolutePath, Kind.DISC_IMAGE, file.length())
            name.endsWith(".elf") || name.endsWith(".self") || name.endsWith(".bin") ->
                Entry(file.name, file.absolutePath, Kind.HOMEBREW, file.length())
            else -> null
        }
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
