package com.example.sosavc

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import kotlin.math.sqrt
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import android.content.IntentFilter
import android.os.BatteryManager
import okhttp3.OkHttpClient
import okhttp3.Response
import java.util.UUID

class DataCollectorService : Service(), SensorEventListener {
    companion object {
        const val CHANNEL_ID = "DataCollectorChannel"
        const val NOTIFICATION_ID = 1
    }

    private var sensorManager: SensorManager? = null
    private var lastAccel = FloatArray(3)
    private var lastMovementTime: Long = 0
    private var fusedLocationClient: FusedLocationProviderClient? = null
    private var locationCallback: LocationCallback? = null
    private val handler = Handler(Looper.getMainLooper())
    private val sendRunnable = object : Runnable {
        override fun run() {
            if (!isInPauseWindow()) {
                evaluateAndMaybeSend()
            } else {
                Log.d("SOSAVC", "Janela de pausa (21:00-06:00): não enviando")
            }
            handler.postDelayed(this, 15 * 60 * 1000L) // verifica a cada 15 min
        }
    }
    // Removido: smsHandler e smsRunnable

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        // Movimento
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val accelerometer = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        accelerometer?.let {
            sensorManager?.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
        }
        // Localização
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 10 * 60 * 1000L) // a cada 10 min
            .setMinUpdateIntervalMillis(5 * 60 * 1000L)
            .build()
        locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val location = result.lastLocation
                location?.let {
                    val prefs = getSharedPreferences("user_prefs", Context.MODE_PRIVATE)
                    prefs.edit()
                        .putString("last_latitude", location.latitude.toString())
                        .putString("last_longitude", location.longitude.toString())
                        .apply()
                }
            }
        }
        try {
            fusedLocationClient?.requestLocationUpdates(locationRequest, locationCallback!!, mainLooper)
        } catch (e: SecurityException) {
            // Permissão não concedida
        }
        // Removido: smsHandler.post(smsRunnable)
    }

    override fun onDestroy() {
        super.onDestroy()
        sensorManager?.unregisterListener(this)
        fusedLocationClient?.removeLocationUpdates(locationCallback!!)
        handler.removeCallbacks(sendRunnable)
        // Removido: smsHandler.removeCallbacks(smsRunnable)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = createNotification()
        startForeground(NOTIFICATION_ID, notification)
        handler.removeCallbacks(sendRunnable)
        handler.post(sendRunnable)
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onSensorChanged(event: SensorEvent?) {
        if (event?.sensor?.type == Sensor.TYPE_ACCELEROMETER) {
            val x = event.values[0]
            val y = event.values[1]
            val z = event.values[2]
            if (lastAccel[0] != 0f || lastAccel[1] != 0f || lastAccel[2] != 0f) {
                val delta = sqrt(
                    ((x - lastAccel[0]) * (x - lastAccel[0]) +
                            (y - lastAccel[1]) * (y - lastAccel[1]) +
                            (z - lastAccel[2]) * (z - lastAccel[2])).toDouble()
                )
                if (delta > 1.5) { // Sensibilidade do movimento
                    val now = System.currentTimeMillis()
                    if (now - lastMovementTime > 10000) { // Evita múltiplos triggers em pouco tempo
                        val prefs = getSharedPreferences("user_prefs", Context.MODE_PRIVATE)
                        prefs.edit().putLong("last_movement", now).apply()
                        lastMovementTime = now
                    }
                }
            }
            lastAccel[0] = x
            lastAccel[1] = y
            lastAccel[2] = z
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Coleta de Dados",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("SOSAVC em execução")
            .setContentText("Coletando dados em segundo plano")
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setOngoing(true)
            .build()
    }

    private fun isInPauseWindow(): Boolean {
        val now = java.util.Calendar.getInstance()
        val nowMinutes = now.get(java.util.Calendar.HOUR_OF_DAY) * 60 + now.get(java.util.Calendar.MINUTE)
        val pauseStart = 21 * 60 // 21:00
        val pauseEnd = 6 * 60    // 06:00
        return nowMinutes >= pauseStart || nowMinutes < pauseEnd
    }

    private fun getOrCreateUserId(context: Context): Int {
        val prefs = context.getSharedPreferences("user_prefs", Context.MODE_PRIVATE)
        var userId = prefs.getInt("user_id", -1)
        if (userId == -1) {
            userId = (100000..999999).random()
            prefs.edit().putInt("user_id", userId).apply()
        }
        return userId
    }

    private fun getAuthToken(): String {
        val prefs = getSharedPreferences("user_prefs", Context.MODE_PRIVATE)
        return prefs.getString("auth_token", "") ?: ""
    }

    private fun evaluateAndMaybeSend() {
        val prefs = getSharedPreferences("user_prefs", Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val lastInteraction = prefs.getLong("last_interaction", 0L)
        val lastMovement = prefs.getLong("last_movement", 0L)
        val latitude = prefs.getString("last_latitude", "?")
        val longitude = prefs.getString("last_longitude", "?")
        val userName = prefs.getString("user_name", "") ?: ""

        val interactionStr = if (now - lastInteraction <= 60 * 60 * 1000L) "S" else "N"
        val movementStr = if (now - lastMovement <= 60 * 60 * 1000L) "S" else "N"
        val locationStr = if (latitude != null && longitude != null && latitude != "?" && longitude != "?") {
            "https://maps.google.com/?q=$latitude,$longitude"
        } else {
            "N/A"
        }

        // Verificar se está carregando
        val intentFilter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        val batteryStatus = registerReceiver(null, intentFilter)
        val status = batteryStatus?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        val carregandoStr = if (isCharging) "S" else "N"

        val contact1 = prefs.getString("contact1", "") ?: ""
        val contact2 = prefs.getString("contact2", "") ?: ""
        val contact3 = prefs.getString("contact3", "") ?: ""

        // Avaliar a janela de 2 horas sem interação e sem movimento
        val twoHoursMs = 2 * 60 * 60 * 1000L
        val noInteraction2h = now - lastInteraction > twoHoursMs
        val noMovement2h = now - lastMovement > twoHoursMs

        if (!(noInteraction2h && noMovement2h)) {
            Log.d("SOSAVC", "Critério não atendido (2h sem interação e movimento). Não enviar.")
            return
        }

        val json = JSONObject()
        // Enviar apenas nome, contatos e localização
        json.put("user_name", userName)
        json.put("contact1", contact1)
        json.put("contact2", contact2)
        json.put("contact3", contact3)
        // Usar chave "location" (backend aceitará location/localizacao)
        json.put("location", locationStr)

        val requestBody = json.toString().toRequestBody("application/json".toMediaType())
        val serverUrl = getString(R.string.server_url) ?: "https://seu-backend.onrender.com"
        val request = Request.Builder()
            .url("$serverUrl/api/receber-dados")
            .post(requestBody)
            .addHeader("Authorization", "Bearer ${getAuthToken()}")
            .build()

        val client = OkHttpClient()
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e("SOSAVC", "Erro ao enviar dados: ${e.message}")
            }
            override fun onResponse(call: Call, response: Response) {
                Log.d("SOSAVC", "Resposta do servidor: ${response.code}")
            }
        })
    }
} 