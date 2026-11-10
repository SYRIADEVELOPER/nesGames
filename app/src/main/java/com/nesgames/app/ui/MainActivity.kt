@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.nesgames.app.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.lifecycleScope
import com.nesgames.app.library.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val settings = Settings(applicationContext)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Color(0xFFE5484D), secondary = Color(0xFF3E63DD))) {
                LibraryScreen(settings)
            }
        }
    }

    @Composable
    private fun LibraryScreen(settings: Settings) {
        val scope = rememberCoroutineScope()
        val tree by settings.treeUri.collectAsState(initial = "")
        var result by remember { mutableStateOf<ScanResult?>(null) }
        var scanning by remember { mutableStateOf(false) }
        var query by remember { mutableStateOf("") }
        var filter by remember { mutableStateOf<GameSystem?>(null) }

        fun rescan(uri: String?) {
            if (uri.isNullOrEmpty() || scanning) return
            scanning = true
            lifecycleScope.launch {
                result = withContext(Dispatchers.IO) { runCatching { LibraryScanner(applicationContext).scan(Uri.parse(uri)) }.getOrNull() }
                scanning = false
            }
        }

        val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri != null) {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                scope.launch { settings.setTreeUri(uri.toString()) }
            }
        }
        val initialHint = remember {
            DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", "primary:Download/nesGames")
        }

        LaunchedEffect(tree) { rescan(tree) }
        LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { rescan(tree) }   // auto-rescan on return to foreground

        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("nesGames", fontWeight = FontWeight.Black, fontSize = 24.sp, color = MaterialTheme.colorScheme.primary) },
                    actions = {
                        IconButton(onClick = { rescan(tree) }, enabled = !tree.isNullOrEmpty()) { Icon(Icons.Default.Refresh, "Refresh library") }
                        IconButton(onClick = { picker.launch(initialHint) }) { Icon(Icons.Default.Settings, "Choose ROM folder") }
                    },
                )
            },
        ) { pad ->
            Column(Modifier.padding(pad).fillMaxSize()) {
                if (tree.isNullOrEmpty()) {
                    FolderExplainer { picker.launch(initialHint) }
                    return@Column
                }
                OutlinedTextField(
                    value = query, onValueChange = { query = it },
                    leadingIcon = { Icon(Icons.Default.Search, null) },
                    placeholder = { Text("Search games") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                )
                val all = result?.games.orEmpty()
                val systems = all.map { it.system }.distinct()
                Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = filter == null, onClick = { filter = null }, label = { Text("All") })
                    systems.forEach { s -> FilterChip(selected = filter == s, onClick = { filter = s }, label = { Text(s.short) }) }
                }
                if (scanning) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
                val shown = all.filter { (filter == null || it.system == filter) && it.title.contains(query.trim(), true) }
                if (shown.isEmpty() && !scanning) {
                    EmptyState(result)
                } else {
                    LazyVerticalGrid(GridCells.Adaptive(150.dp), contentPadding = PaddingValues(16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(shown, key = { it.id }) { g -> GameCard(g) { launchGame(g) } }
                    }
                }
            }
        }
    }

    private fun launchGame(g: Game) {
        startActivity(Intent(this, GameActivity::class.java)
            .putExtra(GameActivity.EXTRA_URI, g.uri.toString())
            .putExtra(GameActivity.EXTRA_ENTRY, g.zipEntry)
            .putExtra(GameActivity.EXTRA_NAME, g.fileName)
            .putExtra(GameActivity.EXTRA_SYSTEM, g.system.name))
    }
}

@Composable
private fun FolderExplainer(onPick: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center) {
        Text("Choose your games folder", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(12.dp))
        Text("Put your own ROM files in Download/nesGames on your phone. On the next screen, open Download, select the " +
            "nesGames folder and tap \"Use this folder\". nesGames only gets read access to that one folder.")
        Spacer(Modifier.height(8.dp))
        Text("Android does not allow apps to pick the Download folder itself, so choose the nesGames folder inside it.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(24.dp))
        Button(onClick = onPick) { Text("Select nesGames folder") }
    }
}

@Composable
private fun EmptyState(r: ScanResult?) {
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text("No playable games found", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Text("Copy .nes ROMs into Download/nesGames, then tap refresh.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (r != null && r.detectedUnsupported > 0)
            Text("${r.detectedUnsupported} file(s) are for systems coming in a later update.", color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun GameCard(g: Game, onClick: () -> Unit) {
    val hue = (g.title.hashCode() and 0x7fffffff) % 360
    val c1 = Color.hsv(hue.toFloat(), 0.55f, 0.55f)
    val c2 = Color.hsv(((hue + 40) % 360).toFloat(), 0.65f, 0.30f)
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick), shape = RoundedCornerShape(14.dp)) {
        Box(Modifier.fillMaxWidth().aspectRatio(1f).background(Brush.linearGradient(listOf(c1, c2))), contentAlignment = Alignment.Center) {
            Text(g.title.split(' ').filter { it.isNotEmpty() }.take(2).joinToString("") { it.take(1).uppercase() },
                fontSize = 44.sp, fontWeight = FontWeight.Black, color = Color.White.copy(alpha = 0.9f))
            Text(g.system.short, Modifier.align(Alignment.TopEnd).padding(8.dp).clip(RoundedCornerShape(6.dp))
                .background(Color.Black.copy(alpha = 0.5f)).padding(horizontal = 6.dp, vertical = 2.dp),
                color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        }
        Text(g.title, Modifier.padding(10.dp), maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
    }
}
