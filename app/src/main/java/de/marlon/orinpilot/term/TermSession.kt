package de.marlon.orinpilot.term

import de.marlon.orinpilot.ssh.ShellChannel
import de.marlon.orinpilot.ssh.SshConnection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlin.concurrent.thread

/** Eine Terminal-Sitzung (Tab) = Emulator + SSH-Shell-Kanal. */
class TermSession(
    val id: Int,
    private val conn: SshConnection,
    private val scope: CoroutineScope,
) {
    val emulator = TerminalEmulator(80, 24)

    private val _tick = MutableStateFlow(0L)
    /** ändert sich bei jeder neuen Ausgabe -> UI zeichnet neu */
    val tick: StateFlow<Long> = _tick

    private val _alive = MutableStateFlow(true)
    val alive: StateFlow<Boolean> = _alive

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    @Volatile private var shell: ShellChannel? = null
    private var lastCols = 80
    private var lastRows = 24
    private var lastW = 640
    private var lastH = 384

    /** Eingaben, die vor dem Öffnen der Shell kamen */
    private val pending = StringBuilder()

    val title: String
        get() = emulator.title.ifBlank { "Shell $id" }

    init {
        emulator.onResponse = { send(it) }
        start()
    }

    private fun start() {
        scope.launch {
            try {
                val sh = conn.openShell(lastCols, lastRows)
                synchronized(pending) {
                    shell = sh
                    if (pending.isNotEmpty()) { sh.write(pending.toString()); pending.setLength(0) }
                }
                sh.resize(lastCols, lastRows, lastW, lastH)
                thread(name = "term-reader-$id", isDaemon = true) {
                    val buf = ByteArray(16 * 1024)
                    try {
                        while (true) {
                            val n = sh.input.read(buf)
                            if (n < 0) break
                            if (n > 0) {
                                emulator.feed(buf, n)
                                _tick.value = emulator.version
                            }
                        }
                    } catch (_: Exception) {
                    }
                    emulator.feed("\r\n\u001b[33m[Sitzung beendet]\u001b[0m\r\n")
                    _tick.value = emulator.version
                    _alive.value = false
                }
            } catch (e: Exception) {
                _error.value = e.message ?: e.toString()
                emulator.feed("\u001b[31mShell konnte nicht geöffnet werden: ${e.message}\u001b[0m\r\n")
                _tick.value = emulator.version
                _alive.value = false
            }
        }
    }

    fun send(s: String) {
        if (s.isEmpty()) return
        synchronized(pending) {
            val sh = shell
            if (sh == null) pending.append(s) else sh.write(s)
        }
    }

    /** Text einfügen (mit Bracketed-Paste, falls vom Programm angefordert) */
    fun paste(text: String) {
        val t = text.replace("\r\n", "\r").replace('\n', '\r')
        if (emulator.bracketedPaste) send("\u001b[200~$t\u001b[201~") else send(t)
    }

    fun resize(cols: Int, rows: Int, widthPx: Int, heightPx: Int) {
        if (cols == lastCols && rows == lastRows) return
        lastCols = cols; lastRows = rows; lastW = widthPx; lastH = heightPx
        emulator.resize(cols, rows)
        _tick.value = emulator.version
        shell?.resize(cols, rows, widthPx, heightPx)
    }

    fun close() {
        shell?.close()
        shell = null
        _alive.value = false
    }
}
