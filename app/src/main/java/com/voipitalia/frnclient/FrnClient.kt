
package com.voipitalia.frnclient

import android.media.*
import java.io.*
import java.net.Socket
import kotlin.concurrent.thread

class FrnClient(
    private val serverHost: String = "server.voip-italia.net",
    private val serverPort: Int = 10024,
    private val netName: String = "Nazionale",
    private val nome: String,
    private val nick: String,
    private val email: String,
    private val onStatus: (String)->Unit,
    private val onClients: (String)->Unit,
    private val onAudio: (ByteArray)->Unit
) {
    private var socket: Socket? = null
    private var running = false
    private var wantTx = false
    private var isTxGranted = false

    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null

    fun connect() {
        running = true
        thread {
            try {
                onStatus("Connessione a $serverHost:$serverPort...")
                socket = Socket(serverHost, serverPort)
                val input = DataInputStream(BufferedInputStream(socket!!.getInputStream()))
                val output = DataOutputStream(BufferedOutputStream(socket!!.getOutputStream()))

                // Login FRN 2014000 - per server Standalone voip-italia PW = email
                val callsignAndUser = if (nome.isNotBlank()) "$nick, $nome" else nick
                val ct = "CT:<VX>2014000</VX><EA>$email</EA><PW>$email</PW><ON>$callsignAndUser</ON><CL>2</CL><BC>PC Only</BC><DS>Android FRN</DS><NN>Italy</NN><CT>Italy</CT><NT>$netName</NT>"
                output.write(ct.toByteArray(Charsets.UTF_8))
                output.flush()

                // Leggi versione + risposta
                val ver = readString(input)
                val resp = readString(input)
                onStatus("Server ver: $ver\nResp: $resp")

                if (resp.contains("<AL>WRONG</AL>") || resp.contains("<AL>BLOCK</AL>")) {
                    onStatus("Login rifiutato: $resp - verifica email/nick")
                    return@thread
                }

                // RX0
                output.write("RX0".toByteArray())
                output.flush()

                initAudioPlayback()

                // Loop principale RX
                while (running) {
                    // se non stiamo trasmettendo, chiedi dati
                    if (!wantTx) {
                        output.write("P".toByteArray())
                        output.flush()
                    }

                    val dt = try { input.read() } catch (e: Exception) { -1 }
                    if (dt == -1) break

                    when (dt) {
                        0 -> { /* DT_IDLE */ Thread.sleep(50) }
                        1 -> { // DT_DO_TX - ci danno permesso TX
                            val idx1 = input.read(); val idx2 = input.read()
                            isTxGranted = true
                            onStatus("TX OK - puoi parlare")
                            if (wantTx) startRecording(output)
                        }
                        2 -> { // DT_VOICE_BUFFER 2 byte idx + 325 byte audio
                            val idx1 = input.read(); val idx2 = input.read()
                            val voice = ByteArray(325)
                            input.readFully(voice)
                            // MVP: i primi 320 byte sono PCM 8k
                            onAudio(voice)
                            playAudio(voice)
                        }
                        3 -> { // DT_CLIENT_LIST
                            val idx1 = input.read(); val idx2 = input.read()
                            val countStr = readString(input)
                            val count = countStr.toIntOrNull() ?: 0
                            val sb = StringBuilder()
                            repeat(count) {
                                val cli = readString(input)
                                sb.append(cli).append("\n")
                            }
                            onClients(sb.toString())
                        }
                        4 -> { // DT_TEXT_MESSAGE
                            val msg = readString(input)
                            onStatus("MSG: $msg")
                        }
                        else -> {
                            // altri DT: prova a leggere stringa
                            try { val s = readString(input); onStatus("DT $dt: $s") } catch (e: Exception) {}
                        }
                    }

                    // se utente vuole TX e non abbiamo ancora chiesto
                    if (wantTx && !isTxGranted) {
                        output.write("TX0".toByteArray())
                        output.flush()
                    }
                }
            } catch (e: Exception) {
                onStatus("Errore: ${e.message}")
            }
        }
    }

    fun setPtt(pressed: Boolean) {
        wantTx = pressed
        if (!pressed) {
            isTxGranted = false
            stopRecording()
            // invia TX0 per stoppare dopo aver svuotato buffer (qui semplificato)
            try { socket?.getOutputStream()?.write("TX0".toByteArray()); socket?.getOutputStream()?.flush() } catch (e: Exception) {}
        }
    }

    private fun initAudioPlayback() {
        val sr = 8000
        val minBuf = AudioTrack.getMinBufferSize(sr, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        audioTrack = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(sr).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setBufferSizeInBytes(minBuf * 4).setTransferMode(AudioTrack.MODE_STREAM).build()
        audioTrack?.play()
    }

    private fun playAudio(packet325: ByteArray) {
        // packet325 = 320 byte PCM 8bit? Convertiamo: primi 320 byte -> 16bit
        try {
            // se server invia GSM/Speex reale servirebbe decoder, qui facciamo PCM raw per MVP AlterFRN
            val pcm8 = packet325.copyOf(320)
            val pcm16 = ByteArray(pcm8.size * 2)
            for (i in pcm8.indices) {
                val s = (pcm8[i].toInt() - 128) * 256
                pcm16[i*2] = (s and 0xFF).toByte()
                pcm16[i*2+1] = ((s shr 8) and 0xFF).toByte()
            }
            audioTrack?.write(pcm16, 0, pcm16.size)
        } catch (e: Exception) {}
    }

    private fun startRecording(output: DataOutputStream) {
        if (audioRecord != null) return
        val sr = 8000
        val minBuf = AudioRecord.getMinBufferSize(sr, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        audioRecord = AudioRecord(MediaRecorder.AudioSource.MIC, sr, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, minBuf * 2)
        audioRecord?.startRecording()
        thread {
            val buf16 = ShortArray(160) // 20ms @ 8k
            while (wantTx && isTxGranted && running) {
                val read = audioRecord?.read(buf16, 0, buf16.size) ?: 0
                if (read > 0) {
                    // converti 16bit -> 8bit per invio
                    val pcm8 = ByteArray(320)
                    for (i in 0 until read) {
                        pcm8[i] = ((buf16[i].toInt() / 256) + 128).toByte()
                    }
                    // TX1 + 325 bytes
                    val packet = ByteArray(325)
                    System.arraycopy(pcm8, 0, packet, 0, 320.coerceAtMost(pcm8.size))
                    try {
                        synchronized(output) {
                            output.write("TX1".toByteArray())
                            output.write(packet)
                            output.flush()
                        }
                    } catch (e: Exception) { break }
                } else Thread.sleep(10)
            }
        }
    }

    private fun stopRecording() {
        try { audioRecord?.stop(); audioRecord?.release() } catch (e: Exception) {}
        audioRecord = null
    }

    fun disconnect() {
        running = false
        try { socket?.close() } catch (e: Exception) {}
        try { audioTrack?.stop(); audioTrack?.release() } catch (e: Exception) {}
        stopRecording()
    }

    private fun readString(input: DataInputStream): String {
        // FRN invia stringhe con lunghezza? Nel protocollo 2014000 sono null-terminated o con prefisso?
        // Implementazione robusta: leggi fino a 0x00 o fino a buffer
        // Molti server AlterFRN inviano: 2 byte len LE + stringa UTF8
        // Proviamo a gestire entrambi
        val b1 = input.read()
        if (b1 == -1) return ""
        val b2 = input.read()
        if (b2 == -1) return ""
        // se i due byte sembrano lunghezza piccola (<4096), usali come len
        val len = (b1 and 0xFF) or ((b2 and 0xFF) shl 8)
        if (len in 1..4096) {
            val buf = ByteArray(len)
            input.readFully(buf)
            return String(buf, Charsets.UTF_8)
        } else {
            // fallback: era già inizio stringa, leggi fino a 0
            val baos = ByteArrayOutputStream()
            baos.write(b1); baos.write(b2)
            while (true) {
                val b = input.read()
                if (b == -1 || b == 0) break
                baos.write(b)
                if (baos.size() > 8192) break
            }
            return baos.toString(Charsets.UTF_8.name())
        }
    }
}
