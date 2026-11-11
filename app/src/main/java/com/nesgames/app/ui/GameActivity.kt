package com.nesgames.app.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.net.Uri
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.format.DateUtils
import android.util.Log
import android.view.Gravity
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Warning
import androidx.compose.ui.platform.ComposeView
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.nesgames.app.BuildConfig
import com.nesgames.app.R
import com.nesgames.app.emu.Cores
import com.nesgames.app.emu.NativeBridge
import com.nesgames.app.emu.RetroPad
import com.nesgames.app.library.Covers
import com.nesgames.app.library.GameSystem
import com.nesgames.app.library.LibraryScanner
import com.nesgames.app.library.RomInspector
import com.nesgames.app.library.SaveSlots
import com.nesgames.app.library.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

class GameActivity : ComponentActivity() {
    companion object {
        const val EXTRA_URI = "uri"; const val EXTRA_ENTRY = "entry"; const val EXTRA_NAME = "name"; const val EXTRA_SYSTEM = "system"
        const val EXTRA_ID = "game_id"; const val EXTRA_SAVE_KEY = "save_key"; const val EXTRA_RESUME = "resume"
        const val EXTRA_DIR = "dir"; const val EXTRA_DISC_FILES = "disc_files"; const val EXTRA_TITLE = "title"
        /** Debug builds only: absolute path of a ROM inside this app's private storage, for adb QA runs. */
        const val EXTRA_TEST_FILE = "test_rom_file"
        const val QA_TAG = "nesGamesQA"
        /** Debug builds only: adb shell am broadcast -a com.nesgames.app.QA -p com.nesgames.app --es cmd <cmd> [--ei n <n>] */
        const val QA_ACTION = "com.nesgames.app.QA"
    }

    private lateinit var gl: GLSurfaceView
    private lateinit var controls: ControlsView
    private lateinit var editBar: View
    private lateinit var system: GameSystem
    private lateinit var layout: PadLayout
    private lateinit var padMap: PadMap
    private var loaded = false
    private var dialogDepth = 0
    private var editing = false
    private var qaPaused = false
    private var resumedState = false
    private var touchMask = 0
    private var padMask = 0
    private var qaMask = 0
    private var gameId = "game"
    private var screenMode = 2
    private val ui = Handler(Looper.getMainLooper())

    private val uiBlocked get() = dialogDepth > 0 || editing

    /** Single place that decides whether emulation runs. */
    private fun applyPause() { if (loaded) NativeBridge.nativeSetPaused(uiBlocked || qaPaused || !resumedState) }

    private val exportLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri != null) runCatching {
            contentResolver.openOutputStream(uri)!!.use { it.write(layout.serialize().toByteArray()) }
            toast(R.string.preset_exported)
        }.onFailure { toast(R.string.save_failed) }
    }
    private val importLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) applyImported(runCatching {
            contentResolver.openInputStream(uri)!!.use { String(LibraryScanner.readUpTo(it, 64 * 1024)) }
        }.getOrDefault(""))
    }

    private fun applyImported(text: String): Boolean {
        val l = PadLayout.parse(text, system)
        if (l == null) { toast(R.string.preset_invalid); Log.i(QA_TAG, "IMPORT rejected"); return false }
        layout = l; controls.layout = l; LayoutStore.save(this, l)
        toast(R.string.preset_imported); Log.i(QA_TAG, "IMPORT ok")
        return true
    }


    /** The ARMSX2 core reads its shaders, fonts and game database from <system>/pcsx2/resources (bundled in assets). */
    private fun installPs2Resources() {
        val root = File(filesDir, "system/pcsx2")
        val marker = File(root, "resources/.installed-${BuildConfig.VERSION_CODE}")
        File(root, "bios").mkdirs()
        if (marker.exists()) return
        fun copy(asset: String, out: File) {
            val kids = assets.list(asset).orEmpty()
            if (kids.isEmpty()) { out.parentFile?.mkdirs(); assets.open(asset).use { i -> out.outputStream().use { i.copyTo(it) } } }
            else for (k in kids) copy("$asset/$k", File(out, k))
        }
        copy("pcsx2/resources", File(root, "resources"))
        marker.writeText("ok")
        Log.i(QA_TAG, "PS2 resources installed")
    }

    private var playSeconds = 0
    private var snapshotTries = 0

    /** Fallback cover: after ~20 s of play, save a frame that is not a blank/loading screen. */
    private fun maybeSnapshot() {
        val id = intent.getStringExtra(EXTRA_ID) ?: return
        if (uiBlocked || snapshotTries >= 4 || playSeconds < 20 || playSeconds % 10 != 0) return
        if (Covers.bestFile(this, id) != null) { snapshotTries = 99; return }
        snapshotTries++
        val f = NativeBridge.nativeCopyFrame() ?: return
        val w = f[0]; val h = f[1]
        val px = f.copyOfRange(2, f.size)
        val distinct = HashSet<Int>()
        var i = 0; while (i < px.size && distinct.size < 24) { distinct += px[i]; i += 97 }
        if (distinct.size < 24) return   // mostly uniform: probably a loading/black screen, try later
        lifecycleScope.launch(Dispatchers.IO) { runCatching { Covers.saveSnapshot(this@GameActivity, id, px, w, h) } }
        snapshotTries = 99
    }

    private val qaTick = object : Runnable {
        override fun run() {
            if (loaded && !uiBlocked && resumedState) { playSeconds++; maybeSnapshot() }
            if (loaded && resumedState) {
                val s = NativeBridge.nativeGetStats()
                Log.i(QA_TAG, "frames=${s[0]} video=${s[1]} audioFrames=${s[2]} underruns=${s[3]} hash=%08x center=%08x size=${s[6]}x${s[7]} mask=${touchMask or padMask or qaMask} inputFrames=${s[8]}"
                    .format(s[4], s[5]))
            }
            ui.postDelayed(this, 1000)
        }
    }

    private val qaReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) { if (i != null) handleQa(i) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        hideBars()
        system = runCatching { GameSystem.valueOf(intent.getStringExtra(EXTRA_SYSTEM) ?: "NES") }.getOrDefault(GameSystem.NES)
        layout = LayoutStore.load(this, system)
        padMap = LayoutStore.loadMap(this, system)
        gl = GLSurfaceView(this).apply {
            // GPU-rendering cores (PS2) need an OpenGL ES 3.x context; the others draw a plain texture.
            setEGLContextClientVersion(if (system == GameSystem.PS2) 3 else 2)
            preserveEGLContextOnPause = true
            setRenderer(object : GLSurfaceView.Renderer {
                override fun onSurfaceCreated(g: GL10?, c: EGLConfig?) = NativeBridge.nativeSurfaceCreated()
                override fun onSurfaceChanged(g: GL10?, w: Int, h: Int) = NativeBridge.nativeSurfaceChanged(w, h)
                override fun onDrawFrame(g: GL10?) = NativeBridge.nativeDrawFrame()   // vsync-paced by EGL swap
            })
        }
        controls = ControlsView(this, layout, { touchMask = it; pushInput() }, { showMenu() },
            onStick = { stick, x, y -> touchStick[stick] = x to y; pushAnalog(stick) })
        controls.onEdited = { LayoutStore.save(this, layout) }
        controls.layoutDirection = View.LAYOUT_DIRECTION_LTR
        editBar = buildEditBar()
        menuView = ComposeView(this).apply {
            visibility = View.GONE
            layoutDirection = View.LAYOUT_DIRECTION_LOCALE   // the menu follows the UI language (Arabic = RTL)
            val hue = menuHue(system, gameTitle)
            val sel = getString(R.string.menu_select); val back = getString(R.string.back)
            setContent { GameMenuOverlay(menu, hue, sel, back, ::menuPick) }
        }
        setContentView(FrameLayout(this).apply {
            layoutDirection = View.LAYOUT_DIRECTION_LTR   // a game pad never mirrors, whatever the UI language
            addView(gl); addView(controls)
            addView(editBar, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.CENTER_HORIZONTAL))
            addView(menuView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        })
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { if (menu.page != null) menuBack() else if (editing) finishEdit() else showMenu() }
        })
        if (BuildConfig.DEBUG) ContextCompat.registerReceiver(this, qaReceiver, IntentFilter(QA_ACTION), ContextCompat.RECEIVER_EXPORTED)
        lifecycleScope.launch { load() }
    }

    private fun hideBars() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    private suspend fun load() {
        val corePath = Cores.corePath(this, system) ?: return fail(getString(R.string.no_core, system.label))
        // The PS2 core "loads" without a BIOS and then shows nothing, so check first and say why.
        val skipBiosCheck = BuildConfig.DEBUG && intent.getBooleanExtra("qa_skip_bios_check", false)
        if (system == GameSystem.PS2 && !skipBiosCheck && com.nesgames.app.library.Ps2Bios.installed(this).none { it.valid }) {
            Log.e(QA_TAG, "BOOT_FAIL no PS2 BIOS")
            return fail(getString(R.string.ps2_bios_missing))
        }
        val result = withContext(Dispatchers.IO) {
            runCatching {
                if (system == GameSystem.PS2) installPs2Resources()
                val testFile = intent.getStringExtra(EXTRA_TEST_FILE)
                val (name, bytes) = if (BuildConfig.DEBUG && testFile != null) {
                    val f = File(testFile).canonicalFile
                    require(f.path.startsWith(filesDir.canonicalPath) || f.path.startsWith(getExternalFilesDir(null)!!.canonicalPath)) { "test ROM outside app storage" }
                    // discs (cue/chd/bin) stay on disk; the core opens the cue and its referenced tracks
                    if (f.extension.lowercase() in setOf("cue", "chd", "bin", "iso", "pbp")) f.path to ByteArray(0) else f.name to f.readBytes()
                } else if (system == GameSystem.PS2) {
                    // The core opens the disc by path. Re-opening the SAF document through /proc/self/fd is refused
                    // by Android (EACCES, tested on the S24), so the disc is copied into app storage on first launch.
                    val name = intent.getStringExtra(EXTRA_NAME) ?: "disc.iso"
                    withContext(Dispatchers.Main) {
                        openPage(MenuPage(getString(R.string.preparing_disc), getString(R.string.first_launch), blocking = true, progress = 0f, tag = "prepare"))
                    }
                    try {
                        var last = -1
                        val disc = LibraryScanner.importSingleDisc(this@GameActivity, Uri.parse(intent.getStringExtra(EXTRA_URI)), name) { p ->
                            val pct = (p * 100).toInt()
                            if (pct != last) { last = pct; ui.post { menu.page = menu.page?.copy(subtitle = getString(R.string.copying_disc, pct), progress = p) } }
                        }
                        Log.i(QA_TAG, "PS2 disc ready ${disc.length()} B")
                        disc.path to ByteArray(0)
                    } finally { withContext(Dispatchers.Main) { closeMenu() } }
                } else if (intent.getStringExtra(EXTRA_DIR) != null) {
                    // CUE disc: copy cue + tracks into app storage once, then let the core open them by path
                    val name = intent.getStringExtra(EXTRA_NAME) ?: "disc.cue"
                    withContext(Dispatchers.Main) {
                        openPage(MenuPage(getString(R.string.preparing_disc), getString(R.string.first_launch), blocking = true, progress = 0f, tag = "prepare"))
                    }
                    try {
                        var last = -1
                        val cue = LibraryScanner.importDisc(this@GameActivity, Uri.parse(intent.getStringExtra(EXTRA_URI)),
                            Uri.parse(intent.getStringExtra(EXTRA_DIR)), name, intent.getStringArrayExtra(EXTRA_DISC_FILES)?.toList().orEmpty()) { p ->
                            val pct = (p * 100).toInt()
                            if (pct != last) { last = pct; ui.post { menu.page = menu.page?.copy(subtitle = getString(R.string.copying_disc, pct), progress = pct / 100f) } }
                        }
                        cue.path to ByteArray(0)
                    } finally { withContext(Dispatchers.Main) { closeMenu() } }
                } else {
                    val name = intent.getStringExtra(EXTRA_NAME) ?: "game.nes"
                    name to LibraryScanner.loadRom(this@GameActivity, Uri.parse(intent.getStringExtra(EXTRA_URI)), intent.getStringExtra(EXTRA_ENTRY))
                }
                // Save folder: the library's stable key, else derived from the library id, else from the content itself
                gameId = intent.getStringExtra(EXTRA_SAVE_KEY)?.takeIf { Regex("[0-9a-f]{8,40}").matches(it) }
                    ?: intent.getStringExtra(EXTRA_ID)?.let { SaveSlots.keyFor(it) }
                    ?: MessageDigest.getInstance("SHA-1").digest(if (bytes.isEmpty()) name.toByteArray() else bytes).joinToString("") { "%02x".format(it) }.take(20)
                val sys = File(filesDir, "system").apply { mkdirs() }
                val saves = SaveSlots.dir(this@GameActivity, gameId)
                NativeBridge.nativeLoad(corePath, name, bytes, sys.path, saves.path, SaveSlots.sram(this@GameActivity, gameId).path, cacheDir.path)
            }.getOrElse { Log.e(QA_TAG, "load error", it); it.message ?: "read error" }
        }
        if (result != null) {
            Log.e(QA_TAG, "BOOT_FAIL $result")
            val noBios = system == GameSystem.PS2 && File(filesDir, "system/pcsx2/bios").listFiles().isNullOrEmpty()
            return fail(getString(if (noBios) R.string.ps2_bios_missing else R.string.core_rejected))
        }
        screenMode = Settings(applicationContext).screenMode()
        NativeBridge.nativeSetScaleMode(screenMode)
        loaded = true
        if (intent.getBooleanExtra(EXTRA_RESUME, false) && SaveSlots.exists(this, gameId, SaveSlots.AUTO)) {
            // Restore only after the core has run a few frames: PCSX ReARMed (disc drive, GPU) is not fully
            // up straight after retro_load_game, and a state applied that early is overwritten by its boot.
            // RetroArch delays state loading the same way. Bounded wait, ~0.25 s.
            NativeBridge.nativeSetPaused(false)
            withContext(Dispatchers.Default) { var n = 0; while (NativeBridge.nativeGetStats()[0] < 12 && n++ < 200) kotlinx.coroutines.delay(5) }
            val res = loadSlot(SaveSlots.AUTO)
            Log.i(QA_TAG, "RESUME ${if (res == R.string.game_loaded) "OK" else "FAILED"}")
            toast(if (res == R.string.game_loaded) R.string.continuing else R.string.resume_failed)
        }
        applyPause()
        Log.i(QA_TAG, "BOOT_OK id=$gameId system=$system fps=${NativeBridge.nativeGetFps()} hw=${NativeBridge.nativeIsHw()}")
        ui.post(qaTick)
    }

    private fun fail(msg: String) {
        openPage(MenuPage(getString(R.string.cant_start), msg,
            listOf(MenuEntry(getString(R.string.back_to_library), Icons.AutoMirrored.Filled.ArrowBack) { finish() }),
            onBack = { finish() }, tag = "fail"))
    }

    private val touchStick = arrayOf(0 to 0, 0 to 0)
    private val padStick = arrayOf(0 to 0, 0 to 0)

    /** A physical stick wins over the touch stick while it is deflected. */
    private fun pushAnalog(stick: Int) {
        if (!loaded) return
        val p = padStick[stick]; val t = touchStick[stick]
        val v = if (p.first != 0 || p.second != 0) p else t
        NativeBridge.nativeSetAnalog(0, stick, v.first, v.second)
    }

    private fun clearAnalog() { for (s in 0..1) { touchStick[s] = 0 to 0; padStick[s] = 0 to 0; if (loaded) NativeBridge.nativeSetAnalog(0, s, 0, 0) } }

    private fun pushInput() { if (loaded) NativeBridge.nativeSetInput(0, touchMask or padMask or qaMask) }
    private fun toast(res: Int, vararg a: Any) = Toast.makeText(this, getString(res, *a), Toast.LENGTH_SHORT).show()

    // ---------------------------------------------------------------- saves

    /**
     * Writes a slot: the emulator is frozen first, so the state and its screenshot are the same frame.
     * Order: drop old integrity record -> state (temp file, fsync, rename) -> integrity record -> screenshot.
     * A crash at any point leaves either the previous state or the new one, never a half-written file.
     */
    private fun saveSlot(slot: Int): Boolean {
        if (!loaded) return false
        NativeBridge.nativeSetPaused(true)
        val st = SaveSlots.state(this, gameId, slot); val meta = SaveSlots.meta(this, gameId, slot)
        meta.delete()
        val ok = NativeBridge.nativeSaveState(st.path)
        var thumb = "none"
        if (ok) {
            SaveSlots.writeMeta(st, meta)
            NativeBridge.nativeCopyFrame()?.let { f ->
                runCatching {
                    val bmp = Bitmap.createBitmap(f.copyOfRange(2, f.size), f[0], f[1], Bitmap.Config.ARGB_8888)
                    val out = SaveSlots.thumb(this, gameId, slot); val tmp = File(out.path + ".tmp")
                    tmp.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 90, it) }
                    if (tmp.renameTo(out)) thumb = "${f[0]}x${f[1]}/${out.length()}B"
                }
            }
        }
        Log.i(QA_TAG, "SAVE slot=$slot ok=$ok size=${st.length()} ram=%08x thumb=$thumb".format(NativeBridge.nativeRamHash()))
        applyPause()
        return ok
    }

    /** @return string resource describing the outcome (R.string.game_loaded on success). */
    private fun loadSlot(slot: Int): Int {
        if (!loaded) return R.string.load_failed
        val health = SaveSlots.health(this, gameId, slot)
        if (health == SaveSlots.Health.EMPTY) return R.string.slot_empty
        if (health == SaveSlots.Health.CORRUPT) { Log.i(QA_TAG, "LOAD slot=$slot refused health=CORRUPT"); return R.string.slot_damaged }
        NativeBridge.nativeSetPaused(true)
        val ok = NativeBridge.nativeLoadState(SaveSlots.state(this, gameId, slot).path)   // size-checked, rolls back on failure
        Log.i(QA_TAG, "LOAD slot=$slot ok=$ok health=$health ram=%08x".format(NativeBridge.nativeRamHash()))
        applyPause()
        return if (ok) R.string.game_loaded else R.string.load_failed
    }

    private fun slotLabel(slot: Int) = if (slot == SaveSlots.AUTO) getString(R.string.auto_save) else getString(R.string.slot_fmt, slot)

    /** Slot chooser: thumbnail + time per slot. Saving to a used slot asks before overwriting. */
    private fun showSlots(saving: Boolean) {
        val slots = (if (saving) SaveSlots.MANUAL.toList() else listOf(SaveSlots.AUTO) + SaveSlots.MANUAL)
        val items = slots.map { slot ->
            val health = SaveSlots.health(this, gameId, slot)
            val f = SaveSlots.state(this, gameId, slot)
            val sub = when (health) {
                SaveSlots.Health.EMPTY -> getString(R.string.empty)
                SaveSlots.Health.CORRUPT -> getString(R.string.damaged)
                else -> DateUtils.getRelativeTimeSpanString(f.lastModified(), System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString()
            }
            val thumb = SaveSlots.thumb(this, gameId, slot).takeIf { it.exists() && health != SaveSlots.Health.EMPTY }
            MenuEntry(slotLabel(slot), sub = sub, thumb = thumb, thumbRow = true, danger = health == SaveSlots.Health.CORRUPT) {
                if (saving) {
                    if (SaveSlots.exists(this, gameId, slot)) confirmOverwrite(slot) else saveAndClose(slot)
                } else {
                    val res = loadSlot(slot); toast(res)
                    if (res == R.string.game_loaded) closeMenu()
                }
            }
        }
        val back = { openPage(pauseRoot(if (saving) 1 else 2)) }
        openPage(MenuPage(getString(if (saving) R.string.save_game else R.string.load_game).trimEnd('…', '.'), gameTitle,
            items + backItem(back), onBack = back, tag = if (saving) "save" else "load"))
    }

    private fun saveAndClose(slot: Int) {
        if (saveSlot(slot)) { toast(R.string.saved_to, slot); closeMenu() } else toast(R.string.save_failed)
    }

    private fun confirmOverwrite(slot: Int) {
        val back = { showSlots(saving = true) }
        openPage(MenuPage(getString(R.string.overwrite_q, slot), gameTitle, listOf(
            MenuEntry(getString(R.string.overwrite), Icons.Filled.Warning, danger = true) { saveAndClose(slot) },
            MenuEntry(getString(R.string.cancel), Icons.Filled.Close) { back() }), onBack = back, initial = 1, tag = "overwrite"))
    }

    // ---------------------------------------------------------------- menus (overlay in the library's style)

    private val menu = MenuState()
    private lateinit var menuView: ComposeView
    private var navDir = 0
    private val gameTitle by lazy {
        intent.getStringExtra(EXTRA_TITLE) ?: intent.getStringExtra(EXTRA_NAME)?.substringBeforeLast('.') ?: system.label
    }

    private fun backItem(action: () -> Unit) = MenuEntry(getString(R.string.back), Icons.AutoMirrored.Filled.ArrowBack, action = action)

    /** Opens or switches a menu page. The first page freezes the game and releases every held input. */
    private fun openPage(p: MenuPage) {
        if (menu.page == null) { dialogDepth++; controls.releaseAll(); padMask = 0; qaMask = 0; pushInput(); clearAnalog(); applyPause() }
        menuView.visibility = View.VISIBLE
        menu.page = p; menu.sel = p.initial.coerceIn(0, maxOf(0, p.items.size - 1))
        Log.i(QA_TAG, "MENU open '${p.title}' tag=${p.tag} items=${p.items.size}")
    }

    private fun closeMenu() {
        if (menu.page == null) return
        menu.page = null; dialogDepth--; applyPause()
        Log.i(QA_TAG, "MENU closed")
        ui.postDelayed({ if (menu.page == null) menuView.visibility = View.GONE }, 260)
    }

    private fun menuBack() { val p = menu.page ?: return; if (p.blocking) return; p.onBack?.invoke() ?: closeMenu() }

    private fun menuPick(i: Int) {
        val p = menu.page ?: return
        val item = p.items.getOrNull(i) ?: return
        menu.sel = i
        Log.i(QA_TAG, "MENU pick '${p.title}' #$i '${item.label}'")
        item.action()
    }

    /** Gamepad / keyboard: up-down (or L1/R1) move, A or Enter selects, B goes back, Home closes. */
    private fun menuKey(p: MenuPage, e: KeyEvent) {
        if (e.action != KeyEvent.ACTION_DOWN) return
        p.keyCapture?.let { if (e.repeatCount == 0) it(e.keyCode); return }
        when (e.keyCode) {
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_BUTTON_L1 -> menu.move(-1)
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_BUTTON_R1 -> menu.move(1)
            KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER ->
                if (e.repeatCount == 0) menuPick(menu.sel)
            KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_ESCAPE -> if (e.repeatCount == 0) menuBack()
            KeyEvent.KEYCODE_BUTTON_MODE -> if (loaded && !p.blocking) closeMenu()
        }
    }

    private fun modeName(m: Int) = getString(listOf(R.string.mode_original, R.string.mode_integer, R.string.mode_full, R.string.mode_43)[m.coerceIn(0, 3)])

    private fun showMenu() {
        if (!loaded || uiBlocked) return
        openPage(pauseRoot(0))
    }

    private fun pauseRoot(initial: Int): MenuPage = MenuPage(gameTitle, "${getString(R.string.paused)}  ·  ${system.label}", listOf(
        MenuEntry(getString(R.string.resume), Icons.Filled.PlayArrow) { closeMenu() },
        MenuEntry(getString(R.string.save_game), Icons.Filled.KeyboardArrowDown) { showSlots(saving = true) },
        MenuEntry(getString(R.string.load_game), Icons.Filled.KeyboardArrowUp) { showSlots(saving = false) },
        MenuEntry(getString(R.string.restart), Icons.Filled.Refresh) { NativeBridge.nativeReset(); closeMenu() },
        MenuEntry(getString(R.string.controls_menu), Icons.Filled.Build) { showControlsMenu(0) },
        MenuEntry(getString(R.string.screen_fmt, modeName(screenMode)), Icons.Filled.Settings) {
            screenMode = (screenMode + 1) % 4
            NativeBridge.nativeSetScaleMode(screenMode)
            lifecycleScope.launch { Settings(applicationContext).setScreenMode(screenMode) }
            openPage(pauseRoot(5))
        },
        MenuEntry(getString(R.string.exit_library), Icons.AutoMirrored.Filled.ExitToApp) { finish() }),
        initial = initial, tag = "pause")

    private fun showControlsMenu(initial: Int) {
        val shown = controls.alpha > 0f
        val back = { openPage(pauseRoot(4)) }
        openPage(MenuPage(getString(R.string.controls_menu).trimEnd('…', '.'), gameTitle, listOf(
            MenuEntry(getString(R.string.edit_layout), Icons.Filled.Edit) { startEdit(); closeMenu() },
            MenuEntry(getString(if (layout.dpadStick) R.string.move_stick else R.string.move_dpad), Icons.Filled.Place,
                sub = getString(R.string.move_hint)) {
                layout.dpadStick = !layout.dpadStick; controls.releaseAll(); controls.invalidate(); LayoutStore.save(this, layout)
                Log.i(QA_TAG, "MOVE style=${if (layout.dpadStick) "joystick" else "dpad"} system=$system")
                showControlsMenu(1)
            },
            MenuEntry(getString(if (layout.haptic) R.string.haptic_on else R.string.haptic_off), Icons.Filled.Notifications) {
                layout.haptic = !layout.haptic; LayoutStore.save(this, layout); showControlsMenu(2)
            },
            MenuEntry(getString(if (shown) R.string.hide_touch else R.string.show_touch), if (shown) Icons.Filled.Close else Icons.Filled.Check) {
                controls.alpha = if (shown) 0f else 1f; showControlsMenu(3)
            },
            MenuEntry(getString(R.string.remap), Icons.Filled.Settings) { showRemap(0) },
            MenuEntry(getString(R.string.export_preset), Icons.Filled.Share) { closeMenu(); exportLauncher.launch("nesgames-${system.name.lowercase()}-layout.txt") },
            MenuEntry(getString(R.string.import_preset), Icons.Filled.Add) { closeMenu(); importLauncher.launch(arrayOf("text/*", "application/octet-stream")) },
            MenuEntry(getString(R.string.reset_default), Icons.Filled.Refresh) {
                layout.reset(); controls.invalidate(); LayoutStore.save(this, layout); toast(R.string.layout_reset); showControlsMenu(7)
            },
        ) + backItem(back), onBack = back, initial = initial, tag = "controls"))
    }

    // ---------------------------------------------------------------- layout editor

    private fun buildEditBar(): View {
        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setBackgroundColor(0xCC101014.toInt()); visibility = View.GONE }
        fun btn(res: Int, action: () -> Unit) = bar.addView(Button(this).apply {
            text = getString(res); textSize = 12f; isAllCaps = false; minWidth = 0; minimumWidth = 0
            setOnClickListener { action(); controls.invalidate(); LayoutStore.save(this@GameActivity, this@GameActivity.layout) }
        })
        btn(R.string.ed_size_minus) { controls.resizeSelected(1 / 1.1f) }
        btn(R.string.ed_size_plus) { controls.resizeSelected(1.1f) }
        btn(R.string.ed_opacity_minus) { layout.opacity = (layout.opacity - 0.1f).coerceIn(0.15f, 1f) }
        btn(R.string.ed_opacity_plus) { layout.opacity = (layout.opacity + 0.1f).coerceIn(0.15f, 1f) }
        btn(R.string.ed_dpad_minus) { layout.dpadSpread = (layout.dpadSpread - 0.1f).coerceIn(0.7f, 1.6f) }
        btn(R.string.ed_dpad_plus) { layout.dpadSpread = (layout.dpadSpread + 0.1f).coerceIn(0.7f, 1.6f) }
        btn(R.string.ed_reset) { layout.reset() }
        btn(R.string.ed_done) { finishEdit() }
        return bar
    }

    private fun startEdit() {
        editing = true; controls.alpha = 1f; controls.editMode = true; editBar.visibility = View.VISIBLE
        applyPause()   // the frozen game frame stays visible underneath for positioning
        toast(R.string.ed_hint)
    }

    private fun finishEdit() {
        LayoutStore.save(this, layout)
        controls.editMode = false; editBar.visibility = View.GONE; editing = false
        applyPause()
    }

    // ---------------------------------------------------------------- gamepad

    private fun keyName(code: Int?) = if (code == null) getString(R.string.not_set) else KeyEvent.keyCodeToString(code).removePrefix("KEYCODE_")

    private fun remapTargets(): List<Pair<String, Int>> =
        listOf(getString(R.string.d_up) to RetroPad.UP, getString(R.string.d_down) to RetroPad.DOWN,
            getString(R.string.d_left) to RetroPad.LEFT, getString(R.string.d_right) to RetroPad.RIGHT) +
            layout.elems.filter { it.spec.retro >= 0 }.map { it.spec.label to it.spec.retro }

    private fun showRemap(initial: Int) {
        val targets = remapTargets()
        val back = { showControlsMenu(4) }
        val items = targets.mapIndexed { k, t -> MenuEntry(t.first, sub = keyName(padMap.keyFor(t.second))) { captureKey(k, t.first, t.second) } } +
            MenuEntry(getString(R.string.remap_reset), Icons.Filled.Refresh) { LayoutStore.resetMap(this, system); padMap = PadMap(); showRemap(targets.size) } +
            backItem(back)
        openPage(MenuPage(getString(R.string.remap), gameTitle, items, onBack = back, initial = initial, tag = "remap"))
    }

    /** Waits for one controller button (everything except Back) and binds it. */
    private fun captureKey(index: Int, label: String, retro: Int) {
        val back = { showRemap(index) }
        openPage(MenuPage(label, getString(R.string.press_button, label), listOf(MenuEntry(getString(R.string.cancel), Icons.Filled.Close) { back() }),
            onBack = back, tag = "capture", keyCapture = { code ->
                padMap.bind(code, retro); LayoutStore.saveMap(this, system, padMap)
                Log.i(QA_TAG, "REMAP ${keyName(code)} -> $label($retro)")
                showRemap(index)
            }))
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        menu.page?.let { p -> if (event.keyCode != KeyEvent.KEYCODE_BACK) { menuKey(p, event); return true } }
        if (uiBlocked) return super.dispatchKeyEvent(event)
        if (event.keyCode == KeyEvent.KEYCODE_BUTTON_MODE) { if (event.action == KeyEvent.ACTION_DOWN) showMenu(); return true }
        val id = padMap.lookup(event.keyCode)
        if (id < 0) return super.dispatchKeyEvent(event)
        padMask = if (event.action == KeyEvent.ACTION_DOWN) padMask or RetroPad.bit(id) else padMask and RetroPad.bit(id).inv()
        if (event.source and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD && controls.alpha > 0f) controls.alpha = 0f  // auto-hide
        pushInput()
        return true
    }

    override fun onGenericMotionEvent(e: MotionEvent): Boolean {
        if (menu.page != null) {   // stick / hat moves the menu selection, one step per push
            if (e.source and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK && e.action == MotionEvent.ACTION_MOVE) {
                val y = e.getAxisValue(MotionEvent.AXIS_HAT_Y).let { if (it == 0f) e.getAxisValue(MotionEvent.AXIS_Y) else it }
                val d = if (y < -0.5f) -1 else if (y > 0.5f) 1 else 0
                if (d != 0 && d != navDir && menu.page?.keyCapture == null) menu.move(d)
                navDir = d
            }
            return true
        }
        if (uiBlocked || e.source and InputDevice.SOURCE_JOYSTICK != InputDevice.SOURCE_JOYSTICK || e.action != MotionEvent.ACTION_MOVE) return super.onGenericMotionEvent(e)
        if (system == GameSystem.PS2) {
            fun ax(a: Int) = e.getAxisValue(a).let { if (kotlin.math.abs(it) < 0.12f) 0 else (it * 32767).toInt() }   // dead zone
            padStick[0] = ax(MotionEvent.AXIS_X) to ax(MotionEvent.AXIS_Y)
            padStick[1] = ax(MotionEvent.AXIS_Z) to ax(MotionEvent.AXIS_RZ)
            pushAnalog(0); pushAnalog(1)
            val lt = maxOf(e.getAxisValue(MotionEvent.AXIS_LTRIGGER), e.getAxisValue(MotionEvent.AXIS_BRAKE)) > 0.5f
            val rt = maxOf(e.getAxisValue(MotionEvent.AXIS_RTRIGGER), e.getAxisValue(MotionEvent.AXIS_GAS)) > 0.5f
            var m = padMask and (RetroPad.bit(RetroPad.L2) or RetroPad.bit(RetroPad.R2) or RetroPad.bit(RetroPad.UP) or RetroPad.bit(RetroPad.DOWN) or RetroPad.bit(RetroPad.LEFT) or RetroPad.bit(RetroPad.RIGHT)).inv()
            if (lt) m = m or RetroPad.bit(RetroPad.L2); if (rt) m = m or RetroPad.bit(RetroPad.R2)
            val hx = e.getAxisValue(MotionEvent.AXIS_HAT_X); val hy = e.getAxisValue(MotionEvent.AXIS_HAT_Y)
            if (hx < -0.5f) m = m or RetroPad.bit(RetroPad.LEFT); if (hx > 0.5f) m = m or RetroPad.bit(RetroPad.RIGHT)
            if (hy < -0.5f) m = m or RetroPad.bit(RetroPad.UP); if (hy > 0.5f) m = m or RetroPad.bit(RetroPad.DOWN)
            padMask = m; pushInput()
            return true
        }
        val x = e.getAxisValue(MotionEvent.AXIS_HAT_X).let { if (it == 0f) e.getAxisValue(MotionEvent.AXIS_X) else it }
        val y = e.getAxisValue(MotionEvent.AXIS_HAT_Y).let { if (it == 0f) e.getAxisValue(MotionEvent.AXIS_Y) else it }
        val dirs = RetroPad.bit(RetroPad.UP) or RetroPad.bit(RetroPad.DOWN) or RetroPad.bit(RetroPad.LEFT) or RetroPad.bit(RetroPad.RIGHT)
        var m = padMask and dirs.inv()
        if (x < -0.5f) m = m or RetroPad.bit(RetroPad.LEFT); if (x > 0.5f) m = m or RetroPad.bit(RetroPad.RIGHT)
        if (y < -0.5f) m = m or RetroPad.bit(RetroPad.UP); if (y > 0.5f) m = m or RetroPad.bit(RetroPad.DOWN)
        padMask = m; pushInput()
        return true
    }

    // ---------------------------------------------------------------- lifecycle

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideBars()
        else { controls.releaseAll(); padMask = 0; qaMask = 0; pushInput(); clearAnalog() }   // never leave a button held behind a dialog or another window
    }

    override fun onPause() {
        super.onPause()
        resumedState = false
        controls.releaseAll(); padMask = 0; qaMask = 0; pushInput(); clearAnalog()
        // Order matters for GPU cores (PS2): their state is saved on the GL thread, so it must still be running.
        if (loaded) {
            NativeBridge.nativeSetPaused(true)
            NativeBridge.nativeSaveSram()
            saveSlot(SaveSlots.AUTO)   // auto-save powers "Continue" on the home screen
            if (isFinishing) { NativeBridge.nativeUnload(); loaded = false }   // releases the core's GL objects while the context exists
        }
        NativeBridge.nativeGlPaused()
        gl.onPause()
    }

    override fun onResume() {
        super.onResume()
        resumedState = true
        gl.onResume()
        applyPause()
    }

    override fun onDestroy() {
        ui.removeCallbacksAndMessages(null)
        if (BuildConfig.DEBUG) runCatching { unregisterReceiver(qaReceiver) }
        if (loaded) { NativeBridge.nativeUnload(); loaded = false }
        super.onDestroy()
    }

    // ---------------------------------------------------------------- debug QA channel

    private fun handleQa(i: Intent) {
        if (!BuildConfig.DEBUG || !loaded) return
        val n = i.getIntExtra("n", 0)
        when (val cmd = i.getStringExtra("cmd")) {
            "pause" -> { qaPaused = true; applyPause(); Log.i(QA_TAG, "QA paused ram=%08x".format(NativeBridge.nativeRamHash())) }
            "resume" -> { qaPaused = false; applyPause(); Log.i(QA_TAG, "QA resumed") }
            "ram" -> Log.i(QA_TAG, "QA ram=%08x frames=${NativeBridge.nativeGetStats()[0]}".format(NativeBridge.nativeRamHash()))
            "save" -> saveSlot(n)
            "load" -> Log.i(QA_TAG, "QA load slot=$n result=${resources.getResourceEntryName(loadSlot(n))}")
            "input" -> { qaMask = n; pushInput(); ui.postDelayed({ qaMask = 0; pushInput() }, i.getIntExtra("ms", 500).toLong()) }
            "menu" -> showMenu()
            "finish" -> finish()
            "edit" -> startEdit()
            "edit_done" -> finishEdit()
            "geom" -> for (e in layout.elems) Log.i(QA_TAG, "GEOM ${e.spec.id} cx=${controls.geom.cx(e).toInt()} cy=${controls.geom.cy(e).toInt()} r=${controls.geom.r(e).toInt()} retro=${e.spec.retro} opacity=${layout.opacity} spread=${layout.dpadSpread} haptic=${layout.haptic}")
            "export" -> Log.i(QA_TAG, "EXPORT " + layout.serialize().trim().replace("\n", "|"))
            "import" -> applyImported((i.getStringExtra("text") ?: "").replace("|", "\n"))
            "reset_layout" -> { layout.reset(); controls.invalidate(); LayoutStore.save(this, layout); Log.i(QA_TAG, "QA layout reset") }
            "haptic" -> { layout.haptic = n != 0; LayoutStore.save(this, layout); Log.i(QA_TAG, "QA haptic=${layout.haptic}") }
            "touch2" -> qaMultiTouch()
            "stick" -> qaStick()
            "move_style" -> { layout.dpadStick = n != 0; controls.releaseAll(); controls.invalidate(); LayoutStore.save(this, layout); Log.i(QA_TAG, "MOVE style=${if (layout.dpadStick) "joystick" else "dpad"} system=$system") }
            "dpadstick" -> qaDpadStick()
            else -> Log.i(QA_TAG, "QA unknown cmd $cmd")
        }
    }

    /** D-pad in joystick mode, through the real view: centre, right, up-left diagonal while a button is held, lift, cancel. */
    private fun qaDpadStick() {
        val g = controls.geom
        val d = layout.elem("DPAD") ?: return
        val face = layout.elems.first { it.spec.shape == Shape.CIRCLE }
        val t0 = SystemClock.uptimeMillis()
        fun ev(action: Int, idx: Int, vararg pts: Triple<Int, Float, Float>) {
            val props = Array(pts.size) { MotionEvent.PointerProperties().apply { id = pts[it].first; toolType = MotionEvent.TOOL_TYPE_FINGER } }
            val coords = Array(pts.size) { MotionEvent.PointerCoords().apply { x = pts[it].second; y = pts[it].third; pressure = 1f; size = 1f } }
            val m = MotionEvent.obtain(t0, SystemClock.uptimeMillis(), action or (idx shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), pts.size, props, coords, 0, 0, 1f, 1f, 79, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
            controls.dispatchTouchEvent(m); m.recycle()
        }
        val R = g.dpadStickR(d); val cx = g.cx(d); val cy = g.cy(d)
        val dirs = RetroPad.bit(RetroPad.UP) or RetroPad.bit(RetroPad.DOWN) or RetroPad.bit(RetroPad.LEFT) or RetroPad.bit(RetroPad.RIGHT)
        fun log(name: String, expect: Int) { val m = controls.currentMask and dirs; Log.i(QA_TAG, "DSTICK $name mask=$m expect=$expect ${if (m == expect) "OK" else "MISMATCH"} full=${controls.currentMask}") }
        val c = Triple(0, cx, cy); val right = Triple(0, cx + R * 2f, cy); val upLeft = Triple(0, cx - R * 0.6f, cy - R * 0.6f)
        val b = Triple(1, g.cx(face), g.cy(face))
        ev(MotionEvent.ACTION_DOWN, 0, c); log("centre (dead zone)", 0)
        ev(MotionEvent.ACTION_MOVE, 0, right); log("pushed right past the edge", RetroPad.bit(RetroPad.RIGHT))
        ev(MotionEvent.ACTION_POINTER_DOWN, 1, right, b); ev(MotionEvent.ACTION_MOVE, 0, upLeft, b)
        log("up-left while a face button is held", RetroPad.bit(RetroPad.UP) or RetroPad.bit(RetroPad.LEFT))
        ev(MotionEvent.ACTION_POINTER_UP, 0, upLeft, b); log("stick finger lifted", 0)
        ev(MotionEvent.ACTION_UP, 0, b)
        ev(MotionEvent.ACTION_DOWN, 0, right); ev(MotionEvent.ACTION_CANCEL, 0, right); log("cancel", 0)
    }

    /** Left stick + a face button held together through the real view; checks values, release and spring-back. */
    private fun qaStick() {
        val g = controls.geom
        val st = layout.elem("LSTICK") ?: run { Log.i(QA_TAG, "STICK n/a for $system"); return }
        val face = layout.elems.first { it.spec.shape == Shape.CIRCLE }
        val t0 = SystemClock.uptimeMillis()
        fun ev(action: Int, idx: Int, vararg pts: Triple<Int, Float, Float>) {
            val props = Array(pts.size) { MotionEvent.PointerProperties().apply { id = pts[it].first; toolType = MotionEvent.TOOL_TYPE_FINGER } }
            val coords = Array(pts.size) { MotionEvent.PointerCoords().apply { x = pts[it].second; y = pts[it].third; pressure = 1f; size = 1f } }
            val m = MotionEvent.obtain(t0, SystemClock.uptimeMillis(), action or (idx shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), pts.size, props, coords, 0, 0, 1f, 1f, 78, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
            controls.dispatchTouchEvent(m); m.recycle()
        }
        val r = g.r(st); val sx = g.cx(st); val sy = g.cy(st)
        val a = Triple(0, sx, sy); val right = Triple(0, sx + r * 2f, sy); val upLeft = Triple(0, sx - r * 0.5f, sy - r * 0.5f)
        val b = Triple(1, g.cx(face), g.cy(face))
        fun log(name: String) = Log.i(QA_TAG, "STICK $name value=${controls.stickValue(0)} mask=${controls.currentMask}")
        ev(MotionEvent.ACTION_DOWN, 0, a); log("centre (dead zone)")
        ev(MotionEvent.ACTION_MOVE, 0, right); log("full right (clamped)")
        ev(MotionEvent.ACTION_POINTER_DOWN, 1, right, b); log("+face button held")
        ev(MotionEvent.ACTION_MOVE, 0, upLeft, b); log("half up-left while button held")
        ev(MotionEvent.ACTION_POINTER_UP, 0, upLeft, b); log("stick finger lifted -> centre, button still held")
        ev(MotionEvent.ACTION_UP, 0, b); log("all lifted")
        ev(MotionEvent.ACTION_DOWN, 0, right); ev(MotionEvent.ACTION_CANCEL, 0, right); log("cancel -> centre")
    }

    /** Dispatches synthetic multi-pointer events through the real view (adb cannot inject two fingers). */
    private fun qaMultiTouch() {
        val g = controls.geom
        val dp = layout.elem("DPAD")!!; val face = layout.elems.first { it.spec.shape == Shape.CIRCLE }
        val ax = g.cx(dp) + g.r(dp) * 0.6f; val ay = g.cy(dp); val bx = g.cx(face); val by = g.cy(face)
        val right = RetroPad.bit(RetroPad.RIGHT); val fb = RetroPad.bit(face.spec.retro)
        val t0 = SystemClock.uptimeMillis()
        fun send(action: Int, idx: Int, vararg pts: Triple<Int, Float, Float>) {
            val props = Array(pts.size) { MotionEvent.PointerProperties().apply { id = pts[it].first; toolType = MotionEvent.TOOL_TYPE_FINGER } }
            val coords = Array(pts.size) { MotionEvent.PointerCoords().apply { x = pts[it].second; y = pts[it].third; pressure = 1f; size = 1f } }
            val ev = MotionEvent.obtain(t0, SystemClock.uptimeMillis(), action or (idx shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), pts.size, props, coords,
                0, 0, 1f, 1f, 77, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
            controls.dispatchTouchEvent(ev); ev.recycle()
        }
        val a = Triple(0, ax, ay); val b = Triple(1, bx, by)
        var step = 0
        fun check(name: String, expect: Int) { step++; Log.i(QA_TAG, "TOUCH2 $step $name mask=${controls.currentMask} expect=$expect ${if (controls.currentMask == expect) "OK" else "MISMATCH"}") }
        val seq = listOf<() -> Unit>(
            { send(MotionEvent.ACTION_DOWN, 0, a); check("dpad-right down", right) },
            { send(MotionEvent.ACTION_POINTER_DOWN, 1, a, b); check("+face button (chord)", right or fb) },
            { send(MotionEvent.ACTION_POINTER_UP, 0, a, b) },
            { check("first finger lifted, second held", fb) },
            { send(MotionEvent.ACTION_UP, 0, b) },
            { check("all lifted", 0) },
            { send(MotionEvent.ACTION_DOWN, 0, a); send(MotionEvent.ACTION_POINTER_DOWN, 1, a, b); send(MotionEvent.ACTION_CANCEL, 0, a, b); check("ACTION_CANCEL releases at once", 0) },
            { send(MotionEvent.ACTION_DOWN, 0, b); send(MotionEvent.ACTION_UP, 0, b); check("0 ms tap is still held", fb) },
            { check("tap released after hold window", 0) },
            { send(MotionEvent.ACTION_DOWN, 0, a); controls.releaseAll(); check("focus-loss release", 0) },
        )
        seq.forEachIndexed { k, f -> ui.postDelayed(f, 150L * k) }
    }
}
