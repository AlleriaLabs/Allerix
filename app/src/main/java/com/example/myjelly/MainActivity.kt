package com.example.myjelly

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.animation.core.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val detailTypes = setOf("Series", "Season", "Movie", "Episode", "Video", "MusicVideo")

enum class NavTab(val label: String, val icon: Int) {
    HOME("Home", R.drawable.ic_nav_home),
    MOVIES("Movies", R.drawable.ic_nav_movie),
    SHOWS("TV Shows", R.drawable.ic_nav_tv),
    LIST("My List", R.drawable.ic_nav_star),
    DOWNLOADS("Downloads", R.drawable.ic_nav_download)
}

sealed class Nav {
    data class Grid(val item: Item) : Nav()
    data class Detail(val item: Item) : Nav()
    object Search : Nav()
    object Settings : Nav()
    object RequestShow : Nav()
    object ClientSettings : Nav()
    object PlaybackSettings : Nav()
    data class Web(val url: String, val title: String) : Nav()
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Api.load(this)
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Red, background = BgDark, surface = BgDark,
                    onBackground = Color.White, onSurface = Color.White
                )
            ) { Surface(color = BgDark) { App() } }
        }
    }
}

@Composable
fun App() {
    val ctx = LocalContext.current
    var splash by remember { mutableStateOf(true) }
    var loggedIn by remember { mutableStateOf(Api.token.isNotEmpty()) }
    var loginSettings by remember { mutableStateOf(false) }
    var loginWeb by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { delay(2400); splash = false }

    if (splash) {
        SplashScreen()
    } else if (loggedIn) {
        MainShell(onLogout = { Api.logout(ctx); loggedIn = false })
    } else if (loginWeb != null) {
        WebScreen(loginWeb.orEmpty(), "Reset password", back = { loginWeb = null })
    } else if (loginSettings) {
        BackHandler { loginSettings = false }
        ClientSettingsScreen(back = { loginSettings = false }, onServerChanged = { loginSettings = false }, loggedIn = false)
    } else {
        LoginScreen(
            onDone = { loggedIn = true }, onSettings = { loginSettings = true },
            onForgot = { loginWeb = Api.RESET_PASSWORD_URL }
        )
    }
}

@Composable
fun SplashScreen() {
    var started by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { started = true }
    val logoScale by animateFloatAsState(
        if (started) 1f else 0.6f, tween(1000, easing = FastOutSlowInEasing), label = "logoScale"
    )
    val logoAlpha by animateFloatAsState(if (started) 1f else 0f, tween(900), label = "logoAlpha")
    val tagAlpha by animateFloatAsState(if (started) 1f else 0f, tween(900, delayMillis = 800), label = "tagAlpha")
    val pulse = rememberInfiniteTransition(label = "pulse")
    val glow by pulse.animateFloat(
        0.15f, 0.40f, infiniteRepeatable(tween(1300, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "glow"
    )
    val slide by pulse.animateFloat(
        0f, 1f, infiniteRepeatable(tween(1100, easing = LinearEasing), RepeatMode.Restart), label = "slide"
    )
    Box(
        Modifier.fillMaxSize().background(Brush.radialGradient(listOf(Red.copy(alpha = glow), BgDark), radius = 1100f)),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Image(
                painterResource(R.drawable.logo_square), null,
                Modifier.size(230.dp).scale(logoScale).alpha(logoAlpha)
            )
            Spacer(Modifier.height(22.dp))
            Text(
                "Great stories.\nAnytime. Anywhere.", color = Grey, fontSize = 14.sp,
                textAlign = TextAlign.Center, modifier = Modifier.alpha(tagAlpha)
            )
        }
        Box(
            Modifier.align(Alignment.BottomCenter).padding(bottom = 64.dp).width(120.dp).height(3.dp)
                .clip(RoundedCornerShape(2.dp)).background(Color(0x33FFFFFF))
        ) {
            Box(Modifier.offset(x = 80.dp * slide).width(40.dp).fillMaxHeight().background(Red))
        }
    }
}

@Composable
fun MainShell(onLogout: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var tab by remember { mutableStateOf(NavTab.HOME) }
    val stack = remember { mutableStateListOf<Nav>() }

    val onPlay: (Item) -> Unit = { target ->
        scope.launch {
            val ep = if (target.type == "Series" || target.type == "Season")
                runCatching { Api.nextEpisode(target.id, target.type) }.getOrNull() else target
            if (ep != null) ctx.startActivity(
                Intent(ctx, PlayerActivity::class.java).putExtra("id", ep.id).putExtra("title", ep.name)
            )
        }
    }
    val onOpen: (Item) -> Unit = { target ->
        stack.add(if (target.type in detailTypes || !target.isFolder) Nav.Detail(target) else Nav.Grid(target))
    }
    val back: () -> Unit = { if (stack.isNotEmpty()) stack.removeAt(stack.lastIndex) }
    val signOut: () -> Unit = { stack.clear(); onLogout() }
    val openSettings: () -> Unit = { stack.add(Nav.Settings) }

    BackHandler(enabled = stack.isNotEmpty()) { back() }
    BackHandler(enabled = stack.isEmpty() && tab != NavTab.HOME) { tab = NavTab.HOME }

    val top = stack.lastOrNull()
    val showBar = top == null || top is Nav.Detail || top is Nav.Grid

    Column(Modifier.fillMaxSize().background(BgDark)) {
        Box(Modifier.weight(1f)) {
            when (top) {
                null -> when (tab) {
                    NavTab.HOME -> HomeScreen(
                        onOpen, onPlay, onSeeAll = { stack.add(Nav.Grid(it)) },
                        onSearch = { stack.add(Nav.Search) }, onSettings = openSettings, onLogout = signOut
                    )
                    NavTab.MOVIES -> LibraryTab("Movies", "Movie", onOpen, onSearch = { stack.add(Nav.Search) }, onSettings = openSettings)
                    NavTab.SHOWS -> LibraryTab("TV Shows", "Series", onOpen, onSearch = { stack.add(Nav.Search) }, onSettings = openSettings)
                    NavTab.LIST -> MyListTab(
                        onOpen, onProfile = { stack.add(Nav.ClientSettings) },
                        onSettings = openSettings, onSignOut = signOut,
                        onWeb = { u, t -> stack.add(Nav.Web(u, t)) }
                    )
                    NavTab.DOWNLOADS -> DownloadsTab(onPlay, openSettings)
                }
                is Nav.Grid -> key(top) { GridScreen(top.item, onOpen, back) }
                is Nav.Detail -> key(top) { DetailScreen(top.item, onOpen, onPlay, back) }
                Nav.Search -> SearchScreen(onOpen, back)
                Nav.Settings -> SettingsMenuScreen(
                    back, onRequest = { stack.add(Nav.RequestShow) }, onClient = { stack.add(Nav.ClientSettings) },
                    onPlayback = { stack.add(Nav.PlaybackSettings) }, onSignOut = signOut
                )
                Nav.RequestShow -> RequestShowScreen(back)
                Nav.ClientSettings -> ClientSettingsScreen(
                    back, onServerChanged = signOut, loggedIn = true,
                    onWeb = { u, t -> stack.add(Nav.Web(u, t)) }
                )
                is Nav.Web -> key(top) { WebScreen(top.url, top.title, back) }
                Nav.PlaybackSettings -> PlaybackSettingsScreen(back)
            }
        }
        if (showBar) BottomBar(tab) { selected -> stack.clear(); tab = selected }
    }
}

@Composable
fun BottomBar(selected: NavTab, onTab: (NavTab) -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(Color(0xF2101014)).padding(top = 8.dp, bottom = 6.dp)
    ) {
        NavTab.values().forEach { t ->
            val on = t == selected
            Column(
                Modifier.weight(1f).clickable { onTab(t) }.padding(vertical = 2.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                SmallIcon(t.icon, if (on) Red else Dim, 24)
                Text(t.label, color = if (on) Red else Dim, fontSize = 10.sp)
            }
        }
    }
}
