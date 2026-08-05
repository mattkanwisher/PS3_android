package nu.hyperworks.cellstation

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings as AndroidSettings
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import java.io.File
import kotlin.concurrent.thread

/**
 * Library screen: the installed games are the content, and everything else
 * (firmware, adding a game, choosing folders to scan, settings) lives behind
 * the menu button.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var status: TextView
    private lateinit var gameList: LinearLayout

    private val pickPup = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@registerForActivityResult
        status.text = getString(R.string.installing_firmware)
        thread {
            // The core reopens the file by path, which fails for SAF fds on
            // scoped-storage FUSE mounts — stage a private copy instead.
            val staged = File(cacheDir, "PS3UPDAT.PUP")
            val ok = try {
                contentResolver.openInputStream(uri)?.use { input ->
                    staged.outputStream().use { output -> input.copyTo(output, 1 shl 20) }
                } ?: return@thread
                EmuBridge.installFirmwarePath(staged.absolutePath)
            } finally {
                staged.delete()
            }
            runOnUiThread {
                status.text = if (ok) {
                    getString(R.string.firmware_version, EmuBridge.firmwareVersion())
                } else {
                    getString(R.string.firmware_install_failed)
                }
            }
        }
    }

    /** Boot a single game the library roots don't cover. */
    private val pickGame = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@registerForActivityResult
        val path = BootTarget.fromUri(this, uri)
        if (path == null) {
            Toast.makeText(this, R.string.game_unreadable, Toast.LENGTH_LONG).show()
        } else {
            boot(path)
        }
    }

    /** Remember a folder to scan on top of the conventional roots. */
    private val pickFolder = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri ?: return@registerForActivityResult
        val path = BootTarget.pathFromTreeUri(uri)
        if (path == null) {
            Toast.makeText(this, R.string.folder_unreadable, Toast.LENGTH_LONG).show()
        } else {
            Settings.addScanFolder(this, path)
            Toast.makeText(this, getString(R.string.folder_added, path), Toast.LENGTH_SHORT).show()
            refreshGames()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 40, 32, 32)
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(TextView(this).apply {
            text = getString(R.string.app_name)
            textSize = 22f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        header.addView(Button(this).apply {
            text = getString(R.string.menu)
            setOnClickListener { showMenu(it) }
        })
        root.addView(header)

        status = TextView(this).apply { setPadding(0, 16, 0, 24) }
        root.addView(status)

        gameList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(gameList)

        setContentView(ScrollView(this).apply { addView(root) })
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
        refreshGames()
    }

    private fun showMenu(anchor: View) {
        PopupMenu(this, anchor).apply {
            menu.add(0, MENU_FIRMWARE, 0, R.string.install_firmware)
            menu.add(0, MENU_ADD_GAME, 1, R.string.add_game)
            menu.add(0, MENU_SCAN_FOLDER, 2, R.string.scan_folder)
            menu.add(0, MENU_REFRESH, 3, R.string.refresh)
            menu.add(0, MENU_SETTINGS, 4, R.string.settings)
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    MENU_FIRMWARE -> pickPup.launch(arrayOf("*/*"))
                    MENU_ADD_GAME -> pickGame.launch(arrayOf("*/*"))
                    MENU_SCAN_FOLDER -> pickFolder.launch(null)
                    MENU_REFRESH -> refreshGames()
                    MENU_SETTINGS -> startActivity(Intent(this@MainActivity, SettingsActivity::class.java))
                }
                true
            }
            show()
        }
    }

    private fun refreshStatus() {
        val fw = EmuBridge.firmwareVersion()
        status.text = if (fw.isEmpty()) getString(R.string.no_firmware)
                      else getString(R.string.firmware_version, fw)
    }

    private fun hasStorageAccess(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager()

    private fun requestStorageAccess() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        runCatching {
            startActivity(
                Intent(AndroidSettings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                    .setData(Uri.parse("package:$packageName"))
            )
        }.onFailure {
            startActivity(Intent(AndroidSettings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
        }
    }

    private fun boot(path: String) {
        startActivity(Intent(this, EmulationActivity::class.java).apply {
            action = EmulationActivity.ACTION_EMULATE
            putExtra(EmulationActivity.EXTRA_BOOT_PATH, path)
        })
    }

    private fun refreshGames() {
        gameList.removeAllViews()

        if (!hasStorageAccess()) {
            gameList.addView(TextView(this).apply { text = getString(R.string.storage_needed) })
            gameList.addView(Button(this).apply {
                text = getString(R.string.grant_storage)
                setOnClickListener { requestStorageAccess() }
            })
            return
        }

        // App-external dir stays supported so sideloaded homebrew keeps working.
        val extraRoots = Settings.scanFolders(this).map { File(it) } +
            listOfNotNull(getExternalFilesDir("games")?.also { it.mkdirs() })
        val entries = GameLibrary.scan(extraRoots)

        if (entries.isEmpty()) {
            val roots = (extraRoots + GameLibrary.searchRoots())
                .joinToString("\n") { it.absolutePath }
            gameList.addView(TextView(this).apply { text = getString(R.string.no_games, roots) })
            return
        }

        for (entry in entries) {
            gameList.addView(Button(this).apply {
                text = "${entry.title}\n${label(entry.kind)} · ${GameLibrary.humanSize(entry.sizeBytes)}"
                setOnClickListener { boot(entry.bootPath) }
            })
        }

        if (EmuBridge.firmwareVersion().isEmpty()) {
            Toast.makeText(this, R.string.no_firmware, Toast.LENGTH_LONG).show()
        }
    }

    private fun label(kind: GameLibrary.Kind): String = getString(
        when (kind) {
            GameLibrary.Kind.DISC_IMAGE -> R.string.kind_disc
            GameLibrary.Kind.GAME_FOLDER -> R.string.kind_folder
            GameLibrary.Kind.HOMEBREW -> R.string.kind_homebrew
        }
    )

    private companion object {
        const val MENU_FIRMWARE = 1
        const val MENU_ADD_GAME = 2
        const val MENU_SCAN_FOLDER = 3
        const val MENU_REFRESH = 4
        const val MENU_SETTINGS = 5
    }
}
