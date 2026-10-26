package com.example.myjelly

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

val Red = Color(0xFFE50914)
val BgDark = Color(0xFF0A0A0C)
val Grey = Color(0xFFB3B3B3)
val Dim = Color(0xFF8A8A90)
val GlassColor = Color(0xCC16161B)
val GlassBorder = Color(0x26FFFFFF)

fun Modifier.glass(radius: Dp = 16.dp): Modifier {
    val shape = RoundedCornerShape(radius)
    return this.clip(shape).background(GlassColor).border(1.dp, GlassBorder, shape)
}

@Composable
fun AppBackground(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    Box(
        modifier.fillMaxSize().background(
            Brush.verticalGradient(0f to Color(0xFF1C060A), 0.4f to BgDark, 1f to BgDark)
        ),
        content = content
    )
}

@Composable
fun SmallIcon(res: Int, tint: Color = Color.White, size: Int = 24, modifier: Modifier = Modifier) {
    Icon(painterResource(res), null, tint = tint, modifier = modifier.size(size.dp))
}

@Composable
fun Badge(text: String) {
    Text(
        text, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold,
        modifier = Modifier.border(1.dp, Color(0x99FFFFFF), RoundedCornerShape(4.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp)
    )
}

@Composable
fun TabHeader(title: String, onSearch: (() -> Unit)?) {
    Row(
        Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 14.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(title, color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        if (onSearch != null) IconButton(onClick = onSearch) { SmallIcon(R.drawable.ic_search) }
    }
}

/** "+ My List" toggle backed by Jellyfin favorites. */
@Composable
fun MyListButton(item: Item, modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    var fav by remember(item.id) { mutableStateOf(item.userData?.isFavorite == true) }
    Button(
        onClick = {
            val now = !fav
            fav = now
            scope.launch { runCatching { Api.setFavorite(item.id, now) } }
        },
        modifier = modifier.height(46.dp),
        shape = RoundedCornerShape(10.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Color(0x33FFFFFF), contentColor = Color.White),
        border = BorderStroke(1.dp, Color(0x55FFFFFF))
    ) {
        SmallIcon(if (fav) R.drawable.ic_check else R.drawable.ic_add, size = 18)
        Spacer(Modifier.width(6.dp))
        Text("My List", fontWeight = FontWeight.Bold)
    }
}

fun fmtRuntime(ticks: Long?): String {
    val m = ((ticks ?: 0L) / 600_000_000L).toInt()
    return if (m <= 0) "" else if (m >= 60) "${m / 60}h ${m % 60}m" else "${m}m"
}

fun leftText(item: Item): String {
    val rt = item.runTimeTicks ?: return ""
    val s = fmtRuntime(rt - (item.userData?.positionTicks ?: 0L))
    return if (s.isEmpty()) "" else "$s left"
}

fun fmtSize(bytes: Long): String =
    if (bytes >= 1_000_000_000L) "%.1f GB".format(bytes / 1e9) else "%.0f MB".format(bytes / 1e6)
