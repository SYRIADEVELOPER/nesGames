package com.nesgames.app.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.text.format.DateUtils
import android.util.LruCache
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Image
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.key.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.lifecycleScope
import com.nesgames.app.R
import com.nesgames.app.emu.Cores
import com.nesgames.app.library.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private enum class TabKind { ALL, RECENT, FAVORITES, SYSTEM }
private data class TabDef(val label: String, val kind: TabKind, val system: GameSystem? = null)

private fun controlsFor(s: GameSystem) = when (s) {
    GameSystem.NES -> "D-pad · A · B · Select · Start"
    GameSystem.MD -> "D-pad · A · B · C · Mode · Start"
    GameSystem.PS1 -> "D-pad · ✕ ○ □ △ · L1 · R1 · Select · Start"
    GameSystem.PS2 -> "D-pad · ✕ ○ □ △ · L1 R1 L2 R2 · Select · Start"
    else -> "D-pad"
}

/** Console colour family (NES red, Mega Drive blue, PlayStation violet) with a per-game shift. */
private fun hueOf(g: Game?): Float {
    if (g == null) return 215f
    val base = when (g.system) { GameSystem.NES -> 355f; GameSystem.MD -> 215f; GameSystem.PS1 -> 265f; GameSystem.PS2 -> 235f; else -> 160f }
    val shift = ((g.title.hashCode() ushr 3) % 41) - 20   // -20..+20
    return (base + shift + 360f) % 360f
}

/** Decoded covers kept in memory so scrolling and tab changes never re-read the files. */
private object CoverCache {
    private val cache = LruCache<String, ImageBitmap>(64)
    fun get(f: File): ImageBitmap? {
        val k = f.path + ":" + f.lastModified()
        cache.get(k)?.let { return it }
        return Covers.decode(f)?.asImageBitmap()?.also { cache.put(k, it) }
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        WindowInsetsControllerCompat(window, window.decorView).apply {   // console-style immersive home
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        val settings = Settings(applicationContext)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Color.White)) { Home(settings) }
        }
    }

    @Composable
    private fun Home(settings: Settings) {
        val dao = remember { GameDb.get(applicationContext).games() }
        val tree by settings.treeUri.collectAsState(initial = "")
        // The cached library shows instantly; the scan below only updates what changed.
        val games by remember { dao.observe().map { rows -> rows.mapNotNull { it.toGame() }.filter { Cores.isAvailable(it.system) } } }
            .collectAsState(initial = emptyList())
        var stats by remember { mutableStateOf<ScanStats?>(null) }
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

        val tabs = listOf(
            TabDef(stringResource(R.string.tab_all), TabKind.ALL), TabDef(stringResource(R.string.tab_recent), TabKind.RECENT),
            TabDef(stringResource(R.string.tab_favorites), TabKind.FAVORITES),
            TabDef("NES", TabKind.SYSTEM, GameSystem.NES), TabDef("Mega Drive", TabKind.SYSTEM, GameSystem.MD), TabDef("PlayStation", TabKind.SYSTEM, GameSystem.PS1), TabDef("PS2", TabKind.SYSTEM, GameSystem.PS2),
        )

        fun rescan(uri: String?) {
            if (uri.isNullOrEmpty() || scanning) return
            scanning = true
            lifecycleScope.launch {
                stats = withContext(Dispatchers.IO) {
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
                    if (made > 0) Toast.makeText(this@MainActivity, getString(R.string.created_folders, made), Toast.LENGTH_SHORT).show()
                    settings.setTreeUri(uri.toString())
                }
            }
        }
        val hint = remember { DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", "primary:Download/nesGames") }

        LaunchedEffect(Unit) { delay(1100); splash = false }
        LaunchedEffect(tree) { rescan(tree) }
        LaunchedEffect(games.size) {   // fetch missing box art in the background, one game at a time
            for (g in games) if (withContext(Dispatchers.IO) { Covers.fetchBoxArt(applicationContext, g) }) coverVersion++
        }
        LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { launching = null; coverVersion++; rescan(tree) }   // refresh on return to foreground

        val def = tabs[tab]
        val q = query.trim()
        val shown = when (def.kind) {
            TabKind.ALL -> games
            TabKind.RECENT -> games.filter { it.lastPlayed > 0 }.sortedByDescending { it.lastPlayed }.take(24)
            TabKind.FAVORITES -> games.filter { it.favorite }
            TabKind.SYSTEM -> games.filter { it.system == def.system }
        }.filter { q.isEmpty() || it.title.contains(q, true) }
        val sel = (selByTab[tab] ?: 0).coerceIn(0, (shown.size - 1).coerceAtLeast(0))
        val focused = shown.getOrNull(sel)

        fun hasAuto(g: Game) = SaveSlots.exists(applicationContext, g.saveKey, SaveSlots.AUTO)
        fun launch(g: Game, cont: Boolean = false) { if (launching == null) { resume = cont; launching = g } }
        fun toggleFavorite(g: Game) { scope.launch(Dispatchers.IO) { dao.setFavorite(g.id, !g.favorite) } }

        LaunchedEffect(launching) {
            val g = launching ?: return@LaunchedEffect
            delay(380)   // let the zoom-in animation play
            withContext(Dispatchers.IO) { dao.setLastPlayed(g.id, System.currentTimeMillis()) }
            startActivity(Intent(this@MainActivity, GameActivity::class.java)
                .putExtra(GameActivity.EXTRA_URI, g.uri.toString())
                .putExtra(GameActivity.EXTRA_ID, g.id)
                .putExtra(GameActivity.EXTRA_SAVE_KEY, g.saveKey)
                .putExtra(GameActivity.EXTRA_RESUME, resume)
                .putExtra(GameActivity.EXTRA_ENTRY, g.zipEntry)
                .putExtra(GameActivity.EXTRA_NAME, g.fileName)
                .putExtra(GameActivity.EXTRA_SYSTEM, g.system.name)
                .apply { if (g.dirUri != null) { putExtra(GameActivity.EXTRA_DIR, g.dirUri.toString()); putExtra(GameActivity.EXTRA_DISC_FILES, g.discFiles.toTypedArray()) } })
            @Suppress("DEPRECATION") overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        }

        // --- animated, game-tinted background ---
        val hue by animateFloatAsState(hueOf(focused), tween(700), label = "hue")
        val focus = remember { FocusRequester() }

        Box(
            Modifier.fillMaxSize()
                .background(Brush.verticalGradient(listOf(Color.hsv(hue, 0.55f, 0.32f), Color.hsv((hue + 30) % 360, 0.65f, 0.08f))))
                .focusRequester(focus).focusable()
                .onPreviewKeyEvent { e ->
                    if (e.type != KeyEventType.KeyDown || searching) return@onPreviewKeyEvent false
                    when (e.key) {
                        Key.DirectionRight -> { selByTab[tab] = (sel + 1).coerceAtMost((shown.size - 1).coerceAtLeast(0)); true }
                        Key.DirectionLeft -> { selByTab[tab] = (sel - 1).coerceAtLeast(0); true }
                        Key.ButtonR1, Key.DirectionDown -> { tab = (tab + 1) % tabs.size; true }
                        Key.ButtonL1, Key.DirectionUp -> { tab = (tab + tabs.size - 1) % tabs.size; true }
                        Key.Enter, Key.ButtonA, Key.DirectionCenter -> { focused?.let { launch(it, hasAuto(it)) }; true }   // A = continue if possible
                        Key.ButtonX -> { focused?.let { launch(it) }; true }                                             // X = start new
                        Key.ButtonY -> { focused?.let(::toggleFavorite); true }                                            // Y = favourite
                        else -> false
                    }
                },
        ) {
            LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
            if (tree.isNullOrEmpty() && !splash) {
                FirstRun { picker.launch(hint) }
            } else {
                BoxWithConstraints(Modifier.fillMaxSize().displayCutoutPadding().padding(horizontal = 36.dp, vertical = 18.dp)) {
                    val tile = minOf(170.dp, maxHeight * 0.42f)   // scales down on short landscape screens
                    Column {
                        TopBar(tabs, tab, { tab = it }, scanning, onRefresh = { rescan(tree) },
                            onSearch = { searching = !searching; if (!searching) query = "" }, onFolder = { picker.launch(hint) })
                        AnimatedVisibility(searching) {
                            BasicTextField(query, { query = it }, singleLine = true, cursorBrush = SolidColor(Color.White),
                                textStyle = TextStyle(color = Color.White, fontSize = 18.sp),
                                modifier = Modifier.padding(top = 10.dp).fillMaxWidth(0.5f).clip(RoundedCornerShape(24.dp))
                                    .background(Color.White.copy(0.12f)).padding(horizontal = 18.dp, vertical = 10.dp),
                                decorationBox = { inner -> if (query.isEmpty()) Text(stringResource(R.string.search_hint), color = Color.White.copy(0.5f)); inner() })
                        }
                        Spacer(Modifier.height(18.dp))
                        // Tab change: the whole shelf cross-fades and slides a little, like switching rows on a console home
                        AnimatedContent(tab, transitionSpec = { (fadeIn(tween(220)) + slideInVertically(tween(220)) { it / 10 }) togetherWith fadeOut(tween(120)) }, label = "shelf") { t ->
                            Column {
                                if (t != tab || shown.isEmpty()) {
                                    if (t == tab) EmptyShelf(def, scanning, stats)
                                } else {
                                    GameRow(shown, sel, launching, coverVersion, tile, onSelect = { selByTab[tab] = it }, onLaunch = { launch(it, hasAuto(it)) })
                                    Spacer(Modifier.height(22.dp))
                                    focused?.let { g -> Details(g, coverVersion, onPlay = { launch(g) }, onContinue = { launch(g, cont = true) }, onFavorite = { toggleFavorite(g) }) }
                                }
                            }
                        }
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
                val pop by animateFloatAsState(1f, spring(dampingRatio = 0.55f, stiffness = 300f), label = "pop")
                Image(painterResource(R.drawable.ng_logo), null, Modifier.size(150.dp).scale(0.85f + 0.15f * pop * glow))
                Spacer(Modifier.height(14.dp))
                Text("nesGames", fontSize = 44.sp, fontWeight = FontWeight.Black, color = Color.White.copy(alpha = glow))
                Text("RETRO · HOME", fontSize = 13.sp, letterSpacing = 6.sp, color = Color.White.copy(0.5f))
            }
        }
    }

    @Composable
    private fun TopBar(tabs: List<TabDef>, tab: Int, onTab: (Int) -> Unit, scanning: Boolean, onRefresh: () -> Unit, onSearch: () -> Unit, onFolder: () -> Unit) {
        var now by remember { mutableStateOf(Date()) }
        LaunchedEffect(Unit) { while (true) { now = Date(); delay(15_000) } }
        Row(verticalAlignment = Alignment.CenterVertically) {
            tabs.forEachIndexed { i, t ->
                val selected = i == tab
                val a by animateFloatAsState(if (selected) 1f else 0.55f, label = "tab")
                Column(Modifier.padding(end = 24.dp).clickable(remember { MutableInteractionSource() }, null) { onTab(i) },
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(t.label, color = Color.White.copy(alpha = a), fontSize = if (selected) 20.sp else 17.sp,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium, maxLines = 1)
                    val w by animateDpAsState(if (selected) 26.dp else 0.dp, label = "u")
                    Box(Modifier.padding(top = 4.dp).height(3.dp).width(w).clip(CircleShape).background(Color.White))
                }
            }
            Spacer(Modifier.weight(1f))
            if (scanning) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = Color.White)
            IconButton(onClick = onSearch) { Icon(Icons.Default.Search, stringResource(R.string.search_hint), tint = Color.White) }
            IconButton(onClick = onRefresh) { Icon(Icons.Default.Refresh, null, tint = Color.White) }
            IconButton(onClick = onFolder) { Icon(Icons.Default.Settings, stringResource(R.string.select_folder), tint = Color.White) }
            Text(SimpleDateFormat("HH:mm", Locale.getDefault()).format(now), color = Color.White, fontSize = 18.sp, modifier = Modifier.padding(start = 8.dp))
        }
    }

    @Composable
    private fun GameRow(games: List<Game>, sel: Int, launching: Game?, coverVersion: Int, tile: Dp, onSelect: (Int) -> Unit, onLaunch: (Game) -> Unit) {
        val state = rememberLazyListState()
        LaunchedEffect(sel, games.size) { if (games.isNotEmpty()) state.animateScrollToItem(sel.coerceIn(0, games.size - 1)) }   // focused tile stays at the start, like PS5
        LazyRow(state = state, horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.Top,
            contentPadding = PaddingValues(end = 400.dp), modifier = Modifier.height(tile + 20.dp)) {
            itemsIndexed(games, key = { _, g -> g.id }) { i, g ->
                val isSel = i == sel
                val size by animateDpAsState(if (isSel) tile else tile * 0.66f, spring(dampingRatio = 0.7f, stiffness = 400f), label = "size")
                val zoom by animateFloatAsState(if (launching == g) 1.25f else 1f, tween(360), label = "zoom")
                val dim by animateFloatAsState(if (isSel) 1f else 0.82f, tween(200), label = "dim")
                Box(Modifier.animateItem().size(size).scale(zoom)
                    .shadow(if (isSel) 18.dp else 0.dp, RoundedCornerShape(14.dp))
                    .clip(RoundedCornerShape(14.dp))
                    .then(if (isSel) Modifier.border(3.dp, Color.White, RoundedCornerShape(14.dp)) else Modifier)
                    .clickable { if (isSel) onLaunch(g) else onSelect(i) }) {
                    Cover(g, coverVersion)
                    if (dim < 1f) Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 1f - dim)))
                    val saved = remember(g.id, coverVersion) { SaveSlots.exists(applicationContext, g.saveKey, SaveSlots.AUTO) }
                    Row(Modifier.align(Alignment.TopEnd).padding(6.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (g.favorite) Badge("★")
                        if (saved) Badge("▶")   // has a Continue point
                    }
                }
            }
        }
    }

    @Composable
    private fun Badge(s: String) = Text(s, Modifier.clip(CircleShape).background(Color.Black.copy(0.6f)).padding(horizontal = 6.dp, vertical = 1.dp),
        color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)

    @Composable
    private fun Cover(g: Game, version: Int) {
        val art by produceState<ImageBitmap?>(null, g.id, version) {
            value = withContext(Dispatchers.IO) { Covers.bestFile(applicationContext, g.id)?.let { CoverCache.get(it) } }
        }
        Crossfade(art, label = "cover") { img ->
            if (img != null) Box(Modifier.fillMaxSize().background(Color.Black)) {
                Image(img, g.title, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
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
            SystemBadge(g)
        }
    }

    @Composable
    private fun Details(g: Game, version: Int, onPlay: () -> Unit, onContinue: () -> Unit, onFavorite: () -> Unit) {
        val autoSave by produceState<Long?>(null, g.id, version) {
            value = withContext(Dispatchers.IO) { SaveSlots.state(applicationContext, g.saveKey, SaveSlots.AUTO).takeIf { it.length() > 0 }?.lastModified() }
        }
        AnimatedContent(g.id, transitionSpec = {
            (fadeIn(tween(260)) + slideInHorizontally(tween(260)) { it / 12 }) togetherWith fadeOut(tween(150))
        }, label = "details") { _ ->
            Column {
                Text(g.title, fontSize = 34.sp, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${g.system.label}  ·  ${"%.1f".format(g.size / 1048576.0)} MB", color = Color.White.copy(0.7f), fontSize = 15.sp)
                Spacer(Modifier.height(16.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val saved = autoSave
                    val white = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black)
                    if (saved != null) {
                        Button(onClick = onContinue, shape = RoundedCornerShape(28.dp), colors = white, contentPadding = PaddingValues(horizontal = 30.dp, vertical = 10.dp)) {
                            Icon(Icons.Default.PlayArrow, null); Spacer(Modifier.width(6.dp))
                            Column {
                                Text(stringResource(R.string.continue_game), fontSize = 18.sp, fontWeight = FontWeight.Bold)
                                Text(DateUtils.getRelativeTimeSpanString(saved, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString(), fontSize = 11.sp)
                            }
                        }
                        Spacer(Modifier.width(12.dp))
                        OutlinedButton(onClick = onPlay, shape = RoundedCornerShape(28.dp), colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                            contentPadding = PaddingValues(horizontal = 24.dp, vertical = 12.dp)) { Text(stringResource(R.string.start_new), fontSize = 16.sp) }
                    } else {
                        Button(onClick = onPlay, shape = RoundedCornerShape(28.dp), colors = white, contentPadding = PaddingValues(horizontal = 34.dp, vertical = 12.dp)) {
                            Icon(Icons.Default.PlayArrow, null); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.play), fontSize = 18.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                    Spacer(Modifier.width(12.dp))
                    val favLabel = stringResource(if (g.favorite) R.string.favorite_remove else R.string.favorite_add)
                    OutlinedButton(onClick = onFavorite, shape = CircleShape, colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp)) { Text(if (g.favorite) "★" else "☆", fontSize = 20.sp); Spacer(Modifier.width(6.dp)); Text(favLabel, fontSize = 13.sp) }
                    Spacer(Modifier.width(18.dp))
                    Text(stringResource(R.string.controls_fmt, controlsFor(g.system)), color = Color.White.copy(0.75f), fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }

    @Composable
    private fun EmptyShelf(def: TabDef, scanning: Boolean, s: ScanStats?) {
        Column(Modifier.fillMaxWidth().padding(top = 30.dp)) {
            Text(stringResource(if (scanning) R.string.looking else R.string.no_games), fontSize = 30.sp, fontWeight = FontWeight.Bold, color = Color.White)
            Spacer(Modifier.height(10.dp))
            if (!scanning) Text(when (def.kind) {
                TabKind.RECENT -> stringResource(R.string.no_recent)
                TabKind.FAVORITES -> stringResource(R.string.no_favorites)
                else -> stringResource(R.string.copy_hint, ConsoleFolders.folders.entries.firstOrNull { it.value == def.system }?.key ?: "NES / MegaDrive / PS1")
            }, color = Color.White.copy(0.75f), fontSize = 16.sp)
            if (s != null && s.unsupported > 0 && def.kind != TabKind.RECENT && def.kind != TabKind.FAVORITES)
                Text(stringResource(R.string.later_consoles, s.unsupported), color = Color.White.copy(0.5f), fontSize = 14.sp)
        }
    }

    @Composable
    private fun FirstRun(onPick: () -> Unit) {
        Column(Modifier.fillMaxSize().systemBarsPadding().padding(32.dp), verticalArrangement = Arrangement.Center) {
            Text(stringResource(R.string.welcome), fontSize = 34.sp, fontWeight = FontWeight.Black, color = Color.White)
            Spacer(Modifier.height(12.dp))
            Text(stringResource(R.string.welcome_body), color = Color.White.copy(0.85f), fontSize = 16.sp)
            Spacer(Modifier.height(8.dp))
            Text("NES · MegaDrive · PS1 · GameBoy · GBA · SNES · BIOS", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.welcome_note), color = Color.White.copy(0.6f), fontSize = 14.sp)
            Spacer(Modifier.height(24.dp))
            Button(onClick = onPick, shape = RoundedCornerShape(28.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black)) { Text(stringResource(R.string.select_folder), fontWeight = FontWeight.Bold) }
        }
    }
}
