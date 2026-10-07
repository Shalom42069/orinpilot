package de.marlon.orinpilot.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.marlon.orinpilot.ConnState
import de.marlon.orinpilot.MainViewModel
import de.marlon.orinpilot.data.Parsers
import de.marlon.orinpilot.data.PowerMode
import de.marlon.orinpilot.data.Scripts
import de.marlon.orinpilot.data.WifiNetwork
import de.marlon.orinpilot.ssh.ExecResult
import de.marlon.orinpilot.ssh.SshConnection
import de.marlon.orinpilot.ui.ChipRow
import de.marlon.orinpilot.ui.ConfirmDialog
import de.marlon.orinpilot.ui.OP
import de.marlon.orinpilot.ui.OutputDialog
import de.marlon.orinpilot.ui.Pill
import de.marlon.orinpilot.ui.SectionCard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun ControlScreen(vm: MainViewModel) {
    val scope = rememberCoroutineScope()
    val state by vm.state.collectAsState()
    val profile = (state as? ConnState.Connected)?.profile

    // gemeinsamer Ausgabe-Dialog
    var outTitle by remember { mutableStateOf<String?>(null) }
    var outText by remember { mutableStateOf<String?>(null) }
    var outBusy by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf<Triple<String, String, () -> Unit>?>(null) }

    fun ask(title: String, message: String, action: () -> Unit) {
        confirm = Triple(title, message, action)
    }

    fun show(title: String, block: suspend () -> ExecResult, after: () -> Unit = {}) {
        outTitle = title; outText = null; outBusy = true
        scope.launch {
            val r = block()
            outText = (if (r.ok) "" else "Exit-Code ${r.exit}\n") + r.text
            outBusy = false
            after()
        }
    }

    // ---------------------------------------------------------- Zustände
    var modes by remember { mutableStateOf<List<PowerMode>>(emptyList()) }
    var currentMode by remember { mutableStateOf<Int?>(null) }
    var fanPwm by remember { mutableFloatStateOf(0f) }
    var fanInfo by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var target by remember { mutableStateOf("") }
    var wifi by remember { mutableStateOf<List<WifiNetwork>?>(null) }
    var wifiConnect by remember { mutableStateOf<WifiNetwork?>(null) }

    fun loadPower() = scope.launch {
        modes = Parsers.powerModes(vm.run(Scripts.NVP_CONF).out)
        var q = vm.run(Scripts.NVP_QUERY)
        if (Parsers.currentPowerMode(q.out) == null) q = vm.run(Scripts.NVP_QUERY, sudo = true)
        currentMode = Parsers.currentPowerMode(q.out)
    }

    fun loadFan() = scope.launch {
        val r = vm.run(Scripts.FAN_STATUS)
        fanInfo = r.out.lines().mapNotNull { l ->
            val i = l.indexOf('='); if (i > 0) l.substring(0, i) to l.substring(i + 1).trim() else null
        }.toMap()
        fanPwm = fanInfo["PWM"]?.toFloatOrNull() ?: 0f
    }

    fun loadTarget() = scope.launch { target = vm.run(Scripts.GET_TARGET).out.trim() }

    LaunchedEffect(Unit) {
        loadPower(); loadFan(); loadTarget()
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        RemoteAccessCard(vm)

        // ------------------------------------------------------ Energiemodus
        SectionCard("Energiemodus (nvpmodel)") {
            if (modes.isEmpty()) {
                Text("Keine Modi gefunden (/etc/nvpmodel.conf).", color = OP.TextDim, fontSize = 13.sp)
            }
            ChipRow {
                modes.forEach { m ->
                    FilterChip(
                        selected = m.id == currentMode,
                        onClick = {
                            if (m.id != currentMode) ask(
                                "Modus ${m.name} setzen?",
                                "Manche Wechsel (z. B. auf MAXN SUPER) werden erst nach einem Neustart aktiv.",
                            ) {
                                show("nvpmodel -m ${m.id}", { vm.run(Scripts.nvpSet(m.id), sudo = true, stdin = "no\n") }) { loadPower() }
                            }
                        },
                        label = { Text(m.name) }
                    )
                }
            }
        }

        // ------------------------------------------------------ Takt
        SectionCard("Maximaltakt (jetson_clocks)") {
            Text(
                "Fixiert CPU/GPU/EMC auf den Maximaltakt des aktuellen Modus – mehr Leistung, mehr Wärme.",
                color = OP.TextDim, fontSize = 12.sp
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { show("jetson_clocks", { vm.run(Scripts.JC_ON, sudo = true) }) }) { Text("Aktivieren") }
                OutlinedButton(onClick = { show("jetson_clocks --restore", { vm.run(Scripts.JC_OFF, sudo = true) }) }) { Text("Zurücksetzen") }
                TextButton(onClick = { show("Takte", { vm.run(Scripts.JC_SHOW, sudo = true) }) }) { Text("Anzeigen") }
            }
        }

        // ------------------------------------------------------ Lüfter
        SectionCard("Lüfter") {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Pill("PWM ${fanInfo["PWM"].orEmpty().ifBlank { "?" }}/255", OP.Cyan)
                fanInfo["RPM"]?.takeIf { it.isNotBlank() }?.let { Pill("$it U/min", OP.Cyan) }
                Pill(
                    if (fanInfo["SERVICE"] == "active") "Automatik: ${fanInfo["PROFILE"].orEmpty().ifBlank { "?" }}" else "Manuell",
                    if (fanInfo["SERVICE"] == "active") OP.Green else OP.Amber
                )
            }
            Spacer(Modifier.height(8.dp))
            ChipRow {
                listOf("quiet", "cool").forEach { p ->
                    FilterChip(
                        selected = fanInfo["SERVICE"] == "active" && fanInfo["PROFILE"] == p,
                        onClick = { show("Lüfterprofil $p", { vm.run(Scripts.fanProfile(p), sudo = true) }) { loadFan() } },
                        label = { Text(if (p == "quiet") "Automatik leise" else "Automatik kühl") }
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            Text("Manuell: ${fanPwm.toInt()} / 255 (${(fanPwm / 2.55f).toInt()} %)", fontSize = 13.sp, color = OP.Text)
            Slider(
                value = fanPwm, onValueChange = { fanPwm = it }, valueRange = 0f..255f,
                onValueChangeFinished = {
                    scope.launch { vm.run(Scripts.fanManual(fanPwm.toInt()), sudo = true); loadFan() }
                }
            )
            Text("Manuelle Werte stoppen nvfancontrol – mit einem Automatik-Profil wieder einschalten.", color = OP.TextDim, fontSize = 11.sp)
        }

        // ------------------------------------------------------ Headless
        SectionCard("Desktop / Headless") {
            val headless = target.startsWith("multi-user")
            Text(
                if (headless) "Der Jetson startet ohne Desktop – spart ca. 0,5–1 GB RAM für KI-Modelle."
                else "Der Jetson startet mit Desktop (GNOME). Für reinen Handy-Betrieb lohnt sich Headless.",
                color = OP.TextDim, fontSize = 12.sp
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(enabled = !headless && target.isNotEmpty(), onClick = {
                    ask("Headless-Modus?", "Der Desktop wird sofort beendet und startet auch nach Neustarts nicht mehr.") {
                        show("Headless", { vm.run(Scripts.HEADLESS_ON, sudo = true, timeoutMs = 60_000) }) { loadTarget() }
                    }
                }) { Text("Headless") }
                OutlinedButton(enabled = headless, onClick = {
                    show("Desktop", { vm.run(Scripts.HEADLESS_OFF, sudo = true, timeoutMs = 60_000) }) { loadTarget() }
                }) { Text("Desktop starten") }
            }
        }

        // ------------------------------------------------------ WLAN
        SectionCard("Netzwerk / WLAN", trailing = { Icon(Icons.Default.Wifi, null, tint = OP.TextDim) }) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    wifi = null
                    scope.launch { wifi = Parsers.wifi(vm.run(Scripts.WIFI_LIST, sudo = true, timeoutMs = 40_000).out) }
                }) { Text("WLANs suchen") }
                TextButton(onClick = { show("Aktive Verbindungen", { vm.run(Scripts.NET_ACTIVE) }) }) { Text("Status") }
            }
            wifi?.let { list ->
                if (list.isEmpty()) Text("Keine Netze gefunden (WLAN-Modul vorhanden?)", color = OP.TextDim, fontSize = 13.sp)
                list.take(15).forEach { n ->
                    Row(
                        Modifier.fillMaxWidth().clickable { wifiConnect = n }.padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(n.ssid, color = if (n.inUse) OP.Green else OP.Text, modifier = Modifier.weight(1f))
                        if (n.security.isNotBlank()) Icon(Icons.Default.Lock, null, tint = OP.TextDim, modifier = Modifier.padding(end = 6.dp))
                        Text("${n.signal}%", color = OP.TextDim, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                    }
                }
            }
            Text(
                "Achtung: Wechselt der Jetson das WLAN, bricht die LAN-Verbindung ab – mit eingerichtetem Fernzugriff verbindet sich die App danach über Tailscale.",
                color = OP.TextDim, fontSize = 11.sp
            )
        }

        // ------------------------------------------------------ Sicherheit
        SectionCard("Sicherheit") {
            Text(
                if (profile?.privateKey.isNullOrBlank()) "Anmeldung per Passwort. Ein SSH-Schlüssel ist sicherer, besonders mit Fernzugriff."
                else "Schlüssel-Login aktiv.",
                color = OP.TextDim, fontSize = 12.sp
            )
            Spacer(Modifier.height(6.dp))
            OutlinedButton(onClick = {
                val p = profile ?: return@OutlinedButton
                show("Schlüssel-Login einrichten", {
                    val (priv, pub) = withContext(Dispatchers.Default) { SshConnection.generateKeyPair("orinpilot@${android.os.Build.MODEL}") }
                    val r = vm.run(Scripts.authorizeKey(pub))
                    if (r.out.contains("OK")) {
                        vm.saveProfile(p.copy(privateKey = priv, keyPassphrase = ""))
                        ExecResult(0, "RSA-3072-Schlüssel erzeugt und in ~/.ssh/authorized_keys eingetragen.\n" +
                            "Wird ab der nächsten Verbindung genutzt. Das Passwort bleibt für sudo gespeichert.\n\n$pub", "")
                    } else r
                })
            }) { Text("SSH-Schlüssel erzeugen & installieren") }
        }

        // ------------------------------------------------------ Energie
        SectionCard("Ein/Aus") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    ask("Neustart?", "Der Jetson startet neu. Die Verbindung wird getrennt.") {
                        scope.launch { vm.run(Scripts.REBOOT, sudo = true, timeoutMs = 8_000); vm.disconnect() }
                    }
                }) { Text("Neustart") }
                OutlinedButton(onClick = {
                    ask("Herunterfahren?", "Zum Wiedereinschalten ist physischer Zugriff (Strom) nötig.") {
                        scope.launch { vm.run(Scripts.POWEROFF, sudo = true, timeoutMs = 8_000); vm.disconnect() }
                    }
                }) { Text("Herunterfahren", color = OP.Red) }
            }
        }
        Spacer(Modifier.width(1.dp).height(8.dp))
    }

    outTitle?.let { t -> OutputDialog(t, outText, outBusy) { outTitle = null } }
    confirm?.let { (t, m, action) ->
        ConfirmDialog(t, m, onConfirm = action, onDismiss = { confirm = null })
    }
    wifiConnect?.let { n ->
        var pw by remember(n.ssid) { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { wifiConnect = null },
            title = { Text("Mit \"${n.ssid}\" verbinden") },
            text = {
                if (n.security.isNotBlank()) OutlinedTextField(
                    pw, { pw = it }, label = { Text("WLAN-Passwort") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation()
                ) else Text("Offenes Netz.")
            },
            confirmButton = {
                TextButton(onClick = {
                    val ssid = n.ssid; val pass = pw
                    wifiConnect = null
                    show("WLAN: $ssid", { vm.run(Scripts.wifiConnect(ssid, pass), sudo = true, timeoutMs = 60_000) })
                }) { Text("Verbinden") }
            },
            dismissButton = { TextButton(onClick = { wifiConnect = null }) { Text("Abbrechen") } },
        )
    }
}
