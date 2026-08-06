package nu.hyperworks.cellstation

import android.content.Context
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import nu.hyperworks.cellstation.Ui.dp

/**
 * Loading overlay for the long first boot.
 *
 * A cold boot compiles every PPU module in the game — five minutes or more for a
 * big title — and without this the screen is simply black, which is
 * indistinguishable from a hang. The core already tracks the work (it is what
 * drives rpcs3's desktop progress dialog); this just shows it.
 */
class BootProgressView(context: Context) : FrameLayout(context) {

    private val heading = TextView(context).apply {
        textSize = 17f
        setTextColor(Ui.INK)
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
    }

    private val detail = TextView(context).apply {
        textSize = 13f
        setTextColor(Ui.MUTED)
        gravity = Gravity.CENTER
    }

    private val bar = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
        max = 1000
        isIndeterminate = true
    }

    private val handler = Handler(Looper.getMainLooper())

    /** Wall clock for the current run of work, for the remaining-time estimate. */
    private var phaseStartMs = 0L
    private var phaseText = ""
    private var lastTotal = 0

    private val poll = object : Runnable {
        override fun run() {
            update()
            handler.postDelayed(this, POLL_MS)
        }
    }

    init {
        setBackgroundColor(SCRIM)
        visibility = GONE

        val panel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            background = Ui.rounded(Ui.PANEL_RAISED, 16, context, Ui.LINE)
            setPadding(context.dp(28), context.dp(22), context.dp(28), context.dp(22))
        }
        panel.addView(heading)
        panel.addView(detail.apply { setPadding(0, context.dp(6), 0, context.dp(14)) })
        panel.addView(
            bar,
            LinearLayout.LayoutParams(context.dp(320), ViewGroup.LayoutParams.WRAP_CONTENT)
        )

        addView(
            panel,
            LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            )
        )
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        handler.post(poll)
    }

    override fun onDetachedFromWindow() {
        handler.removeCallbacks(poll)
        super.onDetachedFromWindow()
    }

    private fun update() {
        val raw = try {
            EmuBridge.bootProgress()
        } catch (_: UnsatisfiedLinkError) {
            ""
        }

        if (raw.isEmpty()) {
            hide()
            return
        }

        val parts = raw.split('\t')
        if (parts.size < 3) {
            hide()
            return
        }

        val done = parts[0].toIntOrNull() ?: 0
        val total = parts[1].toIntOrNull() ?: 0
        val text = parts[2]

        // The core leaves its counters sitting at done == total once a phase
        // finishes rather than zeroing them, so "still reporting progress" is not
        // the same as "still working". Without this the overlay stays up over the
        // running game forever.
        if (total > 0 && done >= total) {
            hide()
            return
        }

        // A new phase (or a new batch of work within one) restarts the estimate;
        // carrying the old elapsed time over would badly skew the first guess.
        if (text != phaseText || total != lastTotal) {
            phaseText = text
            lastTotal = total
            phaseStartMs = SystemClock.elapsedRealtime()
        }

        if (visibility != VISIBLE) {
            visibility = VISIBLE
        }

        heading.text = text.ifEmpty { context.getString(R.string.boot_working) }

        if (total > 0) {
            bar.isIndeterminate = false
            bar.progress = (done.toLong() * 1000 / total).toInt().coerceIn(0, 1000)
            detail.text = context.getString(R.string.boot_step, done, total, remainingLabel(done, total))
        } else {
            // Text-only phase: the core knows what it is doing but not how much
            // of it there is. A spinner is honest here; a fake bar is not.
            bar.isIndeterminate = true
            detail.text = context.getString(R.string.boot_please_wait)
        }
    }

    /**
     * Same estimate rpcs3's own dialog makes: scale elapsed time by the fraction
     * still outstanding. Suppressed until a little work has completed, because
     * the first few samples produce wild numbers.
     */
    private fun remainingLabel(done: Int, total: Int): String {
        if (done < 3 || done >= total) return ""
        val elapsed = SystemClock.elapsedRealtime() - phaseStartMs
        if (elapsed < 2_000) return ""

        val remainingMs = elapsed * (total - done) / done
        val seconds = remainingMs / 1000
        val label = when {
            seconds < 60 -> context.getString(R.string.boot_eta_seconds, seconds)
            else -> context.getString(R.string.boot_eta_minutes, seconds / 60)
        }
        return context.getString(R.string.boot_eta_wrap, label)
    }

    private fun hide() {
        if (visibility != GONE) {
            visibility = GONE
        }
        phaseText = ""
        lastTotal = 0
    }

    private companion object {
        const val POLL_MS = 250L
        const val SCRIM = 0xE60B0F19.toInt()
    }
}
