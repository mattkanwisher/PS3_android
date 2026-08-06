package nu.hyperworks.cellstation

import android.app.Dialog
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings as AndroidSettings
import android.text.Editable
import android.text.TextWatcher
import android.util.LruCache
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.AppCompatImageView
import nu.hyperworks.cellstation.Ui.dp
import java.io.File
import java.text.DateFormat
import java.util.Date
import kotlin.concurrent.thread

/**
 * Library screen (design: docs UI research page).
 *
 * Orientation-adaptive: landscape (the handheld posture) gets a nav rail on
 * the left grip edge with live status chips; portrait (phone posture) gets a
 * bottom nav bar. Rotation recreates the activity, which rebuilds the right
 * scaffold — that is the orientation detection, no duplicated screens.
 *
 * Tiles use each game's own ICON0.PNG (320x176) read straight from the game
 * files, tap boots, long-press (or the pad's menu button) opens the per-game
 * sheet. Everything is focusable so a d-pad can drive the whole screen.
 */
class MainActivity : AppCompatActivity() {

    private enum class Tab { LIBRARY, HOMEBREW }

    private lateinit var content: LinearLayout
    private lateinit var chipBar: LinearLayout
    private var tab = Tab.LIBRARY
    private var query = ""
    private var entries: List<GameLibrary.Entry> = emptyList()
    private var scanning = false

    private val iconCache = LruCache<String, Bitmap>(64)

    private val pickPup = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@registerForActivityResult
        Toast.makeText(this, R.string.installing_firmware, Toast.LENGTH_SHORT).show()
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
                Toast.makeText(
                    this,
                    if (ok) getString(R.string.firmware_version, EmuBridge.firmwareVersion())
                    else getString(R.string.firmware_install_failed),
                    Toast.LENGTH_LONG
                ).show()
                refreshChips()
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
            rescan()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        savedInstanceState?.let {
            tab = if (it.getInt(STATE_TAB, 0) == 1) Tab.HOMEBREW else Tab.LIBRARY
            query = it.getString(STATE_QUERY, "")
        }

        if (!Settings.setupDone(this)) {
            startActivity(Intent(this, WelcomeActivity::class.java))
        }

        setContentView(if (Ui.isLandscape(this)) buildLandscape() else buildPortrait())
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(STATE_TAB, if (tab == Tab.HOMEBREW) 1 else 0)
        outState.putString(STATE_QUERY, query)
    }

    override fun onResume() {
        super.onResume()
        refreshChips()
        rescan()
    }

    // ---- scaffolds ---------------------------------------------------------

    private fun buildLandscape(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Ui.BG)
        }

        // Nav rail on the left grip edge.
        val rail = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Ui.PANEL_RAISED)
            setPadding(dp(14), dp(18), dp(14), dp(14))
        }
        rail.addView(brandRow())
        rail.addView(spacer(14))
        rail.addView(navItem(getString(R.string.nav_library), tab == Tab.LIBRARY) { switchTab(Tab.LIBRARY) })
        rail.addView(navItem(getString(R.string.nav_homebrew), tab == Tab.HOMEBREW) { switchTab(Tab.HOMEBREW) })
        rail.addView(navItem(getString(R.string.settings), false) {
            startActivity(Intent(this, SettingsActivity::class.java))
        })
        rail.addView(View(this), LinearLayout.LayoutParams(0, 0, 1f))
        chipBar = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        rail.addView(chipBar)
        root.addView(rail, LinearLayout.LayoutParams(dp(190), ViewGroup.LayoutParams.MATCH_PARENT))
        root.addView(Ui.divider(this, vertical = true))

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), 0)
        }
        column.addView(searchRow())
        column.addView(spacer(12))
        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        column.addView(ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            addView(content)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(column, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        return root
    }

    private fun buildPortrait(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Ui.BG)
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(4))
        }
        header.addView(brandRow(), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        chipBar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        header.addView(chipBar)
        root.addView(header)

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(6), dp(16), 0)
        }
        column.addView(searchRow())
        column.addView(spacer(10))
        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        column.addView(ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            addView(content)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(column, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        // Bottom nav: the phone thumb anchor.
        root.addView(Ui.divider(this, vertical = false))
        val nav = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Ui.PANEL_RAISED)
            setPadding(0, dp(6), 0, dp(10))
        }
        fun bottomItem(label: String, on: Boolean, onClick: () -> Unit) {
            nav.addView(TextView(this).apply {
                text = label
                textSize = 13f
                gravity = Gravity.CENTER
                setTextColor(if (on) Ui.ACCENT else Ui.MUTED)
                typeface = if (on) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                isFocusable = true
                isClickable = true
                background = Ui.focusable(this@MainActivity, Color.TRANSPARENT, 8)
                setPadding(0, dp(8), 0, dp(8))
                setOnClickListener { onClick() }
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        bottomItem(getString(R.string.nav_library), tab == Tab.LIBRARY) { switchTab(Tab.LIBRARY) }
        bottomItem(getString(R.string.nav_homebrew), tab == Tab.HOMEBREW) { switchTab(Tab.HOMEBREW) }
        bottomItem(getString(R.string.settings), false) {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        root.addView(nav)
        return root
    }

    private fun brandRow(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(View(this@MainActivity).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(Ui.ACCENT, Ui.VIOLET)
            ).apply { cornerRadius = dp(7).toFloat() }
            layoutParams = LinearLayout.LayoutParams(dp(24), dp(24)).apply { marginEnd = dp(9) }
        })
        addView(Ui.title(this@MainActivity, getString(R.string.app_name), 17f))
    }

    private fun navItem(label: String, on: Boolean, onClick: () -> Unit): TextView =
        TextView(this).apply {
            text = label
            textSize = 14.5f
            setTextColor(if (on) Ui.INK else Ui.MUTED)
            typeface = if (on) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            background = if (on) Ui.rounded(0x225BD8F0, 9, this@MainActivity)
                        else Ui.focusable(this@MainActivity, Color.TRANSPARENT, 9)
            isFocusable = true
            isClickable = true
            setPadding(dp(12), dp(10), dp(12), dp(10))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(4) }
            setOnClickListener { onClick() }
        }

    private fun searchRow(): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val search = EditText(this).apply {
            hint = getString(R.string.search_hint)
            setText(query)
            textSize = 14f
            setTextColor(Ui.INK)
            setHintTextColor(Ui.MUTED)
            background = Ui.rounded(Ui.PANEL, 20, this@MainActivity, Ui.LINE)
            setPadding(dp(16), dp(9), dp(16), dp(9))
            maxLines = 1
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    query = s?.toString().orEmpty()
                    renderContent()
                }
            })
        }
        row.addView(search, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(TextView(this).apply {
            text = "⋮"
            textSize = 20f
            setTextColor(Ui.INK)
            gravity = Gravity.CENTER
            isFocusable = true
            isClickable = true
            background = Ui.focusable(this@MainActivity, Ui.PANEL, 10)
            layoutParams = LinearLayout.LayoutParams(dp(42), dp(40)).apply { marginStart = dp(10) }
            setOnClickListener { showMenu(it) }
        })
        return row
    }

    private fun spacer(heightDp: Int) = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(0, dp(heightDp))
    }

    private fun switchTab(next: Tab) {
        if (tab != next) {
            tab = next
            recreate()
        }
    }

    // ---- status chips ------------------------------------------------------

    private fun refreshChips() {
        if (!::chipBar.isInitialized) return
        chipBar.removeAllViews()
        val land = Ui.isLandscape(this)

        fun add(label: String, color: Int) {
            val chip = Ui.statusChip(this, label, color)
            val lp = LinearLayout.LayoutParams(
                if (land) ViewGroup.LayoutParams.MATCH_PARENT else ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            if (land) lp.topMargin = dp(5) else lp.marginStart = dp(6)
            chipBar.addView(chip, lp)
        }

        val fw = EmuBridge.firmwareVersion()
        add(
            if (fw.isEmpty()) getString(R.string.chip_no_firmware)
            else if (land) getString(R.string.chip_firmware, fw) else "FW",
            if (fw.isEmpty()) Ui.WARN else Ui.OK
        )
        val driver = Settings.gpuDriver(this)
        add(
            if (land) {
                if (driver.isEmpty()) getString(R.string.chip_driver_system)
                else getString(R.string.chip_driver, driver.take(14))
            } else "GPU",
            if (driver.isEmpty()) Ui.WARN else Ui.OK
        )
        if (scanning && land) add(getString(R.string.chip_scanning), Ui.WARN)
    }

    // ---- library data ------------------------------------------------------

    private fun rescan() {
        if (scanning) return
        scanning = true
        refreshChips()
        thread(name = "LibraryScan") {
            // App-external dir stays supported so sideloaded homebrew keeps working.
            val extraRoots = Settings.scanFolders(this).map { File(it) } +
                listOfNotNull(getExternalFilesDir("games")?.also { it.mkdirs() })
            val scanned = if (hasStorageAccess()) GameLibrary.scan(extraRoots) else emptyList()
            runOnUiThread {
                scanning = false
                entries = scanned
                refreshChips()
                renderContent()
            }

            // Opt-in online data: refresh the RPCS3 compatibility list and pull
            // missing GameTDB covers, then re-render once with whatever landed.
            if (Settings.onlineData(this)) {
                var changed = GameData.refreshCompat(this)
                for (entry in scanned) {
                    val serial = entry.serial ?: continue
                    if (GameData.fetchCover(this, serial)) changed = true
                }
                if (changed) runOnUiThread { renderContent() }
            }
        }
    }

    private fun visibleEntries(): List<GameLibrary.Entry> {
        val hidden = Settings.hiddenGames(this)
        return entries.filter { entry ->
            entry.bootPath !in hidden &&
                (tab == Tab.HOMEBREW) == (entry.kind == GameLibrary.Kind.HOMEBREW) &&
                (query.isEmpty() || entry.title.contains(query, ignoreCase = true))
        }
    }

    // ---- rendering ---------------------------------------------------------

    private fun renderContent() {
        if (!::content.isInitialized) return
        content.removeAllViews()

        if (!hasStorageAccess()) {
            content.addView(Ui.body(this, getString(R.string.storage_needed), Ui.MUTED, 14.5f))
            content.addView(spacer(12))
            content.addView(Ui.actionButton(this, getString(R.string.grant_storage), primary = true) {
                requestStorageAccess()
            })
            return
        }

        val list = visibleEntries()

        // Continue card: resume the last game in one tap.
        if (tab == Tab.LIBRARY && query.isEmpty()) {
            val last = Settings.lastPlayed(this)
            entries.firstOrNull { it.bootPath == last }?.let { entry ->
                content.addView(continueCard(entry))
                content.addView(spacer(14))
            }
        }

        if (list.isEmpty()) {
            if (scanning) {
                content.addView(Ui.body(this, getString(R.string.scanning_library), Ui.MUTED, 14.5f))
            } else {
                val roots = (Settings.scanFolders(this).map { File(it) } + GameLibrary.searchRoots())
                    .joinToString("\n") { it.absolutePath }
                content.addView(Ui.body(this, getString(R.string.no_games, roots), Ui.MUTED, 14f))
            }
            return
        }

        val columns = if (Ui.isLandscape(this)) 4 else 2
        var row: LinearLayout? = null
        list.forEachIndexed { i, entry ->
            if (i % columns == 0) {
                row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { bottomMargin = dp(12) }
                }
                content.addView(row)
            }
            row!!.addView(tile(entry), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                if (i % columns != 0) marginStart = dp(12)
            })
        }
        // Pad the last row so tiles keep their column width.
        val remainder = list.size % columns
        if (remainder != 0) {
            repeat(columns - remainder) {
                row!!.addView(View(this), LinearLayout.LayoutParams(0, 0, 1f).apply { marginStart = dp(12) })
            }
        }
        content.addView(spacer(20))
    }

    private fun continueCard(entry: GameLibrary.Entry): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = Ui.focusable(this@MainActivity, Ui.PANEL_RAISED, 12)
            isFocusable = true
            isClickable = true
            setPadding(dp(12), dp(10), dp(12), dp(10))
            setOnClickListener { boot(entry.bootPath) }
        }
        card.addView(artView(entry, dp(96)))
        val meta = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, dp(8), 0)
        }
        meta.addView(TextView(this).apply {
            text = entry.title
            textSize = 15.5f
            setTextColor(Ui.INK)
            typeface = Typeface.DEFAULT_BOLD
            maxLines = 1
        })
        val at = Settings.lastPlayedAt(this@MainActivity)
        meta.addView(Ui.body(this, getString(
            R.string.continue_subtitle,
            if (at > 0) DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(at)) else "—"
        ), Ui.MUTED, 12.5f))
        card.addView(meta, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        card.addView(Ui.actionButton(this, getString(R.string.continue_play), primary = true) {
            boot(entry.bootPath)
        })
        return card
    }

    /** ImageView locked to the PS3 icon aspect (320x176) at a given width. */
    private fun artView(entry: GameLibrary.Entry, widthPx: Int): ImageView =
        AppCompatImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(widthPx, widthPx * Ui.ICON_H / Ui.ICON_W)
            scaleType = ImageView.ScaleType.CENTER_CROP
            clipToOutline = true
            background = Ui.rounded(Ui.PANEL, 8, this@MainActivity)
            loadArt(this, entry)
        }

    /** Tile: aspect-locked art measured from its column width, plus a kind dot + title. */
    private fun tile(entry: GameLibrary.Entry): View {
        val cell = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            isFocusable = true
            isClickable = true
            isLongClickable = true
            background = Ui.focusable(this@MainActivity, Color.TRANSPARENT, 10)
            setPadding(dp(4), dp(4), dp(4), dp(4))
            setOnClickListener { boot(entry.bootPath) }
            setOnLongClickListener { gameSheet(entry); true }
        }

        val art = object : AppCompatImageView(this) {
            override fun onMeasure(widthSpec: Int, heightSpec: Int) {
                val w = MeasureSpec.getSize(widthSpec)
                val h = w * Ui.ICON_H / Ui.ICON_W
                setMeasuredDimension(w, h)
            }
        }.apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            clipToOutline = true
            background = Ui.rounded(Ui.PANEL, 8, this@MainActivity)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        loadArt(art, entry)
        cell.addView(art)

        val caption = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(2), dp(6), 0, 0)
        }
        caption.addView(View(this).apply {
            background = Ui.rounded(dotColor(entry), 4, this@MainActivity)
            layoutParams = LinearLayout.LayoutParams(dp(7), dp(7)).apply { marginEnd = dp(6) }
        })
        caption.addView(Ui.body(this, entry.title, Ui.MUTED, 12.5f).apply {
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        })
        cell.addView(caption)
        return cell
    }

    /**
     * Tile dot: community compatibility status when known (online data on),
     * else the content type.
     */
    private fun dotColor(entry: GameLibrary.Entry): Int {
        when (GameData.compatStatus(this, entry.serial)) {
            "Playable" -> return Ui.OK
            "Ingame" -> return Ui.WARN
            "Intro", "Loadable", "Nothing" -> return Ui.BAD
        }
        return when (entry.kind) {
            GameLibrary.Kind.GAME_FOLDER -> Ui.OK
            GameLibrary.Kind.DISC_IMAGE -> Ui.VIOLET
            GameLibrary.Kind.HOMEBREW -> Ui.ACCENT
        }
    }

    private fun loadArt(into: ImageView, entry: GameLibrary.Entry) {
        val path = entry.iconPath
        if (path == null) {
            into.setImageDrawable(Ui.placeholderArt(this, entry.title))
            return
        }
        iconCache.get(path)?.let { into.setImageBitmap(it); return }
        into.setImageDrawable(Ui.placeholderArt(this, entry.title))
        thread(name = "IconLoad") {
            val bmp = BitmapFactory.decodeFile(path) ?: return@thread
            iconCache.put(path, bmp)
            runOnUiThread {
                // The view may have been rebound by a re-render; tag-free best effort.
                if (into.isAttachedToWindow) into.setImageBitmap(bmp)
            }
        }
    }

    // ---- per-game sheet ----------------------------------------------------

    private fun gameSheet(entry: GameLibrary.Entry) {
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)

        val sheet = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Ui.rounded(Ui.PANEL_RAISED, 16, this@MainActivity, Ui.LINE)
            setPadding(dp(18), dp(14), dp(18), dp(18))
        }

        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(12))
        }
        // Prefer the downloaded GameTDB box cover for the sheet's larger art;
        // the tile keeps the native ICON0.
        val cover = entry.serial?.let { GameData.coverFile(this, it).takeIf { f -> f.isFile } }
        if (cover != null) {
            head.addView(AppCompatImageView(this).apply {
                adjustViewBounds = true
                scaleType = ImageView.ScaleType.FIT_CENTER
                setImageBitmap(BitmapFactory.decodeFile(cover.absolutePath))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, dp(110)
                )
            })
        } else {
            head.addView(artView(entry, dp(88)))
        }
        val meta = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, 0, 0)
        }
        meta.addView(TextView(this).apply {
            text = entry.title
            textSize = 16f
            setTextColor(Ui.INK)
            typeface = Typeface.DEFAULT_BOLD
            maxLines = 2
        })
        val detail = listOfNotNull(
            entry.serial,
            GameData.compatStatus(this, entry.serial),
            kindLabel(entry.kind),
            GameLibrary.humanSize(entry.sizeBytes)
        ).joinToString(" · ")
        meta.addView(Ui.body(this, detail, Ui.MUTED, 12.5f))
        head.addView(meta, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        sheet.addView(head)

        val land = Ui.isLandscape(this)
        val actions = LinearLayout(this).apply {
            orientation = if (land) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
        }
        fun addAction(view: TextView) {
            val lp = if (land) {
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginEnd = dp(8)
                }
            } else {
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(8) }
            }
            actions.addView(view, lp)
        }
        addAction(Ui.actionButton(this, getString(R.string.sheet_play), primary = true) {
            dialog.dismiss()
            boot(entry.bootPath)
        })
        addAction(Ui.actionButton(this, getString(R.string.sheet_details), primary = false) {
            dialog.dismiss()
            gameDetails(entry)
        })
        addAction(Ui.actionButton(this, getString(R.string.sheet_hide), primary = false) {
            Settings.hideGame(this, entry.bootPath)
            dialog.dismiss()
            renderContent()
            Toast.makeText(this, R.string.sheet_hidden, Toast.LENGTH_SHORT).show()
        })
        sheet.addView(actions)

        dialog.setContentView(sheet)
        dialog.window?.let { w ->
            w.setGravity(Gravity.BOTTOM)
            w.setBackgroundDrawableResource(android.R.color.transparent)
            w.setLayout(
                if (land) (resources.displayMetrics.widthPixels * 0.62).toInt()
                else ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        dialog.show()
    }

    private fun gameDetails(entry: GameLibrary.Entry) {
        AlertDialog.Builder(this)
            .setTitle(entry.title)
            .setMessage(
                listOfNotNull(
                    entry.serial?.let { getString(R.string.detail_serial, it) },
                    getString(R.string.detail_kind, kindLabel(entry.kind)),
                    getString(R.string.detail_size, GameLibrary.humanSize(entry.sizeBytes)),
                    getString(R.string.detail_path, entry.bootPath)
                ).joinToString("\n")
            )
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun kindLabel(kind: GameLibrary.Kind): String = getString(
        when (kind) {
            GameLibrary.Kind.DISC_IMAGE -> R.string.kind_disc
            GameLibrary.Kind.GAME_FOLDER -> R.string.kind_folder
            GameLibrary.Kind.HOMEBREW -> R.string.kind_homebrew
        }
    )

    // ---- actions -----------------------------------------------------------

    private fun showMenu(anchor: View) {
        PopupMenu(this, anchor).apply {
            menu.add(0, MENU_FIRMWARE, 0, R.string.install_firmware)
            menu.add(0, MENU_ADD_GAME, 1, R.string.add_game)
            menu.add(0, MENU_SCAN_FOLDER, 2, R.string.scan_folder)
            menu.add(0, MENU_REFRESH, 3, R.string.refresh)
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    MENU_FIRMWARE -> pickPup.launch(arrayOf("*/*"))
                    MENU_ADD_GAME -> pickGame.launch(arrayOf("*/*"))
                    MENU_SCAN_FOLDER -> pickFolder.launch(null)
                    MENU_REFRESH -> rescan()
                }
                true
            }
            show()
        }
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
        Settings.setLastPlayed(this, path)
        startActivity(Intent(this, EmulationActivity::class.java).apply {
            action = EmulationActivity.ACTION_EMULATE
            putExtra(EmulationActivity.EXTRA_BOOT_PATH, path)
        })
    }

    private companion object {
        const val MENU_FIRMWARE = 1
        const val MENU_ADD_GAME = 2
        const val MENU_SCAN_FOLDER = 3
        const val MENU_REFRESH = 4
        const val STATE_TAB = "tab"
        const val STATE_QUERY = "query"
    }
}
