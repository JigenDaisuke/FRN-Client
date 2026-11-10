package net.voipitalia.frnlite.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
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

    MaterialTheme(colorScheme = lightColorScheme(primary = Steel)) {
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
                PttBar(ui.tx, enabled = ui.state == "online",
                    onDown = { controller.ptt(true) }, onUp = { controller.ptt(false) },
                    muted = controller.muted, onMute = { controller.muted = !controller.muted })
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
        if (showSettings) SettingsDialog(settings) { showSettings = false }
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
    val bg = when { tx == "on" -> TxRed; talker != null -> Amber; else -> Color(0xFFE8E2CF) }
    Surface(color = bg, shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth().padding(12.dp, 4.dp)) {
        Column(Modifier.padding(16.dp)) {
            val big = when { tx == "on" -> "IN ONDA"; tx == "waiting" -> "ATTESA"
                talker != null -> talker.callsign.ifEmpty { "?" }
                state == "online" -> "QRV"; else -> "—" }
            Text(big, fontSize = 30.sp, fontWeight = FontWeight.Bold,
                color = if (tx == "on") Color.White else Steel)
            val sub = when { tx == "on" -> "Stai trasmettendo in $room"
                tx == "waiting" -> "Richiesta del canale…"
                talker != null -> listOf(talker.name, talker.city).filter { it.isNotBlank() }.joinToString(" · ")
                state == "online" -> "Nessuno in trasmissione"; else -> "Non connesso" }
            Text(sub, color = if (tx == "on") Color.White else Steel)
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
private fun PttBar(tx: String, enabled: Boolean, onDown: () -> Unit, onUp: () -> Unit,
                   muted: Boolean, onMute: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
        val bg = when (tx) { "on" -> TxRed; "waiting" -> Amber; else -> Steel }
        Surface(color = if (enabled) bg else Color.Gray, shape = RoundedCornerShape(16.dp),
            modifier = Modifier.weight(1f).height(84.dp).pointerInput(enabled) {
                if (enabled) detectTapGestures(onPress = {
                    onDown(); tryAwaitRelease(); onUp()
                })
            }) {
            Box(contentAlignment = Alignment.Center) {
                Text(when (tx) { "on" -> "IN TRASMISSIONE"; "waiting" -> "ATTENDI…"; else -> "PREMI PER PARLARE" },
                    color = Color.White, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
            }
        }
        Spacer(Modifier.width(8.dp))
        IconButton(onClick = onMute) { Text(if (muted) "🔇" else "🔊") }
    }
}
