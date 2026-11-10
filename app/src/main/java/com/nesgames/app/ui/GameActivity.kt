package com.nesgames.app.ui

import android.app.AlertDialog
import android.net.Uri
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.nesgames.app.BuildConfig
import com.nesgames.app.emu.Cores
import com.nesgames.app.emu.NativeBridge
import com.nesgames.app.emu.RetroPad
import com.nesgames.app.library.Game
import com.nesgames.app.library.GameSystem
import com.nesgames.app.library.LibraryScanner
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
        /** Debug builds only: absolute path of a ROM inside this app's private storage, for adb QA runs. */
        const val EXTRA_TEST_FILE = "test_rom_file"
        const val QA_TAG = "nesGamesQA"
    }

    private lateinit var gl: GLSurfaceView
    private lateinit var controls: ControlsView
    private var loaded = false
    private var menuOpen = false
    private var touchMask = 0
    private var padMask = 0
    private var gameId = "game"
    private val ui = Handler(Looper.getMainLooper())

    private val qaTick = object : Runnable {
        override fun run() {
            if (loaded) {
                val s = NativeBridge.nativeGetStats()
                Log.i(QA_TAG, "frames=${s[0]} video=${s[1]} audioFrames=${s[2]} underruns=${s[3]} hash=%08x center=%08x size=${s[6]}x${s[7]} mask=${touchMask or padMask}"
                    .format(s[4], s[5]))
            }
            ui.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        gl = GLSurfaceView(this).apply {
            setEGLContextClientVersion(2)
            preserveEGLContextOnPause = true
            setRenderer(object : GLSurfaceView.Renderer {
                override fun onSurfaceCreated(g: GL10?, c: EGLConfig?) = NativeBridge.nativeSurfaceCreated()
                override fun onSurfaceChanged(g: GL10?, w: Int, h: Int) = NativeBridge.nativeSurfaceChanged(w, h)
                override fun onDrawFrame(g: GL10?) = NativeBridge.nativeDrawFrame()   // vsync-paced by EGL swap
            })
        }
        controls = ControlsView(this, gameSystem(), { touchMask = it; pushInput() }, { showMenu() })
        setContentView(FrameLayout(this).apply { addView(gl); addView(controls) })
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) { override fun handleOnBackPressed() = showMenu() })
        lifecycleScope.launch { load() }
    }

    private fun gameSystem() = runCatching { GameSystem.valueOf(intent.getStringExtra(EXTRA_SYSTEM) ?: "NES") }.getOrDefault(GameSystem.NES)

    private suspend fun load() {
        val system = gameSystem()
        val corePath = Cores.corePath(this, system) ?: return fail("No emulator core is bundled for ${system.label}.")
        val result = withContext(Dispatchers.IO) {
            runCatching {
                val testFile = intent.getStringExtra(EXTRA_TEST_FILE)
                val (name, bytes) = if (BuildConfig.DEBUG && testFile != null) {
                    val f = File(testFile).canonicalFile
                    require(f.path.startsWith(filesDir.canonicalPath) || f.path.startsWith(getExternalFilesDir(null)!!.canonicalPath)) { "test ROM outside app storage" }
                    // discs (cue/chd/bin) stay on disk; the core opens the cue and its referenced tracks
                    if (f.extension.lowercase() in setOf("cue", "chd", "bin", "iso", "pbp")) f.path to ByteArray(0) else f.name to f.readBytes()
                } else {
                    val name = intent.getStringExtra(EXTRA_NAME) ?: "game.nes"
                    val g = Game("", name, name, system, Uri.parse(intent.getStringExtra(EXTRA_URI)), intent.getStringExtra(EXTRA_ENTRY), 0)
                    name to LibraryScanner.loadRom(this@GameActivity, g)
                }
                gameId = MessageDigest.getInstance("SHA-1").digest(if (bytes.isEmpty()) name.toByteArray() else bytes).joinToString("") { "%02x".format(it) }.take(16)
                val sys = File(filesDir, "system").apply { mkdirs() }
                val saves = File(filesDir, "saves").apply { mkdirs() }
                File(filesDir, "states").mkdirs()
                NativeBridge.nativeLoad(corePath, name, bytes, sys.path, saves.path, File(saves, "$gameId.srm").path, cacheDir.path)
            }.getOrElse { it.message ?: "Could not read the ROM" }
        }
        if (result != null) return fail(result)
        NativeBridge.nativeSetScaleMode(Settings(applicationContext).screenMode())
        loaded = true
        Log.i(QA_TAG, "BOOT_OK id=$gameId fps=${NativeBridge.nativeGetFps()}")
        ui.post(qaTick)
    }

    private fun fail(msg: String) {
        Log.e(QA_TAG, "BOOT_FAIL $msg")
        AlertDialog.Builder(this).setTitle("Can't start game").setMessage(msg).setCancelable(false)
            .setPositiveButton("Back to library") { _, _ -> finish() }.show()
    }

    private fun pushInput() { if (loaded) NativeBridge.nativeSetInput(0, touchMask or padMask) }

    private fun statePath(slot: Int) = File(File(filesDir, "states"), "$gameId.state$slot").path

    private fun showMenu() {
        if (!loaded || menuOpen) return
        menuOpen = true
        NativeBridge.nativeSetPaused(true)
        val items = arrayOf("Resume", "Save state", "Load state", "Restart", if (controls.alpha > 0f) "Hide touch controls" else "Show touch controls", "Screen: full screen / original aspect / integer", "Exit to library")
        AlertDialog.Builder(this).setTitle("Paused").setItems(items) { _, which ->
            when (which) {
                1 -> toast(if (NativeBridge.nativeSaveState(statePath(1))) "State saved" else "Save failed")
                2 -> toast(if (File(statePath(1)).exists() && NativeBridge.nativeLoadState(statePath(1))) "State loaded" else "No saved state")
                3 -> NativeBridge.nativeReset()
                4 -> controls.alpha = if (controls.alpha > 0f) 0f else 1f
                5 -> lifecycleScope.launch {
                    val s = Settings(applicationContext); val v = (s.screenMode() + 1) % 3; s.setScreenMode(v); NativeBridge.nativeSetScaleMode(v)
                    toast(arrayOf("Original aspect", "Integer scale", "Full screen")[v])
                }
                6 -> { finish(); return@setItems }
            }
        }.setOnDismissListener { menuOpen = false; NativeBridge.nativeSetPaused(false) }.show()
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    // ---- external gamepad (Bluetooth / USB) ----
    private fun keyBit(code: Int): Int = when (code) {
        KeyEvent.KEYCODE_DPAD_UP -> RetroPad.UP; KeyEvent.KEYCODE_DPAD_DOWN -> RetroPad.DOWN
        KeyEvent.KEYCODE_DPAD_LEFT -> RetroPad.LEFT; KeyEvent.KEYCODE_DPAD_RIGHT -> RetroPad.RIGHT
        KeyEvent.KEYCODE_BUTTON_A -> RetroPad.B      // bottom face button -> NES B (Nintendo layout)
        KeyEvent.KEYCODE_BUTTON_B -> RetroPad.A      // right face button  -> NES A
        KeyEvent.KEYCODE_BUTTON_X -> RetroPad.Y; KeyEvent.KEYCODE_BUTTON_Y -> RetroPad.X
        KeyEvent.KEYCODE_BUTTON_L1 -> RetroPad.L; KeyEvent.KEYCODE_BUTTON_R1 -> RetroPad.R
        KeyEvent.KEYCODE_BUTTON_START, KeyEvent.KEYCODE_ENTER -> RetroPad.START
        KeyEvent.KEYCODE_BUTTON_SELECT, KeyEvent.KEYCODE_SPACE -> RetroPad.SELECT
        KeyEvent.KEYCODE_Z -> RetroPad.B; KeyEvent.KEYCODE_X -> RetroPad.A
        else -> -1
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val e = event
        if (e.keyCode == KeyEvent.KEYCODE_BUTTON_MODE && e.action == KeyEvent.ACTION_DOWN) { showMenu(); return true }
        val bit = keyBit(e.keyCode)
        if (bit < 0 || menuOpen) return super.dispatchKeyEvent(e)
        padMask = if (e.action == KeyEvent.ACTION_DOWN) padMask or RetroPad.bit(bit) else padMask and RetroPad.bit(bit).inv()
        if (e.source and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD && controls.alpha > 0f) controls.alpha = 0f  // auto-hide
        pushInput()
        return true
    }

    override fun onGenericMotionEvent(e: MotionEvent): Boolean {
        if (e.source and InputDevice.SOURCE_JOYSTICK != InputDevice.SOURCE_JOYSTICK || e.action != MotionEvent.ACTION_MOVE) return super.onGenericMotionEvent(e)
        val x = e.getAxisValue(MotionEvent.AXIS_HAT_X).let { if (it == 0f) e.getAxisValue(MotionEvent.AXIS_X) else it }
        val y = e.getAxisValue(MotionEvent.AXIS_HAT_Y).let { if (it == 0f) e.getAxisValue(MotionEvent.AXIS_Y) else it }
        val dirs = RetroPad.bit(RetroPad.UP) or RetroPad.bit(RetroPad.DOWN) or RetroPad.bit(RetroPad.LEFT) or RetroPad.bit(RetroPad.RIGHT)
        var m = padMask and dirs.inv()
        if (x < -0.5f) m = m or RetroPad.bit(RetroPad.LEFT); if (x > 0.5f) m = m or RetroPad.bit(RetroPad.RIGHT)
        if (y < -0.5f) m = m or RetroPad.bit(RetroPad.UP); if (y > 0.5f) m = m or RetroPad.bit(RetroPad.DOWN)
        padMask = m; pushInput()
        return true
    }

    override fun onPause() {
        super.onPause()
        gl.onPause()
        if (loaded) { NativeBridge.nativeSetPaused(true); NativeBridge.nativeSaveSram() }
    }

    override fun onResume() {
        super.onResume()
        gl.onResume()
        if (loaded && !menuOpen) NativeBridge.nativeSetPaused(false)
    }

    override fun onDestroy() {
        ui.removeCallbacksAndMessages(null)
        if (loaded) { NativeBridge.nativeUnload(); loaded = false }
        super.onDestroy()
    }
}
