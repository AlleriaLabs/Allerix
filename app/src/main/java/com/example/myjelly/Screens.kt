@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.example.myjelly

import android.content.Intent
import android.widget.Toast
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// ------------------------------------------------------------------ home
@Composable
fun HomeScreen(
    onOpen: (Item) -> Unit, onPlay: (Item) -> Unit, onSeeAll: (Item) -> Unit,
    onSearch: () -> Unit, onLogout: () -> Unit
) {
    var featured by remember { mutableStateOf<List<Item>>(emptyList()) }
    var resume by remember { mutableStateOf<List<Item>>(emptyList()) }
    var trending by remember { mutableStateOf<List<Item>>(emptyList()) }
    var rows by remember { mutableStateOf<List<Pair<Item, List<Item>>>>(emptyList()) }
    var error by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        try {
            featured = runCatching { Api.featured() }.getOrDefault(emptyList())
            resume = Api.resume()
            trending = runCatching { Api.trending() }.getOrDefault(emptyList())
            rows = Api.views().map { it to Api.latest(it.id) }.filter { it.second.isNotEmpty() }
        } catch (e: Exception) {
            if (e.message?.contains("401") == true) onLogout() else error = "Can't reach the server. Check your connection."
        }
        loading = false
    }
    val heroes = featured.ifEmpty { trending.take(6) }
    val pager = rememberPagerState(pageCount = { heroes.size })

    LaunchedEffect(pager.currentPage, heroes.size) {
        if (heroes.size > 1) {
            delay(5000)
            pager.animateScrollToPage((pager.currentPage + 1) % heroes.size)
        }
    }
    val bgItem = heroes.getOrNull(pager.currentPage)

    Box(Modifier.fillMaxSize().background(BgDark)) {
        Crossfade(targetState = bgItem, animationSpec = tween(900), label = "bg") { scene ->
            if (scene != null) AsyncImage(
                Api.blurBg(scene), null, Modifier.fillMaxSize().blur(20.dp), contentScale = ContentScale.Crop
            )
        }
        Box(Modifier.fillMaxSize().background(Color(0xE60A0A0C)))

        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
            item { HeroCarousel(heroes, pager, onPlay, onOpen) }
            if (trending.isNotEmpty()) item { MediaRow("Trending Now  ›", trending, onClick = onOpen) }
            if (resume.isNotEmpty()) item { ContinueSection(resume, onPlay) }
            items(rows, key = { it.first.id }) { (lib, list) ->
                MediaRow("Latest in ${lib.name}", list, onClick = onOpen, onSeeAll = { onSeeAll(lib) })
            }
        }
        if (loading) CircularProgressIndicator(Modifier.align(Alignment.Center), color = Red)
        if (error.isNotEmpty()) Text(error, Modifier.align(Alignment.Center).padding(24.dp), color = Grey)

        Row(
            Modifier.fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color(0xCC000000), Color.Transparent)))
                .padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Image(painterResource(R.drawable.logo_banner), "Alleria Filmotheque", Modifier.height(30.dp).aspectRatio(4.198f))
            Spacer(Modifier.weight(1f))
            IconButton(onClick = onSearch) { SmallIcon(R.drawable.ic_search) }
        }
    }
}

@Composable
fun HeroCarousel(heroes: List<Item>, pager: PagerState, onPlay: (Item) -> Unit, onOpen: (Item) -> Unit) {
    Box(Modifier.fillMaxWidth().height(500.dp)) {
        if (heroes.isNotEmpty()) HorizontalPager(state = pager, modifier = Modifier.fillMaxSize()) { page ->
            HeroPage(heroes[page], onPlay, onOpen)
        }
        if (heroes.size > 1) Row(Modifier.align(Alignment.BottomCenter).padding(bottom = 6.dp)) {
            repeat(heroes.size) { i ->
                val on = i == pager.currentPage
                Box(
                    Modifier.padding(3.dp).size(if (on) 7.dp else 5.dp).clip(CircleShape)
                        .background(if (on) Color.White else Color(0x66FFFFFF))
                )
            }
        }
    }
}

@Composable
fun HeroPage(hero: Item, onPlay: (Item) -> Unit, onOpen: (Item) -> Unit) {
    Box(Modifier.fillMaxSize().background(Color(0xFF111111))) {
        AsyncImage(Api.heroImage(hero), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(0f to Color(0x66000000), 0.35f to Color.Transparent, 0.75f to Color(0xDD000000), 1f to Color(0xE60A0A0C))
            )
        )
        Column(Modifier.align(Alignment.BottomStart).padding(start = 20.dp, end = 20.dp, bottom = 26.dp)) {
            Text(
                "FEATURED", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp,
                modifier = Modifier.background(Color(0x44FFFFFF), RoundedCornerShape(4.dp)).padding(horizontal = 6.dp, vertical = 2.dp)
            )
            Spacer(Modifier.height(8.dp))
            Text(
                (hero.seriesName ?: hero.name).uppercase(), color = Color.White, fontSize = 30.sp,
                fontWeight = FontWeight.Bold, letterSpacing = 4.sp, maxLines = 2, overflow = TextOverflow.Ellipsis
            )
            val genres = hero.genres?.take(3)?.joinToString("   •   ").orEmpty()
            if (genres.isNotEmpty()) Text(genres, color = Grey, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
            Text(
                hero.overview.orEmpty(), color = Color(0xFFD2D2D2), fontSize = 13.sp, maxLines = 2,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp)
            )
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = { onPlay(hero) }, modifier = Modifier.height(46.dp), shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black)
                ) {
                    SmallIcon(R.drawable.ic_play, Color.Black, 20)
                    Spacer(Modifier.width(6.dp))
                    Text("Play", fontWeight = FontWeight.Bold)
                }
                MyListButton(hero)
            }
        }
    }
}

@Composable
fun MediaRow(
    title: String, list: List<Item>, landscape: Boolean = false,
    onClick: (Item) -> Unit, onSeeAll: (() -> Unit)? = null
) {
    Column(Modifier.padding(top = 20.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(title, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            if (onSeeAll != null) Text("See all ›", color = Grey, fontSize = 13.sp, modifier = Modifier.clickable { onSeeAll() })
        }
        Spacer(Modifier.height(10.dp))
        LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            items(list, key = { it.id }) {
                MediaCard(it, Modifier.width(if (landscape) 220.dp else 118.dp), landscape) { onClick(it) }
            }
        }
    }
}

@Composable
fun MediaCard(item: Item, modifier: Modifier, landscape: Boolean = false, onClick: () -> Unit) {
    Column(modifier.clickable(onClick = onClick)) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(if (landscape) 16f / 9f else 2f / 3f)
                .clip(RoundedCornerShape(10.dp)).background(Color(0xFF1F1F23))
        ) {
            AsyncImage(
                if (landscape) Api.landscape(item) else Api.poster(item), item.name,
                Modifier.fillMaxSize(), contentScale = ContentScale.Crop
            )
            val rt = item.runTimeTicks ?: 0L
            val pos = item.userData?.positionTicks ?: 0L
            if (rt > 0 && pos > 0) LinearProgressIndicator(
                progress = { (pos.toFloat() / rt).coerceIn(0f, 1f) },
                modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(3.dp),
                color = Red, trackColor = Color(0x66FFFFFF)
            )
        }
        val caption = if (item.type == "Episode" && item.seriesName != null)
            "${item.seriesName} · S${item.parentIndex ?: 0}:E${item.index ?: 0}" else item.name
        Text(
            caption, color = Grey, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 4.dp)
        )
    }
}

@Composable
fun ContinueSection(list: List<Item>, onClick: (Item) -> Unit) {
    Column(Modifier.padding(top = 22.dp)) {
        Text(
            "Continue Watching  ›", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp)
        )
        list.take(4).forEach { ContinueRow(it) { onClick(it) } }
    }
}

@Composable
fun ContinueRow(item: Item, onClick: () -> Unit) {
    val rt = item.runTimeTicks ?: 0L
    val pos = item.userData?.positionTicks ?: 0L
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 5.dp)
            .glass(14.dp).clickable(onClick = onClick).padding(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.width(112.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(8.dp)).background(Color(0xFF1F1F23))) {
            AsyncImage(Api.landscape(item), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                item.seriesName ?: item.name, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold,
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
            Text(leftText(item), color = Grey, fontSize = 12.sp)
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = { if (rt > 0) (pos.toFloat() / rt).coerceIn(0f, 1f) else 0f },
                modifier = Modifier.fillMaxWidth().height(3.dp), color = Red, trackColor = Color(0x33FFFFFF)
            )
        }
        Spacer(Modifier.width(10.dp))
        Box(Modifier.size(38.dp).clip(CircleShape).background(Color(0x33FFFFFF)), contentAlignment = Alignment.Center) {
            SmallIcon(R.drawable.ic_play, Color.White, 20)
        }
    }
}

// ------------------------------------------------------------------ movies / tv shows tabs
@Composable
fun LibraryTab(title: String, type: String, onOpen: (Item) -> Unit, onSearch: () -> Unit) {
    var list by remember { mutableStateOf<List<Item>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    LaunchedEffect(type) {
        list = runCatching { Api.byType(type) }.getOrDefault(emptyList())
        loading = false
    }
    AppBackground {
        Column(Modifier.fillMaxSize()) {
            TabHeader(title, onSearch)
            Box(Modifier.fillMaxSize()) {
                if (loading) CircularProgressIndicator(Modifier.align(Alignment.Center), color = Red)
                LazyVerticalGrid(
                    GridCells.Adaptive(110.dp), contentPadding = PaddingValues(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(list, key = { it.id }) { MediaCard(it, Modifier.fillMaxWidth()) { onOpen(it) } }
                }
            }
        }
    }
}

/** Folder-style browsing (collections, box sets, library folders). */
@Composable
fun GridScreen(parent: Item, onOpen: (Item) -> Unit, back: () -> Unit) {
    var list by remember { mutableStateOf<List<Item>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    LaunchedEffect(parent.id) {
        list = runCatching { Api.children(parent.id) }.getOrDefault(emptyList()); loading = false
    }
    AppBackground {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = back) { SmallIcon(R.drawable.ic_back) }
                Text(parent.name, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Box(Modifier.fillMaxSize()) {
                if (loading) CircularProgressIndicator(Modifier.align(Alignment.Center), color = Red)
                LazyVerticalGrid(
                    GridCells.Adaptive(110.dp), contentPadding = PaddingValues(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(list, key = { it.id }) { MediaCard(it, Modifier.fillMaxWidth()) { onOpen(it) } }
                }
            }
        }
    }
}

// ------------------------------------------------------------------ search
@Composable
fun SearchScreen(onOpen: (Item) -> Unit, back: () -> Unit) {
    var q by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<Item>>(emptyList()) }
    var searched by remember { mutableStateOf(false) }
    LaunchedEffect(q) {
        if (q.trim().length < 2) {
            results = emptyList(); searched = false
        } else {
            delay(350)
            results = runCatching { Api.search(q.trim()) }.getOrDefault(emptyList())
            searched = true
        }
    }
    AppBackground {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = back) { SmallIcon(R.drawable.ic_back) }
                OutlinedTextField(
                    q, { q = it }, singleLine = true, placeholder = { Text("Search movies, shows…", color = Dim) },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Red, unfocusedBorderColor = Color(0xFF444444), cursorColor = Red,
                        focusedTextColor = Color.White, unfocusedTextColor = Color.White
                    ),
                    modifier = Modifier.fillMaxWidth().padding(end = 8.dp)
                )
            }
            if (searched && results.isEmpty()) {
                Text("No results", color = Grey, modifier = Modifier.padding(24.dp))
            }
            LazyVerticalGrid(
                GridCells.Adaptive(110.dp), contentPadding = PaddingValues(12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(results, key = { it.id }) { MediaCard(it, Modifier.fillMaxWidth()) { onOpen(it) } }
            }
        }
    }
}

// ------------------------------------------------------------------ my list + profile
@Composable
fun MyListTab(
    onOpen: (Item) -> Unit, onProfile: () -> Unit, onSettings: () -> Unit, onSignOut: () -> Unit
) {
    val ctx = LocalContext.current
    var favs by remember { mutableStateOf<List<Item>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var help by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        favs = runCatching { Api.favorites() }.getOrDefault(emptyList()); loading = false
    }
    AppBackground {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            Row(
                Modifier.fillMaxWidth().padding(start = 20.dp, end = 16.dp, top = 14.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("My List", color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Box(Modifier.size(36.dp).clip(CircleShape).background(Red), contentAlignment = Alignment.Center) {
                    Text(Api.username.take(1).uppercase().ifEmpty { "A" }, color = Color.White, fontWeight = FontWeight.Bold)
                }
            }
            if (loading) CircularProgressIndicator(Modifier.padding(24.dp), color = Red)
            else if (favs.isEmpty()) Text(
                "Your list is empty. Tap “+ My List” on any title to save it here.",
                color = Grey, fontSize = 14.sp, modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)
            ) else LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                items(favs, key = { it.id }) { MediaCard(it, Modifier.width(112.dp)) { onOpen(it) } }
            }
            Spacer(Modifier.height(20.dp))
            Column(Modifier.padding(horizontal = 16.dp).fillMaxWidth().glass(20.dp).padding(vertical = 8.dp)) {
                Box(Modifier.fillMaxWidth().padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
                    Image(painterResource(R.drawable.logo_banner), null, Modifier.height(34.dp).aspectRatio(4.198f))
                }
                ProfileRow("👤", "My Profile", Api.username, onProfile)
                ProfileRow("⚙", "Settings", "", onSettings)
                ProfileRow("?", "Help & Support", "", { help = true })
                ProfileRow("⎋", "Sign Out", "", onSignOut)
            }
            Spacer(Modifier.height(24.dp))
        }
    }
    if (help) AlertDialog(
        onDismissRequest = { help = false }, containerColor = Color(0xFF181818),
        title = { Text("Help & Support", color = Color.White) },
        text = {
            Text(
                "Forgot your password or need to update your email, Telegram or Discord? Use the account portal. " +
                    "To ask for a movie or show, go to Settings, Request a show.",
                color = Grey, fontSize = 14.sp
            )
        },
        confirmButton = {
            TextButton(onClick = {
                ctx.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(Api.ACCOUNT_URL)))
            }) { Text("Open account portal", color = Red) }
        },
        dismissButton = { TextButton(onClick = { help = false }) { Text("Close", color = Grey) } }
    )
}

@Composable
private fun ProfileRow(icon: String, title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(icon, fontSize = 20.sp, color = Color.White, modifier = Modifier.width(36.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = Color.White, fontSize = 16.sp)
            if (subtitle.isNotEmpty()) Text(subtitle, color = Dim, fontSize = 12.sp)
        }
        Text("›", color = Grey, fontSize = 22.sp)
    }
}

// ------------------------------------------------------------------ downloads
@Composable
fun DownloadsTab(onPlay: (Item) -> Unit) {
    val ctx = LocalContext.current
    var recs by remember { mutableStateOf(Downloads.list(ctx)) }
    var states by remember { mutableStateOf<Map<String, DlState>>(emptyMap()) }
    var resume by remember { mutableStateOf<List<Item>>(emptyList()) }
    LaunchedEffect(Unit) { resume = runCatching { Api.resume() }.getOrDefault(emptyList()) }
    LaunchedEffect(recs) {
        while (true) {
            states = recs.associate { it.id to Downloads.state(ctx, it) }
            delay(1000)
        }
    }
    AppBackground {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
            item { TabHeader("Downloads", null) }
            if (resume.isNotEmpty()) item { ContinueSection(resume, onPlay) }
            item {
                Text(
                    "Downloads", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(start = 16.dp, top = 22.dp, bottom = 6.dp)
                )
                if (recs.isEmpty()) Text(
                    "Nothing downloaded yet. Open a movie or episode and tap the download icon.",
                    color = Grey, fontSize = 14.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }
            items(recs, key = { it.id }) { rec ->
                val st = states[rec.id] ?: DlState(-1, 0L, 0L)
                DownloadRow(
                    rec, st,
                    onPlay = {
                        ctx.startActivity(
                            Intent(ctx, PlayerActivity::class.java).putExtra("id", rec.id)
                                .putExtra("file", rec.file).putExtra("title", rec.title)
                        )
                    },
                    onDelete = { Downloads.remove(ctx, rec); recs = Downloads.list(ctx) }
                )
            }
        }
    }
}

@Composable
private fun DownloadRow(rec: DownloadRec, st: DlState, onPlay: () -> Unit, onDelete: () -> Unit) {
    val complete = st.status == android.app.DownloadManager.STATUS_SUCCESSFUL
    val failed = st.status == android.app.DownloadManager.STATUS_FAILED
    val pct = if (st.total > 0) ((st.done * 100) / st.total).toInt() else 0
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 5.dp).glass(14.dp)
            .clickable(enabled = complete, onClick = onPlay).padding(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(44.dp).clip(CircleShape).background(Color(0x33FFFFFF)), contentAlignment = Alignment.Center) {
            if (complete) SmallIcon(R.drawable.ic_play, Color.White, 22)
            else SmallIcon(R.drawable.ic_download, Color.White, 22)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(rec.title, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val sub = when {
                complete -> listOf(fmtSize(st.done), fmtRuntime(rec.runtimeTicks)).filter { it.isNotEmpty() }.joinToString("  ·  ")
                failed -> "Failed. Delete and try again"
                st.status == -1 -> "Unavailable"
                else -> "Downloading… $pct%"
            }
            Text(sub, color = Grey, fontSize = 12.sp)
            if (!complete && !failed && st.status != -1) {
                Spacer(Modifier.height(6.dp))
                LinearProgressIndicator(
                    progress = { pct / 100f }, modifier = Modifier.fillMaxWidth().height(3.dp),
                    color = Red, trackColor = Color(0x33FFFFFF)
                )
            }
        }
        IconButton(onClick = onDelete) { SmallIcon(R.drawable.ic_close, Grey, 20) }
    }
}

// ------------------------------------------------------------------ detail
@Composable
fun DetailScreen(base: Item, onOpen: (Item) -> Unit, onPlay: (Item) -> Unit, back: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var cur by remember { mutableStateOf(base) }
    var seasons by remember { mutableStateOf<List<Item>>(emptyList()) }
    var episodes by remember { mutableStateOf<List<Item>>(emptyList()) }
    var seasonIdx by remember { mutableStateOf(0) }
    var similar by remember { mutableStateOf<List<Item>>(emptyList()) }
    var tab by remember { mutableStateOf(0) }
    val isSeries = base.type == "Series"
    val isSeason = base.type == "Season"
    val tabs = if (isSeries || isSeason) listOf("Episodes", "Details", "More Like This") else listOf("Details", "More Like This")

    LaunchedEffect(base.id) {
        runCatching { cur = Api.item(base.id) }
        if (isSeries) seasons = runCatching { Api.children(base.id) }.getOrDefault(emptyList()).filter { it.type == "Season" }
        else if (isSeason) episodes = runCatching { Api.children(base.id) }.getOrDefault(emptyList())
        similar = runCatching { Api.similar(base.id) }.getOrDefault(emptyList())
    }
    LaunchedEffect(seasons, seasonIdx) {
        if (isSeries && seasons.isNotEmpty()) {
            episodes = runCatching { Api.children(seasons[seasonIdx.coerceIn(0, seasons.size - 1)].id) }
                .getOrDefault(emptyList())
        }
    }

    val video = cur.mediaSources?.firstOrNull()?.streams?.firstOrNull { it.type == "Video" }
    val h = video?.height ?: 0
    val resBadge = when { h >= 2000 -> "4K"; h >= 1000 -> "1080p"; h >= 700 -> "720p"; h > 0 -> "SD"; else -> "" }
    val hdr = video?.videoRange?.contains("HDR", ignoreCase = true) == true
    val meta = listOfNotNull(cur.year?.toString(), fmtRuntime(cur.runTimeTicks).ifEmpty { null }, cur.officialRating)
        .joinToString("   •   ")
    val canDownload = !isSeries && !isSeason

    AppBackground {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
            item {
                Box(Modifier.fillMaxWidth().height(280.dp).background(Color(0xFF111111))) {
                    AsyncImage(Api.backdrop(cur), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    Box(
                        Modifier.fillMaxSize().background(
                            Brush.verticalGradient(0f to Color(0x88000000), 0.4f to Color.Transparent, 1f to BgDark)
                        )
                    )
                    IconButton(onClick = back, modifier = Modifier.align(Alignment.TopStart).padding(6.dp)) {
                        SmallIcon(R.drawable.ic_back)
                    }
                }
            }
            item {
                Column(Modifier.padding(horizontal = 16.dp)) {
                    Text(cur.seriesName ?: cur.name, color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold)
                    if (cur.seriesName != null) Text(cur.name, color = Grey, fontSize = 15.sp)
                    Spacer(Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (meta.isNotEmpty()) Text(meta, color = Grey, fontSize = 13.sp)
                        if (resBadge.isNotEmpty()) Badge(resBadge)
                        if (hdr) Badge("HDR")
                    }
                    Spacer(Modifier.height(10.dp))
                    Text(cur.overview.orEmpty(), color = Color(0xFFD2D2D2), fontSize = 14.sp, lineHeight = 20.sp)
                    Spacer(Modifier.height(16.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Button(
                            onClick = { onPlay(cur) }, modifier = Modifier.weight(1f).height(46.dp),
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Red, contentColor = Color.White)
                        ) {
                            SmallIcon(R.drawable.ic_play, Color.White, 20)
                            Spacer(Modifier.width(6.dp))
                            Text("Play", fontWeight = FontWeight.Bold)
                        }
                        MyListButton(cur, Modifier.weight(1f))
                        if (canDownload) IconButton(onClick = {
                            scope.launch { Toast.makeText(ctx, Downloads.start(ctx, cur), Toast.LENGTH_SHORT).show() }
                        }) { SmallIcon(R.drawable.ic_download) }
                    }
                }
            }
            item {
                Row(Modifier.fillMaxWidth().padding(top = 22.dp, start = 8.dp)) {
                    tabs.forEachIndexed { i, label ->
                        val on = i == tab
                        Column(
                            Modifier.clickable { tab = i }.padding(horizontal = 12.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(label, color = if (on) Color.White else Dim, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.height(6.dp))
                            Box(Modifier.height(2.dp).width(28.dp).background(if (on) Red else Color.Transparent))
                        }
                    }
                }
            }
            when (tabs[tab.coerceIn(0, tabs.size - 1)]) {
                "Episodes" -> {
                    if (isSeries && seasons.size > 1) item {
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(seasons.size) { i ->
                                val on = i == seasonIdx
                                Text(
                                    seasons[i].name, color = Color.White, fontSize = 13.sp,
                                    modifier = Modifier.clip(RoundedCornerShape(20.dp))
                                        .background(if (on) Red else Color(0xFF2A2A2E))
                                        .clickable { seasonIdx = i }
                                        .padding(horizontal = 14.dp, vertical = 6.dp)
                                )
                            }
                        }
                    }
                    items(episodes, key = { it.id }) { ep ->
                        EpisodeRow(
                            ep, onPlay = { onPlay(ep) },
                            onDownload = { scope.launch { Toast.makeText(ctx, Downloads.start(ctx, ep), Toast.LENGTH_SHORT).show() } }
                        )
                    }
                }
                "Details" -> item {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
                        val genres = cur.genres.orEmpty().joinToString(", ")
                        val studios = cur.studios.orEmpty().joinToString(", ") { it.name }
                        val cast = cur.people.orEmpty().filter { it.type == "Actor" }.take(10).joinToString(", ") { it.name }
                        cur.rating?.let { DetailLine("Rating", "★ %.1f".format(it)) }
                        if (genres.isNotEmpty()) DetailLine("Genres", genres)
                        if (studios.isNotEmpty()) DetailLine("Studio", studios)
                        if (cast.isNotEmpty()) DetailLine("Cast", cast)
                        if (genres.isEmpty() && studios.isEmpty() && cast.isEmpty() && cur.rating == null) {
                            Text("No extra details available.", color = Grey, fontSize = 14.sp)
                        }
                    }
                }
                else -> {
                    if (similar.isEmpty()) item {
                        Text("Nothing similar found.", color = Grey, fontSize = 14.sp, modifier = Modifier.padding(16.dp))
                    }
                    similar.chunked(3).forEach { chunk ->
                        item {
                            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                chunk.forEach { MediaCard(it, Modifier.weight(1f)) { onOpen(it) } }
                                repeat(3 - chunk.size) { Spacer(Modifier.weight(1f)) }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DetailLine(label: String, value: String) {
    Row(Modifier.padding(vertical = 6.dp)) {
        Text(label, color = Dim, fontSize = 13.sp, modifier = Modifier.width(72.dp))
        Text(value, color = Color(0xFFE0E0E0), fontSize = 14.sp, modifier = Modifier.weight(1f))
    }
}

@Composable
fun EpisodeRow(ep: Item, onPlay: () -> Unit, onDownload: () -> Unit) {
    val rt = ep.runTimeTicks ?: 0L
    val pos = ep.userData?.positionTicks ?: 0L
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onPlay).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.width(130.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(8.dp)).background(Color(0xFF1F1F23))) {
            AsyncImage(Api.landscape(ep), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            if (rt > 0 && pos > 0) LinearProgressIndicator(
                progress = { (pos.toFloat() / rt).coerceIn(0f, 1f) },
                modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(3.dp),
                color = Red, trackColor = Color(0x66FFFFFF)
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                "${ep.index ?: ""}. ${ep.name}", color = Color.White, fontWeight = FontWeight.Bold,
                fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis
            )
            Text(fmtRuntime(ep.runTimeTicks), color = Grey, fontSize = 12.sp)
        }
        IconButton(onClick = onDownload) { SmallIcon(R.drawable.ic_download) }
    }
}
