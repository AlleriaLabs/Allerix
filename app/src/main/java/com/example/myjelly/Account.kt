package com.example.myjelly

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

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

    AppBackground {
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
fun SettingsMenuScreen(
    back: () -> Unit, onRequest: () -> Unit, onClient: () -> Unit, onPlayback: () -> Unit, onSignOut: () -> Unit
) {
    Column(Modifier.fillMaxSize().background(BgDark)) {
        ScreenHeader("Settings", back)
        SettingsRow("🎬", "Request a show", "Ask for a movie, series or anime to be added", onRequest)
        SettingsRow("▶", "Playback settings", "Quality, audio, speed, auto-play", onPlayback)
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

    Column(Modifier.fillMaxSize().background(BgDark).verticalScroll(rememberScrollState())) {
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
            Text("Set default video quality and audio output in Settings, Playback settings. " +
                "You can also change them from the More menu inside the player.",
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

    Column(Modifier.fillMaxSize().background(BgDark).verticalScroll(rememberScrollState())) {
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

// ------------------------------------------------------------------ playback settings
fun speedLabel(f: Float): String = (if (f % 1f == 0f) f.toInt().toString() else f.toString()) + "x"

@Composable
private fun <T> ChoiceDialog(
    title: String, options: List<Pair<String, T>>, selected: T, onPick: (T) -> Unit, onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF181818),
        title = { Text(title, color = Color.White) },
        text = {
            Column {
                options.forEach { (label, value) ->
                    Row(
                        Modifier.fillMaxWidth().clickable { onPick(value); onDismiss() }.padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = value == selected, onClick = { onPick(value); onDismiss() },
                            colors = RadioButtonDefaults.colors(selectedColor = Red, unselectedColor = Grey)
                        )
                        Text(label, color = Color.White, fontSize = 15.sp)
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close", color = Grey) } }
    )
}

@Composable
fun PlaybackSettingsScreen(back: () -> Unit) {
    val ctx = LocalContext.current
    val prefs = remember { ctx.getSharedPreferences("player", Context.MODE_PRIVATE) }
    var quality by remember {
        mutableStateOf(runCatching { Quality.valueOf(prefs.getString("quality", "ORIGINAL") ?: "ORIGINAL") }.getOrDefault(Quality.ORIGINAL))
    }
    var audio by remember {
        mutableStateOf(runCatching { AudioMode.valueOf(prefs.getString("audio", "ORIGINAL") ?: "ORIGINAL") }.getOrDefault(AudioMode.ORIGINAL))
    }
    var speed by remember { mutableStateOf(prefs.getFloat("speed", 1f)) }
    var autoPlay by remember { mutableStateOf(prefs.getBoolean("autoplay", true)) }
    var dialog by remember { mutableStateOf(0) }

    Column(Modifier.fillMaxSize().background(BgDark)) {
        ScreenHeader("Playback Settings", back)
        Column(Modifier.padding(horizontal = 16.dp).fillMaxWidth().glass(18.dp)) {
            SettingsRow("HD", "Quality", quality.label) { dialog = 1 }
            SettingsRow("🔊", "Audio output", audio.label) { dialog = 2 }
            SettingsRow("⏱", "Playback speed", speedLabel(speed)) { dialog = 3 }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Auto Play", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                    Text("Next episode", color = Grey, fontSize = 13.sp)
                }
                Switch(
                    checked = autoPlay,
                    onCheckedChange = { autoPlay = it; prefs.edit().putBoolean("autoplay", it).apply() },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.White, checkedTrackColor = Red,
                        uncheckedThumbColor = Grey, uncheckedTrackColor = Color(0xFF333333)
                    )
                )
            }
        }
        Text(
            "Quality and audio changes use server transcoding. \u201COriginal\u201D plays the file directly.",
            color = Dim, fontSize = 12.sp, modifier = Modifier.padding(20.dp)
        )
    }

    when (dialog) {
        1 -> ChoiceDialog("Quality", Quality.values().map { it.label to it }, quality,
            { quality = it; prefs.edit().putString("quality", it.name).apply() }, { dialog = 0 })
        2 -> ChoiceDialog("Audio output", AudioMode.values().map { it.label to it }, audio,
            { audio = it; prefs.edit().putString("audio", it.name).apply() }, { dialog = 0 })
        3 -> ChoiceDialog("Playback speed", listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f).map { speedLabel(it) to it }, speed,
            { speed = it; prefs.edit().putFloat("speed", it).apply() }, { dialog = 0 })
        else -> {}
    }
}
