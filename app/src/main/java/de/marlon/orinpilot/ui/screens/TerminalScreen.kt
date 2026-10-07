package de.marlon.orinpilot.ui.screens

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.ZoomOut
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.marlon.orinpilot.MainViewModel
import de.marlon.orinpilot.term.TermColors
import de.marlon.orinpilot.term.TermKeys
import de.marlon.orinpilot.term.TermSession
import de.marlon.orinpilot.term.TerminalEmulator
import de.marlon.orinpilot.ui.OP
import kotlin.math.ceil

private const val SENTINEL = "  "

@Composable
fun TerminalScreen(vm: MainViewModel) {
    val sessions by vm.sessions.collectAsState()
    val activeId by vm.activeSessionId.collectAsState()
    val fontSize by vm.fontSize.collectAsState()
    val snippets by vm.snippets.collectAsState()
    val clip = LocalClipboardManager.current
    var showSnippets by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { vm.ensureSession() }

    val active = sessions.firstOrNull { it.id == activeId } ?: sessions.firstOrNull()

    Column(Modifier.fillMaxSize().background(TermColors.BG_COLOR)) {
        // ---------------- Tabs + Werkzeuge
        Row(
            Modifier.fillMaxWidth().background(OP.Surface).padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
                sessions.forEach { s ->
                    val sel = s.id == active?.id
                    val alive by s.alive.collectAsState()
                    Row(
                        Modifier
                            .padding(vertical = 6.dp, horizontal = 2.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (sel) OP.SurfaceHi else Color.Transparent)
                            .clickable { vm.selectSession(s.id) }
                            .padding(start = 10.dp, end = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "${s.id}",
                            color = if (!alive) OP.Red else if (sel) OP.Green else OP.TextDim,
                            fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 13.sp
                        )
                        IconButton(onClick = { vm.closeSession(s.id) }, modifier = Modifier.size(28.dp)) {
                            Icon(Icons.Default.Close, "Schließen", tint = OP.TextDim, modifier = Modifier.size(14.dp))
                        }
                    }
                }
                IconButton(onClick = { vm.newSession() }) { Icon(Icons.Default.Add, "Neue Sitzung", tint = OP.Green) }
            }
            IconButton(onClick = { vm.setFontSize(fontSize - 1f) }) { Icon(Icons.Default.ZoomOut, "Kleiner", tint = OP.TextDim) }
            IconButton(onClick = { vm.setFontSize(fontSize + 1f) }) { Icon(Icons.Default.ZoomIn, "Größer", tint = OP.TextDim) }
            IconButton(onClick = { active?.let { clip.setText(AnnotatedString(it.emulator.allText())); vm.toast("Terminal-Text kopiert") } }) {
                Icon(Icons.Default.ContentCopy, "Kopieren", tint = OP.TextDim)
            }
            IconButton(onClick = { clip.getText()?.text?.let { active?.paste(it) } }) {
                Icon(Icons.Default.ContentPaste, "Einfügen", tint = OP.TextDim)
            }
            IconButton(onClick = { showSnippets = true }) { Icon(Icons.Default.FlashOn, "Befehle", tint = OP.Amber) }
        }

        if (active == null) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                TextButton(onClick = { vm.newSession() }) { Text("Neue Terminal-Sitzung") }
            }
        } else {
            TerminalPane(active, fontSize, Modifier.weight(1f).fillMaxWidth())
        }
    }

    if (showSnippets) {
        AlertDialog(
            onDismissRequest = { showSnippets = false },
            title = { Text("Befehl einfügen") },
            text = {
                LazyColumn {
                    items(snippets) { sn ->
                        Column(
                            Modifier.fillMaxWidth().clickable {
                                active?.send((if (sn.sudo) "sudo " else "") + sn.command + "\r")
                                showSnippets = false
                            }.padding(vertical = 8.dp)
                        ) {
                            Text(sn.title, color = OP.Text)
                            Text(
                                (if (sn.sudo) "sudo " else "") + sn.command,
                                color = OP.TextDim, fontFamily = FontFamily.Monospace, fontSize = 11.sp, maxLines = 1
                            )
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showSnippets = false }) { Text("Schließen") } },
        )
    }
}

@Composable
private fun TerminalPane(session: TermSession, fontSizeSp: Float, modifier: Modifier) {
    val density = LocalDensity.current
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = remember { FocusRequester() }
    val tick by session.tick.collectAsState()
    var scrollOffset by remember(session.id) { mutableIntStateOf(0) }
    var dragAccum by remember { mutableFloatStateOf(0f) }
    var ctrl by remember { mutableStateOf(false) }
    var alt by remember { mutableStateOf(false) }
    var tfv by remember { mutableStateOf(TextFieldValue(SENTINEL, TextRange(SENTINEL.length))) }

    val paint = remember {
        Paint().apply {
            typeface = Typeface.MONOSPACE
            isAntiAlias = true
            isSubpixelText = true
        }
    }
    val textPx = with(density) { fontSizeSp.sp.toPx() }
    paint.textSize = textPx
    val cellW = remember(textPx) { paint.measureText("M") }
    val fm = remember(textPx) { paint.fontMetrics }
    val cellH = remember(textPx) { ceil(fm.descent - fm.ascent).toFloat() }
    val baseline = -fm.ascent

    fun sendTyped(text: String) {
        if (text.isEmpty()) return
        scrollOffset = 0
        var t = text.replace("\n", "\r")
        if (ctrl && t.length == 1) {
            t = TermKeys.ctrl(t[0]) ?: t
            ctrl = false
        }
        if (alt) {
            t = TermKeys.alt(t)
            alt = false
        }
        session.send(t)
    }

    fun sendKey(seq: String) {
        scrollOffset = 0
        session.send(if (alt) TermKeys.alt(seq).also { alt = false } else seq)
    }

    val app = session.emulator.applicationCursorKeys

    Column(modifier) {
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(TermColors.BG_COLOR)
                .onSizeChanged { sz ->
                    val cols = (sz.width / cellW).toInt().coerceAtLeast(10)
                    val rows = (sz.height / cellH).toInt().coerceAtLeast(4)
                    session.resize(cols, rows, sz.width, sz.height)
                }
                .pointerInput(session.id) {
                    detectTapGestures(onTap = {
                        if (scrollOffset != 0) scrollOffset = 0
                        runCatching { focus.requestFocus() }
                        keyboard?.show()
                    })
                }
                .pointerInput(session.id, cellH) {
                    detectVerticalDragGestures(
                        onDragEnd = { dragAccum = 0f },
                        onVerticalDrag = { change, dy ->
                            change.consume()
                            dragAccum += dy
                            val lines = (dragAccum / cellH).toInt()
                            if (lines != 0) {
                                dragAccum -= lines * cellH
                                val max = session.emulator.scrollbackSize
                                scrollOffset = (scrollOffset + lines).coerceIn(0, max)
                            }
                        }
                    )
                }
        ) {
            // Re-Lesen bei jedem Tick
            val snap = remember(tick, scrollOffset, session.id) { session.emulator.snapshot(scrollOffset) }
            Canvas(Modifier.fillMaxSize()) {
                val nc = drawContext.canvas.nativeCanvas
                drawTerminal(nc, snap, paint, cellW, cellH, baseline)
            }
            if (scrollOffset > 0) {
                Text(
                    "↑ $scrollOffset Zeilen  – tippen zum Zurückspringen",
                    color = Color.Black, fontSize = 11.sp,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(OP.Amber)
                        .clickable { scrollOffset = 0 }
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }
            // Unsichtbares Eingabefeld für die Bildschirmtastatur
            BasicTextField(
                value = tfv,
                onValueChange = { nv ->
                    val newText = nv.text
                    when {
                        newText == SENTINEL -> {}
                        newText.length < SENTINEL.length && SENTINEL.startsWith(newText) ->
                            repeat(SENTINEL.length - newText.length) { sendKey(TermKeys.BACKSPACE) }
                        newText.startsWith(SENTINEL) -> sendTyped(newText.substring(SENTINEL.length))
                        else -> {
                            val common = newText.commonPrefixWith(SENTINEL)
                            repeat(SENTINEL.length - common.length) { sendKey(TermKeys.BACKSPACE) }
                            sendTyped(newText.substring(common.length))
                        }
                    }
                    tfv = TextFieldValue(SENTINEL, TextRange(SENTINEL.length))
                },
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Password,
                    autoCorrectEnabled = false,
                    imeAction = ImeAction.None,
                ),
                keyboardActions = KeyboardActions(
                    onDone = { sendKey(TermKeys.ENTER) },
                    onGo = { sendKey(TermKeys.ENTER) },
                    onSend = { sendKey(TermKeys.ENTER) },
                    onSearch = { sendKey(TermKeys.ENTER) },
                ),
                singleLine = false,
                modifier = Modifier
                    .size(1.dp)
                    .alpha(0f)
                    .focusRequester(focus)
                    .onPreviewKeyEvent { ev ->
                        if (ev.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        val seq: String? = when (ev.key) {
                            Key.DirectionUp -> TermKeys.up(app)
                            Key.DirectionDown -> TermKeys.down(app)
                            Key.DirectionLeft -> TermKeys.left(app)
                            Key.DirectionRight -> TermKeys.right(app)
                            Key.MoveHome -> TermKeys.home(app)
                            Key.MoveEnd -> TermKeys.end(app)
                            Key.PageUp -> TermKeys.PAGE_UP
                            Key.PageDown -> TermKeys.PAGE_DOWN
                            Key.Delete -> TermKeys.DELETE
                            Key.Insert -> TermKeys.INSERT
                            Key.Escape -> TermKeys.ESC
                            Key.Tab -> TermKeys.TAB
                            Key.Enter, Key.NumPadEnter -> TermKeys.ENTER
                            Key.Backspace -> TermKeys.BACKSPACE
                            Key.F1 -> TermKeys.f(1)
                            Key.F2 -> TermKeys.f(2)
                            Key.F3 -> TermKeys.f(3)
                            Key.F4 -> TermKeys.f(4)
                            Key.F5 -> TermKeys.f(5)
                            Key.F6 -> TermKeys.f(6)
                            Key.F7 -> TermKeys.f(7)
                            Key.F8 -> TermKeys.f(8)
                            Key.F9 -> TermKeys.f(9)
                            Key.F10 -> TermKeys.f(10)
                            Key.F11 -> TermKeys.f(11)
                            Key.F12 -> TermKeys.f(12)
                            else -> null
                        }
                        if (seq != null) {
                            sendKey(seq); true
                        } else if (ev.isCtrlPressed || ev.isAltPressed) {
                            val base = ev.nativeKeyEvent.getUnicodeChar(0)
                            if (base > 0) {
                                val ch = base.toChar()
                                val s = if (ev.isCtrlPressed) TermKeys.ctrl(ch) ?: ch.toString() else ch.toString()
                                sendKey(if (ev.isAltPressed) TermKeys.alt(s) else s)
                                true
                            } else false
                        } else false
                    }
            )
        }

        // ---------------- Zusatztasten
        ExtraKeys(
            ctrl = ctrl, alt = alt,
            onCtrl = { ctrl = !ctrl }, onAlt = { alt = !alt },
            onKey = { sendKey(it) },
            onText = { sendTyped(it) },
            onKeyboard = { runCatching { focus.requestFocus() }; keyboard?.show() },
            app = app,
        )
    }

    LaunchedEffect(session.id) {
        runCatching { focus.requestFocus() }
    }
}

@Composable
private fun ExtraKeys(
    ctrl: Boolean,
    alt: Boolean,
    onCtrl: () -> Unit,
    onAlt: () -> Unit,
    onKey: (String) -> Unit,
    onText: (String) -> Unit,
    onKeyboard: () -> Unit,
    app: Boolean,
) {
    Column(Modifier.fillMaxWidth().background(OP.Surface).padding(vertical = 3.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 3.dp),
            horizontalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            XKey("ESC", Modifier.weight(1f)) { onKey(TermKeys.ESC) }
            XKey("TAB", Modifier.weight(1f)) { onKey(TermKeys.TAB) }
            XKey("CTRL", Modifier.weight(1f), active = ctrl) { onCtrl() }
            XKey("ALT", Modifier.weight(1f), active = alt) { onAlt() }
            XKey("↑", Modifier.weight(1f)) { onKey(TermKeys.up(app)) }
            XKey("HOME", Modifier.weight(1f)) { onKey(TermKeys.home(app)) }
            XKey("PGUP", Modifier.weight(1f)) { onKey(TermKeys.PAGE_UP) }
        }
        Spacer(Modifier.height(3.dp))
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 3.dp),
            horizontalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            XKey("^C", Modifier.weight(1f)) { onKey("\u0003") }
            XKey("|", Modifier.weight(1f)) { onText("|") }
            XKey("←", Modifier.weight(1f)) { onKey(TermKeys.left(app)) }
            XKey("↓", Modifier.weight(1f)) { onKey(TermKeys.down(app)) }
            XKey("→", Modifier.weight(1f)) { onKey(TermKeys.right(app)) }
            XKey("END", Modifier.weight(1f)) { onKey(TermKeys.end(app)) }
            XKey("PGDN", Modifier.weight(1f)) { onKey(TermKeys.PAGE_DOWN) }
        }
        Spacer(Modifier.height(3.dp))
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 3.dp),
            horizontalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            listOf("/", "-", "~", "_", "*", "&", "$", ">").forEach { k ->
                XKey(k, Modifier.weight(1f)) { onText(k) }
            }
            Box(
                Modifier
                    .weight(1f)
                    .height(34.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(OP.SurfaceHi)
                    .clickable { onKeyboard() },
                contentAlignment = Alignment.Center
            ) { Icon(Icons.Default.Keyboard, "Tastatur", tint = OP.Green, modifier = Modifier.size(18.dp)) }
        }
    }
}

@Composable
private fun XKey(label: String, modifier: Modifier = Modifier, active: Boolean = false, onClick: () -> Unit) {
    Box(
        modifier
            .height(34.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(if (active) OP.Green else OP.SurfaceHi)
            .border(1.dp, if (active) OP.Green else OP.Outline, RoundedCornerShape(6.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            color = if (active) Color.Black else OP.Text,
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1
        )
    }
}

/** Zeichnet den Terminal-Inhalt mit nativen Canvas-Aufrufen (schnell, pixelgenaues Raster). */
private fun drawTerminal(
    c: android.graphics.Canvas,
    snap: TerminalEmulator.Snapshot,
    paint: Paint,
    cellW: Float,
    cellH: Float,
    baseline: Float,
) {
    val bgPaint = Paint()
    val sb = StringBuilder()
    for ((row, line) in snap.lines.withIndex()) {
        val y = row * cellH
        val cells = line.cells
        var x = 0
        while (x < cells.size) {
            val start = x
            val first = cells[x]
            val fg0 = first.fg; val bg0 = first.bg; val at0 = first.attr
            sb.setLength(0)
            while (x < cells.size && cells[x].fg == fg0 && cells[x].bg == bg0 && cells[x].attr == at0) {
                sb.append(cells[x].ch); x++
            }
            val inverse = at0 and TerminalEmulator.ATTR_INVERSE != 0
            var fg = TermColors.resolve(fg0, foreground = true, bold = at0 and TerminalEmulator.ATTR_BOLD != 0)
            var bg = TermColors.resolve(bg0, foreground = false, bold = false)
            if (inverse) { val t = fg; fg = bg; bg = t }
            if (bg0 != TerminalEmulator.COLOR_DEFAULT || inverse) {
                bgPaint.color = bg
                c.drawRect(start * cellW, y, x * cellW, y + cellH, bgPaint)
            }
            val text = sb.toString()
            if (text.isBlank() && at0 and TerminalEmulator.ATTR_UNDERLINE == 0) continue
            paint.color = if (at0 and TerminalEmulator.ATTR_DIM != 0) (fg and 0x00FFFFFF) or (0xA0 shl 24) else fg
            paint.isFakeBoldText = at0 and TerminalEmulator.ATTR_BOLD != 0
            paint.textSkewX = if (at0 and TerminalEmulator.ATTR_ITALIC != 0) -0.2f else 0f
            paint.isUnderlineText = at0 and TerminalEmulator.ATTR_UNDERLINE != 0
            paint.isStrikeThruText = at0 and TerminalEmulator.ATTR_STRIKE != 0
            val ascii = text.all { it.code < 0x80 }
            if (ascii) {
                c.drawText(text, start * cellW, y + baseline, paint)
            } else {
                // Nicht-ASCII einzeln zeichnen, damit das Raster stimmt
                for (i in text.indices) {
                    val ch = text[i]
                    if (ch != ' ') c.drawText(ch.toString(), (start + i) * cellW, y + baseline, paint)
                }
            }
        }
    }
    paint.isUnderlineText = false; paint.isStrikeThruText = false; paint.isFakeBoldText = false; paint.textSkewX = 0f

    // Cursor
    if (snap.cursorVisible && snap.cursorRow in 0 until snap.rows) {
        val cx = snap.cursorCol * cellW
        val cy = snap.cursorRow * cellH
        bgPaint.color = TermColors.CURSOR
        bgPaint.alpha = 170
        c.drawRect(cx, cy, cx + cellW, cy + cellH, bgPaint)
        val ch = snap.lines.getOrNull(snap.cursorRow)?.cells?.getOrNull(snap.cursorCol)?.ch ?: ' '
        if (ch != ' ') {
            paint.color = TermColors.BG
            c.drawText(ch.toString(), cx, cy + baseline, paint)
        }
    }
}

