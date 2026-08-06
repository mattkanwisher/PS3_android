package nu.hyperworks.cellstation

import android.app.Dialog
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.ViewGroup
import android.view.Window
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import nu.hyperworks.cellstation.Ui.dp

/**
 * Controller button mapping, the listen-for-input pattern every major
 * emulator uses (AetherSX2, Dolphin, PPSSPP): tap a PS3 control, press the
 * physical button to bind it. Two presets cover the common layouts; sticks
 * and analog triggers stay axis-driven and are not remapped here.
 */
class ControllerMappingActivity : AppCompatActivity() {

    private lateinit var list: LinearLayout
    private var mapping: MutableMap<Int, Int> = HashMap()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        mapping = KeyMap.load(this).toMutableMap()

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Ui.BG)
            setPadding(dp(20), dp(18), dp(20), dp(18))
        }
        column.addView(Ui.title(this, getString(R.string.mapping_title), 21f))
        column.addView(Ui.body(this, getString(R.string.mapping_subtitle), Ui.MUTED, 13f).apply {
            setPadding(0, dp(4), 0, dp(14))
        })

        // Presets row.
        val presets = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun preset(label: String, map: Map<Int, Int>?) {
            presets.addView(Ui.actionButton(this, label, primary = false) {
                if (map == null) KeyMap.reset(this) else KeyMap.save(this, map)
                mapping = KeyMap.load(this).toMutableMap()
                renderRows()
                Toast.makeText(this, R.string.mapping_applied, Toast.LENGTH_SHORT).show()
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = dp(8)
            })
        }
        preset(getString(R.string.preset_standard), KeyMap.DEFAULT)
        preset(getString(R.string.preset_swapped), KeyMap.SWAPPED)
        preset(getString(R.string.preset_reset), null)
        column.addView(presets)
        column.addView(Ui.divider(this, vertical = false).apply {
            (layoutParams as LinearLayout.LayoutParams).topMargin = dp(14)
        })

        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        column.addView(ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            addView(list)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        setContentView(column)
        renderRows()
    }

    private fun renderRows() {
        list.removeAllViews()
        for ((target, name) in KeyMap.TARGETS) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                isFocusable = true
                isClickable = true
                background = Ui.focusable(this@ControllerMappingActivity, android.graphics.Color.TRANSPARENT, 9)
                setPadding(dp(10), dp(11), dp(10), dp(11))
                setOnClickListener { capture(target, name) }
            }
            row.addView(TextView(this).apply {
                text = name
                textSize = 14.5f
                setTextColor(Ui.INK)
                typeface = Typeface.DEFAULT_BOLD
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            val bound = KeyMap.boundKeys(mapping, target)
            row.addView(Ui.body(
                this,
                bound.ifEmpty { getString(R.string.mapping_unbound) },
                if (bound.isEmpty()) Ui.WARN else Ui.MUTED,
                13f
            ))
            list.addView(row)
            list.addView(Ui.divider(this, vertical = false))
        }
    }

    /** Modal capture: the next gamepad key pressed becomes the binding. */
    private fun capture(target: Int, name: String) {
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)

        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            background = Ui.rounded(Ui.PANEL_RAISED, 14, this@ControllerMappingActivity, Ui.LINE)
            setPadding(dp(28), dp(22), dp(28), dp(22))
        }
        box.addView(Ui.title(this, name, 17f))
        box.addView(Ui.body(this, getString(R.string.mapping_press_now), Ui.MUTED, 13.5f).apply {
            setPadding(0, dp(6), 0, 0)
            gravity = Gravity.CENTER
        })
        dialog.setContentView(box)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        dialog.setOnKeyListener { _, keyCode, event ->
            if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener true
            // Back on a non-gamepad source cancels; anything from a pad binds.
            if (keyCode == KeyEvent.KEYCODE_BACK && !PadState.isFromGamepad(event.source)) {
                dialog.dismiss()
                return@setOnKeyListener true
            }
            if (!PadState.isFromGamepad(event.source)) return@setOnKeyListener true

            // One key drives one control: rebinding steals it from any other.
            mapping.remove(keyCode)
            mapping[keyCode] = target
            KeyMap.save(this, mapping)
            dialog.dismiss()
            renderRows()
            true
        }
        dialog.show()
    }
}
