package de.marlon.orinpilot

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import de.marlon.orinpilot.data.HostProfile
import de.marlon.orinpilot.data.Parsers
import de.marlon.orinpilot.data.ProfileStore
import de.marlon.orinpilot.data.Scripts
import de.marlon.orinpilot.data.Snippet
import de.marlon.orinpilot.data.TailscaleStatus
import de.marlon.orinpilot.data.SystemInfo
import de.marlon.orinpilot.data.TegraStats
import de.marlon.orinpilot.ssh.ExecResult
import de.marlon.orinpilot.ssh.HostKeyChangedException
import de.marlon.orinpilot.ssh.SshConnection
import de.marlon.orinpilot.term.TermSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

sealed interface ConnState {
    data object Idle : ConnState
    data class Connecting(val profile: HostProfile) : ConnState
    data class Connected(val profile: HostProfile) : ConnState
    data class Failed(val profile: HostProfile, val message: String) : ConnState
    data class HostKeyChanged(val profile: HostProfile, val fingerprint: String, val expected: String) : ConnState
}

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val store = ProfileStore(app)

    private val _profiles = MutableStateFlow(store.loadProfiles())
    val profiles: StateFlow<List<HostProfile>> = _profiles

    private val _snippets = MutableStateFlow(store.loadSnippets())
    val snippets: StateFlow<List<Snippet>> = _snippets

    private val _state = MutableStateFlow<ConnState>(ConnState.Idle)
    val state: StateFlow<ConnState> = _state

    private var conn: SshConnection? = null

    /** Ollama-Bereich (Modelle, Chat, Benchmark) */
    val ollama = OllamaController(this, viewModelScope, app)

    // ---------------------------------------------------------------- Dashboard
    private val _stats = MutableStateFlow<TegraStats?>(null)
    val stats: StateFlow<TegraStats?> = _stats
    private val _statsError = MutableStateFlow<String?>(null)
    val statsError: StateFlow<String?> = _statsError

    private val _cpuHist = MutableStateFlow<List<Float>>(emptyList())
    val cpuHist: StateFlow<List<Float>> = _cpuHist
    private val _gpuHist = MutableStateFlow<List<Float>>(emptyList())
    val gpuHist: StateFlow<List<Float>> = _gpuHist
    private val _tempHist = MutableStateFlow<List<Float>>(emptyList())
    val tempHist: StateFlow<List<Float>> = _tempHist
    private val _powerHist = MutableStateFlow<List<Float>>(emptyList())
    val powerHist: StateFlow<List<Float>> = _powerHist

    private val _sysInfo = MutableStateFlow<SystemInfo?>(null)
    val sysInfo: StateFlow<SystemInfo?> = _sysInfo

    private var statsJob: Job? = null
    private var watchdogJob: Job? = null

    // ---------------------------------------------------------------- Terminal
    private val _sessions = MutableStateFlow<List<TermSession>>(emptyList())
    val sessions: StateFlow<List<TermSession>> = _sessions
    private val _activeSessionId = MutableStateFlow(-1)
    val activeSessionId: StateFlow<Int> = _activeSessionId
    private var nextSessionId = 1

    private val _fontSize = MutableStateFlow(store.fontSizeSp)
    val fontSize: StateFlow<Float> = _fontSize

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 16)
    val messages: SharedFlow<String> = _messages

    fun toast(msg: String) {
        _messages.tryEmit(msg)
    }

    // ---------------------------------------------------------------- Profile

    fun saveProfile(p: HostProfile) {
        val list = _profiles.value.toMutableList()
        val i = list.indexOfFirst { it.id == p.id }
        if (i >= 0) list[i] = p else list.add(p)
        _profiles.value = list
        store.saveProfiles(list)
        // aktive Verbindung mit dem neuen Profilstand aktualisieren (sonst überschreibt ein alter Stand z. B. den Schlüssel)
        (_state.value as? ConnState.Connected)?.let { if (it.profile.id == p.id) _state.value = ConnState.Connected(p) }
    }

    fun deleteProfile(id: String) {
        val list = _profiles.value.filterNot { it.id == id }
        _profiles.value = list
        store.saveProfiles(list)
    }

    fun saveSnippet(s: Snippet) {
        val list = _snippets.value.toMutableList()
        val i = list.indexOfFirst { it.id == s.id }
        if (i >= 0) list[i] = s else list.add(s)
        _snippets.value = list
        store.saveSnippets(list)
    }

    fun deleteSnippet(id: String) {
        val list = _snippets.value.filterNot { it.id == id }
        _snippets.value = list
        store.saveSnippets(list)
    }

    fun setFontSize(sp: Float) {
        val v = sp.coerceIn(7f, 28f)
        _fontSize.value = v
        store.fontSizeSp = v
    }

    // ---------------------------------------------------------------- Verbindung

    fun connect(profile: HostProfile, acceptNewHostKey: Boolean = false) {
        if (_state.value is ConnState.Connecting) return
        disconnectInternal()
        _state.value = ConnState.Connecting(profile)
        viewModelScope.launch {
            val c = SshConnection(profile)
            try {
                c.connect(acceptNewHostKey)
                conn = c
                var p = profile
                if (p.hostKeyFingerprint != c.fingerprint || p.lastGoodHost != c.connectedHost) {
                    p = p.copy(hostKeyFingerprint = c.fingerprint, lastGoodHost = c.connectedHost)
                    if (_profiles.value.any { it.id == p.id }) saveProfile(p)
                }
                _state.value = ConnState.Connected(p)
                onConnected()
            } catch (e: HostKeyChangedException) {
                _state.value = ConnState.HostKeyChanged(profile, e.fingerprint, e.expected)
            } catch (e: Exception) {
                c.disconnect()
                _state.value = ConnState.Failed(profile, friendlyError(e))
            }
        }
    }

    private fun friendlyError(e: Exception): String {
        val m = e.message ?: e.toString()
        return when {
            m.contains("Auth fail", true) || m.contains("Auth cancel", true) ->
                "Anmeldung fehlgeschlagen – Benutzer/Passwort/Schlüssel prüfen."
            m.contains("timeout", true) || m.contains("timed out", true) ->
                "Zeitüberschreitung – ist der Jetson im selben Netz und eingeschaltet?"
            m.contains("Connection refused", true) ->
                "Verbindung abgelehnt – läuft der SSH-Dienst (sshd) auf dem Jetson?"
            m.contains("UnknownHost", true) || m.contains("Unable to resolve", true) ->
                "Hostname nicht gefunden – lieber die IP-Adresse verwenden."
            m.contains("No route", true) || m.contains("unreachable", true) ->
                "Netz nicht erreichbar – WLAN/USB-Verbindung prüfen."
            else -> m
        }
    }

    private fun onConnected() {
        startStats()
        refreshSysInfo()
        refreshRemote()
        watchdogJob?.cancel()
        watchdogJob = viewModelScope.launch {
            while (isActive) {
                delay(4000)
                val c = conn ?: break
                if (!c.isConnected) {
                    val st = _state.value
                    if (st is ConnState.Connected) {
                        _state.value = ConnState.Failed(st.profile, "Verbindung verloren.")
                        disconnectInternal()
                    }
                    break
                }
            }
        }
    }

    fun disconnect() {
        disconnectInternal()
        _state.value = ConnState.Idle
    }

    fun dismissError() {
        if (_state.value !is ConnState.Connected) _state.value = ConnState.Idle
    }

    private fun disconnectInternal() {
        statsJob?.cancel(); statsJob = null
        watchdogJob?.cancel(); watchdogJob = null
        _sessions.value.forEach { it.close() }
        _sessions.value = emptyList()
        _activeSessionId.value = -1
        // Abbau im Hintergrund (Netzwerk nicht im UI-Thread)
        val c = conn
        conn = null
        if (c != null) CoroutineScope(Dispatchers.IO).launch { c.disconnect() }
        _stats.value = null
        _statsError.value = null
        _cpuHist.value = emptyList(); _gpuHist.value = emptyList()
        _tempHist.value = emptyList(); _powerHist.value = emptyList()
        _sysInfo.value = null
        remoteJob?.cancel()
        _remote.value = RemoteSetup()
        ollama.reset()
    }

    val connection: SshConnection? get() = conn

    val currentProfile: HostProfile?
        get() = (_state.value as? ConnState.Connected)?.profile

    /** Befehl ausführen; liefert bei fehlender Verbindung ein Fehlerergebnis statt Exception. */
    suspend fun run(cmd: String, sudo: Boolean = false, stdin: String? = null, timeoutMs: Long = 30_000): ExecResult {
        val c = conn ?: return ExecResult(-1, "", "Nicht verbunden")
        return try {
            c.exec(cmd, sudo, stdin, timeoutMs)
        } catch (e: Exception) {
            ExecResult(-1, "", e.message ?: e.toString())
        }
    }

    /** Langlaufenden Befehl live streamen (Zeilen + Exit-Marker, siehe LiveOutputDialog). */
    fun live(cmd: String, sudo: Boolean = false): kotlinx.coroutines.flow.Flow<String> {
        val c = conn ?: return kotlinx.coroutines.flow.flowOf("Nicht verbunden", SshConnection.EXIT_MARKER + "1")
        return c.streamWithExit(cmd, sudo)
    }

    /** Für Docker & Co.: erst ohne sudo, bei "permission denied" mit sudo. */
    suspend fun runMaybeSudo(cmd: String, timeoutMs: Long = 30_000): ExecResult {
        val r = run(cmd, false, null, timeoutMs)
        val t = r.text.lowercase()
        return if (!r.ok && (t.contains("permission denied") || t.contains("must be root") || t.contains("are you root"))) {
            run(cmd, true, null, timeoutMs)
        } else r
    }

    // ---------------------------------------------------------------- Live-Werte

    fun startStats() {
        val c = conn ?: return
        statsJob?.cancel()
        _statsError.value = null
        statsJob = viewModelScope.launch {
            var gotAny = false
            val unknown = StringBuilder()
            try {
                c.streamLines(Scripts.TEGRASTATS).collect { line ->
                    val s = TegraStats.parse(line)
                    if (s != null) {
                        gotAny = true
                        _statsError.value = null
                        _stats.value = s
                        push(_cpuHist, s.cpuAvg.toFloat())
                        push(_gpuHist, (s.gpuLoad ?: 0).toFloat())
                        s.hotTemp?.let { push(_tempHist, it) }
                        s.totalPowerMw?.let { push(_powerHist, it / 1000f) }
                    } else if (unknown.length < 600) {
                        unknown.append(line).append('\n')
                    }
                }
            } catch (e: Exception) {
                unknown.append(e.message ?: "")
            }
            if (!gotAny && isActive) {
                _statsError.value = "tegrastats liefert keine Daten.\n" + unknown.toString().trim()
            }
        }
    }

    private var paused = false

    /** App im Hintergrund: tegrastats-Stream anhalten (spart Akku und Datenvolumen) */
    fun onAppBackground() {
        if (statsJob != null) { statsJob?.cancel(); statsJob = null; paused = true }
    }

    fun onAppForeground() {
        if (paused && conn?.isConnected == true) startStats()
        paused = false
    }

    private fun push(flow: MutableStateFlow<List<Float>>, v: Float) {
        val l = flow.value
        val n = ArrayList<Float>(minOf(l.size + 1, 90))
        if (l.size >= 90) n.addAll(l.subList(l.size - 89, l.size)) else n.addAll(l)
        n.add(v)
        flow.value = n
    }

    fun refreshSysInfo() {
        viewModelScope.launch {
            val r = run(Scripts.SYSINFO)
            if (r.out.isNotBlank()) _sysInfo.value = Parsers.systemInfo(r.out)
        }
    }

    // ---------------------------------------------------------------- Terminal-Sitzungen

    fun ensureSession() {
        if (_sessions.value.isEmpty()) newSession()
    }

    fun newSession() {
        val c = conn ?: return
        val s = TermSession(nextSessionId++, c, viewModelScope)
        _sessions.value = _sessions.value + s
        _activeSessionId.value = s.id
    }

    fun selectSession(id: Int) {
        _activeSessionId.value = id
    }

    fun closeSession(id: Int) {
        val s = _sessions.value.firstOrNull { it.id == id } ?: return
        s.close()
        val rest = _sessions.value.filterNot { it.id == id }
        _sessions.value = rest
        if (_activeSessionId.value == id) _activeSessionId.value = rest.lastOrNull()?.id ?: -1
    }

    /** Befehl im aktiven Terminal ausführen (öffnet bei Bedarf eines) */
    fun sendToTerminal(command: String) {
        ensureSession()
        val id = _activeSessionId.value
        _sessions.value.firstOrNull { it.id == id }?.send(command + "\r")
    }

    // ---------------------------------------------------------------- Fernzugriff (Tailscale)

    data class RemoteSetup(
        val status: TailscaleStatus? = null,
        val busy: Boolean = false,
        val step: String = "",
        val log: String = "",
    )

    private val _remote = MutableStateFlow(RemoteSetup())
    val remote: StateFlow<RemoteSetup> = _remote
    private var remoteJob: Job? = null

    /** über welche Adresse die aktuelle Verbindung läuft */
    val connectedVia: String get() = conn?.connectedHost.orEmpty()

    fun refreshRemote() {
        viewModelScope.launch {
            val r = run(Scripts.TS_STATUS)
            val st = TailscaleStatus.parse(r.out)
            _remote.value = _remote.value.copy(status = st)
            if (st.running) adoptRemote(st)
        }
    }

    fun installTailscale() {
        if (_remote.value.busy) return
        remoteJob = viewModelScope.launch {
            _remote.value = _remote.value.copy(busy = true, step = "Installiere Tailscale auf dem Jetson … (1–3 min)", log = "")
            val r = run(Scripts.TS_INSTALL, sudo = true, timeoutMs = 600_000)
            _remote.value = _remote.value.copy(
                busy = false,
                step = if (r.out.contains("INSTALL_OK")) "Installiert. Jetzt anmelden." else "Installation fehlgeschlagen (hat der Jetson Internet?)",
                log = r.text.takeLast(3000),
            )
            refreshRemote()
        }
    }

    /** Startet die Anmeldung. Mit Auth-Key sofort, sonst über Login-Link (Polling bis verbunden). */
    fun loginTailscale(authKey: String?) {
        if (_remote.value.busy) return
        remoteJob = viewModelScope.launch {
            _remote.value = _remote.value.copy(busy = true, step = "Starte Tailscale-Anmeldung …", log = "")
            val r = if (!authKey.isNullOrBlank()) run(Scripts.tsUpWithKey(authKey.trim()), sudo = true, timeoutMs = 90_000)
            else run(Scripts.TS_UP_INTERACTIVE, sudo = true, timeoutMs = 30_000)
            _remote.value = _remote.value.copy(log = r.text.takeLast(2000))
            // bis zu 5 Minuten warten, bis der Jetson eine 100.x-Adresse hat
            val deadline = System.currentTimeMillis() + 5 * 60_000
            while (isActive && System.currentTimeMillis() < deadline) {
                val st = TailscaleStatus.parse(run(Scripts.TS_STATUS).out)
                _remote.value = _remote.value.copy(
                    status = st,
                    step = when {
                        st.running -> "Verbunden: ${st.ip}"
                        st.authUrl.isNotBlank() -> "Bitte Login-Link öffnen und den Jetson freigeben …"
                        else -> "Warte auf Tailscale (${st.state.ifBlank { "startet" }}) …"
                    },
                )
                if (st.running) { adoptRemote(st); break }
                delay(2500)
            }
            _remote.value = _remote.value.copy(busy = false)
        }
    }

    fun cancelRemoteSetup() {
        remoteJob?.cancel()
        _remote.value = _remote.value.copy(busy = false, step = "Abgebrochen")
    }

    fun disableTailscale() {
        viewModelScope.launch {
            val r = run(Scripts.TS_DOWN, sudo = true)
            _remote.value = _remote.value.copy(log = r.text)
            refreshRemote()
        }
    }

    /** Tailscale-IP als Fernzugriffs-Adresse im Profil speichern */
    private fun adoptRemote(st: TailscaleStatus) {
        val cur = currentProfile ?: return
        if (cur.remoteHost == st.ip) return
        val p = cur.copy(remoteHost = st.ip, remotePort = 22)
        saveProfile(p)
        _state.value = ConnState.Connected(p)
        toast("Fernzugriff gespeichert: ${st.ip}")
    }

    /** manuelle Fernzugriffs-Adresse (DynDNS, ZeroTier, …) */
    fun setRemoteHost(host: String, port: Int) {
        val cur = currentProfile ?: return
        val p = cur.copy(remoteHost = host.trim(), remotePort = port)
        saveProfile(p)
        _state.value = ConnState.Connected(p)
    }

    override fun onCleared() {
        disconnectInternal()
        super.onCleared()
    }
}
