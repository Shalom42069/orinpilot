package de.marlon.orinpilot.ui.screens

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.marlon.orinpilot.MainViewModel
import de.marlon.orinpilot.data.ChatMessage
import de.marlon.orinpilot.data.OllamaClient
import de.marlon.orinpilot.data.OllamaEnv
import de.marlon.orinpilot.data.OllamaScripts
import de.marlon.orinpilot.ssh.ExecResult
import de.marlon.orinpilot.ui.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.util.Locale

@Composable
fun OllamaScreen(vm: MainViewModel) {
    val o = vm.ollama
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val status by o.status.collectAsState()

    LaunchedEffect(Unit) { if (status == null) o.refresh() }

    // Ausgabe-Dialoge, gemeinsam für alle Unterseiten
    var live by remember { mutableStateOf<Pair<String, Flow<String>>?>(null) }
    var outTitle by remember { mutableStateOf<String?>(null) }
    var outText by remember { mutableStateOf<String?>(null) }
    var outBusy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val runLive: (String, String, Boolean) -> Unit = { title, cmd, sudo -> live = title to vm.live(cmd, sudo) }
    val show: (String, suspend () -> ExecResult) -> Unit = { title, block ->
        outTitle = title; outText = null; outBusy = true
        scope.launch {
            val r = block()
            outText = (if (r.ok) "" else "Exit-Code ${r.exit}\n") + r.text
            outBusy = false
        }
    }
    val showText: (String, suspend () -> String) -> Unit = { title, block ->
        outTitle = title; outText = null; outBusy = true
        scope.launch { outText = block(); outBusy = false }
    }

    Column(Modifier.fillMaxSize()) {
        Box(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            ChipRow {
                listOf("Chat", "Modelle", "Dienst", "Benchmark").forEachIndexed { i, t ->
                    FilterChip(selected = tab == i, onClick = { tab = i }, label = { Text(t) })
                }
            }
        }
        val st = status
        when {
            st == null -> Busy()
            !st.installed && tab != 2 -> NotInstalled(onInstall = {
                runLive("Ollama installieren", OllamaScripts.INSTALL, true)
            }, onService = { tab = 2 })
            else -> when (tab) {
                0 -> ChatTab(vm, onModels = { tab = 1 })
                1 -> ModelsTab(vm, onChat = { tab = 0 }, showText = showText)
                2 -> ServiceTab(vm, runLive, show)
                else -> BenchTab(vm)
            }
        }
    }

    live?.let { (t, f) ->
        LiveOutputDialog(t, f, onDismiss = { live = null; o.refresh() })
    }
    outTitle?.let { t -> OutputDialog(t, outText, outBusy) { outTitle = null } }
}

@Composable
private fun NotInstalled(onInstall: () -> Unit, onService: () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionCard("Ollama") {
            Text(
                "Ollama ist auf dem Jetson noch nicht installiert. Das offizielle Installationsskript erkennt JetPack " +
                    "automatisch und richtet die CUDA-Variante samt systemd-Dienst ein. Der Jetson braucht dafür Internet (ca. 1–2 GB).",
                color = OP.Text, fontSize = 13.sp
            )
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onInstall) { Icon(Icons.Default.Download, null); Spacer(Modifier.width(6.dp)); Text("Installieren") }
                OutlinedButton(onClick = onService) { Text("Details") }
            }
        }
    }
}

// =====================================================================================================================
// Chat
// =====================================================================================================================

@Composable
private fun ChatTab(vm: MainViewModel, onModels: () -> Unit) {
    val o = vm.ollama
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val models by o.models.collectAsState()
    val chat by o.chat.collectAsState()
    val streaming by o.streaming.collectAsState()
    val model by o.chatModel.collectAsState()
    val apiErr by o.apiError.collectAsState()
    val stats by vm.stats.collectAsState()
    val status by o.status.collectAsState()

    var input by rememberSaveable { mutableStateOf("") }
    var images by remember { mutableStateOf<List<String>>(emptyList()) }
    var modelMenu by remember { mutableStateOf(false) }
    var settings by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) scope.launch {
            val b64 = withContext(Dispatchers.IO) { runCatching { encodeImage(ctx, uri) }.getOrNull() }
            if (b64 != null) images = images + b64 else vm.toast("Bild konnte nicht gelesen werden")
        }
    }

    LaunchedEffect(chat.size, chat.lastOrNull()?.content?.length) {
        if (chat.isNotEmpty()) listState.scrollToItem(chat.lastIndex, Int.MAX_VALUE / 2)
    }

    Column(Modifier.fillMaxSize()) {
        // ---------- Kopf: Modell + Live-Werte
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) {
                Row(
                    Modifier.clip(RoundedCornerShape(10.dp)).background(OP.Surface).border(1.dp, OP.Outline, RoundedCornerShape(10.dp))
                        .clickable { modelMenu = true }.padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.SmartToy, null, tint = OP.Green, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(model.ifBlank { "Modell wählen" }, color = OP.Text, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, false))
                    Icon(Icons.Default.ArrowDropDown, null, tint = OP.TextDim)
                }
                DropdownMenu(modelMenu, { modelMenu = false }) {
                    models.forEach { m ->
                        DropdownMenuItem(
                            text = { Text("${m.name}  ·  ${m.paramSize}", fontSize = 13.sp) },
                            onClick = { modelMenu = false; o.setModel(m.name) }
                        )
                    }
                    HorizontalDivider()
                    DropdownMenuItem(text = { Text("Modelle verwalten …") }, onClick = { modelMenu = false; onModels() })
                }
            }
            IconButton(onClick = { settings = true }) { Icon(Icons.Default.Tune, "Einstellungen", tint = OP.TextDim) }
            IconButton(onClick = { o.clearChat() }, enabled = chat.isNotEmpty()) { Icon(Icons.Default.DeleteSweep, "Leeren", tint = OP.TextDim) }
        }
        stats?.let { s ->
            Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Pill("GPU ${s.gpuLoad ?: 0}%", if ((s.gpuLoad ?: 0) > 50) OP.Green else OP.TextDim)
                Pill("RAM ${s.ramUsedMb * 100 / s.ramTotalMb.coerceAtLeast(1)}%", loadColor(s.ramUsedMb * 100f / s.ramTotalMb.coerceAtLeast(1)))
                s.hotTemp?.let { Pill(String.format(Locale.GERMANY, "%.0f °C", it), tempColor(it)) }
                s.totalPowerMw?.let { Pill(String.format(Locale.GERMANY, "%.1f W", it / 1000f), OP.Amber) }
            }
        }

        // ---------- Verlauf
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                status?.serviceActive == false -> EmptyHint("Der Ollama-Dienst läuft nicht – im Tab „Dienst“ starten.")
                apiErr != null -> Column(Modifier.padding(16.dp)) {
                    Text(apiErr ?: "", color = OP.Amber, fontSize = 13.sp)
                    TextButton(onClick = { o.refresh() }) { Text("Erneut versuchen") }
                }
                models.isEmpty() -> Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Noch keine Modelle installiert.", color = OP.TextDim)
                    TextButton(onClick = onModels) { Text("Modell herunterladen") }
                }
                chat.isEmpty() -> Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.Forum, null, tint = OP.Outline, modifier = Modifier.size(48.dp))
                    Spacer(Modifier.height(8.dp))
                    Text("Läuft komplett lokal auf deinem Jetson.", color = OP.TextDim, fontSize = 13.sp)
                }
                else -> LazyColumn(state = listState, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    itemsIndexed(chat) { i, m ->
                        MessageBubble(m, isLast = i == chat.lastIndex, streaming = streaming, onRegenerate = { o.regenerate() })
                    }
                }
            }
        }

        // ---------- Eingabe
        if (images.isNotEmpty()) {
            Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Pill("${images.size} Bild(er) angehängt", OP.Cyan)
                TextButton(onClick = { images = emptyList() }) { Text("entfernen") }
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.Bottom) {
            IconButton(onClick = { picker.launch("image/*") }) { Icon(Icons.Default.Image, "Bild anhängen", tint = OP.TextDim) }
            OutlinedTextField(
                value = input, onValueChange = { input = it },
                modifier = Modifier.weight(1f), maxLines = 6,
                placeholder = { Text("Nachricht an ${model.ifBlank { "Modell" }}") },
            )
            if (streaming) {
                IconButton(onClick = { o.stop() }) { Icon(Icons.Default.Stop, "Stopp", tint = OP.Red) }
            } else {
                IconButton(
                    enabled = model.isNotBlank() && (input.isNotBlank() || images.isNotEmpty()),
                    onClick = { o.send(input, images); input = ""; images = emptyList() }
                ) { Icon(Icons.Default.Send, "Senden", tint = OP.Green) }
            }
        }
    }

    if (settings) ChatSettingsDialog(vm) { settings = false }
}

@Composable
private fun MessageBubble(m: ChatMessage, isLast: Boolean, streaming: Boolean, onRegenerate: () -> Unit) {
    val clip = LocalClipboardManager.current
    val user = m.role == "user"
    var showThink by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (user) Arrangement.End else Arrangement.Start) {
        Column(
            Modifier
                .widthIn(max = 340.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(if (user) OP.Green.copy(alpha = 0.16f) else OP.Surface)
                .border(1.dp, if (user) OP.Green.copy(alpha = 0.3f) else OP.Outline, RoundedCornerShape(14.dp))
                .padding(10.dp)
        ) {
            if (m.images.isNotEmpty()) Pill("${m.images.size} Bild(er)", OP.Cyan)
            if (m.thinking.isNotBlank()) {
                Text(
                    if (showThink) "▾ Gedankengang" else "▸ Gedankengang (${m.thinking.length} Zeichen)",
                    color = OP.Violet, fontSize = 12.sp, modifier = Modifier.clickable { showThink = !showThink }.padding(bottom = 4.dp)
                )
                if (showThink) Text(m.thinking.trim(), color = OP.TextDim, fontSize = 12.sp, fontStyle = FontStyle.Italic)
            }
            if (m.content.isBlank() && streaming && isLast) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text(if (m.thinking.isNotBlank()) "denkt nach …" else "lädt Modell / verarbeitet …", color = OP.TextDim, fontSize = 12.sp)
                }
            } else {
                SelectionContainer { RichText(m.content) }
            }
            if (!user && !(streaming && isLast)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    m.stats?.let { s ->
                        Text(
                            String.format(Locale.GERMANY, "%d Tok · %.1f tok/s", s.evalTokens, s.tokPerSec) +
                                if (s.loadMs > 500) String.format(Locale.GERMANY, " · geladen %.1f s", s.loadMs / 1000f) else "",
                            color = OP.TextDim, fontSize = 10.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f)
                        )
                    } ?: Spacer(Modifier.weight(1f))
                    Icon(
                        Icons.Default.ContentCopy, "Kopieren", tint = OP.TextDim,
                        modifier = Modifier.size(28.dp).clickable { clip.setText(AnnotatedString(m.content)) }.padding(6.dp)
                    )
                    if (isLast) Icon(
                        Icons.Default.Refresh, "Neu erzeugen", tint = OP.TextDim,
                        modifier = Modifier.size(28.dp).clickable { onRegenerate() }.padding(6.dp)
                    )
                }
            }
        }
    }
}

/** sehr einfaches Markdown: ```Codeblöcke```, `Inline-Code`, **fett** */
@Composable
private fun RichText(text: String) {
    val parts = text.split("```")
    Column {
        parts.forEachIndexed { i, part ->
            if (i % 2 == 1) {
                val code = part.substringAfter('\n', part).trimEnd()
                Box(
                    Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFF0A0C0B)).horizontalScroll(rememberScrollState()).padding(8.dp)
                ) {
                    Text(code, fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = OP.Text, softWrap = false)
                }
            } else if (part.isNotBlank()) {
                Text(inlineMarkdown(part.trim('\n')), color = OP.Text, fontSize = 14.sp, lineHeight = 20.sp)
            }
        }
    }
}

private val INLINE = Regex("""\*\*(.+?)\*\*|`([^`]+)`""")

private fun inlineMarkdown(s: String): AnnotatedString = buildAnnotatedString {
    var last = 0
    INLINE.findAll(s).forEach { m ->
        append(s.substring(last, m.range.first))
        if (m.groupValues[1].isNotEmpty()) withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(m.groupValues[1]) }
        else withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = Color(0xFF0A0C0B), color = OP.Cyan)) { append(m.groupValues[2]) }
        last = m.range.last + 1
    }
    append(s.substring(last))
}

private fun encodeImage(ctx: android.content.Context, uri: Uri): String {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
    var sample = 1
    while (bounds.outWidth / sample > 2048 || bounds.outHeight / sample > 2048) sample *= 2
    val bmp = ctx.contentResolver.openInputStream(uri)?.use {
        BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
    } ?: throw IllegalStateException("kein Bild")
    val scale = minOf(1f, 1024f / maxOf(bmp.width, bmp.height))
    val out = if (scale < 1f) Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt(), (bmp.height * scale).toInt(), true) else bmp
    val bos = ByteArrayOutputStream()
    out.compress(Bitmap.CompressFormat.JPEG, 85, bos)
    return Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP)
}

@Composable
private fun ChatSettingsDialog(vm: MainViewModel, onDismiss: () -> Unit) {
    val o = vm.ollama
    val opts by o.options.collectAsState()
    val sys by o.systemPrompt.collectAsState()
    var system by remember { mutableStateOf(sys) }
    var temp by remember { mutableFloatStateOf(opts.temperature) }
    var ctxLen by remember { mutableIntStateOf(opts.numCtx) }
    var think by remember { mutableStateOf(opts.think) }
    var keep by remember { mutableStateOf(opts.keepAlive) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Chat-Einstellungen") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(system, { system = it }, label = { Text("System-Prompt") }, minLines = 2, maxLines = 5)
                Spacer(Modifier.height(8.dp))
                Text(String.format(Locale.GERMANY, "Temperatur: %.2f", temp), fontSize = 13.sp)
                Slider(temp, { temp = it }, valueRange = 0f..1.5f)
                Text("Kontextlänge (mehr = mehr RAM)", fontSize = 13.sp)
                ChipRow {
                    listOf(2048, 4096, 8192, 16384).forEach { c ->
                        FilterChip(selected = ctxLen == c, onClick = { ctxLen = c }, label = { Text("${c / 1024}k") })
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text("Modell im Speicher halten", fontSize = 13.sp)
                ChipRow {
                    listOf("" to "Standard", "30m" to "30 min", "2h" to "2 h", "-1" to "immer").forEach { (v, l) ->
                        FilterChip(selected = keep == v, onClick = { keep = v }, label = { Text(l) })
                    }
                }
                Spacer(Modifier.height(6.dp))
                SwitchRow("Denkmodus", think, "für Reasoning-Modelle (qwen3, deepseek-r1 …)") { think = it }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                o.setSystem(system)
                o.setOptions(opts.copy(temperature = temp, numCtx = ctxLen, think = think, keepAlive = keep))
                onDismiss()
            }) { Text("Speichern") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Abbrechen") } },
    )
}

// =====================================================================================================================
// Modelle
// =====================================================================================================================

@Composable
private fun ModelsTab(vm: MainViewModel, onChat: () -> Unit, showText: (String, suspend () -> String) -> Unit) {
    val o = vm.ollama
    val models by o.models.collectAsState()
    val running by o.running.collectAsState()
    val pulls by o.pulls.collectAsState()
    val busy by o.busy.collectAsState()
    val apiErr by o.apiError.collectAsState()
    var pullName by rememberSaveable { mutableStateOf("") }
    var deleting by remember { mutableStateOf<String?>(null) }

    // laufende Modelle regelmäßig aktualisieren
    LaunchedEffect(Unit) {
        o.refreshApi()
        while (isActive) { delay(5000); o.refreshRunning() }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        apiErr?.let { Text(it, color = OP.Amber, fontSize = 12.sp) }
        busy?.let {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp); Spacer(Modifier.width(8.dp))
                Text(it, color = OP.TextDim, fontSize = 13.sp)
            }
        }

        SectionCard("Im Speicher (${running.size})") {
            if (running.isEmpty()) Text("Kein Modell geladen – wird beim ersten Chat automatisch geladen.", color = OP.TextDim, fontSize = 12.sp)
            running.forEach { r ->
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(r.name, color = OP.Text, fontSize = 14.sp)
                        Text(
                            "${humanBytes(r.sizeBytes)} · ${r.gpuPct}% GPU" + (if (r.contextLength > 0) " · ctx ${r.contextLength}" else "") +
                                (if (r.expiresAt.startsWith("2318")) " · dauerhaft" else ""),
                            color = OP.TextDim, fontSize = 11.sp
                        )
                    }
                    TextButton(onClick = { o.unload(r.name) }) { Text("Entladen") }
                }
            }
        }

        if (pulls.isNotEmpty()) SectionCard("Downloads") {
            pulls.forEach { (name, p) ->
                Column(Modifier.padding(vertical = 4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(name, color = OP.Text, fontSize = 14.sp, modifier = Modifier.weight(1f))
                        if (p.error != null) TextButton(onClick = { o.dismissPull(name) }) { Text("OK") }
                        else TextButton(onClick = { o.cancelPull(name) }) { Text("Abbrechen") }
                    }
                    if (p.error != null) Text(p.error, color = OP.Red, fontSize = 12.sp)
                    else {
                        if (p.total > 0) LinearProgressIndicator(progress = { p.pct }, modifier = Modifier.fillMaxWidth(), color = OP.Green)
                        else LinearProgressIndicator(Modifier.fillMaxWidth(), color = OP.Green)
                        Text(
                            p.status + if (p.total > 0) "  ${humanBytes(p.completed)} / ${humanBytes(p.total)}" else "",
                            color = OP.TextDim, fontSize = 11.sp
                        )
                    }
                }
            }
        }

        SectionCard("Installiert (${models.size})", trailing = {
            IconButton(onClick = { o.refresh() }) { Icon(Icons.Default.Refresh, null, tint = OP.TextDim) }
        }) {
            if (models.isEmpty()) Text("Noch keine Modelle.", color = OP.TextDim, fontSize = 12.sp)
            models.forEach { m ->
                var menu by remember { mutableStateOf(false) }
                val loaded = running.any { it.name == m.name }
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(m.name, color = OP.Text, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, false))
                            if (loaded) { Spacer(Modifier.width(6.dp)); Pill("geladen", OP.Green) }
                        }
                        Text(
                            listOf(humanBytes(m.sizeBytes), m.paramSize, m.quant, m.family).filter { it.isNotBlank() }.joinToString(" · "),
                            color = OP.TextDim, fontSize = 11.sp
                        )
                    }
                    IconButton(onClick = { o.setModel(m.name); onChat() }) { Icon(Icons.Default.Chat, "Chatten", tint = OP.Green) }
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, null, tint = OP.TextDim) }
                        DropdownMenu(menu, { menu = false }) {
                            DropdownMenuItem(text = { Text("Laden (30 min)") }, onClick = { menu = false; o.load(m.name, "30m") })
                            DropdownMenuItem(text = { Text("Dauerhaft im Speicher") }, onClick = { menu = false; o.load(m.name, "-1") })
                            if (loaded) DropdownMenuItem(text = { Text("Entladen") }, onClick = { menu = false; o.unload(m.name) })
                            DropdownMenuItem(text = { Text("Details") }, onClick = { menu = false; showText(m.name) { o.details(m.name) } })
                            DropdownMenuItem(text = { Text("Aktualisieren (pull)") }, onClick = { menu = false; o.pull(m.name) })
                            HorizontalDivider()
                            DropdownMenuItem(text = { Text("Löschen", color = OP.Red) }, onClick = { menu = false; deleting = m.name })
                        }
                    }
                }
            }
        }

        SectionCard("Modell herunterladen") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    pullName, { pullName = it }, singleLine = true, modifier = Modifier.weight(1f),
                    label = { Text("Name, z. B. qwen3:4b") },
                )
                IconButton(enabled = pullName.isNotBlank(), onClick = { o.pull(pullName); pullName = "" }) {
                    Icon(Icons.Default.Download, "Laden", tint = OP.Green)
                }
            }
            Spacer(Modifier.height(8.dp))
            Text("Empfohlen für den Orin Nano 8 GB", color = OP.TextDim, fontSize = 12.sp)
            OllamaClient.SUGGESTED.forEach { s ->
                val have = models.any { it.name == s.tag || it.name == "${s.tag}:latest" }
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(s.title, color = OP.Text, fontSize = 13.sp)
                        Text("${s.tag} · ${s.size} · ${s.note}", color = OP.TextDim, fontSize = 11.sp)
                    }
                    if (have) Pill("vorhanden", OP.Green)
                    else TextButton(enabled = !pulls.containsKey(s.tag), onClick = { o.pull(s.tag) }) { Text("Laden") }
                }
            }
            Text("Weitere Modelle: ollama.com/library", color = OP.TextDim, fontSize = 11.sp)
        }
        Spacer(Modifier.height(8.dp))
    }

    deleting?.let { n ->
        ConfirmDialog("Modell löschen?", "$n wird vom Jetson entfernt.", confirm = "Löschen", danger = true,
            onConfirm = { o.delete(n) }, onDismiss = { deleting = null })
    }
}

// =====================================================================================================================
// Dienst & Einstellungen
// =====================================================================================================================

@Composable
private fun ServiceTab(vm: MainViewModel, runLive: (String, String, Boolean) -> Unit, show: (String, suspend () -> ExecResult) -> Unit) {
    val o = vm.ollama
    val st by o.status.collectAsState()
    val apiVer by o.apiVersion.collectAsState()
    val s = st ?: return
    val scope = rememberCoroutineScope()

    var env by remember(s.env) { mutableStateOf(s.env) }
    var modelsDir by remember(s.modelsDir) { mutableStateOf(s.modelsDir) }
    var confirmWebUi by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionCard("Ollama-Dienst", trailing = {
            IconButton(onClick = { o.refresh() }) { Icon(Icons.Default.Refresh, null, tint = OP.TextDim) }
        }) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Pill(if (s.installed) "installiert" else "nicht installiert", if (s.installed) OP.Green else OP.Amber)
                if (s.installed) Pill(if (s.serviceActive) "läuft" else "gestoppt", if (s.serviceActive) OP.Green else OP.Red)
                if (s.serviceEnabled) Pill("Autostart", OP.Cyan)
            }
            Spacer(Modifier.height(6.dp))
            KeyValue("Version", listOf(s.cliVersion, apiVer.takeIf { it.isNotBlank() }?.let { "API $it" }.orEmpty()).filter { it.isNotBlank() }.joinToString(" · "))
            KeyValue("Modelle", s.modelsDir)
            KeyValue("Speicher", s.diskFree)
            KeyValue("API", if (s.env.lanExposed) "im LAN erreichbar (Port 11434)" else "nur lokal (App nutzt SSH-Tunnel)")
            Spacer(Modifier.height(8.dp))
            ChipRow {
                if (s.installed) {
                    AssistChip(onClick = { show("Ollama starten") { vm.run(OllamaScripts.service("start"), sudo = true).also { o.refresh() } } }, label = { Text("Start") })
                    AssistChip(onClick = { show("Ollama stoppen") { vm.run(OllamaScripts.service("stop"), sudo = true).also { o.refresh() } } }, label = { Text("Stopp") })
                    AssistChip(onClick = { show("Ollama neu starten") { vm.run(OllamaScripts.service("restart"), sudo = true).also { o.refresh() } } }, label = { Text("Neustart") })
                    AssistChip(onClick = { show("Ollama-Log") { vm.run(OllamaScripts.LOGS, sudo = true) } }, label = { Text("Log") })
                    AssistChip(onClick = { show("GPU-Erkennung") { vm.run(OllamaScripts.GPU_INFO, sudo = true) } }, label = { Text("GPU-Check") })
                }
            }
            Spacer(Modifier.height(4.dp))
            Button(onClick = { runLive(if (s.installed) "Ollama aktualisieren" else "Ollama installieren", OllamaScripts.INSTALL, true) }) {
                Icon(Icons.Default.SystemUpdateAlt, null); Spacer(Modifier.width(6.dp))
                Text(if (s.installed) "Auf neueste Version aktualisieren" else "Installieren")
            }
        }

        if (s.installed) SectionCard("Leistung & Speicher") {
            Text("Diese Werte gelten für alle Programme, die Ollama nutzen. Speichern startet den Dienst neu.", color = OP.TextDim, fontSize = 12.sp)
            Spacer(Modifier.height(6.dp))
            SwitchRow("Flash Attention", env.flashAttention, "schneller, weniger Speicher bei langem Kontext") { env = env.copy(flashAttention = it) }
            Text("KV-Cache-Typ", color = OP.Text, fontSize = 14.sp)
            ChipRow {
                listOf("" to "f16 (Standard)", "q8_0" to "q8_0 (½ RAM)", "q4_0" to "q4_0 (¼ RAM)").forEach { (v, l) ->
                    FilterChip(selected = env.kvCacheType == v, onClick = { env = env.copy(kvCacheType = v) }, label = { Text(l) })
                }
            }
            if (env.kvCacheType.isNotBlank() && !env.flashAttention) Text("Quantisierter KV-Cache braucht Flash Attention.", color = OP.Amber, fontSize = 11.sp)
            Spacer(Modifier.height(4.dp))
            Text("Standard-Kontextlänge", color = OP.Text, fontSize = 14.sp)
            ChipRow {
                listOf("" to "Standard", "2048" to "2k", "4096" to "4k", "8192" to "8k", "16384" to "16k").forEach { (v, l) ->
                    FilterChip(selected = env.contextLength == v, onClick = { env = env.copy(contextLength = v) }, label = { Text(l) })
                }
            }
            Text("Modell im Speicher halten", color = OP.Text, fontSize = 14.sp)
            ChipRow {
                listOf("" to "5 min", "30m" to "30 min", "2h" to "2 h", "-1" to "immer").forEach { (v, l) ->
                    FilterChip(selected = env.keepAlive == v, onClick = { env = env.copy(keepAlive = v) }, label = { Text(l) })
                }
            }
            Text("Gleichzeitig geladene Modelle / parallele Anfragen", color = OP.Text, fontSize = 14.sp)
            ChipRow {
                listOf("1", "2").forEach { v ->
                    FilterChip(selected = env.maxLoaded == v, onClick = { env = env.copy(maxLoaded = if (env.maxLoaded == v) "" else v) }, label = { Text("$v Modell") })
                }
                listOf("1", "2", "4").forEach { v ->
                    FilterChip(selected = env.numParallel == v, onClick = { env = env.copy(numParallel = if (env.numParallel == v) "" else v) }, label = { Text("$v parallel") })
                }
            }
            Spacer(Modifier.height(4.dp))
            SwitchRow("Im LAN freigeben", env.lanExposed, "für Open WebUI, VS Code & Co. auf anderen Geräten (ohne Passwortschutz!)") {
                env = env.copy(host = if (it) "0.0.0.0:11434" else "")
            }
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(enabled = env != s.env, onClick = { runLive("Ollama-Einstellungen", OllamaScripts.applyEnv(env.toEnvLines()), true) }) { Text("Speichern & Neustart") }
                TextButton(onClick = {
                    env = OllamaEnv(flashAttention = true, kvCacheType = "q8_0", maxLoaded = "1", numParallel = "1", contextLength = "4096", keepAlive = "30m", host = env.host)
                }) { Text("Jetson-Empfehlung") }
            }
        }

        if (s.installed) SectionCard("Modellordner") {
            Text("Modelle sind groß. Liegt das System auf einer SD-Karte, lohnt sich ein Ordner auf der NVMe-SSD.", color = OP.TextDim, fontSize = 12.sp)
            OutlinedTextField(modelsDir, { modelsDir = it }, singleLine = true, label = { Text("Pfad") }, modifier = Modifier.fillMaxWidth())
            Button(enabled = modelsDir.isNotBlank() && modelsDir != s.modelsDir, onClick = {
                runLive("Modellordner verlegen", OllamaScripts.moveModels(modelsDir.trim()), true)
            }) { Text("Verlegen") }
        }

        if (s.installed) SectionCard("Open WebUI") {
            Text(
                "Browser-Oberfläche für Ollama (wie ChatGPT) als Docker-Container auf dem Jetson. Danach erreichbar unter http://<Jetson-IP>:8080. " +
                    "Download ca. 2–4 GB.",
                color = OP.TextDim, fontSize = 12.sp
            )
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { confirmWebUi = true }) { Text("Installieren / starten") }
                TextButton(onClick = { show("Open WebUI") { vm.runMaybeSudo("docker ps -a --filter name=open-webui --format '{{.Names}}: {{.Status}}' 2>&1") } }) { Text("Status") }
            }
        }
        Spacer(Modifier.height(8.dp))
    }

    if (confirmWebUi) ConfirmDialog(
        "Open WebUI installieren?",
        "Lädt das Image ghcr.io/open-webui/open-webui und startet es mit --network=host und Autostart.",
        onConfirm = { runLive("Open WebUI", OPEN_WEBUI, true) },
        onDismiss = { confirmWebUi = false },
    )
}

private const val OPEN_WEBUI =
    "docker rm -f open-webui >/dev/null 2>&1; docker pull ghcr.io/open-webui/open-webui:main && " +
        "docker run -d --network=host -v open-webui:/app/backend/data -e OLLAMA_BASE_URL=http://127.0.0.1:11434 " +
        "--name open-webui --restart always ghcr.io/open-webui/open-webui:main && echo 'Läuft auf Port 8080.'"

// =====================================================================================================================
// Benchmark
// =====================================================================================================================

@Composable
private fun BenchTab(vm: MainViewModel) {
    val o = vm.ollama
    val models by o.models.collectAsState()
    val results by o.bench.collectAsState()
    val runningModel by o.benchRunning.collectAsState()
    val selected = remember { mutableStateListOf<String>() }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionCard("Geschwindigkeitstest") {
            Text(
                "Misst Ladezeit, Prompt-Verarbeitung und Generierung (200 Token) für jedes gewählte Modell. " +
                    "Tipp: vorher Energiemodus MAXN SUPER + jetson_clocks aktivieren.",
                color = OP.TextDim, fontSize = 12.sp
            )
            Spacer(Modifier.height(6.dp))
            models.filter { !it.family.contains("bert") && !it.name.contains("embed") }.forEach { m ->
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable {
                    if (m.name in selected) selected.remove(m.name) else selected.add(m.name)
                }) {
                    Checkbox(m.name in selected, { if (it) selected.add(m.name) else selected.remove(m.name) })
                    Text(m.name, color = OP.Text, fontSize = 14.sp, modifier = Modifier.weight(1f))
                    Text(m.paramSize, color = OP.TextDim, fontSize = 12.sp)
                }
            }
            Spacer(Modifier.height(6.dp))
            Button(enabled = selected.isNotEmpty() && runningModel == null, onClick = { o.benchmark(selected.toList()) }) {
                if (runningModel != null) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = OP.Text)
                    Spacer(Modifier.width(8.dp))
                    Text("Teste $runningModel …", maxLines = 1)
                } else Text("Test starten")
            }
        }

        if (results.isNotEmpty()) SectionCard("Ergebnisse") {
            Row(Modifier.fillMaxWidth()) {
                Text("Modell", color = OP.TextDim, fontSize = 11.sp, modifier = Modifier.weight(1f))
                Text("Laden", color = OP.TextDim, fontSize = 11.sp, modifier = Modifier.width(54.dp))
                Text("Prompt", color = OP.TextDim, fontSize = 11.sp, modifier = Modifier.width(58.dp))
                Text("Gen.", color = OP.TextDim, fontSize = 11.sp, modifier = Modifier.width(62.dp))
            }
            val best = results.mapNotNull { it.stats?.tokPerSec }.maxOrNull() ?: 0f
            results.sortedByDescending { it.stats?.tokPerSec ?: -1f }.forEach { r ->
                Column(Modifier.padding(vertical = 4.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(r.model, color = OP.Text, fontSize = 13.sp, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        val s = r.stats
                        if (s == null) Text("Fehler", color = OP.Red, fontSize = 12.sp)
                        else {
                            Text(String.format(Locale.GERMANY, "%.1fs", s.loadMs / 1000f), color = OP.TextDim, fontSize = 12.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.width(54.dp))
                            Text(String.format(Locale.GERMANY, "%.0f t/s", s.promptTokPerSec), color = OP.TextDim, fontSize = 12.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.width(58.dp))
                            Text(String.format(Locale.GERMANY, "%.1f t/s", s.tokPerSec), color = OP.Green, fontSize = 12.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.width(62.dp))
                        }
                    }
                    r.stats?.let { s -> if (best > 0) LoadBar(s.tokPerSec * 100f / best, OP.Green, barHeight = 4) }
                    r.error?.let { Text(it, color = OP.Red, fontSize = 11.sp) }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}
