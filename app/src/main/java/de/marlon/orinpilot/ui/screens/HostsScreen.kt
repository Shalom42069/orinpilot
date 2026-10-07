package de.marlon.orinpilot.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeveloperBoard
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.marlon.orinpilot.ConnState
import de.marlon.orinpilot.MainViewModel
import de.marlon.orinpilot.data.FoundHost
import de.marlon.orinpilot.data.HostProfile
import de.marlon.orinpilot.data.LanScanner
import de.marlon.orinpilot.ui.ConfirmDialog
import de.marlon.orinpilot.ui.OP
import de.marlon.orinpilot.ui.Pill
import kotlinx.coroutines.launch

@Composable
fun HostsScreen(vm: MainViewModel) {
    val profiles by vm.profiles.collectAsState()
    val state by vm.state.collectAsState()
    var editing by remember { mutableStateOf<HostProfile?>(null) }
    var deleting by remember { mutableStateOf<HostProfile?>(null) }
    var scanning by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = OP.Bg,
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { editing = HostProfile(host = "", user = "") },
                containerColor = OP.Green,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                icon = { Icon(Icons.Default.Add, null) },
                text = { Text("Jetson hinzufügen") },
            )
        }
    ) { pad ->
        LazyColumn(
            Modifier.fillMaxSize().padding(pad).statusBarsPadding(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Column(Modifier.padding(top = 8.dp, bottom = 8.dp)) {
                    Text("OrinPilot", fontSize = 30.sp, fontWeight = FontWeight.Bold, color = OP.Text)
                    Text(
                        "Jetson Orin Nano headless steuern – die Oberfläche läuft auf deinem Handy.",
                        color = OP.TextDim, style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
            item {
                OutlinedButton(onClick = { scanning = true }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.Search, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Jetson im Netzwerk suchen")
                }
            }
            if (profiles.isEmpty()) {
                item {
                    Text(
                        "Noch kein Gerät gespeichert. Suche im Netzwerk oder füge den Jetson manuell hinzu " +
                            "(Standard: SSH-Port 22, Benutzer aus der Ersteinrichtung). " +
                            "Per USB-C im Gerätemodus ist der Jetson unter 192.168.55.1 erreichbar.",
                        color = OP.TextDim, style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(vertical = 12.dp)
                    )
                }
            }
            items(profiles, key = { it.id }) { p ->
                val connecting = (state as? ConnState.Connecting)?.profile?.id == p.id
                ProfileCard(
                    p, connecting,
                    onConnect = { vm.connect(p) },
                    onEdit = { editing = p },
                    onDelete = { deleting = p },
                )
            }
            item { Spacer(Modifier.height(80.dp)) }
        }
    }

    editing?.let { p ->
        ProfileDialog(
            initial = p,
            onSave = { vm.saveProfile(it); editing = null },
            onDismiss = { editing = null },
        )
    }
    deleting?.let { p ->
        ConfirmDialog(
            title = "Profil löschen?",
            message = "\"${p.name}\" (${p.host}) wird entfernt.",
            confirm = "Löschen", danger = true,
            onConfirm = { vm.deleteProfile(p.id) },
            onDismiss = { deleting = null },
        )
    }
    if (scanning) {
        ScanDialog(
            onPick = { found ->
                scanning = false
                editing = HostProfile(
                    name = found.hostname.substringBefore('.').ifBlank { "Jetson Orin Nano" },
                    host = found.ip,
                )
            },
            onDismiss = { scanning = false },
        )
    }

    when (val s = state) {
        is ConnState.Failed -> AlertDialog(
            onDismissRequest = { vm.dismissError() },
            title = { Text("Verbindung fehlgeschlagen") },
            text = {
                Column {
                    Text(s.message)
                    if (s.profile.remoteHost.isNotBlank()) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Unterwegs? Prüfe, ob die Tailscale-App auf dem Handy eingeschaltet ist " +
                                "(${s.profile.remoteHost}).",
                            color = OP.TextDim, style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            },
            confirmButton = { TextButton(onClick = { vm.connect(s.profile) }) { Text("Erneut versuchen") } },
            dismissButton = { TextButton(onClick = { vm.dismissError() }) { Text("OK") } },
        )
        is ConnState.HostKeyChanged -> AlertDialog(
            onDismissRequest = { vm.dismissError() },
            title = { Text("⚠ Host-Schlüssel geändert") },
            text = {
                Text(
                    "Der Jetson meldet einen anderen SSH-Schlüssel als beim letzten Mal. " +
                        "Das passiert nach einer Neuinstallation (neu geflasht) – oder bei einem Angriff.\n\n" +
                        "Neu: ${s.fingerprint}\nBekannt: ${s.expected}",
                    fontSize = 13.sp
                )
            },
            confirmButton = {
                TextButton(onClick = { vm.connect(s.profile, acceptNewHostKey = true) }) {
                    Text("Neuen Schlüssel akzeptieren", color = OP.Amber)
                }
            },
            dismissButton = { TextButton(onClick = { vm.dismissError() }) { Text("Abbrechen") } },
        )
        else -> {}
    }
}

@Composable
private fun ProfileCard(
    p: HostProfile,
    connecting: Boolean,
    onConnect: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(OP.Surface)
            .border(1.dp, OP.Outline, RoundedCornerShape(16.dp))
            .clickable(enabled = !connecting, onClick = onConnect)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier.size(46.dp).clip(RoundedCornerShape(12.dp)).background(OP.Green.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center
        ) {
            if (connecting) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp, color = OP.Green)
            else Icon(Icons.Default.DeveloperBoard, null, tint = OP.Green)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(p.name.ifBlank { p.host }, fontWeight = FontWeight.SemiBold, color = OP.Text)
            Text(
                "${p.user}@${p.host}" + if (p.port != 22) ":${p.port}" else "",
                color = OP.TextDim, fontFamily = FontFamily.Monospace, fontSize = 12.sp
            )
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (p.remoteHost.isNotBlank()) Pill("Fernzugriff", OP.Cyan) else Pill("nur LAN", OP.TextDim)
                if (p.privateKey.isNotBlank()) Pill("Schlüssel", OP.Violet)
            }
        }
        IconButton(onClick = onEdit) { Icon(Icons.Default.Edit, "Bearbeiten", tint = OP.TextDim) }
        IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, "Löschen", tint = OP.TextDim) }
    }
}

@Composable
fun ProfileDialog(initial: HostProfile, onSave: (HostProfile) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(initial.name) }
    var host by remember { mutableStateOf(initial.host) }
    var port by remember { mutableStateOf(initial.port.toString()) }
    var user by remember { mutableStateOf(initial.user) }
    var password by remember { mutableStateOf(initial.password) }
    var showPw by remember { mutableStateOf(false) }
    var key by remember { mutableStateOf(initial.privateKey) }
    var passphrase by remember { mutableStateOf(initial.keyPassphrase) }
    var remote by remember { mutableStateOf(initial.remoteHost) }
    var remotePort by remember { mutableStateOf(initial.remotePort.toString()) }
    var advanced by remember { mutableStateOf(initial.privateKey.isNotBlank()) }

    val valid = host.isNotBlank() && user.isNotBlank() && (password.isNotBlank() || key.isNotBlank())

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial.host.isBlank()) "Jetson hinzufügen" else "Profil bearbeiten") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        host, { host = it.trim() }, label = { Text("IP / Hostname (LAN)") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        port, { port = it.filter(Char::isDigit).take(5) }, label = { Text("Port") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.width(86.dp)
                    )
                }
                OutlinedTextField(user, { user = it.trim() }, label = { Text("Benutzer") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(
                    password, { password = it }, label = { Text("Passwort (auch für sudo)") }, singleLine = true,
                    visualTransformation = if (showPw) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    trailingIcon = {
                        IconButton(onClick = { showPw = !showPw }) {
                            Icon(if (showPw) Icons.Default.VisibilityOff else Icons.Default.Visibility, null)
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )
                HorizontalDivider(Modifier.padding(vertical = 4.dp), color = OP.Outline)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Public, null, tint = OP.Cyan, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Fernzugriff (außerhalb des Heimnetzes)", fontWeight = FontWeight.Medium, fontSize = 14.sp)
                }
                Text(
                    "Wird nach der ersten Verbindung unter Steuerung → Fernzugriff automatisch per Tailscale eingerichtet. " +
                        "Alternativ hier eine eigene Adresse (DynDNS, ZeroTier …) eintragen.",
                    color = OP.TextDim, fontSize = 12.sp
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        remote, { remote = it.trim() }, label = { Text("Fernzugriff-Adresse") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        remotePort, { remotePort = it.filter(Char::isDigit).take(5) }, label = { Text("Port") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.width(86.dp)
                    )
                }
                TextButton(onClick = { advanced = !advanced }) {
                    Text(if (advanced) "Schlüssel-Login ausblenden" else "Privaten SSH-Schlüssel verwenden …")
                }
                if (advanced) {
                    OutlinedTextField(
                        key, { key = it }, label = { Text("Privater Schlüssel (PEM/OpenSSH)") },
                        textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        minLines = 3, maxLines = 6, modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        passphrase, { passphrase = it }, label = { Text("Passphrase (optional)") }, singleLine = true,
                        visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        "Tipp: Unter Steuerung → Sicherheit kann die App selbst einen Schlüssel erzeugen und auf dem Jetson hinterlegen.",
                        color = OP.TextDim, fontSize = 12.sp
                    )
                }
            }
        },
        confirmButton = {
            Button(
                enabled = valid,
                onClick = {
                    onSave(
                        initial.copy(
                            name = name.ifBlank { host },
                            host = host,
                            port = port.toIntOrNull() ?: 22,
                            user = user,
                            password = password,
                            privateKey = key,
                            keyPassphrase = passphrase,
                            remoteHost = remote,
                            remotePort = remotePort.toIntOrNull() ?: 22,
                            lastGoodHost = if (remote != initial.remoteHost || host != initial.host) "" else initial.lastGoodHost,
                        )
                    )
                }
            ) { Text("Speichern") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Abbrechen") } },
    )
}

@Composable
private fun ScanDialog(onPick: (FoundHost) -> Unit, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var progress by remember { mutableFloatStateOf(0f) }
    var running by remember { mutableStateOf(false) }
    var results by remember { mutableStateOf<List<FoundHost>?>(null) }
    val myIp = remember { LanScanner.localIpv4(ctx) }

    fun start() {
        running = true; results = null; progress = 0f
        scope.launch {
            val r = LanScanner.scan(ctx, onProgress = { d, t -> scope.launch { progress = d.toFloat() / t } })
            results = r
            running = false
        }
    }
    androidx.compose.runtime.LaunchedEffect(Unit) { start() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Netzwerksuche") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("Handy-IP: ${myIp ?: "unbekannt"} · suche SSH (Port 22)", color = OP.TextDim, fontSize = 12.sp)
                Spacer(Modifier.height(8.dp))
                if (running) {
                    LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth(), color = OP.Green)
                    Spacer(Modifier.height(8.dp))
                }
                val list = results
                if (list != null && list.isEmpty()) {
                    Text("Nichts gefunden. Ist der Jetson im selben WLAN/LAN und eingeschaltet?", color = OP.TextDim)
                }
                list?.forEach { h ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .clickable { onPick(h) }
                            .padding(vertical = 8.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            Modifier.size(10.dp).clip(CircleShape)
                                .background(if (h.looksLikeJetson) OP.Green else OP.TextDim)
                        )
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                h.ip + if (h.hostname.isNotBlank()) "  ·  ${h.hostname}" else "",
                                fontFamily = FontFamily.Monospace, fontSize = 13.sp, color = OP.Text
                            )
                            Text(h.banner.ifBlank { "SSH" }, color = OP.TextDim, fontSize = 11.sp, maxLines = 1)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Schließen") } },
        dismissButton = { TextButton(onClick = { start() }, enabled = !running) { Text("Erneut suchen") } },
    )
}
