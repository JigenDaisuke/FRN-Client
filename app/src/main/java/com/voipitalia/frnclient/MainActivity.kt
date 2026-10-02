
package com.voipitalia.frnclient

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat

class MainActivity : AppCompatActivity() {
    private var client: FrnClient? = null
    private lateinit var etNome: EditText
    private lateinit var etNick: EditText
    private lateinit var etEmail: EditText
    private lateinit var tvStatus: TextView
    private lateinit var tvClients: TextView
    private lateinit var btnConnect: Button
    private lateinit var btnPtt: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        etNome = findViewById(R.id.etNome)
        etNick = findViewById(R.id.etNick)
        etEmail = findViewById(R.id.etEmail)
        tvStatus = findViewById(R.id.tvStatus)
        tvClients = findViewById(R.id.tvClients)
        btnConnect = findViewById(R.id.btnConnect)
        btnPtt = findViewById(R.id.btnPtt)

        val prefs = getSharedPreferences("frn", MODE_PRIVATE)
        etNome.setText(prefs.getString("nome",""))
        etNick.setText(prefs.getString("nick",""))
        etEmail.setText(prefs.getString("email",""))

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 1)
        }

        btnConnect.setOnClickListener {
            if (client == null) {
                val nome = etNome.text.toString().trim()
                val nick = etNick.text.toString().trim()
                val email = etEmail.text.toString().trim()
                if (nick.isEmpty() || email.isEmpty()) { Toast.makeText(this, "Inserisci Nick ed Email", Toast.LENGTH_SHORT).show(); return@setOnClickListener }
                prefs.edit().putString("nome", nome).putString("nick", nick).putString("email", email).apply()

                client = FrnClient(
                    nome = nome, nick = nick, email = email,
                    onStatus = { s -> runOnUiThread { tvStatus.text = s } },
                    onClients = { c -> runOnUiThread { tvClients.text = c } },
                    onAudio = {}
                )
                client?.connect()
                btnConnect.text = "DISCONNETTI"
                btnPtt.isEnabled = true
            } else {
                client?.disconnect()
                client = null
                tvStatus.text = "Disconnesso"
                btnConnect.text = "CONNETTI A NAZIONALE"
                btnPtt.isEnabled = false
            }
        }

        btnPtt.isEnabled = false
        btnPtt.setOnTouchListener { _, event ->
            when (event.action) {
                android.view.MotionEvent.ACTION_DOWN -> client?.setPtt(true)
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> client?.setPtt(false)
            }
            true
        }
    }

    override fun onDestroy() {
        client?.disconnect()
        super.onDestroy()
    }
}
