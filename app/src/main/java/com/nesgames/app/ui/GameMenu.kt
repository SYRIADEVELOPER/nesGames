package com.nesgames.app.ui

import android.graphics.BitmapFactory
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nesgames.app.library.GameSystem
import java.io.File

/** One row of the in-game menu. [thumbRow] rows show a save-state picture instead of an icon. */
class MenuEntry(
    val label: String,
    val icon: ImageVector? = null,
    val sub: String? = null,
    val thumb: File? = null,
    val thumbRow: Boolean = false,
    val danger: Boolean = false,
    val action: () -> Unit,
)

/**
 * A page of the in-game menu. [blocking] pages (disc copy) cannot be dismissed; [keyCapture] pages receive
 * every gamepad key (button remapping); [progress] shows a bar.
 */
data class MenuPage(
    val title: String,
    val subtitle: String? = null,
    val items: List<MenuEntry> = emptyList(),
    val onBack: (() -> Unit)? = null,
    val blocking: Boolean = false,
    val initial: Int = 0,
    val progress: Float? = null,
    val keyCapture: ((Int) -> Unit)? = null,
    val tag: String = "",
)

class MenuState {
    var page by mutableStateOf<MenuPage?>(null)
    var sel by mutableIntStateOf(0)
    fun move(d: Int) { val n = page?.items?.size ?: 0; if (n > 0) sel = (sel + d).mod(n) }
}

/** Same colour per game as the library's background, so the pause screen feels like the same app. */
fun menuHue(system: GameSystem, title: String): Float {
    val base = when (system) { GameSystem.NES -> 355f; GameSystem.MD -> 215f; GameSystem.PS1 -> 265f; GameSystem.PS2 -> 235f; else -> 160f }
    val shift = ((title.hashCode() ushr 3) % 41) - 20
    return (base + shift + 360f) % 360f
}

/**
 * Full-screen overlay drawn above the (frozen) game: library-style gradient on the start side, white pill on
 * the selected row, gamepad hints. Touch, D-pad and A/B all work (keys are routed by GameActivity).
 */
@Composable
fun GameMenuOverlay(state: MenuState, hue: Float, selectLabel: String, backLabel: String, onPick: (Int) -> Unit) {
    val page = state.page
    var last by remember { mutableStateOf<MenuPage?>(null) }
    if (page != null) last = page
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    AnimatedVisibility(page != null, enter = fadeIn(tween(180)), exit = fadeOut(tween(160))) {
        val p = page ?: last ?: return@AnimatedVisibility
        val shade = listOf(
            Color.hsv(hue, 0.55f, 0.22f).copy(alpha = 0.97f),
            Color.hsv((hue + 30) % 360, 0.65f, 0.07f).copy(alpha = 0.93f),
            Color(0x9905070D), Color(0x2605070D),
        )
        Box(Modifier.fillMaxSize()
            .background(Brush.horizontalGradient(if (rtl) shade.reversed() else shade))
            .pointerInput(Unit) { detectTapGestures { } }) {   // taps never reach the game pad underneath
            AnimatedContent(p, contentKey = { it.title + "|" + it.tag }, label = "menuPage", transitionSpec = {
                (fadeIn(tween(200)) + slideInHorizontally(tween(220)) { w -> -w / 14 }) togetherWith fadeOut(tween(110))
            }) { pg -> MenuPageView(pg, state, rtl, selectLabel, backLabel, onPick) }
        }
    }
}

@Composable
private fun MenuPageView(p: MenuPage, state: MenuState, rtl: Boolean, selectLabel: String, backLabel: String, onPick: (Int) -> Unit) {
    Row(Modifier.fillMaxSize().padding(horizontal = 52.dp, vertical = 22.dp)) {
        Column(Modifier.fillMaxHeight().weight(0.85f).padding(end = 30.dp)) {
            Text("nesGames", color = Color.White.copy(alpha = 0.5f), fontSize = 13.sp, fontWeight = FontWeight.Black, letterSpacing = 3.sp)
            Spacer(Modifier.height(10.dp))
            Text(p.title, color = Color.White, fontSize = 32.sp, lineHeight = 36.sp, fontWeight = FontWeight.Bold,
                maxLines = 3, overflow = TextOverflow.Ellipsis)
            p.subtitle?.let { Text(it, color = Color.White.copy(alpha = 0.72f), fontSize = 15.sp, lineHeight = 21.sp, modifier = Modifier.padding(top = 8.dp)) }
            p.progress?.let { pr ->
                LinearProgressIndicator(progress = { pr }, color = Color.White, trackColor = Color.White.copy(alpha = 0.15f),
                    modifier = Modifier.padding(top = 22.dp).fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)))
            }
            Spacer(Modifier.weight(1f))
            if (!p.blocking && p.items.isNotEmpty()) Row(verticalAlignment = Alignment.CenterVertically) {
                KeyChip("A", selectLabel); Spacer(Modifier.width(20.dp)); KeyChip("B", backLabel)
            }
        }
        val list = rememberLazyListState()
        LaunchedEffect(state.sel) {
            val info = list.layoutInfo
            val it = info.visibleItemsInfo.firstOrNull { v -> v.index == state.sel }
            if (it == null || it.offset < info.viewportStartOffset || it.offset + it.size > info.viewportEndOffset)
                list.animateScrollToItem(maxOf(0, state.sel - 1))
        }
        LazyColumn(Modifier.fillMaxHeight().weight(1f), state = list, contentPadding = PaddingValues(vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically)) {
            itemsIndexed(p.items) { i, e -> MenuRow(e, i == state.sel, rtl) { onPick(i) } }
        }
        Spacer(Modifier.weight(0.55f))   // the paused game stays visible on the far side
    }
}

@Composable
private fun MenuRow(e: MenuEntry, selected: Boolean, rtl: Boolean, onClick: () -> Unit) {
    val bg by animateColorAsState(if (selected) Color.White else Color.White.copy(alpha = 0.08f), tween(140), label = "bg")
    val fg by animateColorAsState(if (selected) Color.Black else Color.White, tween(140), label = "fg")
    val scale by animateFloatAsState(if (selected) 1.04f else 1f, tween(160), label = "scale")
    val shape = RoundedCornerShape(if (e.thumbRow) 18.dp else 28.dp)
    val tint = if (e.danger && !selected) Color(0xFFFF8A80) else fg
    Row(Modifier.fillMaxWidth()
        .graphicsLayer { scaleX = scale; scaleY = scale; transformOrigin = TransformOrigin(if (rtl) 1f else 0f, 0.5f) }
        .shadow(if (selected) 16.dp else 0.dp, shape)
        .clip(shape).background(bg)
        .then(if (!selected) Modifier.border(1.dp, Color.White.copy(alpha = 0.10f), shape) else Modifier)
        .clickable(onClick = onClick)
        .heightIn(min = 54.dp)
        .padding(horizontal = 20.dp, vertical = if (e.thumbRow) 8.dp else 0.dp),
        verticalAlignment = Alignment.CenterVertically) {
        if (e.thumbRow) { Thumb(e.thumb); Spacer(Modifier.width(14.dp)) }
        else e.icon?.let { Icon(it, null, tint = tint, modifier = Modifier.size(22.dp)); Spacer(Modifier.width(14.dp)) }
        Column(Modifier.weight(1f)) {
            Text(e.label, color = tint, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            e.sub?.let { Text(it, color = fg.copy(alpha = 0.65f), fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
    }
}

@Composable
private fun Thumb(f: File?) {
    val bmp = remember(f?.path, f?.lastModified()) { f?.let { runCatching { BitmapFactory.decodeFile(it.path)?.asImageBitmap() }.getOrNull() } }
    Box(Modifier.size(96.dp, 56.dp).clip(RoundedCornerShape(10.dp)).background(Color.White.copy(alpha = 0.10f)), contentAlignment = Alignment.Center) {
        if (bmp != null) Image(bmp, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else Icon(Icons.Filled.Add, null, tint = Color.White.copy(alpha = 0.35f))
    }
}

@Composable
private fun KeyChip(key: String, label: String) {
    Box(Modifier.size(24.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
        Text(key, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
    }
    Spacer(Modifier.width(7.dp))
    Text(label, color = Color.White.copy(alpha = 0.7f), fontSize = 13.sp)
}
