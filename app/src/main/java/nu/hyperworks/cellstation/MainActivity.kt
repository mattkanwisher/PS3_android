package nu.hyperworks.cellstation

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import kotlin.concurrent.thread

/**
 * Minimal bring-up UI: install firmware from a PUP, list games dropped into
 * Android/data/nu.hyperworks.cellstation/files/games/, boot one. The real
 * controller-first Compose UI comes later (see PLAN.md M3).
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
            val staged = java.io.File(cacheDir, "PS3UPDAT.PUP")
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 48, 32, 32)
        }

        root.addView(TextView(this).apply {
            text = getString(R.string.app_banner, EmuBridge.getVersion())
            textSize = 18f
            gravity = Gravity.CENTER_HORIZONTAL
        })

        status = TextView(this).apply {
            val fw = EmuBridge.firmwareVersion()
            text = if (fw.isEmpty()) getString(R.string.no_firmware)
                   else getString(R.string.firmware_version, fw)
            setPadding(0, 24, 0, 24)
        }
        root.addView(status)

        root.addView(Button(this).apply {
            text = getString(R.string.install_firmware)
            setOnClickListener { pickPup.launch(arrayOf("*/*")) }
        })

        root.addView(TextView(this).apply {
            text = getString(R.string.games_hint)
            setPadding(0, 32, 0, 8)
        })

        gameList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(gameList)

        setContentView(ScrollView(this).apply { addView(root) })
    }

    override fun onResume() {
        super.onResume()
        refreshGames()
    }

    private fun hasStorageAccess(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager()

    private fun requestStorageAccess() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        runCatching {
            startActivity(
                Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                    .setData(Uri.parse("package:$packageName"))
            )
        }.onFailure {
            startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
        }
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
        val appDir = getExternalFilesDir("games")?.also { it.mkdirs() }
        val entries = GameLibrary.scan(listOfNotNull(appDir))

        if (entries.isEmpty()) {
            val roots = GameLibrary.searchRoots().joinToString("\n") { it.absolutePath }
            gameList.addView(TextView(this).apply { text = getString(R.string.no_games, roots) })
            return
        }

        for (entry in entries) {
            gameList.addView(Button(this).apply {
                text = "${entry.title}  (${GameLibrary.humanSize(entry.sizeBytes)})"
                setOnClickListener {
                    startActivity(Intent(this@MainActivity, EmulationActivity::class.java).apply {
                        action = EmulationActivity.ACTION_EMULATE
                        putExtra(EmulationActivity.EXTRA_BOOT_PATH, entry.bootPath)
                    })
                }
            })
        }

        if (EmuBridge.firmwareVersion().isEmpty()) {
            Toast.makeText(this, R.string.no_firmware, Toast.LENGTH_LONG).show()
        }
    }
}
