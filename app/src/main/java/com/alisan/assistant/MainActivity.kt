package com.alisan.assistant

import android.Manifest
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.NoteAdd
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

private val Navy = Color(0xFF071426)
private val Panel = Color(0xFF10243D)
private val Blue = Color(0xFF29B6F6)
private val Cyan = Color(0xFF7CE7FF)
private val WhiteBlue = Color(0xFFEAF7FF)

class MainActivity : ComponentActivity() {
    private var tts: TextToSpeech? = null
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) tts?.language = Locale("fa", "IR")
        }
        setContent { AlisanApp() }
    }
    override fun onDestroy() { tts?.stop(); tts?.shutdown(); super.onDestroy() }
    fun speak(text: String) { tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "alisan_reply") }
}

data class ChatLine(val text: String, val isUser: Boolean, val link: String? = null)

@Composable
fun AlisanApp() {
    val context = LocalContext.current
    val activity = context as MainActivity
    val prefs = remember { context.getSharedPreferences("alisan", Context.MODE_PRIVATE) }
    var noteText by remember { mutableStateOf("") }
    var notes by remember { mutableStateOf(prefs.getStringSet("notes", emptySet())?.toList()?.reversed() ?: emptyList()) }
    var message by remember { mutableStateOf("") }
    var alarmTitle by remember { mutableStateOf("یادآوری من") }
    var alarmTime by remember { mutableStateOf("08:00") }
    var showAlarm by remember { mutableStateOf(false) }
    var listening by remember { mutableStateOf(false) }
    var chat by remember { mutableStateOf(listOf(ChatLine("سلام! من علیسانم 💙 با تایپ یا زدن دکمه میکروفون باهام حرف بزن. می‌تونم برات جست‌وجو کنم، نوت نگه دارم و یادآوری بسازم.", false))) }
    var speechRecognizer by remember { mutableStateOf<SpeechRecognizer?>(null) }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startListening(context, { listening = it }, { result -> message = result; listening = false }, { listening = false })
    }
    val infinite = rememberInfiniteTransition(label = "orb")
    val pulse by infinite.animateFloat(initialValue = 0.94f, targetValue = 1.08f, animationSpec = infiniteRepeatable(tween(950), RepeatMode.Reverse), label = "pulse")

    MaterialTheme(colorScheme = darkColorScheme(primary = Blue, background = Navy, surface = Panel, onSurface = WhiteBlue)) {
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF06101F), Navy, Color(0xFF0B2440))))) {
            Column(Modifier.fillMaxSize().padding(horizontal = 18.dp, vertical = 14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.weight(1f)) {
                        Text("علیسان", fontSize = 30.sp, fontWeight = FontWeight.Black, color = WhiteBlue)
                        Text("دستیار هوشمند فارسی", fontSize = 13.sp, color = Cyan)
                    }
                    Text("● آنلاین", color = Color(0xFF69E6B1), fontSize = 12.sp)
                }
                Spacer(Modifier.height(10.dp))
                Box(Modifier.fillMaxWidth().height(150.dp), contentAlignment = Alignment.Center) {
                    Box(Modifier.size(120.dp).scale(pulse).background(Brush.radialGradient(listOf(Color(0xFF1767A7), Color(0xFF103050), Color.Transparent)), CircleShape), contentAlignment = Alignment.Center) {
                        Text("✨", fontSize = 58.sp)
                    }
                    Text(if (listening) "دارم گوش می‌دم…" else "بگو علیسان!", color = Cyan, modifier = Modifier.align(Alignment.BottomCenter), fontSize = 14.sp)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    QuickAction("📝 نوت", Modifier.weight(1f)) { noteText = ""; chat = chat + ChatLine("برای ذخیره نوت، متنش رو در بخش نوت‌ها بنویس.", false) }
                    QuickAction("⏰ آلارم", Modifier.weight(1f)) { showAlarm = true }
                    QuickAction("🔎 جست‌وجو", Modifier.weight(1f)) { message = "طرز تهیه "; }
                }
                Spacer(Modifier.height(12.dp))
                Text("گفت‌وگو", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = WhiteBlue)
                LazyColumn(Modifier.weight(1f).fillMaxWidth().padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(chat) { line ->
                        Column(Modifier.fillMaxWidth(), horizontalAlignment = if (line.isUser) Alignment.End else Alignment.Start) {
                            Surface(color = if (line.isUser) Color(0xFF075985) else Panel, shape = RoundedCornerShape(16.dp)) {
                                Column(Modifier.padding(12.dp).widthIn(max = 310.dp)) {
                                    Text(line.text, color = WhiteBlue, fontSize = 14.sp)
                                    if (line.link != null) {
                                        Spacer(Modifier.height(5.dp))
                                        Text("باز کردن لینک 🔗", color = Cyan, modifier = Modifier.clickable { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(line.link))) } })
                                    }
                                }
                            }
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    IconButton(onClick = {
                        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                            startListening(context, { listening = it }, { message = it; listening = false }, { listening = false })
                        } else permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    }) { Icon(Icons.Default.Mic, contentDescription = "میکروفون", tint = if (listening) Cyan else Blue) }
                    Surface(Modifier.weight(1f), color = Panel, shape = RoundedCornerShape(24.dp)) {
                        BasicTextField(value = message, onValueChange = { message = it }, modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 13.dp), textStyle = androidx.compose.ui.text.TextStyle(color = WhiteBlue, fontSize = 14.sp), decorationBox = { inner -> if (message.isEmpty()) Text("با علیسان صحبت کن…", color = Color.LightGray); inner() })
                    }
                    IconButton(onClick = {
                        val q = message.trim()
                        if (q.isNotEmpty()) {
                            chat = chat + ChatLine(q, true)
                            val reply = makeReply(q)
                            chat = chat + reply
                            if (reply.link == null) activity.speak(reply.text)
                            else activity.speak("برات لینک جست‌وجو رو آماده کردم")
                            message = ""
                        }
                    }) { Icon(Icons.Default.Send, contentDescription = "ارسال", tint = Cyan) }
                }
                Spacer(Modifier.height(10.dp))
                Surface(color = Color(0x9910243D), shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.NoteAdd, null, tint = Cyan)
                            Spacer(Modifier.width(7.dp))
                            Text("نوت‌های من", color = WhiteBlue, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.weight(1f))
                            Text("${notes.size} نوت", color = Cyan, fontSize = 12.sp)
                        }
                        Spacer(Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            BasicTextField(value = noteText, onValueChange = { noteText = it }, modifier = Modifier.weight(1f).background(Color(0xFF07182B), RoundedCornerShape(10.dp)).padding(10.dp), textStyle = androidx.compose.ui.text.TextStyle(color = WhiteBlue, fontSize = 13.sp), decorationBox = { inner -> if (noteText.isEmpty()) Text("یه چیزی یادداشت کن…", color = Color.Gray); inner() })
                            Text(" ذخیره ", color = Cyan, modifier = Modifier.clickable {
                                if (noteText.isNotBlank()) {
                                    notes = listOf(noteText.trim()) + notes
                                    prefs.edit().putStringSet("notes", notes.toSet()).apply()
                                    chat = chat + ChatLine("نوتت ذخیره شد ✅", false)
                                    activity.speak("نوتت ذخیره شد")
                                    noteText = ""
                                }
                            }.padding(8.dp), fontWeight = FontWeight.Bold)
                        }
                        if (notes.isNotEmpty()) Text(notes.first().take(90) + if (notes.first().length > 90) "…" else "", color = Color.LightGray, fontSize = 12.sp, modifier = Modifier.padding(top = 7.dp))
                    }
                }
            }
        }
        if (showAlarm) {
            AlertDialog(onDismissRequest = { showAlarm = false }, containerColor = Panel,
                title = { Text("تنظیم یادآوری ⏰", color = WhiteBlue) },
                text = { Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(value = alarmTitle, onValueChange = { alarmTitle = it }, label = { Text("عنوان یادآوری") }, singleLine = true)
                    OutlinedTextField(value = alarmTime, onValueChange = { alarmTime = it }, label = { Text("ساعت (مثلاً 08:30)") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii))
                    Text("یادآوری برای نزدیک‌ترین زمان آینده تنظیم می‌شود.", color = Color.LightGray, fontSize = 12.sp)
                } },
                confirmButton = { TextButton(onClick = {
                    if (alarmTime.matches(Regex("^([01]?[0-9]|2[0-3]):[0-5][0-9]$"))) {
                        scheduleAlarm(context, alarmTitle.ifBlank { "یادآوری علیسان" }, alarmTime)
                        chat = chat + ChatLine("یادآوری «${alarmTitle.ifBlank { "یادآوری علیسان" }}» برای ساعت $alarmTime تنظیم شد. اگر اندروید اجازه بده، اعلان سر ساعت نمایش داده می‌شه.", false)
                        showAlarm = false
                    } else { chat = chat + ChatLine("ساعت رو به شکل 08:30 وارد کن.", false); showAlarm = false }
                }) { Text("تنظیم", color = Cyan) } },
                dismissButton = { TextButton(onClick = { showAlarm = false }) { Text("لغو", color = Color.LightGray) } }
            )
        }
    }
}

@Composable
private fun QuickAction(label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Surface(modifier.clickable(onClick = onClick), color = Panel, shape = RoundedCornerShape(14.dp)) {
        Text(label, color = WhiteBlue, modifier = Modifier.padding(horizontal = 8.dp, vertical = 12.dp), fontSize = 12.sp)
    }
}

private fun makeReply(query: String): ChatLine {
    val q = query.lowercase(Locale.ROOT)
    val searchTerms = listOf("طرز تهیه", "چی بپزم", "جستجو", "جست‌وجو", "لینک", "سایت", "درباره", "اخبار", "قیمت")
    return when {
        q.contains("سلام") || q.contains("درود") -> ChatLine("سلام! 💙 چه کاری از دستم برمیاد؟", false)
        q.contains("اسمت") || q.contains("اسم تو") -> ChatLine("من علیسانم؛ دستیار فارسی تو! ✨", false)
        q.contains("ساعت") -> ChatLine("برای ساعت دقیق، ساعت گوشی‌ات رو ببین؛ بعداً قابلیت اعلام ساعت دقیق رو هم اضافه می‌کنیم.", false)
        q.contains("یادداشت") || q.contains("نوت") -> ChatLine("متن نوتت رو در بخش نوت‌های پایین صفحه بنویس و روی ذخیره بزن. 📝", false)
        q.contains("آلارم") || q.contains("یادآور") -> ChatLine("برای تنظیم آلارم، دکمه آلارم بالای صفحه رو بزن و ساعت رو مثل 08:30 وارد کن. ⏰", false)
        searchTerms.any { q.contains(it) } -> {
            val encoded = URLEncoder.encode(query, "UTF-8")
            ChatLine("این لینک جست‌وجوی وب برای «$query» است. نتیجه‌ها و سایت‌های مرتبط رو می‌تونی باز کنی. 🔎", false, "https://www.google.com/search?q=$encoded")
        }
        else -> ChatLine("حرفت رو گرفتم 💙 من فعلاً نسخه‌ی اولیه‌ام و برای جواب‌های دقیق‌تر می‌تونم موضوعت رو در وب جست‌وجو کنم. مثلاً بگو: طرز تهیه پیتزا یا سایت‌های آموزش زبان.", false)
    }
}

private fun startListening(context: Context, onListening: (Boolean) -> Unit, onResult: (String) -> Unit, onFailure: () -> Unit) {
    if (!SpeechRecognizer.isRecognitionAvailable(context)) { onResult("تشخیص گفتار روی این دستگاه در دسترس نیست."); onFailure(); return }
    val recognizer = SpeechRecognizer.createSpeechRecognizer(context)
    val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, "fa-IR")
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "fa-IR")
        putExtra(RecognizerIntent.EXTRA_PROMPT, "با علیسان فارسی صحبت کن")
    }
    recognizer.setRecognitionListener(object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) { onListening(true) }
        override fun onBeginningOfSpeech() { onListening(true) }
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() { onListening(false) }
        override fun onError(error: Int) { onListening(false); onFailure(); recognizer.destroy() }
        override fun onResults(results: Bundle?) {
            val words = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
            if (words != null) onResult(words) else onFailure()
            onListening(false); recognizer.destroy()
        }
        override fun onPartialResults(partialResults: Bundle?) {}
        override fun onEvent(eventType: Int, params: Bundle?) {}
    })
    try { recognizer.startListening(intent) } catch (_: Exception) { onFailure(); recognizer.destroy() }
}

private fun scheduleAlarm(context: Context, title: String, time: String) {
    val parts = time.split(":")
    val calendar = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, parts[0].toInt())
        set(Calendar.MINUTE, parts[1].toInt())
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
        if (timeInMillis <= System.currentTimeMillis()) add(Calendar.DAY_OF_YEAR, 1)
    }
    val intent = Intent(context, AlarmReceiver::class.java).putExtra("title", title)
    val pending = PendingIntent.getBroadcast(context, (System.currentTimeMillis() % Int.MAX_VALUE).toInt(), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    val manager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !manager.canScheduleExactAlarms()) {
        manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, calendar.timeInMillis, pending)
    } else {
        manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, calendar.timeInMillis, pending)
    }
}
