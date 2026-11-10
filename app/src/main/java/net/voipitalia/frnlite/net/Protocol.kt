package net.voipitalia.frnlite.net

import net.voipitalia.frnlite.gsm.Gsm
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.charset.Charset
import java.util.concurrent.Executors
import kotlin.concurrent.thread

/** Account used to log in to an FRN server. */
data class Account(
    var email: String = "",
    var password: String = "",
    var callsign: String = "",
    var name: String = "",
    var clientType: Int = 2,            // 0 crosslink, 1 gateway, 2 PC only
    var bandChannel: String = "PC Only",
    var description: String = "FRN Client Lite",
    var country: String = "",
    var city: String = "",              // "City - part"
) {
    val version = "2014003"
}

/** A user as shown in the room's client list. */
data class FrnUser(
    val index: Int,
    val id: String,
    val callsign: String,
    val name: String,
    val country: String,
    val city: String,
    val band: String,
    val description: String,
    val clientType: String,
    val status: String,
    val muted: Boolean,
    var isAdmin: Boolean = false,
)

/** Events emitted by [FrnClient]; delivered on the network thread. */
sealed class FrnEvent {
    data class State(val state: String) : FrnEvent()
    data class Login(val access: String, val room: String, val myId: String,
                     val notice: String, val maxTx: String) : FrnEvent()
    data class Users(val users: List<FrnUser>) : FrnEvent()
    data class Rooms(val rooms: List<String>, val current: String) : FrnEvent()
    data class Talker(val user: FrnUser?) : FrnEvent()
    data class Voice(val pcm: ShortArray) : FrnEvent()
    data class Text(val senderId: String, val sender: String, val senderName: String,
                    val private: Boolean, val text: String) : FrnEvent()
    object TxGranted : FrnEvent()
    data class ListPacket(val kind: String, val items: List<String>) : FrnEvent()
    data class Error(val text: String, val fatal: Boolean = false) : FrnEvent()
    data class Log(val text: String) : FrnEvent()
}

private val TAG_RE = Regex("<(\\w+)>(.*?)(?:</\\1>|(?=<\\w+>)|$)", RegexOption.DOT_MATCHES_ALL)

fun parseTags(line: String): Map<String, String> =
    TAG_RE.findAll(line).associate { it.groupValues[1] to it.groupValues[2] }

private fun clean(v: String): String =
    v.replace('<', '(').replace('>', ')').replace('\r', ' ').replace('\n', ' ').trim()

private val CLIENT_TYPES = mapOf("0" to "Crosslink", "1" to "Gateway", "2" to "PC")
private val STATUS_NAMES = mapOf("0" to "available", "1" to "busy", "2" to "away")

/**
 * Threaded FRN protocol client.  A faithful port of the project's Python
 * engine (frnclient/protocol.py), verified against AlterFRN r7348.
 */
class FrnClient(private val onEvent: (FrnEvent) -> Unit) {

    companion object {
        const val VOICE_BYTES = 325
        private const val KEEPALIVE_MS = 1000L
        private const val TALK_TIMEOUT_MS = 700L
    }

    var textEncoding: Charset = Charsets.UTF_8
    @Volatile var state = "disconnected"; private set
    @Volatile var myId = ""; private set
    @Volatile var room = ""; private set
    var users: List<FrnUser> = emptyList(); private set

    private var account = Account()
    private var host = ""
    private var port = 10024
    private var backup: Pair<String, Int>? = null
    private var autoReconnect = true

    private var socket: Socket? = null
    private val sender = Executors.newSingleThreadExecutor { r -> Thread(r, "frn-send").apply { isDaemon = true } }
    private val lifecycle = Executors.newSingleThreadExecutor { r -> Thread(r, "frn-life").apply { isDaemon = true } }
    private var netThread: Thread? = null
    @Volatile private var stop = false

    @Volatile private var wantTx = false
    @Volatile var txGranted = false; private set
    private var decoder = Gsm()
    private var talker: FrnUser? = null
    private var lastVoice = 0L

    // ------------------------------------------------------------------ API
    fun connect(host: String, port: Int, room: String, account: Account, autoReconnect: Boolean = true) {
        this.host = host.trim(); this.port = port; this.room = room.trim()
        this.account = account; this.autoReconnect = autoReconnect
        // Serialize teardown + start on a background thread: the old session must
        // be fully closed (so the server frees it) before the new login, or the
        // server rejects it as a duplicate (DUPL).
        lifecycle.execute {
            teardown()
            stop = false
            netThread = thread(name = "frn-net", isDaemon = true) { run() }
        }
    }

    fun disconnect() {
        lifecycle.execute { teardown(); setState("disconnected") }
    }

    private fun teardown() {
        val had = netThread != null
        stop = true
        runCatching { socket?.close() }
        netThread?.let { if (it != Thread.currentThread()) runCatching { it.join(3000) } }
        netThread = null
        // give the server a moment to release the old session before re-login
        if (had) runCatching { Thread.sleep(400) }
    }

    /** FRN has no join command: reconnect to the same server, other room. */
    fun changeRoom(room: String) {
        if (host.isEmpty()) return
        connect(host, port, room, account, autoReconnect)
    }

    fun requestTx(): Boolean {
        if (state != "online") return false
        wantTx = true; txGranted = false
        sendLine("TX0"); return true
    }

    fun sendVoice(gsm325: ByteArray) {
        if (txGranted && gsm325.size == VOICE_BYTES) send("TX1\r\n".toByteArray() + gsm325)
    }

    fun endTx() {
        val was = wantTx || txGranted
        wantTx = false; txGranted = false
        if (was && state == "online") sendLine("RX0")
    }

    fun sendText(text: String, toId: String = "") {
        if (state != "online") return
        sendLine("TM:<ID>$toId</ID><MS>${clean(text)}</MS>")
    }

    fun setStatus(status: Int) { if (state == "online") sendLine("ST:$status") }

    // ---- moderation (verified against AlterFRN r7348) --------------------
    fun moderate(cmd: String, clientId: String, seconds: Int = 0) {
        if (clientId.isEmpty() || state != "online") return
        if (cmd !in listOf("MC", "UM", "BC", "UC", "AA", "DA")) return
        val ti = if (seconds != 0 && (cmd == "MC" || cmd == "BC")) "<TI>$seconds</TI>" else ""
        sendLine("$cmd:<IP>$clientId</IP>$ti")
    }

    fun setRoomMode(connect: Boolean? = null, talk: Boolean? = null) {
        if (state != "online") return
        connect?.let { sendLine("ENA:${if (it) 1 else 0}") }
        talk?.let { sendLine("TXR:${if (it) 1 else 0}") }
    }

    fun accessList(cmd: String, email: String) {
        if (email.isEmpty() || state != "online") return
        if (cmd in listOf("AT", "DT", "ETX", "RTX")) sendLine("$cmd:<EA>$email</EA><EAU>$email</EAU>")
    }

    fun setNotice(text: String) {
        if (state != "online") return
        val ansi = (text).toByteArray(Charset.forName("windows-1252")).take(255).toByteArray()
        val utf8 = (text).toByteArray(Charsets.UTF_8).take(255).toByteArray()
        val head1 = "AIU:%02X".format(ansi.size).toByteArray()
        val head2 = "%02X".format(utf8.size).toByteArray()
        send(head1 + ansi + head2 + utf8 + "\r\n".toByteArray())
    }

    // ------------------------------------------------------------ internals
    private fun emit(e: FrnEvent) = runCatching { onEvent(e) }

    private fun setState(st: String) { if (st != state) { state = st; emit(FrnEvent.State(st)) } }

    private fun send(data: ByteArray) {
        val s = socket ?: return
        // Android forbids socket I/O on the main thread (NetworkOnMainThreadException);
        // a single-thread executor keeps every write off the UI thread and ordered.
        sender.execute {
            runCatching {
                val os = s.getOutputStream(); os.write(data); os.flush()
            }.onFailure { emit(FrnEvent.Log("send error: ${it.message}")) }
        }
    }

    private fun sendLine(line: String) = send(line.toByteArray(textEncoding) + "\r\n".toByteArray())

    private fun decodeText(b: ByteArray): String =
        runCatching { String(b, Charsets.UTF_8) }.getOrElse { String(b, Charset.forName("windows-1252")) }

    private fun run() {
        var delay = 3000L
        var useBackup = false
        while (!stop) {
            val (h, p) = if (useBackup && backup != null) backup!! else (host to port)
            val ok = session(h, p)
            if (stop || !autoReconnect || ok == "fatal") break
            setState("reconnecting")
            emit(FrnEvent.Log("Riconnessione tra ${delay / 1000} s…"))
            if (sleepStop(delay)) break
            delay = minOf((delay * 3) / 2, 60000L)
            if (ok != "online" && backup != null) useBackup = !useBackup
            if (ok == "online") delay = 3000L
        }
        if (!stop) setState("disconnected")
    }

    private fun sleepStop(ms: Long): Boolean {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) { if (stop) return true; Thread.sleep(50) }
        return stop
    }

    private fun session(host: String, port: Int): String {
        setState("connecting")
        users = emptyList(); talker = null; txGranted = false
        val sock = Socket()
        try {
            sock.tcpNoDelay = true
            sock.connect(InetSocketAddress(host, port), 8000)
            sock.soTimeout = 200
        } catch (e: Exception) {
            emit(FrnEvent.Error("Impossibile connettersi a $host:$port: ${e.message}"))
            return "failed"
        }
        socket = sock
        decoder = Gsm()
        val a = account
        val on = "${clean(a.callsign).uppercase()}, ${clean(a.name)}"
        val ct = "CT:<VX>${a.version}</VX><EA>${clean(a.email)}</EA><PW>${clean(a.password)}</PW>" +
                "<ON>$on</ON><CL>${a.clientType}</CL><BC>${clean(a.bandChannel)}</BC>" +
                "<DS>${clean(a.description)}</DS><NN>${clean(a.country)}</NN>" +
                "<CT>${clean(a.city)}</CT><NT>${clean(room)}</NT>"
        setState("logging_in")
        send(ct.toByteArray(Charsets.UTF_8) + "\r\n".toByteArray())

        val buf = ArrayList<Byte>(8192)
        var phase = 0
        var result = "failed"
        var lastRx = System.currentTimeMillis()
        var lastPing = 0L
        val input: InputStream = sock.getInputStream()
        val tmp = ByteArray(65536)
        try {
            while (!stop) {
                try {
                    val n = input.read(tmp)
                    if (n < 0) {
                        if (phase < 2) {
                            emit(FrnEvent.Error(
                                "Il server ha chiuso la connessione durante l'accesso: controlla che " +
                                "nominativo, nome, città e paese siano compilati, e che email e " +
                                "password siano quelle dell'account FRN", fatal = true))
                            return "fatal"
                        }
                        emit(FrnEvent.Error("Il server ha chiuso la connessione")); break
                    }
                    for (i in 0 until n) buf.add(tmp[i])
                    lastRx = System.currentTimeMillis()
                } catch (e: java.net.SocketTimeoutException) {
                    // normal: no data this tick
                } catch (e: Exception) {
                    if (!stop) emit(FrnEvent.Error("Connessione interrotta: ${e.message}")); break
                }
                val now = System.currentTimeMillis()
                if (phase < 2) {
                    while (phase < 2) {
                        val i = buf.indexOf('\n'.code.toByte())
                        if (i < 0) break
                        val line = decodeText(buf.subList(0, i).toByteArray()).trim('\r')
                        repeat(i + 1) { buf.removeAt(0) }
                        if (phase == 0) phase = 1
                        else {
                            if (handleLogin(line)) return "fatal"
                            phase = 2; result = "online"; sendLine("RX0")
                        }
                    }
                    if (phase < 2 && now - lastRx > 10000) {
                        emit(FrnEvent.Error("Nessuna risposta dal server durante il login")); break
                    }
                }
                if (phase == 2) {
                    while (buf.isNotEmpty() && parsePacket(buf)) { /* loop */ }
                    if (now - lastPing >= KEEPALIVE_MS && !txGranted) { sendLine("P"); lastPing = now }
                    if (talker != null && now - lastVoice > TALK_TIMEOUT_MS) setTalker(null)
                    if (now - lastRx > 20000) { emit(FrnEvent.Error("Connessione persa (timeout)")); break }
                }
            }
        } finally {
            socket = null
            runCatching { sock.close() }
            if (talker != null) setTalker(null)
            wantTx = false; txGranted = false
        }
        return if (result == "online") "online" else "failed"
    }

    private fun handleLogin(line: String): Boolean {
        val info = parseTags(line)
        val al = info["AL"] ?: ""
        info["BN"]?.let { bn -> info["BP"]?.toIntOrNull()?.let { bp -> backup = bn to bp } }
        myId = info["ID"] ?: ""
        info["NT"]?.let { if (it.isNotEmpty()) room = it }
        if (al in listOf("OK", "OWNER", "NETOWNER", "ADMIN")) {
            setState("online")
            emit(FrnEvent.Login(al, room, myId, info["MT"] ?: "", info["TT"] ?: ""))
            return false
        }
        val reason = when (al) {
            "WRONG" -> "Email o password errati (o account non registrato)"
            "BLOCK" -> "Accesso bloccato dal server"
            "NOK" -> "Accesso rifiutato dal server"
            else -> "Login rifiutato (${if (al.isEmpty()) line else al})"
        }
        emit(FrnEvent.Error(reason, fatal = true))
        setState("disconnected")
        return true
    }

    // --- packet parser: returns true if one packet was consumed -----------
    private val listNames = mapOf(6 to "admins", 7 to "access", 8 to "blocks", 9 to "mutes", 10 to "access_mode")

    private fun parsePacket(buf: MutableList<Byte>): Boolean {
        val t = buf[0].toInt() and 0xFF
        when (t) {
            0 -> { buf.removeAt(0); return true }
            1 -> {
                if (buf.size < 3) return false
                repeat(3) { buf.removeAt(0) }
                if (wantTx) { txGranted = true; emit(FrnEvent.TxGranted) }
                return true
            }
            2 -> {
                if (buf.size < 3 + VOICE_BYTES) return false
                val idx = ((buf[1].toInt() and 0xFF) shl 8) or (buf[2].toInt() and 0xFF)
                val data = ByteArray(VOICE_BYTES) { buf[3 + it] }
                repeat(3 + VOICE_BYTES) { buf.removeAt(0) }
                lastVoice = System.currentTimeMillis()
                val u = users.getOrNull(idx - 1)
                if (talker == null || (u != null && talker!!.id != u.id)) {
                    decoder = Gsm()
                    setTalker(u ?: FrnUser(idx, "", "", "?", "", "", "", "", "", "", false))
                }
                runCatching { emit(FrnEvent.Voice(decoder.decodePacket(data))) }
                    .onFailure { emit(FrnEvent.Log("errore decodifica audio: ${it.message}")) }
                sendLine("P")
                return true
            }
            3 -> {
                if (buf.size < 3) return false
                val lines = takeList(buf, 3) ?: return false
                users = lines.mapIndexed { i, l -> userFromLine(i + 1, l) }
                emit(FrnEvent.Users(users))
                return true
            }
            4 -> {
                val lines = takeList(buf, 1) ?: return false
                handleText(lines); return true
            }
            5 -> {
                val lines = takeList(buf, 1) ?: return false
                emit(FrnEvent.Rooms(lines, room)); return true
            }
            in 6..10 -> {
                val lines = takeList(buf, 1) ?: return false
                emit(FrnEvent.ListPacket(listNames[t] ?: "list", lines)); return true
            }
            else -> { emit(FrnEvent.Log("tipo pacchetto sconosciuto $t")); buf.removeAt(0); return true }
        }
    }

    /** Parse '<count>\r\n' + count lines after `skip` header bytes. */
    private fun takeList(buf: MutableList<Byte>, skip: Int): List<String>? {
        var pos = skip
        val nl = indexOfFrom(buf, '\n'.code.toByte(), pos)
        if (nl < 0) return null
        val count = String(buf.subList(pos, nl).toByteArray()).trim().ifEmpty { "0" }.toIntOrNull() ?: 0
        val safe = count.coerceIn(0, 10000)
        pos = nl + 1
        val lines = ArrayList<String>(safe)
        for (k in 0 until safe) {
            val j = indexOfFrom(buf, '\n'.code.toByte(), pos)
            if (j < 0) return null
            lines.add(decodeText(buf.subList(pos, j).toByteArray()).trimEnd('\r'))
            pos = j + 1
        }
        repeat(pos) { buf.removeAt(0) }
        return lines
    }

    private fun indexOfFrom(buf: List<Byte>, b: Byte, from: Int): Int {
        for (i in from until buf.size) if (buf[i] == b) return i
        return -1
    }

    private fun userFromLine(index: Int, line: String): FrnUser {
        val t = parseTags(line)
        val on = t["ON"] ?: ""
        val call = on.substringBefore(",").trim()
        val name = on.substringAfter(",", "").trim()
        return FrnUser(
            index = index, id = t["ID"] ?: "", callsign = call, name = name,
            country = t["NN"] ?: "", city = t["CT"] ?: "", band = t["BC"] ?: "",
            description = t["DS"] ?: "", clientType = CLIENT_TYPES[t["CL"]] ?: (t["CL"] ?: ""),
            status = STATUS_NAMES[t["S"] ?: "0"] ?: (t["S"] ?: ""),
            muted = (t["M"] ?: "0").let { it.isNotEmpty() && it != "0" })
    }

    private fun handleText(lines: List<String>) {
        if (lines.isEmpty()) return
        val senderId = lines[0]
        val kind = if (lines.size >= 3) lines.last() else "A"
        val text = if (lines.size >= 3) lines.subList(1, lines.size - 1).joinToString("\n")
                   else lines.drop(1).joinToString("\n")
        val sender = users.firstOrNull { it.id == senderId }
        emit(FrnEvent.Text(senderId, sender?.callsign ?: senderId.ifEmpty { "server" },
            sender?.name ?: "", kind.trim().uppercase() == "P", text))
    }

    private fun setTalker(u: FrnUser?) { talker = u; emit(FrnEvent.Talker(u)) }
}
