package net.voipitalia.frnlite.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.flow.collectLatest
import net.voipitalia.frnlite.Brand
import net.voipitalia.frnlite.Controller
import net.voipitalia.frnlite.FrnService
import net.voipitalia.frnlite.Settings
import net.voipitalia.frnlite.net.FrnUser

private val Steel = Color(0xFF1F3A5F)
private val Amber = Color(0xFFF3C95D)
private val TxRed = Color(0xFFC0392B)
private val GreenOn = Color(0xFF2ECC71)

private fun normDb(db: Float): Float = ((db + 55f) / 55f).coerceIn(0f, 1f)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun FrnApp(controller: Controller, settings: Settings) {
    val ui by controller.ui.collectAsState()
    val ctx = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    var showSettings by remember { mutableStateOf(settings.callsign.isBlank() || settings.password.isBlank()) }
    var showAdmin by remember { mutableStateOf(false) }
    var menuUser by remember { mutableStateOf<FrnUser?>(null) }
    var target by remember { mutableStateOf<FrnUser?>(null) }
    var tab by remember { mutableStateOf(0) }
    var themeMode by remember { mutableStateOf(settings.theme) }

    LaunchedEffect(Unit) {
        controller.ui.collectLatest { st ->
            st.error?.let { snackbar.showSnackbar(it); controller.clearError() }
        }
    }
    // keep a foreground service only while connected
    LaunchedEffect(ui.state) {
        if (ui.state == "online" || ui.state == "connecting" || ui.state == "logging_in") FrnService.start(ctx)
        else if (ui.state == "disconnected") FrnService.stop(ctx)
    }

    val dark = when (themeMode) { "dark" -> true; "light" -> false; else -> isSystemInDarkTheme() }
    val colors = if (dark) darkColorScheme(primary = Color(0xFF8FB4E3))
                 else lightColorScheme(primary = Steel)
    MaterialTheme(colorScheme = colors) {
        Scaffold(
            snackbarHost = { SnackbarHost(snackbar) },
            topBar = {
                TopAppBar(
                    title = { Text("FRN Lite · ${if (ui.state == "online") ui.room else statusLabel(ui.state)}") },
                    actions = {
                        if (ui.isAdmin) IconButton(onClick = { showAdmin = true }) { Text("🛡") }
                        IconButton(onClick = { showSettings = true }) { Text("⚙") }
                    })
            },
            bottomBar = {
                PttArea(ui, controller,
                    onDown = { controller.ptt(true) }, onUp = { controller.ptt(false) })
            }
        ) { pad ->
            Column(Modifier.padding(pad).fillMaxSize()) {
                ConnectRow(ui.state, settings,
                    onConnect = { controller.connect() }, onDisconnect = { controller.disconnect() },
                    onStatus = { controller.setStatus(it) })
                Readout(ui.talker, ui.tx, ui.room, ui.state)
                if (ui.rooms.isNotEmpty()) RoomRow(ui.rooms, ui.room) { controller.joinRoom(it) }
                TabRow(selectedTabIndex = tab) {
                    Tab(tab == 0, { tab = 0 }, text = { Text("Chat") })
                    Tab(tab == 1, { tab = 1 }, text = { Text("Utenti (${ui.users.size})") })
                }
                Box(Modifier.weight(1f)) {
                    if (tab == 0) ChatPane(ui, target, onReply = { target = it },
                        onSend = { controller.sendText(it, target?.id ?: ""); })
                    else UsersPane(ui, onUser = { u -> if (ui.isAdmin && u.id != ui.myId) menuUser = u else target = u; if (u.id != ui.myId) tab = 0 })
                }
            }
        }

        menuUser?.let { u -> UserMenu(u, onDismiss = { menuUser = null }) { op ->
            menuUser = null
            when (op) {
                "pm" -> target = u
                else -> controller.moderate(op, u.id, if (op == "mute10") 600 else 0)
            }
        } }
        if (showAdmin) AdminDialog(ui, controller, settings) { showAdmin = false }
        if (showSettings) SettingsDialog(settings, controller, themeMode,
            onTheme = { themeMode = it; settings.theme = it }) { showSettings = false }
    }
}

private fun statusLabel(s: String) = when (s) {
    "online" -> "connesso"; "connecting" -> "connessione…"; "logging_in" -> "accesso…"
    "reconnecting" -> "riconnessione…"; else -> "non connesso"
}

@Composable
private fun ConnectRow(state: String, settings: Settings, onConnect: () -> Unit,
                       onDisconnect: () -> Unit, onStatus: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(12.dp, 6.dp), verticalAlignment = Alignment.CenterVertically) {
        if (state == "disconnected") Button(onClick = onConnect) { Text("Connetti") }
        else OutlinedButton(onClick = onDisconnect) { Text("Disconnetti") }
        Spacer(Modifier.width(8.dp))
        Text(settings.serverName, fontWeight = FontWeight.Medium)
        Spacer(Modifier.weight(1f))
        var exp by remember { mutableStateOf(false) }
        val labels = listOf("Disponibile", "Occupato", "Assente")
        var sel by remember { mutableStateOf(0) }
        Box {
            TextButton(onClick = { exp = true }) { Text(labels[sel]) }
            DropdownMenu(exp, { exp = false }) {
                labels.forEachIndexed { i, l -> DropdownMenuItem(text = { Text(l) },
                    onClick = { sel = i; exp = false; onStatus(i) }) }
            }
        }
    }
}

@Composable
private fun Readout(talker: FrnUser?, tx: String, room: String, state: String) {
    val transmitting = tx == "on"
    val receiving = talker != null && !transmitting
    val active = transmitting || receiving
    val accent = when { transmitting -> TxRed; receiving -> GreenOn; tx == "waiting" -> Amber; else -> Color(0xFF5A6B7A) }
    val key = if (transmitting) "tx" else talker?.id ?: "idle"
    var seconds by remember(key) { mutableStateOf(0) }
    LaunchedEffect(key) {
        seconds = 0
        if (active) while (true) { kotlinx.coroutines.delay(1000); seconds++ }
    }
    Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth().padding(12.dp, 6.dp)
            .border(if (active) 2.dp else 1.dp, accent, RoundedCornerShape(14.dp))) {
        Column(Modifier.padding(16.dp).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            if (active) {
                Text("● IN ONDA", color = accent, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                Text(if (transmitting) "TU" else talker!!.callsign.ifEmpty { "?" },
                    fontSize = 30.sp, fontWeight = FontWeight.Bold)
                if (receiving) {
                    if (talker!!.name.isNotBlank()) Text(talker.name)
                    val place = listOf(talker.city, talker.country).filter { it.isNotBlank() }.joinToString(" · ")
                    if (place.isNotBlank()) Text(place, fontSize = 12.sp, color = Color.Gray)
                }
                Text("%d:%02d".format(seconds / 60, seconds % 60), color = accent, fontWeight = FontWeight.Medium)
            } else {
                Text(if (state == "online") "QRV" else "—", fontSize = 28.sp, fontWeight = FontWeight.Bold)
                Text(if (state == "online") "Nessuno in trasmissione" else "Non connesso", color = Color.Gray)
            }
        }
    }
}

@Composable
private fun RoomRow(rooms: List<String>, current: String, onPick: (String) -> Unit) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(12.dp, 2.dp)) {
        rooms.forEach { r ->
            FilterChip(selected = r == current, onClick = { onPick(r) }, label = { Text(r) },
                modifier = Modifier.padding(end = 6.dp))
        }
    }
}

@Composable
private fun ChatPane(ui: net.voipitalia.frnlite.UiState, target: FrnUser?,
                     onReply: (FrnUser) -> Unit, onSend: (String) -> Unit) {
    Column(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            items(ui.chat) { m ->
                Column(Modifier.padding(vertical = 4.dp)) {
                    val who = when (m.kind) {
                        "private" -> "${m.sender} (privato)"; "me_private" -> "Tu → ${m.to} (privato)"
                        "me" -> "Tu"; "room" -> m.sender; else -> "" }
                    if (who.isNotEmpty()) Text("$who · ${m.time}", fontSize = 12.sp, color = Color.Gray)
                    Text(m.text)
                }
            }
        }
        target?.let {
            AssistChip(onClick = { onReply(it) }, label = { Text("Privato a ${it.callsign} ✕") },
                modifier = Modifier.padding(12.dp, 0.dp))
        }
        var text by remember { mutableStateOf("") }
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(text, { text = it }, Modifier.weight(1f),
                placeholder = { Text(if (target != null) "Messaggio privato…" else "Scrivi alla stanza…") })
            Spacer(Modifier.width(8.dp))
            Button(onClick = { if (text.isNotBlank()) { onSend(text); text = "" } }) { Text("Invia") }
        }
    }
}

@Composable
private fun UsersPane(ui: net.voipitalia.frnlite.UiState, onUser: (FrnUser) -> Unit) {
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        items(ui.users) { u ->
            val talking = ui.talker?.id == u.id
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)
                .background(if (talking) Amber.copy(alpha = .3f) else Color.Transparent, RoundedCornerShape(8.dp))
                .clickable { onUser(u) }.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Row {
                        Text(u.callsign, fontWeight = FontWeight.Bold)
                        if (u.id == ui.myId) Text(" (tu)", color = Color.Gray)
                        if (u.muted) Text("  muto", color = TxRed, fontSize = 12.sp)
                        if (u.isAdmin) Text("  admin", color = Steel, fontSize = 12.sp)
                    }
                    Text(listOf(u.name, u.city).filter { it.isNotBlank() }.joinToString(" · "),
                        fontSize = 12.sp, color = Color.Gray)
                }
                Text(u.clientType, fontSize = 11.sp, color = Color.Gray)
            }
            Divider()
        }
    }
}

@Composable
private fun UserMenu(u: FrnUser, onDismiss: () -> Unit, onAction: (String) -> Unit) {
    AlertDialog(onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Chiudi") } },
        title = { Text("${u.callsign} — amministra") },
        text = {
            Column {
                TextButton(onClick = { onAction("pm") }) { Text("Messaggio privato") }
                if (u.muted) TextButton(onClick = { onAction("unmute") }) { Text("Riattiva (togli muto)") }
                else {
                    TextButton(onClick = { onAction("mute") }) { Text("Silenzia") }
                    TextButton(onClick = { onAction("mute10") }) { Text("Silenzia 10 min") }
                }
                if (u.isAdmin) TextButton(onClick = { onAction("unadmin") }) { Text("Rimuovi amministratore") }
                else TextButton(onClick = { onAction("admin") }) { Text("Rendi amministratore") }
                TextButton(onClick = { onAction("kick") }) { Text("Espelli e blocca", color = TxRed) }
            }
        })
}

@Composable
private fun PttArea(ui: net.voipitalia.frnlite.UiState, controller: Controller,
                   onDown: () -> Unit, onUp: () -> Unit) {
    var txLevel by remember { mutableStateOf(0f) }
    var rxLevel by remember { mutableStateOf(0f) }
    LaunchedEffect(Unit) {
        while (true) {
            txLevel = normDb(controller.inLevelDb)
            rxLevel = normDb(controller.outLevelDb)
            kotlinx.coroutines.delay(60)
        }
    }
    val transmitting = ui.tx == "on"
    val receiving = ui.talker != null && !transmitting
    val enabled = ui.state == "online"
    val level = when { transmitting -> txLevel; receiving -> rxLevel; else -> 0f }
    val accent = when { transmitting -> TxRed; receiving -> GreenOn; ui.tx == "waiting" -> Amber; else -> Steel }
    Box(Modifier.fillMaxWidth().height(208.dp), contentAlignment = Alignment.Center) {
        // S-meter ring around the button (green in RX, red in TX)
        Canvas(Modifier.size(196.dp)) {
            val pad = 10.dp.toPx()
            val tl = Offset(pad, pad)
            val sz = Size(size.width - 2 * pad, size.height - 2 * pad)
            val stroke = Stroke(width = 14.dp.toPx(), cap = StrokeCap.Round)
            drawArc(accent.copy(alpha = 0.18f), 135f, 270f, false, tl, sz, style = stroke)
            drawArc(accent, 135f, 270f * level.coerceIn(0f, 1f), false, tl, sz, style = stroke)
        }
        Box(
            Modifier.size(150.dp, 112.dp).clip(RoundedCornerShape(56.dp))
                .background(if (enabled) accent else Color(0xFF8A8A8A))
                .pointerInput(enabled) {
                    if (enabled) detectTapGestures(onPress = { onDown(); tryAwaitRelease(); onUp() })
                },
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                val label = when {
                    transmitting -> "IN ONDA"
                    ui.tx == "waiting" -> "ATTENDI…"
                    receiving -> ui.talker!!.callsign.ifEmpty { "RX" }
                    else -> "PARLA"
                }
                Text(label, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 22.sp,
                    textAlign = TextAlign.Center)
                if (receiving && ui.talker!!.name.isNotBlank())
                    Text(ui.talker!!.name, color = Color.White.copy(alpha = .9f), fontSize = 12.sp)
            }
        }
    }
}
