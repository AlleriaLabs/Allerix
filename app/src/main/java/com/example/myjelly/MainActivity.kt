@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.example.myjelly

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.blur
import kotlinx.coroutines.delay
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import kotlinx.coroutines.launch

private val Red = Color(0xFFE50914)
private val Grey = Color(0xFFB3B3B3)
private val detailTypes = setOf("Series", "Season", "Movie", "Episode", "Video", "MusicVideo")

sealed class Nav {
    data class Grid(val item: Item) : Nav()
    data class Detail(val item: Item) : Nav()
    object Settings : Nav()
    object RequestShow : Nav()
    object ClientSettings : Nav()
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Api.load(this)
        setContent {
            MaterialTheme(
                colorScheme = androidx.compose.material3.darkColorScheme(
                    primary = Red, background = Color.Black, surface = Color.Black,
                    onBackground = Color.White, onSurface = Color.White
                )
            ) { Surface(color = Color.Black) { App() } }
        }
    }
}

@Composable
fun App() {
    val ctx = LocalContext.current
    var loggedIn by remember { mutableStateOf(Api.token.isNotEmpty()) }
    var loginSettings by remember { mutableStateOf(false) }
    if (loggedIn) {
        MainContent(onLogout = { Api.logout(ctx); loggedIn = false })
    } else if (loginSettings) {
        BackHandler { loginSettings = false }
        ClientSettingsScreen(back = { loginSettings = false }, onServerChanged = { loginSettings = false }, loggedIn = false)
    } else {
        LoginScreen(onDone = { loggedIn = true }, onSettings = { loginSettings = true })
    }
}

@Composable
fun MainContent(onLogout: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val stack = remember { mutableStateListOf<Nav>() }

    val play: (Item) -> Unit = { it ->
        scope.launch {
            val target = if (it.type == "Series" || it.type == "Season")
                runCatching { Api.nextEpisode(it.id, it.type) }.getOrNull() else it
            if (target != null) ctx.startActivity(
                Intent(ctx, PlayerActivity::class.java).putExtra("id", target.id).putExtra("title", target.name)
            )
        }
    }
    val open: (Item) -> Unit = { it ->
        stack.add(if (it.type in detailTypes || !it.isFolder) Nav.Detail(it) else Nav.Grid(it))
    }
    val back: () -> Unit = { if (stack.isNotEmpty()) stack.removeAt(stack.lastIndex) }
    BackHandler(enabled = stack.isNotEmpty()) { back() }

    when (val top = stack.lastOrNull()) {
        null -> HomeScreen(open, play, onSeeAll = { stack.add(Nav.Grid(it)) },
            onSettings = { stack.add(Nav.Settings) }, onLogout = { stack.clear(); onLogout() })
        Nav.Settings -> SettingsMenuScreen(back, onRequest = { stack.add(Nav.RequestShow) },
            onClient = { stack.add(Nav.ClientSettings) }, onSignOut = { stack.clear(); onLogout() })
        Nav.RequestShow -> RequestShowScreen(back)
        Nav.ClientSettings -> ClientSettingsScreen(back, onServerChanged = { stack.clear(); onLogout() }, loggedIn = true)
        is Nav.Grid -> key(top) { GridScreen(top.item, open, back) }
        is Nav.Detail -> key(top) { DetailScreen(top.item, open, play, back) }
    }
}

// ------------------------------------------------------------------ login
@Composable
fun LoginScreen(onDone: () -> Unit, onSettings: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var user by remember { mutableStateOf("") }
    var pw by remember { mutableStateOf("") }
    var err by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = Red, unfocusedBorderColor = Color(0xFF555555),
        cursorColor = Red, focusedLabelColor = Red, unfocusedLabelColor = Grey,
        focusedTextColor = Color.White, unfocusedTextColor = Color.White
    )

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        Column(
            Modifier.fillMaxSize().padding(horizontal = 28.dp).padding(bottom = 90.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center
        ) {
            Image(painterResource(R.drawable.logo_banner), "Alleria Filmotheque", Modifier.width(280.dp).aspectRatio(4.198f))
            Spacer(Modifier.height(24.dp))
            Text("Sign In", fontSize = 30.sp, fontWeight = FontWeight.Bold, color = Color.White,
                modifier = Modifier.align(Alignment.Start))
            Spacer(Modifier.height(16.dp))
            OutlinedTextField(user, { user = it }, label = { Text("Username") }, singleLine = true,
                colors = fieldColors, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(pw, { pw = it }, label = { Text("Password") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(), colors = fieldColors,
                modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(20.dp))
            Button(
                enabled = !busy, modifier = Modifier.fillMaxWidth().height(50.dp),
                shape = RoundedCornerShape(4.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Red, contentColor = Color.White),
                onClick = {
                    busy = true; err = ""
                    scope.launch {
                        try { Api.login(ctx, user.trim(), pw); onDone() }
                        catch (e: Exception) { err = "Couldn't sign in. Check your username and password." }
                        busy = false
                    }
                }
            ) { Text(if (busy) "Signing in…" else "Sign In", fontWeight = FontWeight.Bold) }
            TextButton(onClick = {
                ctx.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(Api.RESET_PASSWORD_URL)))
            }) { Text("Forgot password?", color = Grey) }
            if (err.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                Text(err, color = Color(0xFFFF6B6B))
            }
        }
        TextButton(onClick = onSettings, modifier = Modifier.align(Alignment.TopEnd).padding(8.dp)) {
            Text("⚙", fontSize = 24.sp, color = Grey)
        }
        Text(
            "DISCLAIMER: Alleria Filmotheque is a private service for authorized members only. " +
                "Content is provided for personal, non-commercial viewing. Copying, recording, " +
                "sharing your account or redistributing any content is prohibited. " +
                "By signing in you accept these terms.",
            color = Color(0xFF8C8C8C), fontSize = 11.sp, lineHeight = 15.sp,
            modifier = Modifier.align(Alignment.BottomCenter).padding(horizontal = 24.dp, vertical = 20.dp)
        )
    }
}

// ------------------------------------------------------------------ home
@Composable
fun HomeScreen(open: (Item) -> Unit, play: (Item) -> Unit, onSeeAll: (Item) -> Unit,
               onSettings: () -> Unit, onLogout: () -> Unit) {
    var featured by remember { mutableStateOf<List<Item>>(emptyList()) }
    var resume by remember { mutableStateOf<List<Item>>(emptyList()) }
    var rows by remember { mutableStateOf<List<Pair<Item, List<Item>>>>(emptyList()) }
    var error by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        try {
            featured = runCatching { Api.featured() }.getOrDefault(emptyList())
            resume = Api.resume()
            rows = Api.views().map { it to Api.latest(it.id) }.filter { it.second.isNotEmpty() }
        } catch (e: Exception) {
            if (e.message?.contains("401") == true) onLogout() else error = "Can't reach the server. Check your connection."
        }
        loading = false
    }
    val heroes = featured.ifEmpty { (resume + rows.flatMap { it.second }).distinctBy { it.id }.take(6) }
    val pager = rememberPagerState(pageCount = { heroes.size })

    // auto-advance every 5s (timer restarts after a manual swipe)
    LaunchedEffect(pager.currentPage, heroes.size) {
        if (heroes.size > 1) {
            delay(5000)
            pager.animateScrollToPage((pager.currentPage + 1) % heroes.size)
        }
    }
    val bgItem = heroes.getOrNull(pager.currentPage)

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        // blurred movie scene background (crossfades with the carousel)
        Crossfade(targetState = bgItem, animationSpec = tween(900), label = "bg") { scene ->
            if (scene != null) AsyncImage(
                Api.blurBg(scene), null, Modifier.fillMaxSize().blur(20.dp), contentScale = ContentScale.Crop
            )
        }
        Box(Modifier.fillMaxSize().background(Color(0xB3000000)))

        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
            item { HeroCarousel(heroes, pager, play, open) }
            if (resume.isNotEmpty()) item {
                MediaRow("Continue Watching", resume, landscape = true, onClick = play)
            }
            items(rows, key = { it.first.id }) { (lib, list) ->
                MediaRow("Latest in ${lib.name}", list, onClick = open, onSeeAll = { onSeeAll(lib) })
            }
        }
        if (loading) CircularProgressIndicator(Modifier.align(Alignment.Center), color = Red)
        if (error.isNotEmpty()) Text(error, Modifier.align(Alignment.Center).padding(24.dp), color = Grey)

        Row(
            Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(Color(0xCC000000), Color.Transparent)))
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Image(painterResource(R.drawable.logo_banner), "Alleria Filmotheque", Modifier.height(34.dp).aspectRatio(4.198f))
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onSettings) { Text("⚙", color = Color.White, fontSize = 22.sp) }
            TextButton(onClick = onLogout) { Text("Sign out", color = Color.White) }
        }
    }
}

@Composable
fun HeroCarousel(heroes: List<Item>, pager: PagerState, play: (Item) -> Unit, open: (Item) -> Unit) {
    Box(Modifier.fillMaxWidth().height(520.dp)) {
        if (heroes.isNotEmpty()) HorizontalPager(state = pager, modifier = Modifier.fillMaxSize()) { page ->
            HeroPage(heroes[page], play, open)
        }
        if (heroes.size > 1) Row(Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp)) {
            repeat(heroes.size) { i ->
                val on = i == pager.currentPage
                Box(Modifier.padding(3.dp).size(if (on) 8.dp else 6.dp).clip(CircleShape)
                    .background(if (on) Red else Color(0x88FFFFFF)))
            }
        }
    }
}

@Composable
fun HeroPage(hero: Item, play: (Item) -> Unit, open: (Item) -> Unit) {
    Box(Modifier.fillMaxSize().background(Color(0xFF111111))) {
        AsyncImage(Api.heroImage(hero), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(
            listOf(Color(0x55000000), Color.Transparent, Color(0xE6000000), Color(0xB3000000)))))
        Column(
            Modifier.align(Alignment.BottomCenter).padding(start = 20.dp, end = 20.dp, bottom = 30.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(hero.seriesName ?: hero.name, fontSize = 28.sp, fontWeight = FontWeight.Bold,
                color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(6.dp))
            Text(hero.overview.orEmpty(), color = Grey, fontSize = 13.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = { play(hero) }, shape = RoundedCornerShape(4.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black)
                ) { Text("▶  Play", fontWeight = FontWeight.Bold) }
                Button(onClick = { open(hero) }, shape = RoundedCornerShape(4.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6D6D6E), contentColor = Color.White)
                ) { Text("ⓘ  More Info", fontWeight = FontWeight.Bold) }
            }
        }
    }
}

@Composable
fun MediaRow(title: String, list: List<Item>, landscape: Boolean = false,
             onClick: (Item) -> Unit, onSeeAll: (() -> Unit)? = null) {
    Column(Modifier.padding(top = 18.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(title, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            if (onSeeAll != null) Text("See all ›", color = Grey, fontSize = 13.sp,
                modifier = Modifier.clickable { onSeeAll() })
        }
        Spacer(Modifier.height(8.dp))
        LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(list, key = { it.id }) {
                MediaCard(it, Modifier.width(if (landscape) 220.dp else 120.dp), landscape) { onClick(it) }
            }
        }
    }
}

@Composable
fun MediaCard(item: Item, modifier: Modifier, landscape: Boolean = false, onClick: () -> Unit) {
    Column(modifier.clickable(onClick = onClick)) {
        Box(Modifier.fillMaxWidth().aspectRatio(if (landscape) 16f / 9f else 2f / 3f)
            .clip(RoundedCornerShape(6.dp)).background(Color(0xFF1F1F1F))) {
            AsyncImage(if (landscape) Api.landscape(item) else Api.poster(item), item.name,
                Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
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
        Text(caption, color = Grey, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 4.dp))
    }
}

// ------------------------------------------------------------------ grid
@Composable
fun GridScreen(parent: Item, open: (Item) -> Unit, back: () -> Unit) {
    var list by remember { mutableStateOf<List<Item>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    LaunchedEffect(parent.id) {
        list = runCatching { Api.children(parent.id) }.getOrDefault(emptyList()); loading = false
    }
    Column(Modifier.fillMaxSize().background(Color.Black)) {
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = back) { Text("‹", color = Color.White, fontSize = 28.sp) }
            Text(parent.name, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Box(Modifier.fillMaxSize()) {
            if (loading) CircularProgressIndicator(Modifier.align(Alignment.Center), color = Red)
            LazyVerticalGrid(GridCells.Adaptive(110.dp), contentPadding = PaddingValues(10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(list, key = { it.id }) { MediaCard(it, Modifier.fillMaxWidth()) { open(it) } }
            }
        }
    }
}

// ------------------------------------------------------------------ detail
@Composable
fun DetailScreen(base: Item, open: (Item) -> Unit, play: (Item) -> Unit, back: () -> Unit) {
    var cur by remember { mutableStateOf(base) }
    var kids by remember { mutableStateOf<List<Item>>(emptyList()) }
    LaunchedEffect(base.id) {
        runCatching { cur = Api.item(base.id) }
        if (base.type == "Series" || base.type == "Season") runCatching { kids = Api.children(base.id) }
    }
    val meta = listOfNotNull(
        cur.year?.toString(), cur.officialRating,
        cur.runTimeTicks?.let { t -> val m = (t / 600_000_000L).toInt(); if (m >= 60) "${m / 60}h ${m % 60}m" else "${m}m" },
        cur.rating?.let { "★ %.1f".format(it) }
    ).joinToString("   •   ")

    LazyColumn(Modifier.fillMaxSize().background(Color.Black), contentPadding = PaddingValues(bottom = 32.dp)) {
        item {
            Box(Modifier.fillMaxWidth().height(250.dp).background(Color(0xFF111111))) {
                AsyncImage(Api.backdrop(cur), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(
                    listOf(Color(0x66000000), Color.Transparent, Color.Black))))
                TextButton(onClick = back, modifier = Modifier.align(Alignment.TopStart)) {
                    Text("‹", color = Color.White, fontSize = 32.sp)
                }
            }
        }
        item {
            Column(Modifier.padding(horizontal = 16.dp)) {
                Text(cur.seriesName ?: cur.name, color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold)
                if (cur.seriesName != null) Text(cur.name, color = Grey, fontSize = 15.sp)
                if (meta.isNotEmpty()) Text(meta, color = Grey, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
                Spacer(Modifier.height(14.dp))
                Button(onClick = { play(cur) }, modifier = Modifier.fillMaxWidth().height(46.dp),
                    shape = RoundedCornerShape(4.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black)
                ) { Text("▶  Play", fontWeight = FontWeight.Bold, fontSize = 16.sp) }
                Spacer(Modifier.height(14.dp))
                Text(cur.overview.orEmpty(), color = Color(0xFFD2D2D2), fontSize = 14.sp, lineHeight = 20.sp)
            }
        }
        if (kids.isNotEmpty()) {
            item {
                Text(if (base.type == "Series") "Seasons" else "Episodes", color = Color.White, fontSize = 18.sp,
                    fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 16.dp, top = 22.dp, bottom = 8.dp))
            }
            if (base.type == "Series") item {
                LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(kids, key = { it.id }) { MediaCard(it, Modifier.width(120.dp)) { open(it) } }
                }
            } else items(kids, key = { it.id }) { ep -> EpisodeRow(ep) { play(ep) } }
        }
    }
}

@Composable
fun EpisodeRow(ep: Item, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.Top) {
        Box(Modifier.width(140.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(4.dp)).background(Color(0xFF1F1F1F))) {
            AsyncImage(Api.landscape(ep), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            Text("▶", color = Color.White, fontSize = 20.sp, modifier = Modifier.align(Alignment.Center))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text("${ep.index ?: ""}. ${ep.name}", color = Color.White, fontWeight = FontWeight.Bold,
                fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            ep.runTimeTicks?.let { Text("${(it / 600_000_000L)}m", color = Grey, fontSize = 12.sp) }
            Text(ep.overview.orEmpty(), color = Grey, fontSize = 12.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
    }
}

// ------------------------------------------------------------------ settings
@Composable
private fun appFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = Red, unfocusedBorderColor = Color(0xFF555555),
    cursorColor = Red, focusedLabelColor = Red, unfocusedLabelColor = Grey,
    focusedTextColor = Color.White, unfocusedTextColor = Color.White
)

@Composable
private fun ScreenHeader(title: String, back: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = back) { Text("‹", color = Color.White, fontSize = 28.sp) }
        Text(title, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun SettingsRow(icon: String, title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(icon, fontSize = 22.sp, modifier = Modifier.width(40.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
            if (subtitle.isNotEmpty()) Text(subtitle, color = Grey, fontSize = 13.sp)
        }
        Text("›", color = Grey, fontSize = 22.sp)
    }
    HorizontalDivider(color = Color(0xFF222222))
}

@Composable
fun SettingsMenuScreen(back: () -> Unit, onRequest: () -> Unit, onClient: () -> Unit, onSignOut: () -> Unit) {
    Column(Modifier.fillMaxSize().background(Color.Black)) {
        ScreenHeader("Settings", back)
        SettingsRow("🎬", "Request a show", "Ask for a movie, series or anime to be added", onRequest)
        SettingsRow("⚙", "Client settings", "Server address and password", onClient)
        SettingsRow("⎋", "Sign out", "", onSignOut)
        Spacer(Modifier.weight(1f))
        Text("Alleria Filmotheque 1.0", color = Color(0xFF666666), fontSize = 12.sp,
            modifier = Modifier.padding(20.dp))
    }
}

@Composable
fun ClientSettingsScreen(back: () -> Unit, onServerChanged: () -> Unit, loggedIn: Boolean) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var url by remember { mutableStateOf(Api.base) }
    var status by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var cur by remember { mutableStateOf("") }
    var new1 by remember { mutableStateOf("") }
    var new2 by remember { mutableStateOf("") }
    var pwStatus by remember { mutableStateOf("") }
    var pwBusy by remember { mutableStateOf(false) }
    val colors = appFieldColors()

    Column(Modifier.fillMaxSize().background(Color.Black).verticalScroll(rememberScrollState())) {
        ScreenHeader("Client settings", back)
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text("SERVER", color = Red, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                url, { url = it; status = "" }, label = { Text("Server URL") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                colors = colors, modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(
                    enabled = !busy, shape = RoundedCornerShape(4.dp),
                    onClick = {
                        busy = true; status = "Testing…"
                        scope.launch {
                            status = try { "✓ Connected: ${Api.ping(url)}" }
                            catch (e: Exception) { "✗ Can't connect (${e.message})" }
                            busy = false
                        }
                    }
                ) { Text("Test connection", color = Color.White) }
                Button(
                    shape = RoundedCornerShape(4.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Red, contentColor = Color.White),
                    onClick = {
                        val n = Api.normalize(url)
                        if (n.isEmpty()) status = "Enter a server URL"
                        else {
                            val changed = n != Api.base
                            Api.setServer(ctx, n); url = n
                            if (changed) { Api.logout(ctx); onServerChanged() }
                            else status = "Saved"
                        }
                    }
                ) { Text("Save", fontWeight = FontWeight.Bold) }
            }
            if (status.isNotEmpty()) Text(status, color = Grey, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
            TextButton(onClick = { url = Api.SERVER; status = "" }) { Text("Reset to default", color = Grey) }
            Text("Changing the server signs you out so you can log in to the new one.",
                color = Color(0xFF777777), fontSize = 12.sp)

            if (loggedIn) {
                Spacer(Modifier.height(20.dp))
                HorizontalDivider(color = Color(0xFF333333))
                Spacer(Modifier.height(16.dp))
                Text("CHANGE PASSWORD", color = Red, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(cur, { cur = it }, label = { Text("Current password") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(), colors = colors, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(new1, { new1 = it }, label = { Text("New password") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(), colors = colors, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(new2, { new2 = it }, label = { Text("Confirm new password") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(), colors = colors, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(10.dp))
                Button(
                    enabled = !pwBusy, shape = RoundedCornerShape(4.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Red, contentColor = Color.White),
                    onClick = {
                        when {
                            new1.isEmpty() -> pwStatus = "Enter a new password"
                            new1 != new2 -> pwStatus = "New passwords don't match"
                            else -> {
                                pwBusy = true; pwStatus = "Changing…"
                                scope.launch {
                                    pwStatus = try {
                                        Api.changePassword(cur, new1)
                                        cur = ""; new1 = ""; new2 = ""
                                        "✓ Password changed"
                                    } catch (e: Exception) {
                                        if (e.message?.contains("40") == true) "Current password is incorrect"
                                        else "Couldn't change password (${e.message})"
                                    }
                                    pwBusy = false
                                }
                            }
                        }
                    }
                ) { Text("Change password", fontWeight = FontWeight.Bold) }
                if (pwStatus.isNotEmpty()) Text(pwStatus, color = Grey, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
            }

            if (loggedIn && Api.JFA_URL.isNotEmpty()) {
                TextButton(onClick = {
                    ctx.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(Api.ACCOUNT_URL)))
                }) { Text("Manage account on the web (email, Telegram, Discord)", color = Red) }
            }
            Spacer(Modifier.height(20.dp))
            HorizontalDivider(color = Color(0xFF333333))
            Spacer(Modifier.height(16.dp))
            Text("PLAYER", color = Red, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            Text("Inside the video player, tap the quality label (top right) or the sliders button to choose " +
                "video quality (480p to 4K) and audio output (stereo, mono, 5.1, 7.1).",
                color = Grey, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
fun RequestShowScreen(back: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var title by remember { mutableStateOf("") }
    var year by remember { mutableStateOf("") }
    var category by remember { mutableStateOf("Movie") }
    var status by remember { mutableStateOf("") }
    var isError by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    val colors = appFieldColors()

    Column(Modifier.fillMaxSize().background(Color.Black).verticalScroll(rememberScrollState())) {
        ScreenHeader("Request a show", back)
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            OutlinedTextField(
                title, { title = it; status = "" }, label = { Text("Title *") }, singleLine = true,
                colors = colors, modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                year, { if (it.length <= 4 && it.all { c -> c.isDigit() }) { year = it; status = "" } },
                label = { Text("Year (optional)") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                colors = colors, modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(16.dp))
            Text("CATEGORY", color = Red, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("Movie", "Series", "Anime").forEach { c ->
                    Button(
                        onClick = { category = c }, shape = RoundedCornerShape(20.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (c == category) Red else Color(0xFF2A2A2A),
                            contentColor = Color.White
                        )
                    ) { Text(c) }
                }
            }
            Spacer(Modifier.height(20.dp))
            Button(
                enabled = !busy, modifier = Modifier.fillMaxWidth().height(50.dp), shape = RoundedCornerShape(4.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Red, contentColor = Color.White),
                onClick = {
                    val t = title.trim()
                    val thisYear = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)
                    when {
                        t.isEmpty() -> { status = "Title is required"; isError = true }
                        year.isNotEmpty() && (year.length != 4 || year.toInt() !in 1888..(thisYear + 3)) ->
                            { status = "Enter a valid 4-digit year, or leave it empty"; isError = true }
                        else -> {
                            busy = true; isError = false; status = "Sending…"
                            scope.launch {
                                try {
                                    if (Api.sendRequest(t, year, category)) {
                                        status = "✓ Request sent. Thank you!"
                                        title = ""; year = ""
                                    } else {
                                        ctx.startActivity(Intent.createChooser(
                                            Intent(Intent.ACTION_SEND).apply {
                                                type = "text/plain"
                                                putExtra(Intent.EXTRA_SUBJECT, "Show request: $t")
                                                putExtra(Intent.EXTRA_TEXT, Api.requestText(t, year, category))
                                            }, "Send request via"))
                                        status = "Choose an app to send your request to the admin."
                                    }
                                } catch (e: Exception) {
                                    isError = true; status = "Couldn't send request (${e.message})"
                                }
                                busy = false
                            }
                        }
                    }
                }
            ) { Text("Submit request", fontWeight = FontWeight.Bold) }
            if (status.isNotEmpty()) Text(status, color = if (isError) Color(0xFFFF6B6B) else Grey,
                fontSize = 13.sp, modifier = Modifier.padding(top = 10.dp))
        }
    }
}
