package nu.hyperworks.cellstation

import android.graphics.Typeface
import android.os.Bundle
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import nu.hyperworks.cellstation.Ui.dp

/**
 * Per-game compatibility settings, stored by the core as
 * config/custom_configs/config_<SERIAL>.yml and applied on top of the global
 * config at boot.
 *
 * Deliberately a short list: these are the settings that decide whether a game
 * runs at all, not a mirror of rpcs3's full options tree. Everything else stays
 * global so one awkward title can't quietly slow the rest of the library down.
 */
class GameSettingsActivity : AppCompatActivity() {

    /** [path] is "Section/Setting" as spelled in config.yml. */
    private data class Entry(val path: String, val label: Int, val hint: Int)

    private lateinit var pane: LinearLayout
    private lateinit var serial: String
    private var title: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        serial = intent.getStringExtra(EXTRA_SERIAL).orEmpty()
        title = intent.getStringExtra(EXTRA_TITLE).orEmpty()

        if (serial.isEmpty()) {
            Toast.makeText(this, R.string.game_settings_no_serial, Toast.LENGTH_LONG).show()
            finish()
            return
        }

        setContentView(scaffold())
        render()
    }

    private fun scaffold(): ViewGroup {
        val scroll = ScrollView(this).apply { setBackgroundColor(Ui.BG) }
        pane = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(24))
        }
        scroll.addView(
            pane,
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        return scroll
    }

    private fun render() {
        pane.removeAllViews()

        pane.addView(Ui.title(this, getString(R.string.game_settings_title)))
        pane.addView(
            Ui.body(this, listOf(title, serial).filter { it.isNotEmpty() }.joinToString(" · "))
                .apply { setPadding(0, dp(2), 0, dp(4)) }
        )
        pane.addView(
            Ui.body(this, getString(R.string.game_settings_sub), Ui.MUTED, 12.5f)
                .apply { setPadding(0, 0, 0, dp(10)) }
        )

        val overridden = EmuBridge.gameConfigExists(serial)
        pane.addView(
            Ui.statusChip(
                this,
                getString(if (overridden) R.string.game_settings_custom else R.string.game_settings_global),
                if (overridden) Ui.WARN else Ui.MUTED
            ).apply { setPadding(0, 0, 0, dp(6)) }
        )
        pane.addView(Ui.divider(this, vertical = false))

        for (entry in ENTRIES) {
            val options = EmuBridge.gameConfigOptions(entry.path)
                .split('\n')
                .filter { it.isNotBlank() }

            // A setting rpcs3 renamed or dropped upstream: skip it rather than
            // render a control that cannot resolve to a config node.
            if (options.isEmpty()) continue

            val current = EmuBridge.gameConfigGet(serial, entry.path)
            val selected = options.indexOf(current).coerceAtLeast(0)

            row(
                getString(entry.label),
                getString(entry.hint),
                Ui.segmented(this, options.map(::prettify), selected) { i ->
                    if (EmuBridge.gameConfigSet(serial, entry.path, options[i])) {
                        // Re-render so the global/custom chip reflects the file
                        // this write may have just created.
                        render()
                        Toast.makeText(
                            this,
                            getString(R.string.game_settings_applies_next_boot),
                            Toast.LENGTH_SHORT
                        ).show()
                    } else {
                        Toast.makeText(this, R.string.game_settings_rejected, Toast.LENGTH_LONG).show()
                    }
                }
            )
        }

        pane.addView(
            Ui.actionButton(this, getString(R.string.game_settings_reset), primary = false) {
                AlertDialog.Builder(this)
                    .setTitle(R.string.game_settings_reset)
                    .setMessage(getString(R.string.game_settings_reset_confirm, title.ifEmpty { serial }))
                    .setPositiveButton(R.string.game_settings_reset) { _, _ ->
                        EmuBridge.gameConfigReset(serial)
                        render()
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }.apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(14) }
            }
        )
    }

    /** The core spells booleans "true"/"false"; give the toggles real labels. */
    private fun prettify(value: String): String = when (value) {
        "true" -> getString(R.string.opt_on)
        "false" -> getString(R.string.opt_off)
        else -> value
    }

    private fun row(title: String, subtitle: String, control: android.view.View) {
        val item = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(10), 0, dp(12))
        }
        item.addView(TextView(this).apply {
            text = title
            textSize = 15f
            setTextColor(Ui.INK)
            typeface = Typeface.DEFAULT_BOLD
        })
        item.addView(Ui.body(this, subtitle, Ui.MUTED, 12.5f).apply {
            setPadding(0, dp(2), 0, dp(8))
        })
        item.addView(control)
        pane.addView(item)
        pane.addView(Ui.divider(this, vertical = false))
    }

    companion object {
        const val EXTRA_SERIAL = "serial"
        const val EXTRA_TITLE = "title"

        private val ENTRIES = listOf(
            Entry(
                "Core/SPU XFloat Accuracy",
                R.string.gs_spu_xfloat, R.string.gs_spu_xfloat_sub
            ),
            Entry(
                "Core/SPU Block Size",
                R.string.gs_spu_block, R.string.gs_spu_block_sub
            ),
            Entry(
                "Core/Accurate SPU DMA",
                R.string.gs_spu_dma, R.string.gs_spu_dma_sub
            ),
            Entry(
                "Video/Write Color Buffers",
                R.string.gs_wcb, R.string.gs_wcb_sub
            ),
            Entry(
                "Video/Strict Rendering Mode",
                R.string.gs_strict, R.string.gs_strict_sub
            )
        )
    }
}
