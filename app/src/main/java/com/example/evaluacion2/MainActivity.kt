package com.example.evaluacion2

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import java.util.UUID

class MainActivity : AppCompatActivity() {

    // --- VARIABLES DE FIREBASE ---
    private lateinit var auth: FirebaseAuth
    private lateinit var database: DatabaseReference

    // --- VARIABLES DE VISTAS (UI) ---
    private lateinit var editTextCorreo: EditText
    private lateinit var editTextPassword: EditText
    private lateinit var textViewEstadoAuth: TextView
    private lateinit var textViewEstadoSensor: TextView

    // --- VARIABLES DE BLUETOOTH ---
    private var permisosOk = false
    private var socketActual: BluetoothSocket? = null
    private var miRol = "" // "servidor" o "cliente"

    private val adapter: BluetoothAdapter by lazy {
        (getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
    }

    // UUID estándar para perfil puerto serie SPP (HC-05 / Celular)
    private val uuid: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

    // Lanzador de permisos para Android 12+ (BLUETOOTH_SCAN y BLUETOOTH_CONNECT)
    private val permisos = arrayOf(
        Manifest.permission.BLUETOOTH_SCAN,
        Manifest.permission.BLUETOOTH_CONNECT
    )

    private val lanzadorPermisos = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { resultados ->
        permisosOk = resultados.values.all { it }
        if (permisosOk) {
            Toast.makeText(this, "Permisos de Bluetooth concedidos", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "Sin permisos no se puede usar Bluetooth", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)

        // Ajuste de paddings para barras del sistema (Edge to Edge)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        // 1. Inicializar Firebase
        auth = FirebaseAuth.getInstance()
        database = FirebaseDatabase.getInstance().reference

        // 2. Enlazar Vistas con el XML
        editTextCorreo = findViewById(R.id.editTextCorreo)
        editTextPassword = findViewById(R.id.editTextPassword)
        textViewEstadoAuth = findViewById(R.id.textViewEstadoAuth)
        textViewEstadoSensor = findViewById(R.id.textViewEstadoSensor)

        val btnRegistrar = findViewById<Button>(R.id.btnRegistrar)
        val btnIniciarSesion = findViewById<Button>(R.id.btnIniciarSesion)
        val btnServidor = findViewById<Button>(R.id.btnServidor)
        val btnCliente = findViewById<Button>(R.id.btnCliente)
        val btnSimularAlerta = findViewById<Button>(R.id.btnSimularAlerta)

        // 3. Solicitar Permisos al iniciar
        lanzadorPermisos.launch(permisos)

        // 4. Eventos de Autenticación
        btnRegistrar.setOnClickListener { registrarUsuario() }
        btnIniciarSesion.setOnClickListener { iniciarSesion() }

        // Verificar sesión previa
        if (auth.currentUser != null) {
            textViewEstadoAuth.text = "Sesión activa: ${auth.currentUser?.email}"
            escucharEstadoSensorEnNube()
        }

        // 5. Eventos de Bluetooth
        btnServidor.setOnClickListener {
            if (permisosOk) {
                miRol = "servidor"
                iniciarComoServidor()
            }
        }

        btnCliente.setOnClickListener {
            if (permisosOk) {
                miRol = "cliente"
                iniciarComoCliente()
            }
        }

        // Botón de prueba/simulación local
        btnSimularAlerta.setOnClickListener {
            notificarAlertaMovimiento("Movimiento Detectado!")
        }
    }

    private fun registrarUsuario() {
        val correo = editTextCorreo.text.toString().trim()
        val password = editTextPassword.text.toString().trim()

        if (correo.isBlank() || password.isBlank()) {
            Toast.makeText(this, "Completa correo y contraseña", Toast.LENGTH_SHORT).show()
            return
        }

        auth.createUserWithEmailAndPassword(correo, password)
            .addOnCompleteListener(this) { tarea ->
                if (tarea.isSuccessful) {
                    Toast.makeText(this, "Usuario registrado", Toast.LENGTH_SHORT).show()
                    textViewEstadoAuth.text = "Sesión iniciada: ${auth.currentUser?.email}"
                    escucharEstadoSensorEnNube()
                } else {
                    Toast.makeText(this, "Error: ${tarea.exception?.message}", Toast.LENGTH_LONG).show()
                }
            }
    }

    private fun iniciarSesion() {
        val correo = editTextCorreo.text.toString().trim()
        val password = editTextPassword.text.toString().trim()

        if (correo.isBlank() || password.isBlank()) {
            Toast.makeText(this, "Completa correo y contraseña", Toast.LENGTH_SHORT).show()
            return
        }

        auth.signInWithEmailAndPassword(correo, password)
            .addOnCompleteListener(this) { tarea ->
                if (tarea.isSuccessful) {
                    textViewEstadoAuth.text = "Sesión iniciada: ${auth.currentUser?.email}"
                    escucharEstadoSensorEnNube()
                } else {
                    textViewEstadoAuth.text = "Error al iniciar sesión"
                }
            }
    }

    private fun guardarEstadoEnNube(estado: String) {
        val uid = auth.currentUser?.uid ?: return
        database.child("usuarios").child(uid).child("estado_sensor").setValue(estado)
    }

    private fun escucharEstadoSensorEnNube() {
        val uid = auth.currentUser?.uid ?: return
        database.child("usuarios").child(uid).child("estado_sensor")
            .addValueEventListener(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    val estado = snapshot.getValue(String::class.java) ?: "Sin estado"
                    textViewEstadoSensor.text = "Estado del Sensor: $estado"
                }

                override fun onCancelled(error: DatabaseError) {
                    Toast.makeText(this@MainActivity, "Error en BD: ${error.message}", Toast.LENGTH_SHORT).show()
                }
            })
    }

    @SuppressLint("MissingPermission")
    private fun iniciarComoServidor() {
        Toast.makeText(this, "Esperando conexión de sensor...", Toast.LENGTH_SHORT).show()
        val serverSocket = adapter.listenUsingRfcommWithServiceRecord("SensorApp", uuid)
        Thread {
            try {
                val socket = serverSocket.accept()
                runOnUiThread {
                    Toast.makeText(this, "¡Sensor Conectado!", Toast.LENGTH_SHORT).show()
                }
                manejarConexion(socket)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }.start()
    }

    @SuppressLint("MissingPermission")
    private fun iniciarComoCliente() {
        Toast.makeText(this, "Buscando dispositivo emparejado...", Toast.LENGTH_SHORT).show()
        // Cambia "DISPOSITIVO_SENSOR" por el nombre real del Bluetooth con el que probarás
        val dispositivo = adapter.bondedDevices.firstOrNull { it.name == "DISPOSITIVO_SENSOR" }
        if (dispositivo == null) {
            Toast.makeText(this, "No se encontró el dispositivo emparejado", Toast.LENGTH_SHORT).show()
            return
        }

        val socket = dispositivo.createRfcommSocketToServiceRecord(uuid)
        Thread {
            try {
                adapter.cancelDiscovery()
                socket.connect()
                runOnUiThread {
                    Toast.makeText(this, "¡Conectado al Servidor!", Toast.LENGTH_SHORT).show()
                }
                manejarConexion(socket)
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this, "Error al conectar por Bluetooth", Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }

    private fun manejarConexion(socket: BluetoothSocket) {
        socketActual = socket
        val entrada = socket.inputStream
        val buffer = ByteArray(1024)

        while (true) {
            try {
                val bytes = entrada.read(buffer)
                val mensaje = String(buffer, 0, bytes).trim()

                if (mensaje.contains("MOVIMIENTO")) {
                    runOnUiThread {
                        notificarAlertaMovimiento("¡MOVIMIENTO DETECTADO EN HABITACIÓN!")
                    }
                }
            } catch (e: Exception) {
                break
            }
        }
    }

    private fun notificarAlertaMovimiento(alerta: String) {
        textViewEstadoSensor.text = alerta
        guardarEstadoEnNube(alerta)
    }
}