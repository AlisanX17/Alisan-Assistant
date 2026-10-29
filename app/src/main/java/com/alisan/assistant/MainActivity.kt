package com.alisan.assistant

import android.Manifest
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
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
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.NoteAlt
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Stop
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import java.net.URLEncoder
import java.util.Calendar
import java.util.Locale

private val Bg = Color(0xFF080B12)
private val SurfaceDark = Color(0xFF111722)
private val SurfaceRaised = Color(0xFF192332)
private val Blue = Color(0xFF3B82F6)
private val Cyan = Color(0xFF67E8F9)
private val TextMain = Color(0xFFF1F5F9)
private val TextMuted = Color(0xFF94A3B8)

class MainActivity : ComponentActivity() {
    private var tts: TextToSpeech? = null
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val result = tts?.setLanguage(Locale("fa", "IR"))
                if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                    tts?.language = Locale("fa")
                }
            }
        }
        setContent { AlisanAiApp() }
    }
    override fun onDestroy() { tts?.stop(); tts?.shutdown(); super.onDestroy() }
    fun speak(text: String) { tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "alisan_ai_reply") }
}

data class ChatLine(val text: String, val isUser: Boolean, val link: String? = null)

@Composable
fun AlisanAiApp() {
    val context = LocalContext.current
    val activity = context as MainActivity
    val prefs = remember { context.getSharedPreferences("alisan_ai", Context.MODE_PRIVATE) }
    var currentTab by remember { mutableStateOf("chat") }
    var message by remember { mutableStateOf("") }
    var noteDraft by remember { mutableStateOf("") }
    var notes by remember { mutableStateOf(prefs.getStringSet("notes", emptySet())?.toList()?.sortedDescending() ?: emptyList()) }
    var alarmTitle by remember { mutableStateOf("یادآوری من") }
    var alarmTime by remember { mutableStateOf("08:00") }
    var showAlarm by remember { mutableStateOf(false) }
    var listening by remember { mutableStateOf(false) }
    var speechError by remember { mutableStateOf("") }
    var chat by remember { mutableStateOf(listOf(ChatLine("سلام! من Alisan AI هستم 💙 هر چیزی خواستی بنویس یا روی میکروفون بزن. می‌تونم کمکت کنم جست‌وجو کنی، یادداشت بسازی و یادآوری تنظیم کنی.", false))) }

    fun sendMessage(raw: String) {
        val q = raw.trim()
        if (q.isBlank()) return
        chat = chat + ChatLine(q, true)
        val reply = makeReply(q)
        chat = chat + reply
        if (reply.link == null) activity.speak(reply.text) else activity.speak("لینک جست‌وجو رو برات آماده کردم")
        message = ""
        currentTab = "chat"
    }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            speechError = ""
            startListening(context, { listening = it }, { result -> listening = false; sendMessage(result) }, { listening = false; speechError = "صدای شما تشخیص داده نشد. دوباره امتحان کن یا متن رو تایپ کن." })
        } else speechError = "برای استفاده از دستیار صوتی، اجازه میکروفون لازمه."
    }
    val transition = rememberInfiniteTransition(label = "assistantGlow")
    val pulse by transition.animateFloat(0.94f, 1.06f, infiniteRepeatable(tween(1300), RepeatMode.Reverse), label = "pulse")

    MaterialTheme(colorScheme = darkColorScheme(primary = Blue, secondary = Cyan, background = Bg, surface = SurfaceDark, onSurface = TextMain)) {
        Column(Modifier.fillMaxSize().background(Bg)) {
            TopHeader(currentTab = currentTab, onTab = { currentTab = it }, onAlarm = { showAlarm = true })
            when (currentTab) {
                "notes" -> NotesScreen(notes = notes, draft = noteDraft, onDraft = { noteDraft = it }, onAdd = {
                    if (noteDraft.isNotBlank()) {
                        val newNote = "${System.currentTimeMillis()}|${noteDraft.trim()}"
                        notes = listOf(newNote) + notes
                        prefs.edit().putStringSet("notes", notes.toSet()).apply()
                        noteDraft = ""
                        activity.speak("یادداشت ذخیره شد")
                    }
                }, onDelete = { note -> notes = notes.filterNot { it == note }; prefs.edit().putStringSet("notes", notes.toSet()).apply() })
                else -> ChatScreen(
                    chat = chat, message = message, onMessage = { message = it }, listening = listening,
                    speechError = speechError, pulse = pulse,
                    onSend = { sendMessage(message) },
                    onMic = {
                        if (listening) { listening = false }
                        else if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                            speechError = ""
                            startListening(context, { listening = it }, { result -> listening = false; sendMessage(result) }, { listening = false; speechError = "تشخیص صدا ناموفق بود؛ اتصال اینترنت و زبان فارسی سرویس گفتار گوشی رو بررسی کن." })
                        } else permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    },
                    onQuick = { message = it },
                    onOpenLink = { url -> runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } }
                )
            }
        }
        if (showAlarm) {
            AlertDialog(onDismissRequest = { showAlarm = false }, containerColor = SurfaceRaised,
                title = { Text("یادآوری جدید", color = TextMain, fontWeight = FontWeight.Bold) },
                text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(value = alarmTitle, onValueChange = { alarmTitle = it }, label = { Text("عنوان") }, singleLine = true)
                    OutlinedTextField(value = alarmTime, onValueChange = { alarmTime = it }, label = { Text("ساعت (مثلاً 08:30)") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii))
                    Text("یادآوری برای نزدیک‌ترین زمان آینده تنظیم می‌شه.", color = TextMuted, fontSize = 12.sp)
                } },
                confirmButton = { TextButton(onClick = {
                    if (alarmTime.matches(Regex("^([01]?[0-9]|2[0-3]):[0-5][0-9]$"))) {
                        scheduleAlarm(context, alarmTitle.ifBlank { "یادآوری Alisan AI" }, alarmTime)
                        chat = chat + ChatLine("یادآوری «${alarmTitle.ifBlank { "Alisan AI" }}» برای ساعت $alarmTime تنظیم شد. ⏰", false)
                        currentTab = "chat"
                        showAlarm = false
                    } else { chat = chat + ChatLine("لطفاً ساعت رو مثل 08:30 وارد کن.", false); currentTab = "chat"; showAlarm = false }
                }) { Text("تنظیم یادآوری", color = Cyan) } },
                dismissButton = { TextButton(onClick = { showAlarm = false }) { Text("لغو", color = TextMuted) } }
            )
        }
    }
}

@Composable
private fun TopHeader(currentTab: String, onTab: (String) -> Unit, onAlarm: () -> Unit) {
    Column(Modifier.fillMaxWidth().background(Bg).padding(horizontal = 18.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(42.dp).background(Brush.linearGradient(listOf(Blue, Color(0xFF6D5DFB))), RoundedCornerShape(15.dp)), contentAlignment = Alignment.Center) {
                Icon(Icons.Default.AutoAwesome, contentDescription = null, tint = Color.White, modifier = Modifier.size(24.dp))
            }
            Spacer(Modifier.width(11.dp))
            Column(Modifier.weight(1f)) {
                Text("Alisan AI", fontSize = 21.sp, fontWeight = FontWeight.ExtraBold, color = TextMain)
                Text("دستیار فارسی تو", fontSize = 12.sp, color = TextMuted)
            }
            IconButton(onClick = onAlarm, modifier = Modifier.size(42.dp).background(SurfaceDark, CircleShape)) {
                Icon(Icons.Default.Alarm, contentDescription = "یادآوری", tint = Cyan)
            }
        }
        Spacer(Modifier.height(14.dp))
        Row(Modifier.fillMaxWidth().background(SurfaceDark, RoundedCornerShape(14.dp)).padding(4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TabPill("گفت‌وگو", currentTab == "chat", Modifier.weight(1f)) { onTab("chat") }
            TabPill("یادداشت‌ها", currentTab == "notes", Modifier.weight(1f)) { onTab("notes") }
        }
    }
}

@Composable
private fun TabPill(label: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(modifier.background(if (selected) SurfaceRaised else Color.Transparent, RoundedCornerShape(11.dp)).clickable(onClick = onClick).padding(vertical = 10.dp), contentAlignment = Alignment.Center) {
        Text(label, color = if (selected) TextMain else TextMuted, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal, fontSize = 13.sp)
    }
}

@Composable
private fun ChatScreen(
    chat: List<ChatLine>, message: String, onMessage: (String) -> Unit, listening: Boolean, speechError: String, pulse: Float,
    onSend: () -> Unit, onMic: () -> Unit, onQuick: (String) -> Unit, onOpenLink: (String) -> Unit
) {
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        if (chat.size <= 1) {
            Spacer(Modifier.height(14.dp))
            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                    Box(Modifier.size(180.dp).scale(pulse).background(Brush.radialGradient(listOf(Color(0x663B82F6), Color(0x223B82F6), Color.Transparent)), CircleShape).border(1.dp, Color(0x443B82F6), CircleShape), contentAlignment = Alignment.Center) {
                        Box(Modifier.size(112.dp).background(Brush.linearGradient(listOf(Color(0xFF2563EB), Color(0xFF6D5DFB), Color(0xFF0EA5E9))), CircleShape), contentAlignment = Alignment.Center) {
                            Icon(Icons.Default.AutoAwesome, contentDescription = null, tint = Color.White, modifier = Modifier.size(50.dp))
                        }
                    }
                    Spacer(Modifier.height(24.dp))
                    Text("از کجا شروع کنیم؟", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = TextMain, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(8.dp))
                    Text("هر سوالی داری بپرس یا با صدات باهام حرف بزن.", color = TextMuted, fontSize = 14.sp, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(24.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        SuggestionCard("طرز تهیه غذا", "یک دستور غذا پیدا کن", Modifier.weight(1f)) { onQuick("طرز تهیه پیتزا") }
                        SuggestionCard("ایده و برنامه", "کمک برای برنامه‌ریزی", Modifier.weight(1f)) { onQuick("برای امروز یک برنامه پیشنهاد بده") }
                    }
                    Spacer(Modifier.height(10.dp))
                    SuggestionCard("جست‌وجوی وب", "لینک سایت‌های مرتبط", Modifier.fillMaxWidth()) { onQuick("لینک سایت‌های آموزش زبان") }
                }
            }
        } else {
            LazyColumn(Modifier.weight(1f).fillMaxWidth().padding(top = 8.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                items(chat) { line ->
                    if (line.isUser) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            Box(Modifier.widthIn(max = 310.dp).background(SurfaceRaised, RoundedCornerShape(topStart = 18.dp, topEnd = 5.dp, bottomEnd = 18.dp, bottomStart = 18.dp)).padding(horizontal = 14.dp, vertical = 11.dp)) {
                                Text(line.text, color = TextMain, fontSize = 15.sp, lineHeight = 22.sp)
                            }
                        }
                    } else {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                            Box(Modifier.size(29.dp).background(Brush.linearGradient(listOf(Blue, Color(0xFF6D5DFB))), RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) {
                                Icon(Icons.Default.AutoAwesome, null, tint = Color.White, modifier = Modifier.size(17.dp))
                            }
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f).padding(top = 3.dp)) {
                                Text("Alisan AI", fontSize = 12.sp, color = Cyan, fontWeight = FontWeight.Bold)
                                Spacer(Modifier.height(5.dp))
                                Text(line.text, color = TextMain, fontSize = 15.sp, lineHeight = 23.sp)
                                if (line.link != null) {
                                    Spacer(Modifier.height(10.dp))
                                    Row(Modifier.fillMaxWidth().background(SurfaceDark, RoundedCornerShape(12.dp)).clickable { onOpenLink(line.link) }.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Default.Search, null, tint = Cyan)
                                        Spacer(Modifier.width(8.dp))
                                        Column(Modifier.weight(1f)) { Text("نتایج جست‌وجو", color = TextMain, fontWeight = FontWeight.SemiBold); Text("برای باز کردن لمس کن", color = TextMuted, fontSize = 12.sp) }
                                        Text("↗", color = Cyan, fontSize = 20.sp)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        AnimatedVisibility(visible = listening) {
            Text("در حال گوش دادن به صدای فارسی…", color = Cyan, fontSize = 13.sp, modifier = Modifier.fillMaxWidth().padding(bottom = 7.dp), textAlign = TextAlign.Center)
        }
        AnimatedVisibility(visible = speechError.isNotBlank()) {
            Text(speechError, color = Color(0xFFFCA5A5), fontSize = 12.sp, modifier = Modifier.fillMaxWidth().padding(bottom = 7.dp))
        }
        Row(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 14.dp).background(SurfaceDark, RoundedCornerShape(25.dp)).border(1.dp, Color(0xFF273448), RoundedCornerShape(25.dp)).padding(horizontal = 8.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onMic, modifier = Modifier.size(42.dp).background(if (listening) Color(0xFF7F1D1D) else SurfaceRaised, CircleShape)) {
                Icon(if (listening) Icons.Default.Stop else Icons.Default.Mic, contentDescription = "دستیار صوتی", tint = if (listening) Color(0xFFFCA5A5) else Cyan)
            }
            BasicTextField(value = message, onValueChange = onMessage, modifier = Modifier.weight(1f).padding(horizontal = 12.dp), textStyle = androidx.compose.ui.text.TextStyle(color = TextMain, fontSize = 15.sp), singleLine = false, maxLines = 4, decorationBox = { inner ->
                Box { if (message.isEmpty()) Text("پیامت رو بنویس…", color = TextMuted, fontSize = 14.sp); inner() }
            })
            IconButton(onClick = onSend, modifier = Modifier.size(42.dp).background(Brush.linearGradient(listOf(Blue, Color(0xFF6366F1))), CircleShape)) {
                Icon(Icons.Default.Send, contentDescription = "ارسال", tint = Color.White)
            }
        }
    }
}

@Composable
private fun SuggestionCard(title: String, subtitle: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Column(modifier.background(SurfaceDark, RoundedCornerShape(16.dp)).border(1.dp, Color(0xFF202C3D), RoundedCornerShape(16.dp)).clickable(onClick = onClick).padding(13.dp)) {
        Text(title, color = TextMain, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
        Spacer(Modifier.height(4.dp))
        Text(subtitle, color = TextMuted, fontSize = 11.sp, lineHeight = 15.sp)
    }
}

@Composable
private fun NotesScreen(notes: List<String>, draft: String, onDraft: (String) -> Unit, onAdd: () -> Unit, onDelete: (String) -> Unit) {
    Column(Modifier.fillMaxSize().padding(horizontal = 18.dp)) {
        Spacer(Modifier.height(20.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("یادداشت‌های من", color = TextMain, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                Text("ایده‌ها و چیزهایی که می‌خوای یادت بمونه", color = TextMuted, fontSize = 12.sp)
            }
            Box(Modifier.size(44.dp).background(Color(0xFF12334A), RoundedCornerShape(15.dp)), contentAlignment = Alignment.Center) { Icon(Icons.Default.NoteAlt, null, tint = Cyan, modifier = Modifier.size(24.dp)) }
        }
        Spacer(Modifier.height(18.dp))
        Column(Modifier.fillMaxWidth().background(SurfaceDark, RoundedCornerShape(18.dp)).border(1.dp, Color(0xFF263447), RoundedCornerShape(18.dp)).padding(14.dp)) {
            BasicTextField(value = draft, onValueChange = onDraft, modifier = Modifier.fillMaxWidth().heightIn(min = 90.dp).background(Bg, RoundedCornerShape(12.dp)).padding(13.dp), textStyle = androidx.compose.ui.text.TextStyle(color = TextMain, fontSize = 15.sp, lineHeight = 22.sp), decorationBox = { inner -> Box { if (draft.isEmpty()) Text("یادداشت جدیدت رو اینجا بنویس…", color = TextMuted, fontSize = 14.sp); inner() } })
            Spacer(Modifier.height(10.dp))
            Button(onClick = onAdd, enabled = draft.isNotBlank(), modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = Blue), shape = RoundedCornerShape(12.dp)) {
                Icon(Icons.Default.Add, null); Spacer(Modifier.width(6.dp)); Text("ذخیره یادداشت", fontWeight = FontWeight.Bold)
            }
        }
        Spacer(Modifier.height(20.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("همه یادداشت‌ها", color = TextMain, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            Spacer(Modifier.width(8.dp)); Text("${notes.size}", color = Cyan, fontSize = 12.sp)
        }
        Spacer(Modifier.height(10.dp))
        if (notes.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) { Text("هنوز یادداشتی نداری. اولین مورد رو بالا اضافه کن ✨", color = TextMuted, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 35.dp)) }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(bottom = 20.dp)) {
                items(notes) { raw ->
                    val noteText = raw.substringAfter('|', raw)
                    Row(Modifier.fillMaxWidth().background(SurfaceDark, RoundedCornerShape(15.dp)).border(1.dp, Color(0xFF202C3D), RoundedCornerShape(15.dp)).padding(13.dp), verticalAlignment = Alignment.Top) {
                        Box(Modifier.size(34.dp).background(Color(0xFF12334A), RoundedCornerShape(11.dp)), contentAlignment = Alignment.Center) { Icon(Icons.Default.NoteAlt, null, tint = Cyan, modifier = Modifier.size(19.dp)) }
                        Spacer(Modifier.width(11.dp))
                        Text(noteText, color = TextMain, fontSize = 14.sp, lineHeight = 21.sp, modifier = Modifier.weight(1f).padding(top = 5.dp))
                        IconButton(onClick = { onDelete(raw) }, modifier = Modifier.size(34.dp)) { Icon(Icons.Default.DeleteOutline, contentDescription = "حذف یادداشت", tint = TextMuted) }
                    }
                }
            }
        }
    }
}

private fun makeReply(query: String): ChatLine {
    val q = query.lowercase(Locale.ROOT)
    val searchTerms = listOf("طرز تهیه", "چی بپزم", "جستجو", "جست‌وجو", "لینک", "سایت", "درباره", "اخبار", "قیمت", "آموزش", "پیدا کن")
    return when {
        q.contains("سلام") || q.contains("درود") -> ChatLine("سلام! 💙 خوش اومدی. چه کاری برات انجام بدم؟", false)
        q.contains("اسمت") || q.contains("اسم تو") -> ChatLine("من Alisan AI هستم؛ دستیار فارسی تو! ✨", false)
        q.contains("ساعت") -> ChatLine("ساعت رو می‌تونی از نوار وضعیت گوشی ببینی. برای جواب‌های هوشمندتر باید سرویس هوش مصنوعی به برنامه وصل بشه.", false)
        q.contains("یادداشت") || q.contains("نوت") -> ChatLine("از بالای صفحه وارد بخش «یادداشت‌ها» شو؛ می‌تونی یادداشت بسازی، ذخیره کنی و هر مورد رو جداگانه حذف کنی. 📝", false)
        q.contains("آلارم") || q.contains("یادآور") -> ChatLine("برای ساخت یادآوری، آیکون زنگ بالای صفحه رو بزن و ساعت رو مثل 08:30 وارد کن. ⏰", false)
        searchTerms.any { q.contains(it) } -> {
            val encoded = URLEncoder.encode(query, "UTF-8")
            ChatLine("این لینک جست‌وجوی وب برای «$query» است. نتیجه‌ها و سایت‌های مرتبط رو باز کن. 🔎", false, "https://www.google.com/search?q=$encoded")
        }
        else -> ChatLine("متوجه شدم 💙 من در این نسخه هنوز به یک مدل هوش مصنوعی آنلاین متصل نیستم؛ برای پاسخ‌های واقعی مثل ChatGPT باید API یا بک‌اند هوش مصنوعی به برنامه اضافه بشه. فعلاً می‌تونی از من برای جست‌وجوی وب، یادداشت و یادآوری استفاده کنی.", false)
    }
}

private fun startListening(context: Context, onListening: (Boolean) -> Unit, onResult: (String) -> Unit, onFailure: () -> Unit) {
    if (!SpeechRecognizer.isRecognitionAvailable(context)) { onResult("تشخیص گفتار روی این دستگاه در دسترس نیست."); onFailure(); return }
    val recognizer = SpeechRecognizer.createSpeechRecognizer(context)
    val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, "fa-IR")
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "fa-IR")
        putExtra(RecognizerIntent.EXTRA_ONLY_RETURN_LANGUAGE_PREFERENCE, false)
        putExtra(RecognizerIntent.EXTRA_PROMPT, "با Alisan AI فارسی صحبت کن")
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
            if (!words.isNullOrBlank()) onResult(words) else onFailure()
            onListening(false); recognizer.destroy()
        }
        override fun onPartialResults(partialResults: Bundle?) {}
        override fun onEvent(eventType: Int, params: Bundle?) {}
    })
    try { recognizer.startListening(intent) } catch (_: Exception) { onListening(false); onFailure(); recognizer.destroy() }
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
