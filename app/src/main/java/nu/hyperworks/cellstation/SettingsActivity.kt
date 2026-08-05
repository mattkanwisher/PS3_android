package nu.hyperworks.cellstation

import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import java.io.File

/** App settings. Emulator-core options still live in the on-device config.yml. */
class SettingsActivity : AppCompatActivity() {

    private lateinit var content: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 40, 32, 32)
        }
        setContentView(ScrollView(this).apply { addView(content) })
        rebuild()
    }

    private fun rebuild() {
        content.removeAllViews()

        content.addView(TextView(this).apply {
            text = getString(R.string.settings)
            textSize = 22f
            setPadding(0, 0, 0, 24)
        })

        content.addView(sectionHeader(R.string.section_input))
        content.addView(Button(this).apply {
            text = touchOverlayLabel()
            setOnClickListener {
                // Cycle Auto -> Always -> Off; Auto hides the overlay as soon as
                // a physical controller is used.
                val next = when (Settings.touchOverlayMode(this@SettingsActivity)) {
                    Settings.TouchOverlayMode.AUTO -> Settings.TouchOverlayMode.ALWAYS
                    Settings.TouchOverlayMode.ALWAYS -> Settings.TouchOverlayMode.NEVER
                    Settings.TouchOverlayMode.NEVER -> Settings.TouchOverlayMode.AUTO
                }
                Settings.setTouchOverlayMode(this@SettingsActivity, next)
                text = touchOverlayLabel()
            }
        })

        content.addView(sectionHeader(R.string.section_folders))
        val folders = Settings.scanFolders(this).sorted()
        if (folders.isEmpty()) {
            content.addView(TextView(this).apply {
                text = getString(R.string.no_scan_folders)
                setPadding(0, 0, 0, 16)
            })
        } else {
            for (path in folders) {
                content.addView(Button(this).apply {
                    val missing = if (File(path).isDirectory) "" else getString(R.string.folder_missing)
                    text = getString(R.string.remove_folder, path + missing)
                    setOnClickListener {
                        Settings.removeScanFolder(this@SettingsActivity, path)
                        rebuild()
                    }
                })
            }
        }

        content.addView(sectionHeader(R.string.section_about))
        content.addView(TextView(this).apply {
            text = getString(R.string.app_banner, EmuBridge.getVersion())
        })
    }

    private fun sectionHeader(resId: Int) = TextView(this).apply {
        text = getString(resId)
        textSize = 16f
        setPadding(0, 32, 0, 8)
    }

    private fun touchOverlayLabel(): String {
        val mode = when (Settings.touchOverlayMode(this)) {
            Settings.TouchOverlayMode.AUTO -> getString(R.string.touch_auto)
            Settings.TouchOverlayMode.ALWAYS -> getString(R.string.touch_always)
            Settings.TouchOverlayMode.NEVER -> getString(R.string.touch_never)
        }
        return getString(R.string.touch_overlay, mode)
    }
}
