@file:OptIn(androidx.compose.animation.ExperimentalAnimationApi::class)

package com.nesgames.app.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.key.*
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.lifecycleScope
import com.nesgames.app.library.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Console tabs, PS5-home style. null = all games. */
private val TABS: List<Pair<String, GameSystem?>> = listOf(
    "All Games" to null, "NES" to GameSystem.NES, "Mega Drive" to GameSystem.MD, "PlayStation" to GameSystem.PS1,
)

private fun folderFor(s: GameSystem?) = ConsoleFolders.folders.entries.firstOrNull { it.value == s }?.key ?: "NES / MegaDrive / PS1"

private fun controlsFor(s: GameSystem) = when (s) {
    GameSystem.NES -> "D-pad · A · B · Select · Start"
    GameSystem.MD -> "D-pad · A · B · C · Mode · Start"
    GameSystem.PS1 -> "D-pad · ✕ ○ □ △ · L1 · R1 · Select · Start"
    else -> "D-pad · buttons"
}

/** Console colour family (NES red, Mega Drive blue, PlayStation violet) with a per-game shift. */
private fun hueOf(g: Game?): Float {
    if (g == null) return 215f
    val base = when (g.system) { GameSystem.NES -> 355f; GameSystem.MD -> 215f; GameSystem.PS1 -> 265f; else -> 160f }
    val shift = ((g.title.hashCode() ushr 3) % 41) - 20   // -20..+20
    return (base + shift + 360f) % 360f
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        androidx.core.view.WindowInsetsControllerCompat(window, window.decorView).apply {   // console-style immersive home
            hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        val settings = Settings(applicationContext)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Color.White)) { Home(settings) }
        }
    }

    @Composable
    private fun Home(settings: Settings) {
        val tree by settings.treeUri.collectAsState(initial = "")
        var result by remember { mutableStateOf<ScanResult?>(null) }
        var scanning by remember { mutableStateOf(false) }
        var tab by remember { mutableIntStateOf(0) }
        val selByTab = remember { mutableStateMapOf<Int, Int>() }
        var query by remember { mutableStateOf("") }
        var searching by remember { mutableStateOf(false) }
        var launching by remember { mutableStateOf<Game?>(null) }
        var resume by remember { mutableStateOf(false) }
        var splash by remember { mutableStateOf(true) }
        var coverVersion by remember { mutableIntStateOf(0) }
        val scope = rememberCoroutineScope()

        fun rescan(uri: String?) {
            if (uri.isNullOrEmpty() || scanning) return
            scanning = true
            lifecycleScope.launch {
                result = withContext(Dispatchers.IO) {
                    runCatching { ConsoleFolders.ensure(applicationContext, Uri.parse(uri)) }   // re-create folders if deleted
                    runCatching { LibraryScanner(applicationContext).scan(Uri.parse(uri)) }.getOrNull()
                }
                scanning = false
            }
        }

        val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri != null) {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                scope.launch {
                    val made = withContext(Dispatchers.IO) { runCatching { ConsoleFolders.ensure(applicationContext, uri) }.getOrDefault(0) }
                    if (made > 0) Toast.makeText(this@MainActivity, "Created $made console folders", Toast.LENGTH_SHORT).show()
                    settings.setTreeUri(uri.toString())
                }
            }
        }
        val hint = remember { DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", "primary:Download/nesGames") }

        LaunchedEffect(Unit) { delay(1100); splash = false }
        LaunchedEffect(tree) { rescan(tree) }
        LaunchedEffect(result) {   // fetch missing box art in the background, one game at a time
            for (g in result?.games.orEmpty()) {
                if (withContext(Dispatchers.IO) { Covers.fetchBoxArt(applicationContext, g) }) coverVersion++
            }
        }
        LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { coverVersion++ }   // pick up new gameplay snapshots
        LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { launching = null; rescan(tree) }

        val sys = TABS[tab].second
        val shown = result?.games.orEmpty().filter { (sys == null || it.system == sys) && it.title.contains(query.trim(), true) }
        val sel = (selByTab[tab] ?: 0).coerceIn(0, (shown.size - 1).coerceAtLeast(0))
        val focused = shown.getOrNull(sel)

        fun launch(g: Game, cont: Boolean = false) { if (launching == null) { resume = cont; launching = g } }
        LaunchedEffect(launching) {
            val g = launching ?: return@LaunchedEffect
            delay(380)   // let the zoom-in animation play
            startActivity(Intent(this@MainActivity, GameActivity::class.java)
                .putExtra(GameActivity.EXTRA_URI, g.uri.toString())
                .putExtra(GameActivity.EXTRA_ID, g.id)
                .putExtra(GameActivity.EXTRA_RESUME, resume)
                .putExtra(GameActivity.EXTRA_ENTRY, g.zipEntry)
                .putExtra(GameActivity.EXTRA_NAME, g.fileName)
                .putExtra(GameActivity.EXTRA_SYSTEM, g.system.name)
                .apply { if (g.dirUri != null) { putExtra(GameActivity.EXTRA_DIR, g.dirUri.toString()); putExtra(GameActivity.EXTRA_DISC_FILES, g.discFiles.toTypedArray()) } })
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        }

        // --- animated, game-tinted background ---
        val hue by animateFloatAsState(hueOf(focused), tween(700), label = "hue")
        val top = Color.hsv(hue, 0.55f, 0.32f)
        val bottom = Color.hsv((hue + 30) % 360, 0.65f, 0.08f)
        val focus = remember { FocusRequester() }

        Box(
            Modifier.fillMaxSize()
                .background(Brush.verticalGradient(listOf(top, bottom)))
                .focusRequester(focus).focusable()
                .onPreviewKeyEvent { e ->
                    if (e.type != KeyEventType.KeyDown || searching) return@onPreviewKeyEvent false
                    when (e.key) {
                        Key.DirectionRight -> { selByTab[tab] = (sel + 1).coerceAtMost(shown.size - 1); true }
                        Key.DirectionLeft -> { selByTab[tab] = (sel - 1).coerceAtLeast(0); true }
                        Key.ButtonR1, Key.DirectionDown -> { tab = (tab + 1) % TABS.size; true }
                        Key.ButtonL1, Key.DirectionUp -> { tab = (tab + TABS.size - 1) % TABS.size; true }
                        Key.Enter, Key.ButtonA, Key.DirectionCenter -> { focused?.let(::launch); true }
                        else -> false
                    }
                },
        ) {
            LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
            if (tree.isNullOrEmpty() && !splash) {
                FirstRun { picker.launch(hint) }
            } else {
                Column(Modifier.fillMaxSize().displayCutoutPadding().padding(horizontal = 36.dp, vertical = 18.dp)) {
                    TopBar(tab, { tab = it }, scanning, onRefresh = { rescan(tree) }, onSearch = { searching = !searching; if (!searching) query = "" },
                        onFolder = { picker.launch(hint) })
                    AnimatedVisibility(searching) {
                        BasicTextField(query, { query = it }, singleLine = true, cursorBrush = SolidColor(Color.White),
                            textStyle = TextStyle(color = Color.White, fontSize = 18.sp),
                            modifier = Modifier.padding(top = 10.dp).fillMaxWidth(0.5f).clip(RoundedCornerShape(24.dp))
                                .background(Color.White.copy(0.12f)).padding(horizontal = 18.dp, vertical = 10.dp),
                            decorationBox = { inner -> if (query.isEmpty()) Text("Search games", color = Color.White.copy(0.5f)); inner() })
                    }
                    Spacer(Modifier.height(18.dp))
                    if (shown.isEmpty()) {
                        EmptyShelf(sys, scanning, result)
                    } else {
                        GameRow(shown, sel, launching, coverVersion, onSelect = { selByTab[tab] = it }, onLaunch = ::launch)
                        Spacer(Modifier.height(22.dp))
                        focused?.let { Details(it, coverVersion, onPlay = { launch(it) }, onContinue = { launch(it, cont = true) }) }
                    }
                }
            }

            // launch: fade to black while the tile zooms
            val black by animateFloatAsState(if (launching != null) 1f else 0f, tween(360), label = "launch")
            if (black > 0f) Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = black)))

            AnimatedVisibility(splash, enter = fadeIn(), exit = fadeOut(tween(500)) + scaleOut(targetScale = 1.15f)) { Splash() }
        }
    }

    @Composable
    private fun Splash() {
        val t = rememberInfiniteTransition(label = "s")
        val glow by t.animateFloat(0.6f, 1f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "g")
        Box(Modifier.fillMaxSize().background(Color(0xFF05070D)), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("nesGames", fontSize = 54.sp, fontWeight = FontWeight.Black, color = Color.White.copy(alpha = glow))
                Text("RETRO · HOME", fontSize = 13.sp, letterSpacing = 6.sp, color = Color.White.copy(0.5f))
            }
        }
    }

    @Composable
    private fun TopBar(tab: Int, onTab: (Int) -> Unit, scanning: Boolean, onRefresh: () -> Unit, onSearch: () -> Unit, onFolder: () -> Unit) {
        var now by remember { mutableStateOf(Date()) }
        LaunchedEffect(Unit) { while (true) { now = Date(); delay(15_000) } }
        Row(verticalAlignment = Alignment.CenterVertically) {
            TABS.forEachIndexed { i, (label, _) ->
                val selected = i == tab
                val a by animateFloatAsState(if (selected) 1f else 0.55f, label = "tab")
                Column(Modifier.padding(end = 26.dp).clickable(remember { MutableInteractionSource() }, null) { onTab(i) },
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(label, color = Color.White.copy(alpha = a), fontSize = if (selected) 20.sp else 17.sp,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium)
                    val w by animateDpAsState(if (selected) 26.dp else 0.dp, label = "u")
                    Box(Modifier.padding(top = 4.dp).height(3.dp).width(w).clip(CircleShape).background(Color.White))
                }
            }
            Spacer(Modifier.weight(1f))
            if (scanning) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = Color.White)
            IconButton(onClick = onSearch) { Icon(Icons.Default.Search, "Search", tint = Color.White) }
            IconButton(onClick = onRefresh) { Icon(Icons.Default.Refresh, "Refresh", tint = Color.White) }
            IconButton(onClick = onFolder) { Icon(Icons.Default.Settings, "Games folder", tint = Color.White) }
            Text(SimpleDateFormat("HH:mm", Locale.getDefault()).format(now), color = Color.White, fontSize = 18.sp, modifier = Modifier.padding(start = 8.dp))
        }
    }

    @Composable
    private fun GameRow(games: List<Game>, sel: Int, launching: Game?, coverVersion: Int, onSelect: (Int) -> Unit, onLaunch: (Game) -> Unit) {
        val state = rememberLazyListState()
        LaunchedEffect(sel) { state.animateScrollToItem(sel.coerceAtLeast(0)) }   // focused tile stays on the left, like PS5
        LazyRow(state = state, horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.Top,
            contentPadding = PaddingValues(end = 400.dp), modifier = Modifier.height(190.dp)) {
            itemsIndexed(games, key = { _, g -> g.id }) { i, g ->
                val isSel = i == sel
                val size by animateDpAsState(if (isSel) 170.dp else 112.dp, spring(dampingRatio = 0.7f, stiffness = 400f), label = "size")
                val zoom by animateFloatAsState(if (launching == g) 1.25f else 1f, tween(360), label = "zoom")
                Box(Modifier.size(size).scale(zoom)
                    .shadow(if (isSel) 18.dp else 0.dp, RoundedCornerShape(14.dp))
                    .clip(RoundedCornerShape(14.dp))
                    .then(if (isSel) Modifier.border(3.dp, Color.White, RoundedCornerShape(14.dp)) else Modifier)
                    .clickable { if (isSel) onLaunch(g) else onSelect(i) }) {
                    Cover(g, coverVersion)
                }
            }
        }
    }

    @Composable
    private fun Cover(g: Game, version: Int) {
        val art by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, g.id, version) {
            value = withContext(Dispatchers.IO) { Covers.bestFile(applicationContext, g.id)?.let { Covers.decode(it)?.asImageBitmap() } }
        }
        Crossfade(art, label = "cover") { img ->
            if (img != null) Box(Modifier.fillMaxSize().background(Color.Black)) {
                androidx.compose.foundation.Image(img, g.title, Modifier.fillMaxSize(), contentScale = androidx.compose.ui.layout.ContentScale.Crop)
                SystemBadge(g)
            } else GeneratedCover(g)
        }
    }

    @Composable
    private fun SystemBadge(g: Game) = Box(Modifier.fillMaxSize()) {
        Text(g.system.short, Modifier.align(Alignment.BottomStart).padding(8.dp).clip(RoundedCornerShape(5.dp))
            .background(Color.Black.copy(alpha = 0.55f)).padding(horizontal = 6.dp, vertical = 2.dp),
            color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
    }

    @Composable
    private fun GeneratedCover(g: Game) {
        val h = hueOf(g)
        Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(Color.hsv(h, 0.55f, 0.62f), Color.hsv((h + 45) % 360, 0.7f, 0.25f)))),
            contentAlignment = Alignment.Center) {
            Text(g.title.split(' ').filter { it.isNotEmpty() }.take(2).joinToString("") { it.take(1).uppercase() },
                fontSize = 40.sp, fontWeight = FontWeight.Black, color = Color.White.copy(alpha = 0.92f))
            Text(g.system.short, Modifier.align(Alignment.BottomStart).padding(8.dp).clip(RoundedCornerShape(5.dp))
                .background(Color.Black.copy(alpha = 0.45f)).padding(horizontal = 6.dp, vertical = 2.dp),
                color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
        }
    }

    @Composable
    private fun Details(g: Game, version: Int, onPlay: () -> Unit, onContinue: () -> Unit) {
        val autoSave by produceState<Long?>(null, g.id, version) {
            value = withContext(Dispatchers.IO) { SaveSlots.state(applicationContext, SaveSlots.keyFor(g.id), SaveSlots.AUTO).takeIf { it.length() > 0 }?.lastModified() }
        }
        AnimatedContent(g, transitionSpec = {
            (fadeIn(tween(260)) + slideInHorizontally(tween(260)) { it / 12 }) togetherWith fadeOut(tween(150))
        }, label = "details") { game ->
            Column {
                Text(game.title, fontSize = 34.sp, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${game.system.label}  ·  ${"%.1f".format(game.size / 1048576.0)} MB", color = Color.White.copy(0.7f), fontSize = 15.sp)
                Spacer(Modifier.height(16.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val saved = autoSave
                    if (saved != null) {
                        Button(onClick = onContinue, shape = RoundedCornerShape(28.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black),
                            contentPadding = PaddingValues(horizontal = 30.dp, vertical = 12.dp)) {
                            Icon(Icons.Default.PlayArrow, null); Spacer(Modifier.width(6.dp))
                            Column { Text("Continue", fontSize = 18.sp, fontWeight = FontWeight.Bold); Text(SaveSlots.ago(saved), fontSize = 11.sp) }
                        }
                        Spacer(Modifier.width(12.dp))
                        OutlinedButton(onClick = onPlay, shape = RoundedCornerShape(28.dp),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                            contentPadding = PaddingValues(horizontal = 24.dp, vertical = 12.dp)) { Text("Start new", fontSize = 16.sp) }
                    } else {
                        Button(onClick = onPlay, shape = RoundedCornerShape(28.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black),
                            contentPadding = PaddingValues(horizontal = 34.dp, vertical = 12.dp)) {
                            Icon(Icons.Default.PlayArrow, null); Spacer(Modifier.width(6.dp)); Text("Play", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                    Spacer(Modifier.width(18.dp))
                    Text("Controls: ${controlsFor(game.system)}", color = Color.White.copy(0.75f), fontSize = 14.sp)
                }
            }
        }
    }

    @Composable
    private fun EmptyShelf(sys: GameSystem?, scanning: Boolean, r: ScanResult?) {
        Column(Modifier.fillMaxWidth().padding(top = 30.dp)) {
            Text(if (scanning) "Looking for games…" else "No games here yet", fontSize = 30.sp, fontWeight = FontWeight.Bold, color = Color.White)
            Spacer(Modifier.height(10.dp))
            if (!scanning) Text("Copy your games into Download/nesGames/${folderFor(sys)} and they will appear here automatically.",
                color = Color.White.copy(0.75f), fontSize = 16.sp)
            if (r != null && r.detectedUnsupported > 0)
                Text("${r.detectedUnsupported} file(s) belong to consoles coming in a later update.", color = Color.White.copy(0.5f), fontSize = 14.sp)
        }
    }

    @Composable
    private fun FirstRun(onPick: () -> Unit) {
        Column(Modifier.fillMaxSize().systemBarsPadding().padding(32.dp), verticalArrangement = Arrangement.Center) {
            Text("Welcome to nesGames", fontSize = 34.sp, fontWeight = FontWeight.Black, color = Color.White)
            Spacer(Modifier.height(12.dp))
            Text("Choose the nesGames folder inside Download. The app creates one folder per console:",
                color = Color.White.copy(0.85f), fontSize = 16.sp)
            Spacer(Modifier.height(8.dp))
            Text("NES · MegaDrive · PS1 · GameBoy · GBA · SNES · BIOS", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            Spacer(Modifier.height(8.dp))
            Text("Put each game in its console's folder. It shows up automatically, and the right controller layout loads when you play. " +
                "Android won't let apps pick the Download folder itself, so select the nesGames folder inside it.",
                color = Color.White.copy(0.6f), fontSize = 14.sp)
            Spacer(Modifier.height(24.dp))
            Button(onClick = onPick, shape = RoundedCornerShape(28.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black)) { Text("Select nesGames folder", fontWeight = FontWeight.Bold) }
        }
    }
}
