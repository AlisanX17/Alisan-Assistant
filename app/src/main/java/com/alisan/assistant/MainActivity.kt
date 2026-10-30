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
import android.provider.OpenableColumns
import android.util.Base64
import android.speech.*
import android.speech.tts.TextToSpeech
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Calendar
import java.util.Locale
import java.util.UUID

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
        window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        tts = TextToSpeech(this) { status -> if (status == TextToSpeech.SUCCESS) tts?.setLanguage(Locale("fa", "IR")) }
        setContent { AlisanAiApp() }
    }
    override fun onDestroy() { tts?.stop(); tts?.shutdown(); super.onDestroy() }
    fun speak(text: String, language: String = "fa") {
        val locale = if (language == "en") Locale.US else Locale("fa", "IR")
        val result = tts?.setLanguage(locale)
        if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
            tts?.setLanguage(if (language == "en") Locale.ENGLISH else Locale("fa"))
        }
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "alisan_ai_reply")
    }
}

data class SelectedAttachment(val name: String, val mimeType: String, val base64: String, val size: Int)
data class ChatLine(val text: String, val isUser: Boolean, val sources: List<AiSource> = emptyList())
data class ChatSession(val id: String, val title: String, val messages: List<ChatLine>)

private fun loadSessions(prefs: android.content.SharedPreferences): List<ChatSession> = try {
    val arr = JSONArray(prefs.getString("sessions", "[]"))
    (0 until arr.length()).mapNotNull { i ->
        val o = arr.optJSONObject(i) ?: return@mapNotNull null
        val msgs = o.optJSONArray("messages") ?: JSONArray()
        val lines = (0 until msgs.length()).mapNotNull { j ->
            val m = msgs.optJSONObject(j) ?: return@mapNotNull null
            val src = m.optJSONArray("sources") ?: JSONArray()
            val sources = (0 until src.length()).mapNotNull { k -> src.optJSONObject(k)?.let { AiSource(it.optString("title"), it.optString("url")) } }
            ChatLine(m.optString("text"), m.optBoolean("user"), sources)
        }
        ChatSession(o.optString("id"), o.optString("title"), lines)
    }
} catch (_: Exception) { emptyList() }

private fun saveSessions(prefs: android.content.SharedPreferences, sessions: List<ChatSession>) {
    val arr = JSONArray()
    sessions.forEach { session ->
        val msgs = JSONArray()
        session.messages.forEach { line ->
            val src = JSONArray(); line.sources.forEach { src.put(JSONObject().put("title", it.title).put("url", it.url)) }
            msgs.put(JSONObject().put("text", line.text).put("user", line.isUser).put("sources", src))
        }
        arr.put(JSONObject().put("id", session.id).put("title", session.title).put("messages", msgs))
    }
    prefs.edit().putString("sessions", arr.toString()).apply()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlisanAiApp() {
    val context = LocalContext.current
    val activity = context as MainActivity
    val prefs = remember { context.getSharedPreferences("alisan_ai", Context.MODE_PRIVATE) }
    var language by remember { mutableStateOf(prefs.getString("language", "fa") ?: "fa") }
    val en = language == "en"
    var sessions by remember { mutableStateOf(loadSessions(prefs)) }
    var activeId by remember { mutableStateOf("") }
    var chat by remember { mutableStateOf(emptyList<ChatLine>()) }
    var currentTab by remember { mutableStateOf("chat") }
    var message by remember { mutableStateOf("") }
    var noteDraft by remember { mutableStateOf("") }
    var notes by remember { mutableStateOf(prefs.getStringSet("notes", emptySet())?.toList()?.sortedDescending() ?: emptyList()) }
    var alarmTitle by remember { mutableStateOf(if (en) "My reminder" else "یادآوری من") }
    var alarmTime by remember { mutableStateOf("08:00") }
    var showAlarm by remember { mutableStateOf(false) }
    var showDrawer by remember { mutableStateOf(false) }
    var listening by remember { mutableStateOf(false) }
    var speechError by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current
    val clipboard = LocalClipboardManager.current
    var selectedAttachments by remember { mutableStateOf(emptyList<SelectedAttachment>()) }
    val attachmentPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        scope.launch {
            val added = mutableListOf<SelectedAttachment>()
            for (uri in uris.take((4 - selectedAttachments.size).coerceAtLeast(0))) {
                try {
                    val item = withContext(Dispatchers.IO) { readSelectedAttachment(context, uri) }
                    if (item.size > 5 * 1024 * 1024) {
                        speechError = if (en) "Each file must be 5 MB or smaller." else "حجم هر فایل باید حداکثر ۵ مگابایت باشد."
                    } else if (item.mimeType !in listOf("image/jpeg", "image/png", "image/webp", "application/pdf", "text/plain")) {
                        speechError = if (en) "This file type is not supported." else "این فرمت فایل پشتیبانی نمی‌شود."
                    } else {
                        added.add(item)
                    }
                } catch (_: Exception) {
                    speechError = if (en) "Couldn't read the selected file." else "خواندن فایل انتخاب‌شده ناموفق بود."
                }
            }
            selectedAttachments = (selectedAttachments + added).take(4)
            if (uris.size > 4) speechError = if (en) "You can attach up to 4 files." else "حداکثر ۴ فایل می‌تونی اضافه کنی."
        }
    }
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    LaunchedEffect(showDrawer) { if (showDrawer) drawerState.open() else drawerState.close() }

    fun persist() {
        if (activeId.isBlank() || chat.isEmpty()) return
        val title = chat.firstOrNull { it.isUser }?.text?.take(38) ?: if (en) "New chat" else "گفت‌وگوی جدید"
        val updated = ChatSession(activeId, title, chat)
        sessions = (listOf(updated) + sessions.filterNot { it.id == activeId }).take(60)
        saveSessions(prefs, sessions)
    }
    fun newChat() {
        persist(); activeId = UUID.randomUUID().toString(); chat = emptyList(); message = ""; currentTab = "chat"; showDrawer = false
    }
    fun openSession(session: ChatSession) {
        persist(); activeId = session.id; chat = session.messages; message = ""; currentTab = "chat"; showDrawer = false
    }
    fun sendMessage(raw: String) {
        val outgoingAttachments = selectedAttachments
        val typed = raw.trim()
        if ((typed.isBlank() && outgoingAttachments.isEmpty()) || isLoading) return
        val q = typed.ifBlank { if (en) "Please analyze the attached file(s)." else "لطفاً فایل(های) پیوست‌شده را بررسی کن." }
        if (activeId.isBlank()) activeId = UUID.randomUUID().toString()
        keyboard?.hide()
        val attachmentLabel = outgoingAttachments.joinToString("\n") { "📎 ${it.name}" }
        val userLine = ChatLine(if (attachmentLabel.isBlank()) q else "$q\n$attachmentLabel", true)
        selectedAttachments = emptyList()
        chat = chat + userLine + ChatLine(if (en) "Thinking…" else "در حال دریافت پاسخ…", false)
        message = ""; currentTab = "chat"; isLoading = true
        scope.launch {
            try {
                val history = chat.dropLast(2).takeLast(16).joinToString("\n") { line ->
                    (if (line.isUser) (if (en) "User" else "کاربر") else "Alisan AI") + ": " + line.text.take(1800)
                }
                val languageInstruction = if (en)
                    "Answer naturally in English. Understand the user's intent from the conversation context; do not define individual words unless asked. Be accurate, relevant, concise, and use a few fitting emojis naturally. If unsure, say so. Keep correct English grammar and formatting."
                else
                    "فقط فارسی روان و طبیعی جواب بده. منظور جمله را با توجه به زمینه و پیام‌های قبلی بفهم؛ کلمه‌ها را جداگانه معنی نکن مگر اینکه کاربر معنی کلمه را خواسته باشد. دقیق، مرتبط و روشن پاسخ بده؛ از چند ایموجی مناسب و به‌اندازه استفاده کن. اگر مطمئن نیستی صادقانه بگو. دستور زبان و نیم‌فاصله فارسی را رعایت کن."
                val contextualMessage = buildString {
                    append("[ASSISTANT INSTRUCTIONS]\n").append(languageInstruction).append("\n")
                    if (history.isNotBlank()) append("[CONVERSATION HISTORY]\n").append(history).append("\n")
                    append("[LATEST USER MESSAGE]\n").append(q)
                }
                val result = AlisanAiApi.ask(contextualMessage, outgoingAttachments)
                chat = chat.dropLast(1) + ChatLine(result.answer, false, result.sources)
                activity.speak(result.answer.take(3500), language)
            } catch (e: Exception) {
                chat = chat.dropLast(1) + ChatLine((if (en) "Couldn't connect to Alisan AI. Check your internet and server.\n" else "اتصال به هوش مصنوعی ناموفق بود. اینترنت و Worker را بررسی کن.\n") + (e.message ?: ""), false)
            } finally { isLoading = false; persist() }
        }
        persist()
    }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startListening(context, language, { listening = it }, { sendMessage(it) }, { listening = false; speechError = if (en) "Speech recognition failed." else "صدای شما تشخیص داده نشد؛ دوباره امتحان کن." })
        else speechError = if (en) "Microphone permission is required." else "برای استفاده از میکروفون اجازه لازم است."
    }
    val transition = rememberInfiniteTransition(label = "glow")
    val pulse by transition.animateFloat(0.96f, 1.04f, infiniteRepeatable(tween(1300), RepeatMode.Reverse), label = "pulse")

    MaterialTheme(colorScheme = darkColorScheme(primary = Blue, secondary = Cyan, background = Bg, surface = SurfaceDark, onSurface = TextMain)) {
        ModalNavigationDrawer(drawerState = drawerState, gesturesEnabled = true,
            drawerContent = {
                ModalDrawerSheet(drawerContainerColor = SurfaceDark, modifier = Modifier.width(310.dp)) {
                    Spacer(Modifier.height(24.dp))
                    Text("Alisan AI", color = TextMain, fontSize = 23.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.padding(horizontal = 20.dp))
                    Spacer(Modifier.height(18.dp))
                    NavigationDrawerItem(label = { Text(if (en) "New chat" else "گفت‌وگوی جدید") }, selected = currentTab == "chat" && chat.isEmpty(), onClick = { newChat() }, icon = { Icon(Icons.Default.Add, null) })
                    NavigationDrawerItem(label = { Text(if (en) "Notes" else "یادداشت‌ها") }, selected = currentTab == "notes", onClick = { currentTab = "notes"; showDrawer = false }, icon = { Icon(Icons.Default.NoteAlt, null) })
                    NavigationDrawerItem(label = { Text(if (en) "Reminder / Alarm" else "یادآوری و آلارم") }, selected = false, onClick = { showAlarm = true; showDrawer = false }, icon = { Icon(Icons.Default.Alarm, null) })
                    HorizontalDivider(color = Color(0xFF263447), modifier = Modifier.padding(vertical = 12.dp))
                    Text(if (en) "LANGUAGE" else "زبان برنامه", color = TextMuted, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp))
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = !en, onClick = { language = "fa"; prefs.edit().putString("language", language).apply() }, label = { Text("فارسی") })
                        FilterChip(selected = en, onClick = { language = "en"; prefs.edit().putString("language", language).apply() }, label = { Text("English") })
                    }
                    HorizontalDivider(color = Color(0xFF263447), modifier = Modifier.padding(vertical = 12.dp))
                    Text(if (en) "RECENT CHATS" else "گفت‌وگوهای قبلی", color = TextMuted, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp))
                    LazyColumn(Modifier.weight(1f)) {
                        items(sessions) { s ->
                            NavigationDrawerItem(label = { Text(s.title, maxLines = 1) }, selected = s.id == activeId, onClick = { openSession(s) }, icon = { Icon(Icons.Default.ChatBubbleOutline, null) })
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                }
            }) {
            Column(Modifier.fillMaxSize().background(Bg)) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { showDrawer = true }) { Icon(Icons.Default.Menu, if (en) "Menu" else "منو", tint = TextMain) }
                    Column(Modifier.weight(1f)) {
                        Text("Alisan AI", color = TextMain, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold)
                        Text(if (en) "Your AI assistant" else "دستیار هوشمند تو", color = TextMuted, fontSize = 11.sp)
                    }
                    IconButton(onClick = { newChat() }) { Icon(Icons.Default.AddComment, if (en) "New chat" else "پیام جدید", tint = Cyan) }
                }
                when (currentTab) {
                    "notes" -> NotesScreen(notes, noteDraft, { noteDraft = it }, {
                        if (noteDraft.isNotBlank()) { val n = "${System.currentTimeMillis()}|${noteDraft.trim()}"; notes = listOf(n) + notes; prefs.edit().putStringSet("notes", notes.toSet()).apply(); noteDraft = "" }
                    }, { n -> notes = notes.filterNot { it == n }; prefs.edit().putStringSet("notes", notes.toSet()).apply() }, en)
                    else -> ChatScreen(chat, message, { message = it }, listening, speechError, pulse, isLoading, en,
                        attachments = selectedAttachments,
                        onPickAttachment = { attachmentPicker.launch(arrayOf("image/*", "application/pdf", "text/plain")) },
                        onRemoveAttachment = { item -> selectedAttachments = selectedAttachments.filterNot { it == item } },
                        onSend = { sendMessage(message) },
                        onMic = { if (listening) listening = false else if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) startListening(context, language, { listening = it }, { sendMessage(it) }, { listening = false; speechError = if (en) "Speech recognition failed. Check your device's speech service and language pack." else "تشخیص صدا ناموفق بود؛ سرویس گفتار و زبان فارسی گوشی را بررسی کن." }) else permissionLauncher.launch(Manifest.permission.RECORD_AUDIO) },
                        onOpenLink = { url -> runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } },
                        onCopy = { clipboard.setText(AnnotatedString(it)) },
                        onSpeak = { activity.speak(it, language) },
                        onRetry = { index ->
                            val previousUser = chat.take(index).lastOrNull { it.isUser }?.text
                            if (previousUser != null && !isLoading) {
                                chat = chat.take((index - 1).coerceAtLeast(0))
                                sendMessage(previousUser)
                            }
                        })
                }
            }
        }
        if (showAlarm) AlertDialog(onDismissRequest = { showAlarm = false }, containerColor = SurfaceRaised,
            title = { Text(if (en) "New reminder" else "یادآوری جدید", color = TextMain) },
            text = { Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(alarmTitle, { alarmTitle = it }, label = { Text(if (en) "Title" else "عنوان") }, singleLine = true)
                OutlinedTextField(alarmTime, { alarmTime = it }, label = { Text(if (en) "Time (08:30)" else "ساعت (08:30)") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii))
            } },
            confirmButton = { TextButton(onClick = { if (alarmTime.matches(Regex("^([01]?[0-9]|2[0-3]):[0-5][0-9]$"))) { scheduleAlarm(context, alarmTitle, alarmTime); chat = chat + ChatLine(if (en) "Reminder set for $alarmTime" else "یادآوری برای ساعت $alarmTime تنظیم شد ⏰", false); persist(); currentTab = "chat"; showAlarm = false } }) { Text(if (en) "Set" else "تنظیم", color = Cyan) } },
            dismissButton = { TextButton(onClick = { showAlarm = false }) { Text(if (en) "Cancel" else "لغو") } })
    }
}

@Composable
private fun ChatScreen(chat: List<ChatLine>, message: String, onMessage: (String) -> Unit, listening: Boolean, speechError: String, pulse: Float, isLoading: Boolean, en: Boolean, attachments: List<SelectedAttachment>, onPickAttachment: () -> Unit, onRemoveAttachment: (SelectedAttachment) -> Unit, onSend: () -> Unit, onMic: () -> Unit, onOpenLink: (String) -> Unit, onCopy: (String) -> Unit, onSpeak: (String) -> Unit, onRetry: (Int) -> Unit) {
    val keyboard = LocalSoftwareKeyboardController.current
    val focusRequester = remember { FocusRequester() }
    Column(Modifier.fillMaxSize().imePadding().padding(horizontal = 16.dp)) {
        if (chat.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.size(174.dp).scale(pulse).background(Brush.radialGradient(listOf(Color(0x663B82F6), Color(0x223B82F6), Color.Transparent)), CircleShape).border(1.dp, Color(0x443B82F6), CircleShape), contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.AutoAwesome, null, tint = Cyan, modifier = Modifier.size(58.dp))
                    }
                    Spacer(Modifier.height(24.dp))
                    Text(if (en) "What can I help with?" else "از کجا شروع کنیم؟", color = TextMain, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    Text(if (en) "Ask anything. Start a new conversation." else "هر سوالی داری بپرس و گفت‌وگوی جدیدت رو شروع کن.", color = TextMuted, fontSize = 13.sp, textAlign = TextAlign.Center)
                }
            }
        } else {
            LazyColumn(Modifier.weight(1f).fillMaxWidth().padding(vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                itemsIndexed(chat) { index, line ->
                    if (line.isUser) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        Text(line.text, Modifier.widthIn(max = 310.dp).background(SurfaceRaised, RoundedCornerShape(18.dp)).padding(13.dp), color = TextMain, fontSize = 15.sp)
                    } else Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                        Box(Modifier.size(29.dp).background(Brush.linearGradient(listOf(Blue, Color(0xFF6D5DFB))), RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) { Icon(Icons.Default.AutoAwesome, null, tint = Color.White, modifier = Modifier.size(17.dp)) }
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Alisan AI", color = Cyan, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.height(5.dp)); Text(line.text, color = TextMain, fontSize = 15.sp, lineHeight = 23.sp)
                            if (!line.text.startsWith("در حال دریافت") && line.text != "Thinking…") {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    IconButton(onClick = { onCopy(line.text) }, modifier = Modifier.size(34.dp)) { Icon(Icons.Default.ContentCopy, if (en) "Copy" else "کپی", tint = TextMuted, modifier = Modifier.size(17.dp)) }
                                    IconButton(onClick = { onRetry(index) }, modifier = Modifier.size(34.dp)) { Icon(Icons.Default.Refresh, if (en) "Regenerate" else "پاسخ دوباره", tint = TextMuted, modifier = Modifier.size(18.dp)) }
                                    IconButton(onClick = { onSpeak(line.text) }, modifier = Modifier.size(34.dp)) { Icon(Icons.Default.VolumeUp, if (en) "Read aloud" else "خواندن با صدا", tint = Cyan, modifier = Modifier.size(18.dp)) }
                                }
                            }
                            line.sources.forEach { source ->
                                Spacer(Modifier.height(7.dp))
                                Row(Modifier.fillMaxWidth().background(SurfaceDark, RoundedCornerShape(11.dp)).clickable { onOpenLink(source.url) }.padding(11.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.Language, null, tint = Cyan, modifier = Modifier.size(19.dp)); Spacer(Modifier.width(9.dp))
                                    Text(source.title.ifBlank { source.url }, color = Cyan, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                                    Icon(Icons.Default.OpenInNew, null, tint = TextMuted, modifier = Modifier.size(16.dp))
                                }
                            }
                        }
                    }
                }
            }
        }
        AnimatedVisibility(listening) { Text(if (en) "Listening…" else "در حال گوش دادن…", color = Cyan, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center) }
        AnimatedVisibility(speechError.isNotBlank()) { Text(speechError, color = Color(0xFFFCA5A5), fontSize = 12.sp) }
        AnimatedVisibility(isLoading) { OrbitingMoonLoader(en) }
        if (attachments.isNotEmpty()) {
            Column(Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 4.dp)) {
                attachments.forEach { item ->
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 3.dp)
                            .background(SurfaceRaised, RoundedCornerShape(10.dp)).padding(horizontal = 10.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(if (item.mimeType.startsWith("image/")) Icons.Default.Image else Icons.Default.AttachFile, null, tint = Cyan, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(7.dp))
                        Text(item.name, color = TextMain, fontSize = 12.sp, modifier = Modifier.weight(1f), maxLines = 1)
                        IconButton(onClick = { onRemoveAttachment(item) }, modifier = Modifier.size(28.dp)) {
                            Icon(Icons.Default.Close, if (en) "Remove attachment" else "حذف پیوست", tint = TextMuted, modifier = Modifier.size(16.dp))
                        }
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 10.dp).background(SurfaceDark, RoundedCornerShape(25.dp)).border(1.dp, Color(0xFF273448), RoundedCornerShape(25.dp)).padding(horizontal = 7.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onPickAttachment, enabled = !isLoading, modifier = Modifier.size(38.dp)) { Icon(Icons.Default.AttachFile, if (en) "Attach file or image" else "افزودن عکس یا فایل", tint = Cyan) }
            IconButton(onClick = onMic, modifier = Modifier.size(38.dp).background(SurfaceRaised, CircleShape)) { Icon(Icons.Default.Mic, null, tint = Cyan) }
            BasicTextField(value = message, onValueChange = onMessage, modifier = Modifier.weight(1f).padding(horizontal = 8.dp).focusRequester(focusRequester), textStyle = TextStyle(color = TextMain, fontSize = 15.sp), singleLine = false, maxLines = 5,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send), keyboardActions = KeyboardActions(onSend = { if ((message.isNotBlank() || attachments.isNotEmpty()) && !isLoading) { keyboard?.hide(); onSend() } }),
                decorationBox = { inner -> Box { if (message.isEmpty()) Text(if (en) "Message Alisan AI…" else "پیامت رو بنویس…", color = TextMuted, fontSize = 14.sp); inner() } })
            IconButton(onClick = { keyboard?.hide(); onSend() }, enabled = (message.isNotBlank() || attachments.isNotEmpty()) && !isLoading, modifier = Modifier.size(42.dp).background(Brush.linearGradient(listOf(Blue, Color(0xFF6366F1))), CircleShape)) { Icon(Icons.Default.Send, null, tint = Color.White) }
        }
    }
}

@Composable
private fun OrbitingMoonLoader(en: Boolean) {
    val infinite = rememberInfiniteTransition(label = "moonOrbit")
    val angle by infinite.animateFloat(0f, 360f, infiniteRepeatable(tween(1800, easing = LinearEasing), RepeatMode.Restart), label = "orbit")
    Row(Modifier.fillMaxWidth().padding(vertical = 9.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        Canvas(Modifier.size(48.dp)) {
            val c = center; val r = size.minDimension * .34f
            drawCircle(Color(0x553B82F6), radius = r + 5f, center = c, style = Stroke(2f))
            drawCircle(Brush.radialGradient(listOf(Cyan, Blue, Color(0xFF172554))), radius = 8f, center = c)
            val rad = Math.toRadians(angle.toDouble()); val mx = c.x + r * kotlin.math.cos(rad).toFloat(); val my = c.y + r * kotlin.math.sin(rad).toFloat()
            drawCircle(Color(0xFFBAE6FD), radius = 4.5f, center = androidx.compose.ui.geometry.Offset(mx, my))
            drawCircle(Color(0x663B82F6), radius = 11f, center = androidx.compose.ui.geometry.Offset(mx, my), style = Stroke(2f))
        }
        Spacer(Modifier.width(10.dp)); Text(if (en) "Alisan AI is thinking…" else "Alisan AI در حال فکر کردنه…", color = Cyan, fontSize = 12.sp)
    }
}

@Composable
private fun NotesScreen(notes: List<String>, draft: String, onDraft: (String) -> Unit, onAdd: () -> Unit, onDelete: (String) -> Unit, en: Boolean) {
    Column(Modifier.fillMaxSize().padding(18.dp)) {
        Text(if (en) "My notes" else "یادداشت‌های من", color = TextMain, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(16.dp))
        BasicTextField(draft, onDraft, Modifier.fillMaxWidth().heightIn(min = 90.dp).background(SurfaceDark, RoundedCornerShape(14.dp)).padding(13.dp), textStyle = TextStyle(color = TextMain, fontSize = 15.sp), decorationBox = { inner -> Box { if (draft.isEmpty()) Text(if (en) "Write a note…" else "یادداشت جدیدت رو اینجا بنویس…", color = TextMuted); inner() } })
        Spacer(Modifier.height(10.dp)); Button(onClick = onAdd, enabled = draft.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Icon(Icons.Default.Add, null); Text(if (en) "Save note" else "ذخیره یادداشت") }
        Spacer(Modifier.height(18.dp)); LazyColumn(verticalArrangement = Arrangement.spacedBy(9.dp)) { items(notes) { raw -> Row(Modifier.fillMaxWidth().background(SurfaceDark, RoundedCornerShape(13.dp)).padding(12.dp), verticalAlignment = Alignment.CenterVertically) { Text(raw.substringAfter('|', raw), Modifier.weight(1f), color = TextMain); IconButton(onClick = { onDelete(raw) }) { Icon(Icons.Default.DeleteOutline, null, tint = TextMuted) } } } }
    }
}

private fun readSelectedAttachment(context: Context, uri: Uri): SelectedAttachment {
    val resolver = context.contentResolver
    val mime = resolver.getType(uri)?.lowercase() ?: "application/octet-stream"
    var name = uri.lastPathSegment?.substringAfterLast('/') ?: "attachment"
    resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) {
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0) name = cursor.getString(index) ?: name
        }
    }
    val bytes = resolver.openInputStream(uri)?.use { input ->
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        var total = 0
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            if (total > 5 * 1024 * 1024) throw IllegalArgumentException("File too large")
            output.write(buffer, 0, count)
        }
        output.toByteArray()
    } ?: throw IllegalArgumentException("Cannot open file")
    return SelectedAttachment(name, mime, Base64.encodeToString(bytes, Base64.NO_WRAP), bytes.size)
}

private fun startListening(context: Context, language: String, onListening: (Boolean) -> Unit, onResult: (String) -> Unit, onFailure: () -> Unit) {
    if (!SpeechRecognizer.isRecognitionAvailable(context)) { onFailure(); return }
    val recognizer = SpeechRecognizer.createSpeechRecognizer(context)
    val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply { putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM); putExtra(RecognizerIntent.EXTRA_LANGUAGE, if (language == "en") "en-US" else "fa-IR"); putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, if (language == "en") "en-US" else "fa-IR"); putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false); putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1) }
    recognizer.setRecognitionListener(object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) { onListening(true) }; override fun onBeginningOfSpeech() { onListening(true) }; override fun onRmsChanged(rmsdB: Float) {}; override fun onBufferReceived(buffer: ByteArray?) {}; override fun onEndOfSpeech() { onListening(false) }
        override fun onError(error: Int) { onListening(false); onFailure(); recognizer.destroy() }
        override fun onResults(results: Bundle?) { results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let(onResult) ?: onFailure(); onListening(false); recognizer.destroy() }
        override fun onPartialResults(partialResults: Bundle?) {}; override fun onEvent(eventType: Int, params: Bundle?) {}
    })
    try { recognizer.startListening(intent) } catch (_: Exception) { onListening(false); onFailure(); recognizer.destroy() }
}

private fun scheduleAlarm(context: Context, title: String, time: String) {
    val p = time.split(":"); val cal = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, p[0].toInt()); set(Calendar.MINUTE, p[1].toInt()); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0); if (timeInMillis <= System.currentTimeMillis()) add(Calendar.DAY_OF_YEAR, 1) }
    val intent = Intent(context, AlarmReceiver::class.java).putExtra("title", title)
    val pending = PendingIntent.getBroadcast(context, (System.currentTimeMillis() % Int.MAX_VALUE).toInt(), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    val manager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !manager.canScheduleExactAlarms()) manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, cal.timeInMillis, pending) else manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, cal.timeInMillis, pending)
}
