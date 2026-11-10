package net.voipitalia.frnlite.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import net.voipitalia.frnlite.Brand
import net.voipitalia.frnlite.Controller
import net.voipitalia.frnlite.Settings

@Composable
fun SettingsDialog(settings: Settings, themeMode: String, onTheme: (String) -> Unit, onClose: () -> Unit) {
    var callsign by remember { mutableStateOf(settings.callsign) }
    var name by remember { mutableStateOf(settings.name) }
    var email by remember { mutableStateOf(settings.email) }
    var password by remember { mutableStateOf(settings.password) }
    var city by remember { mutableStateOf(settings.city) }
    var country by remember { mutableStateOf(settings.country) }
    var band by remember { mutableStateOf(settings.bandChannel) }
    var host by remember { mutableStateOf(settings.serverHost) }
    var port by remember { mutableStateOf(settings.serverPort.toString()) }
    var room by remember { mutableStateOf(settings.serverRoom) }
    var unlocked by remember { mutableStateOf(settings.serverEditable) }
    var pw by remember { mutableStateOf("") }

    AlertDialog(onDismissRequest = onClose,
        confirmButton = {
            TextButton(onClick = {
                settings.callsign = callsign.trim().uppercase(); settings.name = name.trim()
                settings.email = email.trim(); settings.password = password
                settings.city = city.trim(); settings.country = country.trim(); settings.bandChannel = band.trim()
                if (settings.serverEditable) {
                    settings.serverHost = host.trim(); settings.serverPort = port.toIntOrNull() ?: 10024
                }
                settings.serverRoom = room.trim()
                onClose()
            }) { Text("Salva") }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("Annulla") } },
        title = { Text("Impostazioni") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("Profilo", style = MaterialTheme.typography.titleSmall)
                field("Nominativo", callsign) { callsign = it }
                field("Nome", name) { name = it }
                field("Email account FRN", email) { email = it }
                field("Password", password, password = true) { password = it }
                field("Città (Città - zona)", city) { city = it }
                field("Paese", country) { country = it }
                field("Banda e canale", band) { band = it }
                Spacer(Modifier.height(10.dp))
                Text("Server", style = MaterialTheme.typography.titleSmall)
                if (!unlocked) {
                    Text("🔒 Server fissato dall'amministratore. Inserisci la password per modificarlo.",
                        style = MaterialTheme.typography.bodySmall)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(pw, { pw = it }, Modifier.weight(1f), label = { Text("Password") },
                            visualTransformation = PasswordVisualTransformation())
                        Spacer(Modifier.width(8.dp))
                        Button(onClick = { unlocked = settings.unlock(pw) }) { Text("Sblocca") }
                    }
                }
                field("Indirizzo", host, enabled = unlocked) { host = it }
                field("Porta", port, enabled = unlocked, number = true) { port = it }
                field("Stanza iniziale", room) { room = it }
                Spacer(Modifier.height(10.dp))
                Text("Tema", style = MaterialTheme.typography.titleSmall)
                Row {
                    listOf("system" to "Sistema", "light" to "Chiaro", "dark" to "Scuro").forEach { (k, lbl) ->
                        FilterChip(selected = themeMode == k, onClick = { onTheme(k) },
                            label = { Text(lbl) }, modifier = Modifier.padding(end = 6.dp))
                    }
                }
            }
        })
}

@Composable
private fun field(label: String, value: String, password: Boolean = false, enabled: Boolean = true,
                  number: Boolean = false, onChange: (String) -> Unit) {
    OutlinedTextField(value, onChange, Modifier.fillMaxWidth().padding(vertical = 3.dp),
        label = { Text(label) }, enabled = enabled, singleLine = true,
        visualTransformation = if (password) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = if (number) KeyboardType.Number else KeyboardType.Text))
}

@Composable
fun AdminDialog(ui: net.voipitalia.frnlite.UiState, controller: Controller, settings: Settings, onClose: () -> Unit) {
    var notice by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = onClose,
        confirmButton = { TextButton(onClick = onClose) { Text("Chiudi") } },
        title = { Text("Amministrazione — ${ui.room}") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("Restrizioni della stanza", style = MaterialTheme.typography.titleSmall)
                var conn by remember { mutableStateOf(false) }
                var talk by remember { mutableStateOf(false) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(conn, { conn = it; controller.setRoomMode(connect = it) }); Spacer(Modifier.width(8.dp))
                    Text("Connessione solo dalla lista d'accesso")
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(talk, { talk = it; controller.setRoomMode(talk = it) }); Spacer(Modifier.width(8.dp))
                    Text("Trasmissione solo dai marcati")
                }
                Spacer(Modifier.height(10.dp))
                Text("Messaggio di benvenuto", style = MaterialTheme.typography.titleSmall)
                OutlinedTextField(notice, { notice = it }, Modifier.fillMaxWidth(), maxLines = 2)
                Row {
                    Button(onClick = { controller.setNotice(notice) }) { Text("Imposta") }
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton(onClick = { notice = ""; controller.setNotice("") }) { Text("Rimuovi") }
                }
                Spacer(Modifier.height(10.dp))
                Text("Lista d'accesso (per e-mail)", style = MaterialTheme.typography.titleSmall)
                OutlinedTextField(email, { email = it }, Modifier.fillMaxWidth(), label = { Text("utente@esempio.it") })
                Row {
                    Button(onClick = { controller.accessList("add", email) }) { Text("Aggiungi") }
                    Spacer(Modifier.width(6.dp))
                    OutlinedButton(onClick = { controller.accessList("remove", email) }) { Text("Togli") }
                }
                Row {
                    Button(onClick = { controller.accessList("mark", email) }) { Text("Marca") }
                    Spacer(Modifier.width(6.dp))
                    OutlinedButton(onClick = { controller.accessList("unmark", email) }) { Text("Smarca") }
                }
                if (ui.banned.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    Text("Utenti bloccati", style = MaterialTheme.typography.titleSmall)
                    ui.banned.forEach { (id, nm) ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(nm, Modifier.weight(1f))
                            OutlinedButton(onClick = { controller.moderate("unblock", id) }) { Text("Sblocca") }
                        }
                    }
                }
            }
        })
}
