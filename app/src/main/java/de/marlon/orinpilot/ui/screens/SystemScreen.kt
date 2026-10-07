package de.marlon.orinpilot.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Code
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.marlon.orinpilot.MainViewModel
import de.marlon.orinpilot.data.ContainerInfo
import de.marlon.orinpilot.data.Parsers
import de.marlon.orinpilot.data.ProcessInfo
import de.marlon.orinpilot.data.Scripts
import de.marlon.orinpilot.data.ServiceInfo
import de.marlon.orinpilot.data.Snippet
import de.marlon.orinpilot.ssh.ExecResult
import de.marlon.orinpilot.ui.ChipRow
import de.marlon.orinpilot.ui.ConfirmDialog
import de.marlon.orinpilot.ui.EmptyHint
import de.marlon.orinpilot.ui.OP
import de.marlon.orinpilot.ui.OutputDialog
import de.marlon.orinpilot.ui.Pill
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

@Composable
fun SystemScreen(vm: MainViewModel, onOpenTerminal: () -> Unit) {
    var tab by remember { mutableStateOf(0) }
    val scope = rememberCoroutineScope()
    var outTitle by remember { mutableStateOf<String?>(null) }
    var outText by remember { mutableStateOf<String?>(null) }
    var outBusy by remember { mutableStateOf(false) }

    val show: (String, suspend () -> ExecResult) -> Unit = { title, block ->
        outTitle = title; outText = null; outBusy = true
        scope.launch {
            val r = block()
            outText = (if (r.ok) "" else "Exit-Code ${r.exit}\n") + r.text
            outBusy = false
        }
    }

    Column(Modifier.fillMaxSize()) {
        Box(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            ChipRow {
                listOf("Prozesse", "Dienste", "Docker", "Befehle").forEachIndexed { i, t ->
                    FilterChip(selected = tab == i, onClick = { tab = i }, label = { Text(t) })
                }
            }
        }
        when (tab) {
            0 -> ProcessesTab(vm, show)
            1 -> ServicesTab(vm, show)
            2 -> DockerTab(vm, show)
            else -> SnippetsTab(vm, show, onOpenTerminal)
        }
    }
    outTitle?.let { t -> OutputDialog(t, outText, outBusy) { outTitle = null } }
}

// ------------------------------------------------------------------ Prozesse

@Composable
private fun ProcessesTab(vm: MainViewModel, show: (String, suspend () -> ExecResult) -> Unit) {
    val scope = rememberCoroutineScope()
    var list by remember { mutableStateOf<List<ProcessInfo>?>(null) }
    var filter by remember { mutableStateOf("") }
    var killTarget by remember { mutableStateOf<Pair<ProcessInfo, Boolean>?>(null) }
    var auto by remember { mutableStateOf(true) }

    suspend fun reload() { list = Parsers.processes(vm.run(Scripts.PS).out) }

    LaunchedEffect(auto) {
        reload()
        while (auto && isActive) { delay(3000); reload() }
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                filter, { filter = it }, singleLine = true, label = { Text("Filter") },
                modifier = Modifier.weight(1f)
            )
            Checkbox(auto, { auto = it })
            Text("Live", fontSize = 12.sp, color = OP.TextDim)
            IconButton(onClick = { scope.launch { reload() } }) { Icon(Icons.Default.Refresh, null, tint = OP.TextDim) }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp)) {
            Text("PID", color = OP.TextDim, fontSize = 11.sp, modifier = Modifier.width(56.dp))
            Text("Befehl", color = OP.TextDim, fontSize = 11.sp, modifier = Modifier.weight(1f))
            Text("CPU%", color = OP.TextDim, fontSize = 11.sp, modifier = Modifier.width(50.dp))
            Text("RAM", color = OP.TextDim, fontSize = 11.sp, modifier = Modifier.width(64.dp))
            Spacer(Modifier.width(40.dp))
        }
        val l = list
        if (l == null) LinearProgressIndicator(Modifier.fillMaxWidth(), color = OP.Green)
        LazyColumn(Modifier.fillMaxSize()) {
            items((l ?: emptyList()).filter {
                filter.isBlank() || it.command.contains(filter, true) || it.user.contains(filter, true) || it.pid.toString() == filter
            }, key = { it.pid }) { p ->
                var menu by remember { mutableStateOf(false) }
                Row(Modifier.fillMaxWidth().padding(start = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("${p.pid}", fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = OP.TextDim, modifier = Modifier.width(56.dp))
                    Column(Modifier.weight(1f)) {
                        Text(p.command, fontSize = 13.sp, color = OP.Text, maxLines = 1)
                        Text(p.user, fontSize = 10.sp, color = OP.TextDim)
                    }
                    Text(
                        String.format(java.util.Locale.GERMANY, "%.1f", p.cpu), fontFamily = FontFamily.Monospace, fontSize = 12.sp,
                        color = if (p.cpu > 50) OP.Amber else OP.Text, modifier = Modifier.width(50.dp)
                    )
                    Text(humanSize(p.rssKb * 1024), fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = OP.Text, modifier = Modifier.width(64.dp))
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, null, tint = OP.TextDim) }
                        DropdownMenu(menu, { menu = false }) {
                            DropdownMenuItem(text = { Text("Beenden (TERM)") }, onClick = { menu = false; killTarget = p to false })
                            DropdownMenuItem(text = { Text("Erzwingen (KILL)", color = OP.Red) }, onClick = { menu = false; killTarget = p to true })
                            DropdownMenuItem(text = { Text("Details") }, onClick = {
                                menu = false
                                show("Prozess ${p.pid}") { vm.run("ps -o pid,ppid,user,etime,pcpu,pmem,rss,args -p ${p.pid} ww") }
                            })
                        }
                    }
                }
            }
        }
    }
    killTarget?.let { (p, force) ->
        ConfirmDialog(
            title = if (force) "Prozess erzwingen beenden?" else "Prozess beenden?",
            message = "${p.command} (PID ${p.pid}, Benutzer ${p.user})",
            confirm = "Beenden", danger = true,
            onConfirm = {
                scope.launch {
                    var r = vm.run(Scripts.kill(p.pid, force))
                    if (!r.ok) r = vm.run(Scripts.kill(p.pid, force), sudo = true)
                    vm.toast(if (r.ok) "Signal gesendet" else "Fehler: ${r.text}")
                    reload()
                }
            },
            onDismiss = { killTarget = null },
        )
    }
}

// ------------------------------------------------------------------ Dienste

@Composable
private fun ServicesTab(vm: MainViewModel, show: (String, suspend () -> ExecResult) -> Unit) {
    val scope = rememberCoroutineScope()
    var list by remember { mutableStateOf<List<ServiceInfo>?>(null) }
    var filter by remember { mutableStateOf("") }
    var onlyActive by remember { mutableStateOf(true) }

    suspend fun reload() { list = Parsers.services(vm.run(Scripts.SERVICES).out) }
    LaunchedEffect(Unit) { reload() }

    fun act(action: String, s: ServiceInfo) {
        show("systemctl $action ${s.name}") {
            val r = vm.run(Scripts.service(action, s.unit), sudo = true)
            reload()
            r
        }
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(filter, { filter = it }, singleLine = true, label = { Text("Filter") }, modifier = Modifier.weight(1f))
            Checkbox(onlyActive, { onlyActive = it })
            Text("aktiv", fontSize = 12.sp, color = OP.TextDim)
            IconButton(onClick = { scope.launch { reload() } }) { Icon(Icons.Default.Refresh, null, tint = OP.TextDim) }
        }
        val l = list
        if (l == null) LinearProgressIndicator(Modifier.fillMaxWidth(), color = OP.Green)
        LazyColumn(Modifier.fillMaxSize()) {
            items((l ?: emptyList()).filter {
                (!onlyActive || it.active == "active" || it.active == "failed") &&
                    (filter.isBlank() || it.unit.contains(filter, true) || it.description.contains(filter, true))
            }, key = { it.unit }) { s ->
                var menu by remember { mutableStateOf(false) }
                Row(
                    Modifier.fillMaxWidth().padding(start = 14.dp, top = 4.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(s.name, fontSize = 14.sp, color = OP.Text, maxLines = 1)
                        Text(s.description, fontSize = 11.sp, color = OP.TextDim, maxLines = 1)
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Pill(
                                s.sub,
                                when (s.active) { "active" -> OP.Green; "failed" -> OP.Red; else -> OP.TextDim }
                            )
                            if (s.enabled.isNotBlank()) Pill(s.enabled, if (s.enabled == "enabled") OP.Cyan else OP.TextDim)
                        }
                    }
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, null, tint = OP.TextDim) }
                        DropdownMenu(menu, { menu = false }) {
                            listOf(
                                "start" to "Starten", "stop" to "Stoppen", "restart" to "Neu starten",
                                "enable" to "Autostart an", "disable" to "Autostart aus",
                            ).forEach { (a, label) ->
                                DropdownMenuItem(text = { Text(label) }, onClick = { menu = false; act(a, s) })
                            }
                            HorizontalDivider()
                            DropdownMenuItem(text = { Text("Status") }, onClick = {
                                menu = false; show("Status ${s.name}") { vm.run(Scripts.service("status --no-pager -l", s.unit)) }
                            })
                            DropdownMenuItem(text = { Text("Log (journalctl)") }, onClick = {
                                menu = false; show("Log ${s.name}") { vm.run(Scripts.journal(s.unit), sudo = true) }
                            })
                        }
                    }
                }
            }
        }
    }
}

// ------------------------------------------------------------------ Docker

@Composable
private fun DockerTab(vm: MainViewModel, show: (String, suspend () -> ExecResult) -> Unit) {
    val scope = rememberCoroutineScope()
    var list by remember { mutableStateOf<List<ContainerInfo>?>(null) }
    var err by remember { mutableStateOf<String?>(null) }

    suspend fun reload() {
        val r = vm.runMaybeSudo(Scripts.DOCKER_PS)
        if (r.ok) { list = Parsers.containers(r.out); err = null }
        else { list = emptyList(); err = r.text.ifBlank { "Docker nicht verfügbar" } }
    }
    LaunchedEffect(Unit) { reload() }

    fun act(action: String, c: ContainerInfo) {
        show("docker $action ${c.name}") {
            val r = vm.runMaybeSudo(Scripts.docker(action, c.id), timeoutMs = 90_000)
            reload(); r
        }
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Container", color = OP.TextDim, modifier = Modifier.weight(1f))
            TextButton(onClick = { show("docker images") { vm.runMaybeSudo("docker images 2>&1") } }) { Text("Images") }
            IconButton(onClick = { scope.launch { reload() } }) { Icon(Icons.Default.Refresh, null, tint = OP.TextDim) }
        }
        err?.let { Text(it, color = OP.Amber, fontSize = 12.sp, modifier = Modifier.padding(12.dp)) }
        val l = list
        if (l == null) LinearProgressIndicator(Modifier.fillMaxWidth(), color = OP.Green)
        else if (l.isEmpty() && err == null) EmptyHint("Keine Container")
        LazyColumn(Modifier.fillMaxSize()) {
            items(l ?: emptyList(), key = { it.id }) { c ->
                var menu by remember { mutableStateOf(false) }
                Row(Modifier.fillMaxWidth().padding(start = 14.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(c.name, color = OP.Text, fontSize = 14.sp)
                        Text(c.image, color = OP.TextDim, fontSize = 11.sp, fontFamily = FontFamily.Monospace, maxLines = 1)
                        Pill(c.status, if (c.running) OP.Green else OP.TextDim)
                    }
                    IconButton(onClick = { act(if (c.running) "stop" else "start", c) }) {
                        Icon(Icons.Default.PlayArrow, null, tint = if (c.running) OP.TextDim else OP.Green)
                    }
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, null, tint = OP.TextDim) }
                        DropdownMenu(menu, { menu = false }) {
                            DropdownMenuItem(text = { Text("Starten") }, onClick = { menu = false; act("start", c) })
                            DropdownMenuItem(text = { Text("Stoppen") }, onClick = { menu = false; act("stop", c) })
                            DropdownMenuItem(text = { Text("Neu starten") }, onClick = { menu = false; act("restart", c) })
                            DropdownMenuItem(text = { Text("Logs") }, onClick = {
                                menu = false; show("Logs ${c.name}") { vm.runMaybeSudo(Scripts.dockerLogs(c.id)) }
                            })
                            HorizontalDivider()
                            DropdownMenuItem(text = { Text("Entfernen", color = OP.Red) }, onClick = { menu = false; act("rm", c) })
                        }
                    }
                }
            }
        }
    }
}

// ------------------------------------------------------------------ Befehle

@Composable
private fun SnippetsTab(vm: MainViewModel, show: (String, suspend () -> ExecResult) -> Unit, onOpenTerminal: () -> Unit) {
    val snippets by vm.snippets.collectAsState()
    var editing by remember { mutableStateOf<Snippet?>(null) }
    var deleting by remember { mutableStateOf<Snippet?>(null) }
    var quick by remember { mutableStateOf("") }
    var quickSudo by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                quick, { quick = it }, singleLine = true, label = { Text("Befehl ausführen") },
                textStyle = androidx.compose.ui.text.TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp, color = OP.Text),
                modifier = Modifier.weight(1f)
            )
            Checkbox(quickSudo, { quickSudo = it })
            Text("sudo", fontSize = 12.sp, color = OP.TextDim)
            IconButton(enabled = quick.isNotBlank(), onClick = {
                val cmd = quick; val su = quickSudo
                show(cmd) { vm.run(cmd, sudo = su, timeoutMs = 120_000) }
            }) { Icon(Icons.Default.PlayArrow, "Ausführen", tint = OP.Green) }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Gespeicherte Befehle", color = OP.TextDim, fontSize = 13.sp, modifier = Modifier.weight(1f))
            TextButton(onClick = { editing = Snippet(title = "", command = "") }) {
                Icon(Icons.Default.Add, null); Text("Neu")
            }
        }
        LazyColumn(Modifier.fillMaxSize()) {
            items(snippets, key = { it.id }) { s ->
                Row(
                    Modifier.fillMaxWidth()
                        .clickable { show(s.title) { vm.run(s.command, sudo = s.sudo, timeoutMs = 600_000) } }
                        .padding(start = 14.dp, top = 6.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(s.title, color = OP.Text, fontSize = 14.sp)
                            if (s.sudo) { Spacer(Modifier.width(6.dp)); Pill("sudo", OP.Amber) }
                        }
                        Text(s.command, color = OP.TextDim, fontSize = 11.sp, fontFamily = FontFamily.Monospace, maxLines = 1)
                    }
                    IconButton(onClick = {
                        vm.sendToTerminal((if (s.sudo) "sudo " else "") + s.command)
                        onOpenTerminal()
                    }) { Icon(Icons.Default.Code, "Im Terminal", tint = OP.TextDim) }
                    IconButton(onClick = { editing = s }) { Icon(Icons.Default.Edit, "Bearbeiten", tint = OP.TextDim) }
                    IconButton(onClick = { deleting = s }) { Icon(Icons.Default.Delete, "Löschen", tint = OP.TextDim) }
                }
            }
        }
    }

    editing?.let { s ->
        var title by remember(s.id) { mutableStateOf(s.title) }
        var cmd by remember(s.id) { mutableStateOf(s.command) }
        var sudo by remember(s.id) { mutableStateOf(s.sudo) }
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text(if (s.title.isBlank()) "Neuer Befehl" else "Befehl bearbeiten") },
            text = {
                Column {
                    OutlinedTextField(title, { title = it }, label = { Text("Titel") }, singleLine = true)
                    OutlinedTextField(
                        cmd, { cmd = it }, label = { Text("Befehl") }, minLines = 2,
                        textStyle = androidx.compose.ui.text.TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp, color = OP.Text)
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(sudo, { sudo = it }); Text("mit sudo ausführen")
                    }
                }
            },
            confirmButton = {
                TextButton(enabled = cmd.isNotBlank(), onClick = {
                    vm.saveSnippet(s.copy(title = title.ifBlank { cmd.take(30) }, command = cmd, sudo = sudo))
                    editing = null
                }) { Text("Speichern") }
            },
            dismissButton = { TextButton(onClick = { editing = null }) { Text("Abbrechen") } },
        )
    }
    deleting?.let { s ->
        ConfirmDialog("Befehl löschen?", s.title, confirm = "Löschen", danger = true,
            onConfirm = { vm.deleteSnippet(s.id) }, onDismiss = { deleting = null })
    }
}
