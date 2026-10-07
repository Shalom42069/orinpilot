package de.marlon.orinpilot.ui.screens

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.marlon.orinpilot.MainViewModel
import de.marlon.orinpilot.data.JetsonScripts
import de.marlon.orinpilot.data.OllamaScripts
import de.marlon.orinpilot.data.Parsers
import de.marlon.orinpilot.data.Scripts
import de.marlon.orinpilot.ssh.ExecResult
import de.marlon.orinpilot.ui.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/** Steuerung mit Unterseiten: Leistung & Netz, Jetson-Werkzeuge, Kamera */
@Composable
fun ControlHub(vm: MainViewModel) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            ChipRow {
                listOf("Leistung & Netz", "Jetson-Werkzeuge", "Kamera").forEachIndexed { i, t ->
                    FilterChip(selected = tab == i, onClick = { tab = i }, label = { Text(t) })
                }
            }
        }
        Box(Modifier.weight(1f)) {
            when (tab) {
                0 -> ControlScreen(vm)
                1 -> JetsonToolsScreen(vm)
                else -> CameraScreen(vm)
            }
        }
    }
}

// =====================================================================================================================
// Jetson-Werkzeuge
// =====================================================================================================================

@Composable
fun JetsonToolsScreen(vm: MainViewModel) {
    val scope = rememberCoroutineScope()
    var inv by remember { mutableStateOf<Map<String, String>?>(null) }
    var swap by remember { mutableStateOf<String?>(null) }
    var live by remember { mutableStateOf<Pair<String, Flow<String>>?>(null) }
    var outTitle by remember { mutableStateOf<String?>(null) }
    var outText by remember { mutableStateOf<String?>(null) }
    var outBusy by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf<Triple<String, String, () -> Unit>?>(null) }
    var swapGb by rememberSaveable { mutableIntStateOf(8) }

    fun kv(out: String) = out.lines().mapNotNull { l ->
        val i = l.indexOf('='); if (i <= 0) null else l.substring(0, i) to l.substring(i + 1).trim()
    }.toMap()

    fun loadInv() = scope.launch { inv = kv(vm.run(JetsonScripts.INVENTORY, timeoutMs = 20_000).out) }
    fun loadSwap() = scope.launch { swap = vm.run(JetsonScripts.SWAP_STATUS).out }
    fun runLive(title: String, cmd: String, sudo: Boolean = true) { live = title to vm.live(cmd, sudo) }
    fun show(title: String, block: suspend () -> ExecResult) {
        outTitle = title; outText = null; outBusy = true
        scope.launch {
            val r = block()
            outText = (if (r.ok) "" else "Exit-Code ${r.exit}\n") + r.text
            outBusy = false
        }
    }
    fun ask(t: String, m: String, a: () -> Unit) { confirm = Triple(t, m, a) }

    LaunchedEffect(Unit) { loadInv(); loadSwap() }

    // Bausteine der LLM-Optimierung
    var optMaxn by rememberSaveable { mutableStateOf(true) }
    var optClocks by rememberSaveable { mutableStateOf(true) }
    var optHeadless by rememberSaveable { mutableStateOf(true) }
    var optSwap by rememberSaveable { mutableStateOf(true) }
    var optSlim by rememberSaveable { mutableStateOf(true) }
    var optOllama by rememberSaveable { mutableStateOf(true) }
    val ollamaStatus by vm.ollama.status.collectAsState()

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // ------------------------------------------------------ Inventar
        SectionCard("Hardware & Software", trailing = {
            IconButton(onClick = { inv = null; loadInv() }) { Icon(Icons.Default.Refresh, null, tint = OP.TextDim) }
        }) {
            val i = inv
            if (i == null) Busy() else {
                KeyValue("Modell", i["MODEL"].orEmpty())
                KeyValue("Modul", i["MODULE"].orEmpty())
                KeyValue("Seriennr.", i["SERIAL"].orEmpty())
                KeyValue("L4T", Parsers.prettyL4t(i["L4T"].orEmpty()))
                KeyValue("JetPack", i["JETPACK"].orEmpty().ifBlank { "nicht installiert (nur L4T)" })
                KeyValue("CUDA", i["CUDA"].orEmpty())
                KeyValue("cuDNN", i["CUDNN"].orEmpty())
                KeyValue("TensorRT", i["TENSORRT"].orEmpty())
                KeyValue("VPI", i["VPI"].orEmpty())
                KeyValue("OpenCV", i["OPENCV"].orEmpty())
                KeyValue("Python", i["PYTHON"].orEmpty())
                KeyValue("Docker", i["DOCKER"].orEmpty() + if (i["DOCKER"].isNullOrBlank()) "" else " · NVIDIA-Runtime: ${i["NVRUNTIME"]}")
                KeyValue("jtop", i["JTOP"].orEmpty())
                KeyValue("Root-FS", i["BOOTDEV"].orEmpty())
                KeyValue("NVMe", i["NVME"].orEmpty().ifBlank { "keine" })
            }
            Spacer(Modifier.height(6.dp))
            ChipRow {
                AssistChip(onClick = { show("Laufwerke") { vm.run(JetsonScripts.STORAGE) } }, label = { Text("Laufwerke") })
                AssistChip(onClick = { show("PyTorch / CUDA") { vm.run(JetsonScripts.TORCH_CHECK, timeoutMs = 90_000) } }, label = { Text("PyTorch-Check") })
                AssistChip(onClick = { show("deviceQuery") { vm.run(JetsonScripts.CUDA_SAMPLE) } }, label = { Text("CUDA deviceQuery") })
                AssistChip(onClick = { show("Thermik & Drosselung") { vm.run(JetsonScripts.THROTTLE, sudo = true) } }, label = { Text("Thermik") })
                AssistChip(onClick = { show("Kernel-Warnungen") { vm.run(JetsonScripts.DMESG_ERR, sudo = true) } }, label = { Text("dmesg") })
                AssistChip(onClick = { show("Bootzeit") { vm.run(JetsonScripts.BOOT_TIME) } }, label = { Text("Bootzeit") })
            }
        }

        // ------------------------------------------------------ LLM-Optimierung
        SectionCard("Für KI optimieren") {
            Text(
                "Holt das Maximum für lokale Sprachmodelle aus dem Orin Nano heraus. Alles einzeln abwählbar; " +
                    "Energiemodus und Headless wirken vollständig erst nach einem Neustart.",
                color = OP.TextDim, fontSize = 12.sp
            )
            Spacer(Modifier.height(4.dp))
            OptRow("Energiemodus MAXN SUPER", "höchste Takte (25 W)", optMaxn) { optMaxn = it }
            OptRow("jetson_clocks", "Takte fixieren, keine Hochlaufzeit", optClocks) { optClocks = it }
            OptRow("Headless (ohne Desktop)", "spart ca. 0,5–1 GB RAM", optHeadless) { optHeadless = it }
            OptRow("zram aus, Swapfile $swapGb GB", "echter Swap statt komprimiertem RAM", optSwap) { optSwap = it }
            OptRow("Unnötige Dienste aus", "Drucker, Absturzberichte, snap", optSlim) { optSlim = it }
            if (ollamaStatus?.installed == true) OptRow("Ollama: Flash Attention + q8_0-KV-Cache", "halber Kontextspeicher", optOllama) { optOllama = it }
            Spacer(Modifier.height(6.dp))
            Button(onClick = {
                ask("Optimierung anwenden?", "Die gewählten Änderungen werden mit sudo ausgeführt. Ein Neustart wird empfohlen.") {
                    scope.launch {
                        val steps = mutableListOf<String>()
                        if (optMaxn) {
                            val modes = Parsers.powerModes(vm.run(Scripts.NVP_CONF).out)
                            val maxn = modes.firstOrNull { it.name.contains("MAXN", true) && it.name.contains("SUPER", true) }
                                ?: modes.firstOrNull { it.name.contains("MAXN", true) }
                            steps += if (maxn != null) "echo '== Energiemodus ${maxn.name} =='; echo no | nvpmodel -m ${maxn.id} || true"
                            else "echo 'Kein MAXN-Modus gefunden (JetPack 6.2 nötig für MAXN SUPER).'"
                        }
                        if (optClocks) steps += "echo '== jetson_clocks =='; jetson_clocks && echo OK"
                        if (optHeadless) steps += "echo '== Headless =='; systemctl set-default multi-user.target"
                        if (optSwap) steps += "echo '== Swap =='; ${JetsonScripts.ZRAM_OFF}; (${JetsonScripts.createSwapfile(swapGb)})"
                        if (optSlim) steps += "echo '== Dienste =='; ${JetsonScripts.DESKTOP_SLIM}"
                        val st = ollamaStatus
                        if (optOllama && st?.installed == true) {
                            val env = st.env.copy(flashAttention = true, kvCacheType = "q8_0")
                            steps += "echo '== Ollama =='; ${OllamaScripts.applyEnv(env.toEnvLines())}"
                        }
                        steps += "echo; echo 'Fertig. Neustart empfohlen.'"
                        runLive("KI-Optimierung", steps.joinToString("\n"))
                    }
                }
            }) { Icon(Icons.Default.Bolt, null); Spacer(Modifier.width(6.dp)); Text("Anwenden") }
        }

        // ------------------------------------------------------ Swap
        SectionCard("Swap & zram", trailing = {
            IconButton(onClick = { swap = null; loadSwap() }) { Icon(Icons.Default.Refresh, null, tint = OP.TextDim) }
        }) {
            val sw = swap
            if (sw == null) Busy() else Text(
                sw.lines().filterNot { it.startsWith("ZRAM=") || it.startsWith("SWAPFILE=") }.joinToString("\n").trim(),
                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, fontSize = 10.5.sp, color = OP.Text
            )
            val m = sw?.let { kv(it) }.orEmpty()
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Pill("zram: ${m["ZRAM"].orEmpty().ifBlank { "?" }}", if (m["ZRAM"] == "enabled") OP.Cyan else OP.TextDim)
                Pill("Swapfile: ${m["SWAPFILE"].orEmpty().ifBlank { "?" }}", OP.Cyan)
            }
            Spacer(Modifier.height(6.dp))
            Text("Swapfile-Größe", color = OP.Text, fontSize = 13.sp)
            ChipRow {
                listOf(4, 8, 16).forEach { g -> FilterChip(selected = swapGb == g, onClick = { swapGb = g }, label = { Text("$g GB") }) }
            }
            ChipRow {
                AssistChip(onClick = { ask("Swapfile anlegen?", "/swapfile mit $swapGb GB wird (neu) angelegt und in /etc/fstab eingetragen.") { runLive("Swapfile $swapGb GB", JetsonScripts.createSwapfile(swapGb)) } }, label = { Text("Swapfile anlegen") })
                AssistChip(onClick = { runLive("Swapfile entfernen", JetsonScripts.REMOVE_SWAPFILE) }, label = { Text("Swapfile entfernen") })
                AssistChip(onClick = { runLive("zram aus", JetsonScripts.ZRAM_OFF) }, label = { Text("zram aus") })
                AssistChip(onClick = { runLive("zram an", JetsonScripts.ZRAM_ON) }, label = { Text("zram an") })
                AssistChip(onClick = { show("Cache leeren") { vm.run(JetsonScripts.DROP_CACHES, sudo = true) } }, label = { Text("RAM-Cache leeren") })
            }
        }

        // ------------------------------------------------------ Software
        SectionCard("Software & Updates") {
            ChipRow {
                AssistChip(onClick = { runLive("apt update", JetsonScripts.APT_UPDATE) }, label = { Text("Updates suchen") })
                AssistChip(onClick = { ask("System aktualisieren?", "apt-get upgrade installiert alle verfügbaren Updates (auch L4T-Pakete).") { runLive("System-Upgrade", JetsonScripts.APT_UPGRADE) } }, label = { Text("Upgrade") })
                AssistChip(onClick = { runLive("Aufräumen", JetsonScripts.APT_CLEAN) }, label = { Text("Aufräumen") })
            }
            ChipRow {
                AssistChip(onClick = { ask("nvidia-jetpack installieren?", "Installiert CUDA, cuDNN, TensorRT, VPI, Multimedia-API usw. (mehrere GB).") { runLive("nvidia-jetpack", JetsonScripts.JETPACK_INSTALL) } }, label = { Text("JetPack-SDK") })
                AssistChip(onClick = { runLive("jtop installieren", JetsonScripts.JTOP_INSTALL) }, label = { Text("jtop") })
                AssistChip(onClick = { show("Docker-Runtime") { vm.runMaybeSudo(JetsonScripts.DOCKER_NV_CHECK) } }, label = { Text("Docker-GPU prüfen") })
                AssistChip(onClick = { ask("NVIDIA als Docker-Standard?", "Container bekommen automatisch GPU-Zugriff. Docker wird neu gestartet.") { runLive("Docker NVIDIA-Runtime", JetsonScripts.DOCKER_NV_DEFAULT) } }, label = { Text("GPU für Docker") })
            }
        }
        Spacer(Modifier.height(8.dp))
    }

    live?.let { (t, f) -> LiveOutputDialog(t, f, onDismiss = { live = null; loadSwap(); loadInv(); vm.ollama.refresh() }) }
    outTitle?.let { t -> OutputDialog(t, outText, outBusy) { outTitle = null } }
    confirm?.let { (t, m, a) -> ConfirmDialog(t, m, onConfirm = a, onDismiss = { confirm = null }) }
}

@Composable
private fun OptRow(title: String, sub: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!checked) }, verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked, onChange)
        Column {
            Text(title, color = OP.Text, fontSize = 14.sp)
            Text(sub, color = OP.TextDim, fontSize = 11.sp)
        }
    }
}

// =====================================================================================================================
// Kamera
// =====================================================================================================================

private data class Cam(val dev: String, val name: String, val csiId: Int?)

@Composable
fun CameraScreen(vm: MainViewModel) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var cams by remember { mutableStateOf<List<Cam>?>(null) }
    var sel by remember { mutableStateOf<Cam?>(null) }
    var res by rememberSaveable { mutableIntStateOf(1) }
    var busy by remember { mutableStateOf(false) }
    var img by remember { mutableStateOf<Bitmap?>(null) }
    var jpeg by remember { mutableStateOf<ByteArray?>(null) }
    var err by remember { mutableStateOf<String?>(null) }
    val sizes = listOf(640 to 480, 1280 to 720, 1920 to 1080)

    fun loadCams() = scope.launch {
        var csi = 0
        cams = vm.run(JetsonScripts.CAMERAS).out.lines().filter { it.contains('|') }.map {
            val dev = it.substringBefore('|'); val name = it.substringAfter('|')
            // CSI-Kameras hängen am Tegra-VI ("vi-output, imx219 …")
            val isCsi = name.contains("vi-output", true) || name.contains("imx", true)
            Cam(dev, name.ifBlank { dev }, if (isCsi) csi++ else null)
        }.filter { it.csiId != null || !it.name.contains("metadata", true) }
        if (sel == null) sel = cams?.firstOrNull()
    }

    fun snap() {
        val c = sel ?: return
        busy = true; err = null
        scope.launch {
            val (w, h) = sizes[res]
            val r = vm.run(JetsonScripts.snapshot(c.dev, c.csiId, w, h), timeoutMs = 60_000)
            if (r.out.contains("SNAP_OK")) {
                val bytes = runCatching {
                    vm.connection?.sftp { ch -> ByteArrayOutputStream().also { ch.get(JetsonScripts.SNAP_PATH, it) }.toByteArray() }
                }.getOrNull()
                if (bytes != null) {
                    jpeg = bytes
                    img = withContext(Dispatchers.Default) { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }
                } else err = "Bild konnte nicht geladen werden."
            } else err = r.text.ifBlank { "Keine Aufnahme (Kamera belegt oder nicht unterstützt?)" }
            busy = false
        }
    }

    val saver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/jpeg")) { uri: Uri? ->
        val b = jpeg
        if (uri != null && b != null) scope.launch(Dispatchers.IO) {
            runCatching { ctx.contentResolver.openOutputStream(uri)?.use { it.write(b) } }
            vm.toast("Gespeichert")
        }
    }

    LaunchedEffect(Unit) { loadCams() }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionCard("Kameras", trailing = {
            IconButton(onClick = { cams = null; loadCams() }) { Icon(Icons.Default.Refresh, null, tint = OP.TextDim) }
        }) {
            val l = cams
            when {
                l == null -> Busy()
                l.isEmpty() -> Text("Keine Kamera gefunden (CSI-Kamera: Flachbandkabel prüfen, ggf. jetson-io konfigurieren).", color = OP.TextDim, fontSize = 13.sp)
                else -> l.forEach { c ->
                    Row(Modifier.fillMaxWidth().clickable { sel = c }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(sel == c, { sel = c })
                        Column(Modifier.weight(1f)) {
                            Text(c.name, color = OP.Text, fontSize = 14.sp)
                            Text(c.dev + if (c.csiId != null) " · CSI (sensor-id ${c.csiId})" else " · USB/V4L2", color = OP.TextDim, fontSize = 11.sp)
                        }
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
            ChipRow {
                sizes.forEachIndexed { i, (w, h) -> FilterChip(selected = res == i, onClick = { res = i }, label = { Text("${w}×$h") }) }
            }
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(enabled = sel != null && !busy, onClick = { snap() }) {
                    if (busy) { CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = OP.Text); Spacer(Modifier.width(8.dp)) }
                    else { Icon(Icons.Default.PhotoCamera, null); Spacer(Modifier.width(6.dp)) }
                    Text("Foto aufnehmen")
                }
                OutlinedButton(enabled = jpeg != null, onClick = { saver.launch("jetson_${System.currentTimeMillis() / 1000}.jpg") }) { Text("Speichern") }
            }
        }
        err?.let {
            SectionCard("Fehler") { Text(it, color = OP.Amber, fontSize = 12.sp, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace) }
        }
        img?.let { b ->
            Image(
                b.asImageBitmap(), "Aufnahme",
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(OP.Surface),
                contentScale = ContentScale.FillWidth
            )
            Text("${b.width}×${b.height} · ${humanBytes((jpeg?.size ?: 0).toLong())}", color = OP.TextDim, fontSize = 11.sp)
        }
        Text(
            "Hinweis: Während eine andere Anwendung (z. B. ein KI-Skript) die Kamera nutzt, ist keine Aufnahme möglich. " +
                "CSI-Kameras brauchen den nvargus-daemon.",
            color = OP.TextDim, fontSize = 11.sp
        )
    }
}
