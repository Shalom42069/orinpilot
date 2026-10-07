package de.marlon.orinpilot.ui.screens

import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jcraft.jsch.ChannelSftp
import de.marlon.orinpilot.MainViewModel
import de.marlon.orinpilot.data.Scripts
import de.marlon.orinpilot.ui.ConfirmDialog
import de.marlon.orinpilot.ui.EmptyHint
import de.marlon.orinpilot.ui.OP
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class RemoteFile(
    val name: String,
    val path: String,
    val isDir: Boolean,
    val isLink: Boolean,
    val size: Long,
    val mtime: Long,
    val perms: String,
)

private const val MAX_EDIT_BYTES = 512 * 1024

private fun join(dir: String, name: String) = if (dir.endsWith("/")) dir + name else "$dir/$name"
private fun parent(p: String): String {
    val t = p.trimEnd('/')
    val i = t.lastIndexOf('/')
    return if (i <= 0) "/" else t.substring(0, i)
}

fun humanSize(b: Long): String = when {
    b < 1024 -> "$b B"
    b < 1024 * 1024 -> String.format(Locale.GERMANY, "%.1f KB", b / 1024f)
    b < 1024L * 1024 * 1024 -> String.format(Locale.GERMANY, "%.1f MB", b / 1024f / 1024f)
    else -> String.format(Locale.GERMANY, "%.2f GB", b / 1024f / 1024f / 1024f)
}

@Composable
fun FilesScreen(vm: MainViewModel) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var path by remember { mutableStateOf("") }
    var entries by remember { mutableStateOf<List<RemoteFile>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf<String?>(null) }
    var showHidden by remember { mutableStateOf(false) }
    var menuFor by remember { mutableStateOf<RemoteFile?>(null) }
    var renaming by remember { mutableStateOf<RemoteFile?>(null) }
    var deleting by remember { mutableStateOf<RemoteFile?>(null) }
    var newFolder by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Pair<RemoteFile, String>?>(null) }
    var pendingDownload by remember { mutableStateOf<RemoteFile?>(null) }

    fun load(p: String) {
        val conn = vm.connection ?: run { error = "Nicht verbunden"; return }
        error = null
        scope.launch {
            try {
                val (resolved, list) = conn.sftp { ch ->
                    val target = p.ifBlank { ch.home }
                    val raw = ch.ls(target)
                    target to raw.mapNotNull { it as? ChannelSftp.LsEntry }
                        .filter { it.filename != "." && it.filename != ".." }
                        .map { e ->
                            val a = e.attrs
                            RemoteFile(
                                name = e.filename,
                                path = join(target, e.filename),
                                isDir = a.isDir,
                                isLink = a.isLink,
                                size = a.size,
                                mtime = a.mTime.toLong() * 1000L,
                                perms = a.permissionsString ?: "",
                            )
                        }
                }
                path = resolved
                entries = list.sortedWith(compareByDescending<RemoteFile> { it.isDir }.thenBy { it.name.lowercase() })
            } catch (e: Exception) {
                error = e.message ?: e.toString()
            }
        }
    }

    LaunchedEffect(Unit) { load("") }

    // Upload: Datei auf dem Handy wählen -> in den aktuellen Ordner
    val uploadLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        val conn = vm.connection ?: return@rememberLauncherForActivityResult
        val name = ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        } ?: "upload_${System.currentTimeMillis()}"
        val dest = join(path, name)
        busy = "Lade hoch: $name"
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    ctx.contentResolver.openInputStream(uri)?.use { input -> conn.sftp { it.put(input, dest) } }
                }
                vm.toast("Hochgeladen: $name")
            } catch (e: Exception) {
                vm.toast("Upload fehlgeschlagen: ${e.message}")
            }
            busy = null
            load(path)
        }
    }

    // Download: Ziel auf dem Handy wählen
    val downloadLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri: Uri? ->
        val f = pendingDownload
        pendingDownload = null
        if (uri == null || f == null) return@rememberLauncherForActivityResult
        val conn = vm.connection ?: return@rememberLauncherForActivityResult
        busy = "Lade herunter: ${f.name}"
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    ctx.contentResolver.openOutputStream(uri)?.use { out -> conn.sftp { it.get(f.path, out) } }
                }
                vm.toast("Gespeichert: ${f.name}")
            } catch (e: Exception) {
                vm.toast("Download fehlgeschlagen: ${e.message}")
            }
            busy = null
        }
    }

    fun openFile(f: RemoteFile) {
        if (f.isDir) { load(f.path); return }
        if (f.size > MAX_EDIT_BYTES) {
            pendingDownload = f
            downloadLauncher.launch(f.name)
            return
        }
        val conn = vm.connection ?: return
        busy = "Öffne ${f.name}"
        scope.launch {
            try {
                val bytes = conn.sftp { ch ->
                    val bo = ByteArrayOutputStream()
                    ch.get(f.path, bo)
                    bo.toByteArray()
                }
                if (bytes.any { it == 0.toByte() }) {
                    vm.toast("Binärdatei – wird heruntergeladen")
                    pendingDownload = f
                    downloadLauncher.launch(f.name)
                } else {
                    editing = f to String(bytes, Charsets.UTF_8)
                }
            } catch (e: Exception) {
                vm.toast("Öffnen fehlgeschlagen: ${e.message}")
            }
            busy = null
        }
    }

    Column(Modifier.fillMaxSize()) {
        // ------------- Werkzeugleiste
        Row(
            Modifier.fillMaxWidth().background(OP.Surface).padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = { load(parent(path)) }, enabled = path != "/" && path.isNotEmpty()) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Hoch", tint = OP.Text)
            }
            Text(
                path.ifEmpty { "…" },
                color = OP.Text, fontFamily = FontFamily.Monospace, fontSize = 12.sp, maxLines = 1,
                modifier = Modifier.weight(1f).horizontalScroll(rememberScrollState())
            )
            IconButton(onClick = { load("") }) { Icon(Icons.Default.Home, "Home", tint = OP.TextDim) }
            IconButton(onClick = { showHidden = !showHidden }) {
                Icon(if (showHidden) Icons.Default.Visibility else Icons.Default.VisibilityOff, "Versteckte", tint = OP.TextDim)
            }
            IconButton(onClick = { newFolder = true }) { Icon(Icons.Default.CreateNewFolder, "Neuer Ordner", tint = OP.TextDim) }
            IconButton(onClick = { uploadLauncher.launch(arrayOf("*/*")) }) { Icon(Icons.Default.CloudUpload, "Hochladen", tint = OP.Green) }
            IconButton(onClick = { load(path) }) { Icon(Icons.Default.Refresh, "Aktualisieren", tint = OP.TextDim) }
        }
        busy?.let {
            LinearProgressIndicator(Modifier.fillMaxWidth(), color = OP.Green)
            Text(it, color = OP.TextDim, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
        }
        error?.let { Text(it, color = OP.Red, fontSize = 13.sp, modifier = Modifier.padding(12.dp)) }

        val list = entries?.filter { showHidden || !it.name.startsWith(".") }
        if (list == null && error == null) {
            LinearProgressIndicator(Modifier.fillMaxWidth(), color = OP.Green)
        } else if (list != null && list.isEmpty()) {
            EmptyHint("Ordner ist leer")
        }
        LazyColumn(Modifier.fillMaxSize()) {
            items(list ?: emptyList(), key = { it.path }) { f ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { openFile(f) }
                        .padding(start = 14.dp, top = 8.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        when {
                            f.isLink -> Icons.Default.Link
                            f.isDir -> Icons.Default.Folder
                            else -> Icons.Default.Description
                        },
                        null,
                        tint = if (f.isDir) OP.Green else OP.TextDim,
                        modifier = Modifier.size(22.dp)
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(f.name, color = OP.Text, fontSize = 14.sp, maxLines = 1)
                        Text(
                            (if (f.isDir) "Ordner" else humanSize(f.size)) + "  ·  " +
                                SimpleDateFormat("dd.MM.yy HH:mm", Locale.GERMANY).format(Date(f.mtime)) + "  ·  " + f.perms,
                            color = OP.TextDim, fontSize = 11.sp, fontFamily = FontFamily.Monospace, maxLines = 1
                        )
                    }
                    Box {
                        IconButton(onClick = { menuFor = f }) { Icon(Icons.Default.MoreVert, "Mehr", tint = OP.TextDim) }
                        DropdownMenu(expanded = menuFor == f, onDismissRequest = { menuFor = null }) {
                            if (!f.isDir) {
                                DropdownMenuItem(text = { Text("Öffnen / Bearbeiten") }, onClick = { menuFor = null; openFile(f) })
                                DropdownMenuItem(text = { Text("Aufs Handy laden") }, onClick = {
                                    menuFor = null; pendingDownload = f; downloadLauncher.launch(f.name)
                                })
                            }
                            DropdownMenuItem(text = { Text("Pfad im Terminal öffnen") }, onClick = {
                                menuFor = null
                                vm.sendToTerminal("cd " + de.marlon.orinpilot.data.Parsers.q(if (f.isDir) f.path else parent(f.path)))
                                vm.toast("Im Terminal-Tab")
                            })
                            DropdownMenuItem(text = { Text("Umbenennen") }, onClick = { menuFor = null; renaming = f })
                            HorizontalDivider()
                            DropdownMenuItem(text = { Text("Löschen", color = OP.Red) }, onClick = { menuFor = null; deleting = f })
                        }
                    }
                }
            }
        }
    }

    // ------------- Dialoge
    renaming?.let { f ->
        var name by remember(f.path) { mutableStateOf(f.name) }
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text("Umbenennen") },
            text = { OutlinedTextField(name, { name = it }, singleLine = true) },
            confirmButton = {
                TextButton(onClick = {
                    renaming = null
                    val conn = vm.connection ?: return@TextButton
                    scope.launch {
                        runCatching { conn.sftp { it.rename(f.path, join(parent(f.path), name)) } }
                            .onFailure { vm.toast("Fehler: ${it.message}") }
                        load(path)
                    }
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text("Abbrechen") } },
        )
    }
    if (newFolder) {
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { newFolder = false },
            title = { Text("Neuer Ordner") },
            text = { OutlinedTextField(name, { name = it }, singleLine = true, label = { Text("Name") }) },
            confirmButton = {
                TextButton(enabled = name.isNotBlank(), onClick = {
                    newFolder = false
                    val conn = vm.connection ?: return@TextButton
                    scope.launch {
                        runCatching { conn.sftp { it.mkdir(join(path, name.trim())) } }
                            .onFailure { vm.toast("Fehler: ${it.message}") }
                        load(path)
                    }
                }) { Text("Anlegen") }
            },
            dismissButton = { TextButton(onClick = { newFolder = false }) { Text("Abbrechen") } },
        )
    }
    deleting?.let { f ->
        ConfirmDialog(
            title = "Löschen?",
            message = if (f.isDir) "Ordner \"${f.name}\" inklusive Inhalt löschen?" else "Datei \"${f.name}\" löschen?",
            confirm = "Löschen", danger = true,
            onConfirm = {
                scope.launch {
                    val r = if (f.isDir) vm.run(Scripts.rmRecursive(f.path))
                    else runCatching { (vm.connection ?: throw IllegalStateException("Nicht verbunden")).sftp { it.rm(f.path) } }
                        .fold({ de.marlon.orinpilot.ssh.ExecResult(0, "", "") }, { de.marlon.orinpilot.ssh.ExecResult(1, "", it.message ?: "") })
                    if (!r.ok) vm.toast("Löschen fehlgeschlagen: ${r.text}")
                    load(path)
                }
            },
            onDismiss = { deleting = null },
        )
    }
    editing?.let { (f, original) ->
        var text by remember(f.path) { mutableStateOf(original) }
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text(f.name, fontFamily = FontFamily.Monospace, fontSize = 15.sp) },
            text = {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 200.dp, max = 520.dp)
                        .background(Color(0xFF0A0C0B))
                        .padding(8.dp)
                ) {
                    BasicTextField(
                        value = text,
                        onValueChange = { text = it },
                        textStyle = TextStyle(color = OP.Text, fontFamily = FontFamily.Monospace, fontSize = 12.sp),
                        cursorBrush = androidx.compose.ui.graphics.SolidColor(OP.Green),
                        modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
                    )
                }
            },
            confirmButton = {
                TextButton(enabled = text != original, onClick = {
                    val conn = vm.connection ?: return@TextButton
                    val content = text
                    editing = null
                    scope.launch {
                        runCatching {
                            conn.sftp { it.put(ByteArrayInputStream(content.toByteArray(Charsets.UTF_8)), f.path) }
                        }.onSuccess { vm.toast("Gespeichert") }
                            .onFailure {
                                vm.toast("Speichern fehlgeschlagen: ${it.message} – für Systemdateien bitte Terminal mit sudo nutzen")
                            }
                        load(path)
                    }
                }) { Text("Speichern") }
            },
            dismissButton = { TextButton(onClick = { editing = null }) { Text("Schließen") } },
        )
    }
}
