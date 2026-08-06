package nu.hyperworks.cellstation

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings as AndroidSettings
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import nu.hyperworks.cellstation.Ui.dp
import java.io.File
import kotlin.concurrent.thread

/**
 * First-run wizard: exactly three gates — firmware, games folder, GPU driver —
 * each showing its live state, each skippable with the consequence stated
 * (homebrew boots firmware-free through the core's HLE mode). Landscape lays
 * the gates side by side, portrait stacks them.
 */
class WelcomeActivity : AppCompatActivity() {

    private lateinit var steps: LinearLayout

    private val pickPup = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@registerForActivityResult
        Toast.makeText(this, R.string.installing_firmware, Toast.LENGTH_SHORT).show()
        thread {
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
                if (!ok) Toast.makeText(this, R.string.firmware_install_failed, Toast.LENGTH_LONG).show()
                rebuild()
            }
        }
    }

    private val pickFolder = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri ?: return@registerForActivityResult
        val path = BootTarget.pathFromTreeUri(uri)
        if (path == null) {
            Toast.makeText(this, R.string.folder_unreadable, Toast.LENGTH_LONG).show()
        } else {
            Settings.addScanFolder(this, path)
        }
        rebuild()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Settings.markSetupSeen(this)
        rebuild()
    }

    override fun onResume() {
        super.onResume()
        // Storage permission is granted in the system settings app; reflect it
        // when the user comes back.
        rebuild()
    }

    private fun rebuild() {
        val land = Ui.isLandscape(this)

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setBackgroundColor(Ui.BG)
            setPadding(dp(24), dp(if (land) 22 else 40), dp(24), dp(24))
        }

        column.addView(Ui.title(this, getString(R.string.welcome_title), 24f))
        column.addView(Ui.body(this, getString(R.string.welcome_subtitle), Ui.MUTED, 14f).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(6), 0, dp(20))
        })

        steps = LinearLayout(this).apply {
            orientation = if (land) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
        }
        buildSteps(land)
        column.addView(steps, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))

        column.addView(Ui.actionButton(this, getString(R.string.welcome_done), primary = true) {
            Settings.setSetupDone(this)
            finish()
        }.apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(22) }
        })
        column.addView(TextView(this).apply {
            text = getString(R.string.welcome_skip)
            textSize = 12.5f
            setTextColor(Ui.MUTED)
            gravity = Gravity.CENTER
            isFocusable = true
            isClickable = true
            setPadding(dp(12), dp(10), dp(12), dp(6))
            setOnClickListener {
                Settings.setSetupDone(this@WelcomeActivity)
                finish()
            }
        })

        setContentView(ScrollView(this).apply {
            setBackgroundColor(Ui.BG)
            isVerticalScrollBarEnabled = false
            addView(column)
        })
    }

    private fun buildSteps(land: Boolean) {
        val fw = EmuBridge.firmwareVersion()
        addStep(
            land,
            n = 1,
            title = getString(R.string.step_firmware),
            note = getString(R.string.step_firmware_note),
            stateLabel = if (fw.isEmpty()) getString(R.string.step_pending)
                         else getString(R.string.step_firmware_ok, fw),
            stateColor = if (fw.isEmpty()) Ui.WARN else Ui.OK,
            action = getString(R.string.step_firmware_action)
        ) { pickPup.launch(arrayOf("*/*")) }

        val storage = hasStorageAccess()
        val folders = Settings.scanFolders(this)
        addStep(
            land,
            n = 2,
            title = getString(R.string.step_games),
            note = getString(R.string.step_games_note),
            stateLabel = when {
                !storage -> getString(R.string.step_games_permission)
                folders.isEmpty() -> getString(R.string.step_games_default)
                else -> getString(R.string.step_games_ok, folders.size)
            },
            stateColor = if (storage) Ui.OK else Ui.WARN,
            action = if (storage) getString(R.string.step_games_action)
                     else getString(R.string.grant_storage)
        ) {
            if (storage) pickFolder.launch(null) else requestStorageAccess()
        }

        val driver = Settings.gpuDriver(this)
        // Show the driver's human name from its metadata, not the directory slug.
        val driverName = if (driver.isEmpty()) null else (GpuDriver.find(this, driver)?.name ?: driver)
        addStep(
            land,
            n = 3,
            title = getString(R.string.step_driver),
            note = getString(R.string.step_driver_note),
            stateLabel = if (driverName == null) getString(R.string.chip_driver_system)
                         else getString(R.string.chip_driver, driverName),
            stateColor = if (driverName == null) Ui.WARN else Ui.OK,
            action = getString(R.string.step_driver_action)
        ) {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
    }

    private fun addStep(
        land: Boolean,
        n: Int,
        title: String,
        note: String,
        stateLabel: String,
        stateColor: Int,
        action: String,
        onAction: () -> Unit
    ) {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Ui.rounded(Ui.PANEL, 12, this@WelcomeActivity, Ui.LINE)
            setPadding(dp(16), dp(14), dp(16), dp(14))
        }
        card.addView(Ui.body(this, getString(R.string.step_n, n), Ui.VIOLET, 11f))
        card.addView(Ui.title(this, title, 16f).apply { setPadding(0, dp(4), 0, dp(4)) })
        card.addView(Ui.body(this, note, Ui.MUTED, 12.5f).apply { setPadding(0, 0, 0, dp(10)) })
        card.addView(Ui.statusChip(this, stateLabel, stateColor))
        card.addView(Ui.actionButton(this, action, primary = false, onClick = onAction).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(10) }
        })

        val lp = if (land) {
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply {
                if (n > 1) marginStart = dp(12)
            }
        } else {
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { if (n > 1) topMargin = dp(12) }
        }
        steps.addView(card, lp)
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
}
