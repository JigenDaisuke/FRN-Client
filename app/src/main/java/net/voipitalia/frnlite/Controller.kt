package net.voipitalia.frnlite

import net.voipitalia.frnlite.audio.AudioEngine
import net.voipitalia.frnlite.gsm.Gsm
import net.voipitalia.frnlite.net.Account
import net.voipitalia.frnlite.net.FrnClient
import net.voipitalia.frnlite.net.FrnEvent
import net.voipitalia.frnlite.net.FrnUser
import net.voipitalia.frnlite.net.parseTags
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class ChatMsg(val kind: String, val text: String, val sender: String = "",
                   val senderName: String = "", val senderId: String = "",
                   val to: String = "", val time: String = now())

private fun now() = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())

data class UiState(
    val state: String = "disconnected",
    val room: String = "",
    val rooms: List<String> = emptyList(),
    val users: List<FrnUser> = emptyList(),
    val talker: FrnUser? = null,
    val chat: List<ChatMsg> = emptyList(),
    val tx: String = "idle",            // idle | waiting | on
    val myId: String = "",
    val isAdmin: Boolean = false,
    val banned: List<Pair<String, String>> = emptyList(),  // id -> name
    val error: String? = null,
)

/** Application logic: glues the FRN client, audio and GSM codec. */
class Controller(private val settings: Settings) {

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui

    private val encoder = Gsm()
    private val audio = AudioEngine(::onCapture)
    private val client = FrnClient(::onNet)

    private var adminIds: Set<String> = emptySet()
    @Volatile private var txWant = false
    private val pending = ArrayList<ByteArray>()

    fun start() { audio.start() }
    fun shutdown() { ptt(false); client.disconnect(); audio.stop() }

    // ------------------------------------------------------------- actions
    fun connect() {
        val a = settings.account()
        val missing = buildList {
            if (a.callsign.isBlank()) add("nominativo"); if (a.name.isBlank()) add("nome")
            if (a.email.isBlank()) add("email"); if (a.password.isBlank()) add("password")
            if (a.city.isBlank()) add("città"); if (a.country.isBlank()) add("paese")
        }
        if (missing.isNotEmpty()) { emitError("Completa il profilo: manca " + missing.joinToString(", ")); return }
        client.textEncoding = Charsets.UTF_8
        client.connect(settings.serverHost, settings.serverPort, settings.serverRoom, a, settings.autoReconnect)
    }

    fun disconnect() { client.disconnect(); _ui.value = _ui.value.copy(users = emptyList(), talker = null) }

    fun joinRoom(room: String) {
        if (room.isBlank() || (room == client.room && client.state == "online")) return
        ptt(false)
        settings.serverRoom = room
        addChat(ChatMsg("system", "Passaggio alla stanza «$room»…"))
        client.changeRoom(room)
    }

    fun sendText(text: String, toId: String = "") {
        val t = text.trim(); if (t.isEmpty()) return
        if (client.state != "online") { emitError("Non sei connesso"); return }
        client.sendText(t, toId)
        if (toId.isNotEmpty()) {
            val target = client.users.firstOrNull { it.id == toId }
            addChat(ChatMsg("me_private", t, sender = settings.callsign.uppercase(),
                to = target?.callsign ?: toId))
        }
    }

    fun setStatus(status: Int) = client.setStatus(status)

    fun ptt(on: Boolean) {
        if (on) {
            if (client.state != "online" || txWant) return
            txWant = true; pending.clear()
            setTx("waiting")
            client.requestTx()
        } else {
            if (!txWant) return
            txWant = false; pending.clear()
            client.endTx()
            audio.flushPlayback()
            setTx("idle")
        }
    }

    // ---- admin ----------------------------------------------------------
    fun moderate(op: String, clientId: String, seconds: Int = 0) {
        val cmd = mapOf("mute" to "MC", "unmute" to "UM", "kick" to "BC", "unblock" to "UC",
            "admin" to "AA", "unadmin" to "DA")[op] ?: return
        if (_ui.value.isAdmin) client.moderate(cmd, clientId, seconds)
    }
    fun setRoomMode(connect: Boolean? = null, talk: Boolean? = null) { if (_ui.value.isAdmin) client.setRoomMode(connect, talk) }
    fun setNotice(text: String) { if (_ui.value.isAdmin) client.setNotice(text) }
    fun accessList(op: String, email: String) {
        val cmd = mapOf("add" to "AT", "remove" to "DT", "mark" to "ETX", "unmark" to "RTX")[op] ?: return
        if (_ui.value.isAdmin) client.accessList(cmd, email)
    }

    // ------------------------------------------------------------- audio
    private fun onCapture(pkt: ShortArray) {
        if (!txWant) return
        val enc = encoder.encodePacket(pkt)
        if (client.txGranted) client.sendVoice(enc) else synchronized(pending) {
            pending.add(enc); if (pending.size > 4) pending.removeAt(0)
        }
    }

    // ------------------------------------------------------------- events
    private fun onNet(e: FrnEvent) {
        when (e) {
            is FrnEvent.Voice -> if (!txWant) audio.play(e.pcm)
            is FrnEvent.State -> {
                var s = _ui.value.copy(state = e.state)
                if (e.state != "online") s = s.copy(talker = null)
                if (e.state == "disconnected") s = s.copy(users = emptyList(), isAdmin = false, banned = emptyList())
                _ui.value = s
            }
            is FrnEvent.Login -> {
                adminIds = emptySet()
                val admin = e.access in listOf("OWNER", "NETOWNER", "ADMIN")
                _ui.value = _ui.value.copy(room = e.room, myId = e.myId, isAdmin = admin, error = null)
                addChat(ChatMsg("system", "Connesso a «${e.room}» (accesso ${e.access.lowercase()})"))
                if (admin) addChat(ChatMsg("system", "Hai i permessi di amministratore in questa stanza."))
                if (e.notice.isNotBlank()) addChat(ChatMsg("notice", e.notice))
            }
            is FrnEvent.Users -> _ui.value = _ui.value.copy(users = annotate(e.users))
            is FrnEvent.Rooms -> _ui.value = _ui.value.copy(rooms = e.rooms, room = e.current)
            is FrnEvent.Talker -> _ui.value = _ui.value.copy(talker = e.user)
            is FrnEvent.Text -> {
                val kind = if (e.private) "private" else if (e.senderId == client.myId) "me" else "room"
                addChat(ChatMsg(kind, e.text, e.sender, e.senderName, e.senderId))
            }
            is FrnEvent.TxGranted -> {
                if (txWant) {
                    synchronized(pending) { pending.forEach { client.sendVoice(it) }; pending.clear() }
                    setTx("on")
                }
            }
            is FrnEvent.ListPacket -> onList(e)
            is FrnEvent.Error -> { emitError(e.text); addChat(ChatMsg("error", e.text)) }
            is FrnEvent.Log -> { /* debug */ }
        }
    }

    private fun onList(e: FrnEvent.ListPacket) {
        when (e.kind) {
            "admins" -> {
                adminIds = e.items.mapNotNull { parseTags(it)["ID"] }.filter { it.isNotEmpty() }.toSet()
                _ui.value = _ui.value.copy(users = annotate(_ui.value.users))
            }
            "blocks" -> {
                val banned = e.items.mapNotNull { l ->
                    val t = parseTags(l); t["ID"]?.takeIf { it.isNotEmpty() }?.let { it to (t["ON"]?.ifEmpty { it } ?: it) }
                }
                _ui.value = _ui.value.copy(banned = banned)
            }
        }
    }

    private fun annotate(users: List<FrnUser>) = users.map { it.copy(isAdmin = it.id in adminIds) }

    private fun addChat(m: ChatMsg) {
        val list = (_ui.value.chat + m).takeLast(300)
        _ui.value = _ui.value.copy(chat = list)
    }

    private fun setTx(s: String) { _ui.value = _ui.value.copy(tx = s) }
    private fun emitError(text: String) { _ui.value = _ui.value.copy(error = text) }
    fun clearError() { _ui.value = _ui.value.copy(error = null) }

    val inLevelDb get() = audio.inLevelDb
    val outLevelDb get() = audio.outLevelDb
    var muted: Boolean get() = audio.muted; set(v) { audio.muted = v }
}
