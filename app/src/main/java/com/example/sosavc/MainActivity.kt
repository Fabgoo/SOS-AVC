package com.example.sosavc

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.sosavc.ui.theme.SOSAVCTheme
import android.content.Intent
import android.os.Build
import android.Manifest
import androidx.core.content.ContextCompat
import androidx.core.app.ActivityCompat
import android.content.pm.PackageManager
import androidx.compose.foundation.shape.RoundedCornerShape
import android.util.Log
import androidx.compose.foundation.Image
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.Alignment
import androidx.compose.material3.TextButton
import java.util.Calendar
import androidx.compose.material3.AlertDialog
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import android.content.ClipData
import android.content.ClipboardManager
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.ui.text.style.TextAlign
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestPermissions()
        setContent {
            SOSAVCTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = androidx.compose.ui.graphics.Color.White) {
                    UserAndContactsScreen(this)
                }
            }
        }
    }

    private fun requestPermissions() {
        Log.d("SOSAVC", "Solicitando permissões...")
        val permissions = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        val notGranted = permissions.filter {
            val granted = ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
            Log.d("SOSAVC", "Permissão $it: "+ if (granted) "CONCEDIDA" else "NEGADA")
            !granted
        }
        if (notGranted.isNotEmpty()) {
            Log.d("SOSAVC", "Solicitando permissões: $notGranted")
            ActivityCompat.requestPermissions(this, notGranted.toTypedArray(), 100)
        } else {
            Log.d("SOSAVC", "Todas as permissões já foram concedidas")
        }
    }
}

@Composable
fun UserAndContactsScreen(context: Context) {
    val prefs = context.getSharedPreferences("user_prefs", Context.MODE_PRIVATE)
    var userName by remember { mutableStateOf(prefs.getString("user_name", "") ?: "") }
    var contact1 by remember { mutableStateOf(prefs.getString("contact1", "") ?: "") }
    var contact2 by remember { mutableStateOf(prefs.getString("contact2", "") ?: "") }
    var contact3 by remember { mutableStateOf(prefs.getString("contact3", "") ?: "") }
    var sleepStart by remember { mutableStateOf(prefs.getString("sleep_start", "22:00") ?: "22:00") }
    var sleepEnd by remember { mutableStateOf(prefs.getString("sleep_end", "06:00") ?: "06:00") }
    var saved by remember { mutableStateOf(false) }
    var showDonate by remember { mutableStateOf(false) }
    var showHowItWorks by remember { mutableStateOf(false) }
    val activity = context as? ComponentActivity
    val calendar = Calendar.getInstance()

    fun startDataCollectorService() {
        val intent = Intent(context, DataCollectorService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ContextCompat.startForegroundService(context, intent)
        } else {
            context.startService(intent)
        }
    }

    fun authenticateWithServer() {
        val prefs = context.getSharedPreferences("user_prefs", Context.MODE_PRIVATE)
        val deviceId = prefs.getString("device_id", "") ?: ""
        val userName = prefs.getString("user_name", "") ?: ""
        
        if (deviceId.isEmpty()) {
            val newDeviceId = java.util.UUID.randomUUID().toString()
            prefs.edit().putString("device_id", newDeviceId).apply()
        }
        
        val serverUrl = context.getString(R.string.server_url)
        val json = org.json.JSONObject()
        json.put("deviceId", deviceId.ifEmpty { prefs.getString("device_id", "") })
        json.put("userName", userName)
        
        val requestBody = json.toString().toByteArray()
        val request = okhttp3.Request.Builder()
            .url("$serverUrl/api/auth/login")
            .post(requestBody.toRequestBody("application/json".toMediaType()))
            .build()
        
        val client = okhttp3.OkHttpClient()
        client.newCall(request).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {
                android.util.Log.e("SOSAVC", "Erro na autenticação: ${e.message}")
            }
            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                if (response.isSuccessful) {
                    val responseBody = response.body?.string()
                    val jsonResponse = org.json.JSONObject(responseBody ?: "{}")
                    val token = jsonResponse.optString("token", "")
                    if (token.isNotEmpty()) {
                        prefs.edit().putString("auth_token", token).apply()
                        android.util.Log.d("SOSAVC", "Autenticação realizada com sucesso")
                    }
                } else {
                    android.util.Log.e("SOSAVC", "Erro na autenticação: ${response.code}")
                }
            }
        })
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.Top
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Image(
                painter = painterResource(id = R.drawable.logo),
                contentDescription = "Logo SOSAVC",
                modifier = Modifier.size(140.dp)
            )
            Column(horizontalAlignment = Alignment.End) {
                if (showHowItWorks) {
                    AlertDialog(
                        onDismissRequest = { showHowItWorks = false },
                        containerColor = androidx.compose.ui.graphics.Color(0xFF0caf0e),
                        title = {
                            Text(
                                "Como funciona?",
                                fontWeight = FontWeight.Bold,
                                fontSize = 22.sp
                            )
                        },
                        text = {
                            val scrollState = rememberScrollState()
                            val context = LocalContext.current
                            Column(
                                modifier = Modifier
                                    .fillMaxHeight(0.7f)
                                    .verticalScroll(scrollState)
                            ) {
                                Text(
                                    "Este aplicativo foi pensado e programado para coletar e enviar dados, \na fim de tentar diminuir o tempo de socorro em casos de AVC ou qualquer outro mal súbito que possa acontecer. \n\n" +
                                    "Nestes casos, geralmente a pessoa fica sem poder se movimentar, mesmo que consciente por muitas horas e o tempo é crucial. \n\n" +
                                    "O funcionamento é simples:\n\n" +
                                    "\tPeriodicamente o aplicativo envia uma mensagem ao servidor contento as seguintes informações:\n\n" +
                                    "\t- Se houve ou não interação do usuário com a tela do smartphone.\n" +
                                    "\t- Se houve ou não movimentação do smartphone, mesmo que pouco.\n" +
                                    "\t- A ultima localização.\n" +
                                    "\t- Os Dados que forneceu.\n\n" +
                                    "Baseado nestes dados, nossos servidores vão analisar e identificar possíveis casos de AVC ou outro mal súbito. \n\n" +
                                    "Se caso identificado, será enviado imediatamente, mensagens via WhatsApp para os contatos salvos na tela principal.\n(Adicione pelo menos 1)\n\n" +
                                    "Assim, as pessoas poderão tomar as devidas providências.\n\n" +
                                    "O tempo de socorro é crucial para diminuir os danos e sequelas. \nEntão, tentaremos diminuir ao máximo este tempo. \n\n" +
                                    "O app funciona em segundo plano e não atrapalha em nada o funcionamento do aparelho ou qualquer outro aplicativo ou serviço. \n\n" +
                                    "Requisitos atuais:\n- Conexão com internet.\n- Android 12s em diante.\n- Usar o celular com frequência.\n\n" +
                                    "Este app foi criado pensando em pessoas que geralmente moram só, ou que tenha uma idade mais elevada, ou que passam muito tempo sozinhas e que também usam o celular com frequência durante seu dia.\nPorém, qualquer pessoa de qualquer idade também pode instalar e usar.\n\n" +
                                    "Depois de salvar os dados necessários é só fechar o aplicativo e usar o aparelho normalmente. \n\n" +
                                    "\nContribua com esta ideia.\nConsidere doar quantas vezes quiser, qualquer valor... \nAssim poderemos melhorar mais e mais nosso aplicativo \n e ajudar pessoas pelo mundo...",
                                    fontSize = 18.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = androidx.compose.ui.graphics.Color.Black
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = "Contato: contato.sos.avc@gmail.com",
                                    fontSize = 18.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = androidx.compose.ui.graphics.Color.White,
                                    modifier = Modifier.clickable {
                                        val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as ClipboardManager
                                        val clip = ClipData.newPlainText("E-mail", "contato.sos.avc@gmail.com")
                                        clipboard.setPrimaryClip(clip)
                                        Toast.makeText(context, "E-mail copiado!", Toast.LENGTH_SHORT).show()
                                    }
                                )
                            }
                        },
                        confirmButton = {
                            Button(
                                onClick = { showHowItWorks = false },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = androidx.compose.ui.graphics.Color.White,
                                    contentColor = androidx.compose.ui.graphics.Color(0xFF0caf0e)
                                )
                            ) {
                                Text("Fechar")
                            }
                        }
                    )
                }
                if (showDonate) {
                    val context = LocalContext.current
                    AlertDialog(
                        onDismissRequest = { showDonate = false },
                        containerColor = androidx.compose.ui.graphics.Color(0xFF0caf0e),
                        title = { Text("Doar", fontWeight = FontWeight.Bold) },
                        text = {
                            Column {
                                Text(
                                    "Contribua com esta ideia.\nConsidere doar quantas vezes quiser, qualquer valor... \nAssim poderemos melhorar mais e mais nosso aplicativo \n e ajudar pessoas pelo mundo...",
                                    fontSize = 14.sp,
                                    color = androidx.compose.ui.graphics.Color.Black,
                                    fontWeight = FontWeight.Bold
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "PIX - contato.sos.avc@gmail.com",
                                    fontSize = 18.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = androidx.compose.ui.graphics.Color.White,
                                    modifier = Modifier.clickable {
                                        val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as ClipboardManager
                                        val clip = ClipData.newPlainText("PIX", "contato.sos.avc@gmail.com")
                                        clipboard.setPrimaryClip(clip)
                                        Toast.makeText(context, "PIX copiado!", Toast.LENGTH_SHORT).show()
                                    }
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = "Toque para copiar.",
                                    fontSize = 12.sp,
                                    color = androidx.compose.ui.graphics.Color.Black,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        },
                        confirmButton = {
                            Button(
                                onClick = { showDonate = false },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = androidx.compose.ui.graphics.Color.White,
                                    contentColor = androidx.compose.ui.graphics.Color(0xFF0caf0e)
                                )
                            ) {
                                Text("Fechar")
                            }
                        }
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(16.dp))
        Text(text = "Seu nome", style = MaterialTheme.typography.titleMedium)
        Spacer(modifier = Modifier.height(4.dp))
        OutlinedTextField(
            value = userName,
            onValueChange = { if (it.length <= 20) userName = it },
            modifier = Modifier.fillMaxWidth(0.7f),
            singleLine = true,
            shape = RoundedCornerShape(16.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = androidx.compose.ui.graphics.Color(0xFF0caf0e),
                unfocusedBorderColor = androidx.compose.ui.graphics.Color(0xFF0caf0e),
                cursorColor = androidx.compose.ui.graphics.Color(0xFF0caf0e)
            )
        )
        Spacer(modifier = Modifier.height(8.dp))
        // --- Contato 1 com botão Como funciona ---
        Text(text = "Contato 1", style = MaterialTheme.typography.titleMedium)
        Spacer(modifier = Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            OutlinedTextField(
                value = contact1,
                onValueChange = { if (it.length <= 20) contact1 = it },
                modifier = Modifier.fillMaxWidth(0.5f),
                singleLine = true,
                shape = RoundedCornerShape(16.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = androidx.compose.ui.graphics.Color(0xFF0caf0e),
                    unfocusedBorderColor = androidx.compose.ui.graphics.Color(0xFF0caf0e),
                    cursorColor = androidx.compose.ui.graphics.Color(0xFF0caf0e)
                )
            )
            Spacer(modifier = Modifier.weight(1f))
            Button(
                onClick = { showHowItWorks = true },
                modifier = Modifier.height(36.dp),
                colors = ButtonDefaults.buttonColors(containerColor = androidx.compose.ui.graphics.Color(0xFF0caf0e))
            ) {
                Text("Como funciona?", fontSize = 12.sp)
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        // --- Contato 2 com botão Doar ---
        Text(text = "Contato 2", style = MaterialTheme.typography.titleMedium)
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            OutlinedTextField(
                value = contact2,
                onValueChange = { if (it.length <= 20) contact2 = it },
                modifier = Modifier.fillMaxWidth(0.5f),
                singleLine = true,
                shape = RoundedCornerShape(16.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = androidx.compose.ui.graphics.Color(0xFF0caf0e),
                    unfocusedBorderColor = androidx.compose.ui.graphics.Color(0xFF0caf0e),
                    cursorColor = androidx.compose.ui.graphics.Color(0xFF0caf0e)
                )
            )
            Spacer(modifier = Modifier.weight(1f))
            Button(
                onClick = { showDonate = true },
                modifier = Modifier.height(36.dp),
                colors = ButtonDefaults.buttonColors(containerColor = androidx.compose.ui.graphics.Color(0xFF0caf0e))
            ) {
                Text("Doar", fontSize = 12.sp)
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        // --- Contato 3 com botão Compartilhar App (já existente) ---
        Text(text = "Contato 3", style = MaterialTheme.typography.titleMedium)
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            OutlinedTextField(
                value = contact3,
                onValueChange = { if (it.length <= 20) contact3 = it },
                modifier = Modifier.fillMaxWidth(0.5f),
                singleLine = true,
                shape = RoundedCornerShape(16.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = androidx.compose.ui.graphics.Color(0xFF0caf0e),
                    unfocusedBorderColor = androidx.compose.ui.graphics.Color(0xFF0caf0e),
                    cursorColor = androidx.compose.ui.graphics.Color(0xFF0caf0e)
                )
            )
            Spacer(modifier = Modifier.weight(1f))
            Button(
                onClick = {
                    val sendIntent = Intent().apply {
                        action = Intent.ACTION_SEND
                        putExtra(Intent.EXTRA_TEXT, "Conheça o aplicativo SOS AVC! Ele pode salvar vidas em caso de emergência. Baixe agora na Play Store ou entre em contato para saber mais.")
                        type = "text/plain"
                    }
                    val shareIntent = Intent.createChooser(sendIntent, null)
                    context.startActivity(shareIntent)
                },
                modifier = Modifier.height(36.dp),
                colors = ButtonDefaults.buttonColors(containerColor = androidx.compose.ui.graphics.Color(0xFF0caf0e))
            ) {
                Text("Compartilhar App.", fontSize = 12.sp)
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        Spacer(modifier = Modifier.height(8.dp))
        Button(
            onClick = {
                val prefs = context.getSharedPreferences("user_prefs", Context.MODE_PRIVATE)
                prefs.edit()
                    .putString("user_name", userName)
                    .putString("contact1", contact1)
                    .putString("contact2", contact2)
                    .putString("contact3", contact3)
                    .apply()
                saved = true
                authenticateWithServer()
                startDataCollectorService()
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = userName.isNotBlank() && (contact1.isNotBlank() || contact2.isNotBlank() || contact3.isNotBlank()),
            colors = ButtonDefaults.buttonColors(containerColor = androidx.compose.ui.graphics.Color(0xFF0caf0e))
        ) {
            Text("Salvar")
        }
        if (saved) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                "Dados salvos com sucesso!",
                color = androidx.compose.ui.graphics.Color(0xFF0caf0e)
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                "Agora feche o app e use o aparelho normalmente.",
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = androidx.compose.ui.graphics.Color.Black
            )
        }
    }
}