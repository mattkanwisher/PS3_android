package nu.hyperworks.cellstation

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Design tokens and small view builders for the touch UI (docs: UI research
 * page). One place for the palette so every screen matches; everything is
 * plain framework widgets so the app keeps zero UI dependencies.
 */
object Ui {

    // Palette (mirrors the design research page).
    val BG = Color.parseColor("#0B0F19")
    val PANEL = Color.parseColor("#131A2B")
    val PANEL_RAISED = Color.parseColor("#151C2E")
    val LINE = Color.parseColor("#2A3145")
    val INK = Color.parseColor("#E9EDF6")
    val MUTED = Color.parseColor("#97A1B8")
    val ACCENT = Color.parseColor("#5BD8F0")
    val ON_ACCENT = Color.parseColor("#05202A")
    val VIOLET = Color.parseColor("#8B7CFA")
    val OK = Color.parseColor("#58D68A")
    val WARN = Color.parseColor("#F0B84F")
    val BAD = Color.parseColor("#F07059")

    /** PS3 ICON0 aspect: 320x176. */
    const val ICON_W = 320
    const val ICON_H = 176

    fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    fun isLandscape(context: Context): Boolean =
        context.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    fun rounded(color: Int, radiusDp: Int, context: Context, strokeColor: Int = 0): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = context.dp(radiusDp).toFloat()
            if (strokeColor != 0) setStroke(context.dp(1), strokeColor)
        }

    /** Panel background that grows an accent ring when focused (d-pad) or pressed. */
    fun focusable(context: Context, color: Int, radiusDp: Int): StateListDrawable =
        StateListDrawable().apply {
            val focused = rounded(color, radiusDp, context).apply {
                setStroke(context.dp(2), ACCENT)
            }
            addState(intArrayOf(android.R.attr.state_focused), focused)
            addState(intArrayOf(android.R.attr.state_pressed), focused)
            addState(intArrayOf(), rounded(color, radiusDp, context, LINE))
        }

    fun title(context: Context, text: String, sizeSp: Float = 20f): TextView =
        TextView(context).apply {
            this.text = text
            textSize = sizeSp
            setTextColor(INK)
            typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
        }

    fun body(context: Context, text: String, color: Int = MUTED, sizeSp: Float = 13f): TextView =
        TextView(context).apply {
            this.text = text
            textSize = sizeSp
            setTextColor(color)
        }

    /** Small status chip with a colored dot, as in the nav rail mockup. */
    fun statusChip(context: Context, label: String, dotColor: Int): LinearLayout =
        LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(PANEL, 8, context, LINE)
            setPadding(context.dp(8), context.dp(4), context.dp(10), context.dp(4))
            addView(View(context).apply {
                background = rounded(dotColor, 4, context)
                layoutParams = LinearLayout.LayoutParams(context.dp(7), context.dp(7)).apply {
                    marginEnd = context.dp(6)
                }
            })
            addView(body(context, label, MUTED, 11.5f).apply {
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
            })
        }

    /** Primary (accent) or secondary action button used by sheets and the wizard. */
    fun actionButton(context: Context, label: String, primary: Boolean, onClick: () -> Unit): TextView =
        TextView(context).apply {
            text = label
            textSize = 14.5f
            gravity = Gravity.CENTER
            setTextColor(if (primary) ON_ACCENT else INK)
            typeface = if (primary) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            background = if (primary) {
                StateListDrawable().apply {
                    val f = rounded(ACCENT, 10, context).apply { setStroke(context.dp(2), INK) }
                    addState(intArrayOf(android.R.attr.state_focused), f)
                    addState(intArrayOf(android.R.attr.state_pressed), f)
                    addState(intArrayOf(), rounded(ACCENT, 10, context))
                }
            } else {
                focusable(context, PANEL_RAISED, 10)
            }
            isFocusable = true
            isClickable = true
            setPadding(context.dp(18), context.dp(11), context.dp(18), context.dp(11))
            setOnClickListener { onClick() }
        }

    /**
     * Segmented control: one row of options, the selected one filled with the
     * accent. Calls [onSelect] with the chosen index and restyles itself.
     */
    fun segmented(context: Context, options: List<String>, selected: Int, onSelect: (Int) -> Unit): LinearLayout {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            background = rounded(PANEL, 9, context, LINE)
        }

        fun style(view: TextView, on: Boolean) {
            view.setTextColor(if (on) ON_ACCENT else MUTED)
            view.typeface = if (on) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            view.background = if (on) rounded(ACCENT, 8, context) else {
                StateListDrawable().apply {
                    addState(
                        intArrayOf(android.R.attr.state_focused),
                        rounded(PANEL_RAISED, 8, context, ACCENT)
                    )
                    addState(intArrayOf(), rounded(Color.TRANSPARENT, 8, context))
                }
            }
        }

        val cells = options.mapIndexed { i, label ->
            TextView(context).apply {
                text = label
                textSize = 13f
                gravity = Gravity.CENTER
                maxLines = 1
                isFocusable = true
                isClickable = true
                setPadding(context.dp(14), context.dp(7), context.dp(14), context.dp(7))
            }.also { row.addView(it, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)) }
        }
        cells.forEachIndexed { i, cell ->
            style(cell, i == selected)
            cell.setOnClickListener {
                cells.forEachIndexed { j, c -> style(c, j == i) }
                onSelect(i)
            }
        }
        return row
    }

    /** Deterministic placeholder gradient for games with no ICON0 (ISOs, homebrew). */
    fun placeholderArt(context: Context, seed: String): GradientDrawable {
        val palettes = arrayOf(
            intArrayOf(Color.parseColor("#7A3B5E"), Color.parseColor("#2A1430")),
            intArrayOf(Color.parseColor("#2C6A4F"), Color.parseColor("#10241C")),
            intArrayOf(Color.parseColor("#34558B"), Color.parseColor("#101B33")),
            intArrayOf(Color.parseColor("#8B6A2E"), Color.parseColor("#2E2110")),
            intArrayOf(Color.parseColor("#5D3A8B"), Color.parseColor("#1D1030")),
            intArrayOf(Color.parseColor("#8B3434"), Color.parseColor("#2B0F0F")),
            intArrayOf(Color.parseColor("#2F7A8B"), Color.parseColor("#0E252B")),
            intArrayOf(Color.parseColor("#55658B"), Color.parseColor("#171C2B"))
        )
        val pick = palettes[(seed.hashCode() and 0x7FFFFFFF) % palettes.size]
        return GradientDrawable(GradientDrawable.Orientation.TL_BR, pick).apply {
            cornerRadius = context.dp(8).toFloat()
        }
    }

    fun divider(context: Context, vertical: Boolean): View = View(context).apply {
        setBackgroundColor(LINE)
        layoutParams = if (vertical) {
            LinearLayout.LayoutParams(context.dp(1), LinearLayout.LayoutParams.MATCH_PARENT)
        } else {
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, context.dp(1))
        }
    }
}
