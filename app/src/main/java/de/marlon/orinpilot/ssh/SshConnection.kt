package de.marlon.orinpilot.ssh

import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.ChannelShell
import com.jcraft.jsch.JSch
import com.jcraft.jsch.KeyPair
import com.jcraft.jsch.Session
import de.marlon.orinpilot.data.HostProfile
import de.marlon.orinpilot.data.Parsers.q
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.Executors
import kotlin.concurrent.thread

data class ExecResult(val exit: Int, val out: String, val err: String) {
    val ok: Boolean get() = exit == 0
    /** stdout + stderr zusammen, für Anzeigen */
    val text: String get() = listOf(out.trimEnd(), err.trimEnd()).filter { it.isNotEmpty() }.joinToString("\n")
}

class HostKeyChangedException(val fingerprint: String, val expected: String) :
    Exception("Host-Schlüssel hat sich geändert!\nErwartet: $expected\nErhalten: $fingerprint")

class SshConnection(val profile: HostProfile) {

    private val jsch = JSch()
    private var session: Session? = null
    private val sftpMutex = Mutex()
    private var sftpChannel: ChannelSftp? = null

    /** Fingerprint des Servers nach erfolgreichem Verbindungsaufbau */
    var fingerprint: String = ""
        private set

    /** tatsächlich verwendete Adresse (LAN oder Fernzugriff) */
    var connectedHost: String = ""
        private set

    val isConnected: Boolean get() = session?.isConnected == true

    /**
     * Baut die Verbindung auf. Wirft [HostKeyChangedException], wenn ein gespeicherter
     * Fingerprint nicht passt (außer [acceptNewHostKey] ist true).
     */
    suspend fun connect(acceptNewHostKey: Boolean = false) = withContext(Dispatchers.IO) {
        val candidates = profile.candidates()
        if (candidates.isEmpty()) throw IllegalArgumentException("Keine Adresse angegeben")
        var lastError: Exception? = null
        for ((i, c) in candidates.withIndex()) {
            // LAN kurz probieren; die letzte Adresse bekommt mehr Zeit (VPN-Aufbau)
            val timeout = if (i == candidates.lastIndex) 12_000 else 4_000
            try {
                connectTo(c.first, c.second, timeout, acceptNewHostKey)
                connectedHost = c.first
                return@withContext
            } catch (e: HostKeyChangedException) {
                throw e
            } catch (e: Exception) {
                lastError = e
                if (e.message?.contains("Auth fail", true) == true) throw e
            }
        }
        throw lastError ?: IllegalStateException("Verbindung fehlgeschlagen")
    }

    private fun connectTo(host: String, port: Int, timeoutMs: Int, acceptNewHostKey: Boolean) {
        jsch.removeAllIdentity()
        if (profile.privateKey.isNotBlank()) {
            jsch.addIdentity(
                "orinpilot-key",
                profile.privateKey.trim().toByteArray(),
                null,
                profile.keyPassphrase.takeIf { it.isNotEmpty() }?.toByteArray()
            )
        }
        val s = jsch.getSession(profile.user, host, port)
        if (profile.password.isNotEmpty()) s.setPassword(profile.password)
        s.setConfig("StrictHostKeyChecking", "no")
        s.setConfig("PreferredAuthentications", "publickey,keyboard-interactive,password")
        s.setUserInfo(PasswordUserInfo(profile.password))
        s.setServerAliveInterval(15_000)
        s.setServerAliveCountMax(3)
        s.connect(timeoutMs)

        val fp = s.hostKey?.getFingerPrint(jsch) ?: ""
        // gleicher Host-Schlüssel egal ob LAN oder Tailscale -> TOFU schützt beide Wege
        if (profile.hostKeyFingerprint.isNotEmpty() && fp != profile.hostKeyFingerprint && !acceptNewHostKey) {
            s.disconnect()
            throw HostKeyChangedException(fp, profile.hostKeyFingerprint)
        }
        fingerprint = fp
        session = s
    }

    fun disconnect() {
        synchronized(forwards) { forwards.clear() }
        runCatching { sftpChannel?.disconnect() }
        sftpChannel = null
        runCatching { session?.disconnect() }
        session = null
    }

    private fun requireSession(): Session =
        session?.takeIf { it.isConnected } ?: throw IllegalStateException("Nicht verbunden")

    /** Befehl mit optionalem sudo umschließen. Das Passwort wird über stdin übergeben. */
    private fun wrap(cmd: String, sudo: Boolean): String = when {
        !sudo -> cmd
        profile.password.isNotEmpty() -> "sudo -k -S -p '' bash -c ${q(cmd)}"
        else -> "sudo -n bash -c ${q(cmd)}"
    }

    /**
     * Führt einen Befehl aus und wartet auf das Ende.
     * @param stdin zusätzliche Eingabe (nach dem sudo-Passwort)
     */
    suspend fun exec(
        cmd: String,
        sudo: Boolean = false,
        stdin: String? = null,
        timeoutMs: Long = 30_000,
    ): ExecResult = withContext(Dispatchers.IO) {
        val ch = requireSession().openChannel("exec") as ChannelExec
        val out = LimitedOutputStream(4 * 1024 * 1024)
        val err = LimitedOutputStream(512 * 1024)
        ch.setCommand(wrap(cmd, sudo))
        ch.setOutputStream(out)      // stdout des Jetson -> Puffer
        ch.setErrStream(err)         // stderr -> Puffer
        val toRemote: OutputStream = ch.getOutputStream() // stdin zum Jetson
        ch.connect(10_000)
        try {
            val sb = StringBuilder()
            if (sudo && profile.password.isNotEmpty()) sb.append(profile.password).append('\n')
            if (stdin != null) sb.append(stdin)
            if (sb.isNotEmpty()) {
                toRemote.write(sb.toString().toByteArray())
                toRemote.flush()
            }
            runCatching { toRemote.close() }
            val start = System.currentTimeMillis()
            while (!ch.isClosed) {
                if (System.currentTimeMillis() - start > timeoutMs) {
                    return@withContext ExecResult(-2, out.text(), err.text() + "\n[Zeitüberschreitung nach ${timeoutMs / 1000}s]")
                }
                delay(25)
            }
            ExecResult(ch.exitStatus, out.text(), cleanSudo(err.text()))
        } finally {
            ch.disconnect()
        }
    }

    private fun cleanSudo(err: String): String =
        err.lineSequence().filterNot { it.startsWith("[sudo]") }.joinToString("\n")

    /** Liefert die Ausgabe eines langlaufenden Befehls zeilenweise (z. B. tegrastats). */
    fun streamLines(cmd: String, sudo: Boolean = false): Flow<String> = callbackFlow {
        val ch = requireSession().openChannel("exec") as ChannelExec
        ch.setCommand(wrap(cmd, sudo))
        ch.setPty(false)
        val input: InputStream = ch.getInputStream()
        val toRemote = ch.getOutputStream()
        ch.connect(10_000)
        if (sudo && profile.password.isNotEmpty()) {
            toRemote.write((profile.password + "\n").toByteArray()); toRemote.flush()
        }
        val reader = thread(name = "ssh-stream", isDaemon = true) {
            try {
                input.bufferedReader().use { r ->
                    while (true) {
                        val line = r.readLine() ?: break
                        trySend(line)
                    }
                }
            } catch (_: Exception) {
            }
            close()
        }
        awaitClose {
            runCatching { ch.disconnect() }
            reader.interrupt()
        }
    }.flowOn(Dispatchers.IO)

    /**
     * Wie [streamLines], hängt aber am Ende eine Markerzeile mit dem Exit-Code an
     * (siehe [EXIT_MARKER]). stderr wird mit ausgegeben.
     */
    fun streamWithExit(cmd: String, sudo: Boolean = false): Flow<String> =
        streamLines("( $cmd ) 2>&1; echo \"$EXIT_MARKER\$?\"", sudo)

    private val forwards = HashMap<Int, Int>()

    /**
     * SSH-Tunnel: lokaler Port auf dem Handy -> 127.0.0.1:[remotePort] auf dem Jetson.
     * Der Dienst muss dafür nicht im LAN freigegeben sein. Liefert den lokalen Port.
     */
    suspend fun forwardLocal(remotePort: Int): Int = withContext(Dispatchers.IO) {
        synchronized(forwards) {
            val s = requireSession()
            forwards[remotePort]?.let { return@withContext it }
            val local = s.setPortForwardingL("127.0.0.1", 0, "127.0.0.1", remotePort)
            forwards[remotePort] = local
            local
        }
    }

    /** Öffnet eine interaktive Shell mit PTY (xterm-256color). */
    suspend fun openShell(cols: Int, rows: Int): ShellChannel = withContext(Dispatchers.IO) {
        val ch = requireSession().openChannel("shell") as ChannelShell
        ch.setPtyType("xterm-256color", cols, rows, cols * 8, rows * 16)
        ch.setEnv("LANG", "C.UTF-8")
        ch.setEnv("COLORTERM", "truecolor")
        val input = ch.getInputStream()
        val output = ch.getOutputStream()
        ch.connect(10_000)
        ShellChannel(ch, input, output)
    }

    /** SFTP-Zugriff (ein gemeinsamer Kanal, serialisiert). */
    suspend fun <T> sftp(block: (ChannelSftp) -> T): T = withContext(Dispatchers.IO) {
        sftpMutex.withLock {
            var ch = sftpChannel
            if (ch == null || !ch.isConnected || ch.isClosed) {
                ch = requireSession().openChannel("sftp") as ChannelSftp
                ch.connect(10_000)
                sftpChannel = ch
            }
            block(ch)
        }
    }

    companion object {
        const val EXIT_MARKER = "__ORINPILOT_EXIT="

        /** Erzeugt ein RSA-3072-Schlüsselpaar: (privater PEM-Schlüssel, öffentlicher Schlüssel) */
        fun generateKeyPair(comment: String = "orinpilot@android"): Pair<String, String> {
            val kp = KeyPair.genKeyPair(JSch(), KeyPair.RSA, 3072)
            val priv = ByteArrayOutputStream()
            val pub = ByteArrayOutputStream()
            kp.writePrivateKey(priv)
            kp.writePublicKey(pub, comment)
            kp.dispose()
            return priv.toString(Charsets.UTF_8.name()) to pub.toString(Charsets.UTF_8.name()).trim()
        }
    }
}

/** Interaktiver Shell-Kanal; Schreiben läuft auf einem eigenen Thread (kein Netzwerk im UI-Thread). */
class ShellChannel(
    private val channel: ChannelShell,
    val input: InputStream,
    private val output: OutputStream,
) {
    private val writer = Executors.newSingleThreadExecutor()

    val isOpen: Boolean get() = channel.isConnected && !channel.isClosed

    fun write(data: ByteArray) {
        writer.execute {
            try {
                output.write(data)
                output.flush()
            } catch (_: Exception) {
            }
        }
    }

    fun write(s: String) = write(s.toByteArray(Charsets.UTF_8))

    fun resize(cols: Int, rows: Int, widthPx: Int, heightPx: Int) {
        writer.execute { runCatching { channel.setPtySize(cols, rows, widthPx, heightPx) } }
    }

    fun close() {
        writer.execute { runCatching { channel.disconnect() } }
        writer.shutdown()
    }
}

/** begrenzt die gesammelte Ausgabe, damit riesige Ausgaben die App nicht sprengen */
private class LimitedOutputStream(private val limit: Int) : OutputStream() {
    private val buf = ByteArrayOutputStream()
    private var truncated = false

    @Synchronized
    override fun write(b: Int) {
        if (buf.size() < limit) buf.write(b) else truncated = true
    }

    @Synchronized
    override fun write(b: ByteArray, off: Int, len: Int) {
        val room = limit - buf.size()
        if (room <= 0) { truncated = true; return }
        val n = minOf(room, len)
        buf.write(b, off, n)
        if (n < len) truncated = true
    }

    @Synchronized
    fun text(): String = buf.toString(Charsets.UTF_8.name()) + if (truncated) "\n[… Ausgabe gekürzt]" else ""
}

/** beantwortet Passwort- und keyboard-interactive-Abfragen mit dem gespeicherten Passwort */
private class PasswordUserInfo(private val pw: String) : com.jcraft.jsch.UserInfo, com.jcraft.jsch.UIKeyboardInteractive {
    override fun getPassphrase(): String? = null
    override fun getPassword(): String = pw
    override fun promptPassword(message: String?): Boolean = pw.isNotEmpty()
    override fun promptPassphrase(message: String?): Boolean = false
    override fun promptYesNo(message: String?): Boolean = true
    override fun showMessage(message: String?) {}
    override fun promptKeyboardInteractive(
        destination: String?, name: String?, instruction: String?,
        prompt: Array<String>?, echo: BooleanArray?,
    ): Array<String>? {
        if (pw.isEmpty() || prompt == null) return null
        return Array(prompt.size) { pw }
    }
}
