package de.marlon.orinpilot

import android.content.Context
import de.marlon.orinpilot.data.ChatMessage
import de.marlon.orinpilot.data.ChatOptions
import de.marlon.orinpilot.data.GenStats
import de.marlon.orinpilot.data.OllamaClient
import de.marlon.orinpilot.data.OllamaModel
import de.marlon.orinpilot.data.OllamaScripts
import de.marlon.orinpilot.data.OllamaStatus
import de.marlon.orinpilot.data.PullProgress
import de.marlon.orinpilot.data.RunningModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** Zustand & Logik für den Ollama-Bereich. Lebt im ViewModel, damit Chats Tab-Wechsel überstehen. */
class OllamaController(
    private val vm: MainViewModel,
    private val scope: CoroutineScope,
    context: Context,
) {
    private val prefs = context.getSharedPreferences("orinpilot_ollama", Context.MODE_PRIVATE)

    private var client: OllamaClient? = null

    private val _status = MutableStateFlow<OllamaStatus?>(null)
    val status: StateFlow<OllamaStatus?> = _status

    private val _apiVersion = MutableStateFlow("")
    val apiVersion: StateFlow<String> = _apiVersion

    private val _apiError = MutableStateFlow<String?>(null)
    val apiError: StateFlow<String?> = _apiError

    private val _models = MutableStateFlow<List<OllamaModel>>(emptyList())
    val models: StateFlow<List<OllamaModel>> = _models

    private val _running = MutableStateFlow<List<RunningModel>>(emptyList())
    val running: StateFlow<List<RunningModel>> = _running

    private val _pulls = MutableStateFlow<Map<String, PullProgress>>(emptyMap())
    val pulls: StateFlow<Map<String, PullProgress>> = _pulls
    private val pullJobs = HashMap<String, Job>()

    private val _busy = MutableStateFlow<String?>(null)
    val busy: StateFlow<String?> = _busy

    // ------------------------------------------------------------ Chat
    private val _chat = MutableStateFlow<List<ChatMessage>>(emptyList())
    val chat: StateFlow<List<ChatMessage>> = _chat
    private val _streaming = MutableStateFlow(false)
    val streaming: StateFlow<Boolean> = _streaming
    private var chatJob: Job? = null

    val chatModel = MutableStateFlow(prefs.getString("model", "").orEmpty())
    val systemPrompt = MutableStateFlow(prefs.getString("system", "Du bist ein hilfreicher Assistent. Antworte auf Deutsch.").orEmpty())
    val options = MutableStateFlow(
        ChatOptions(
            temperature = prefs.getFloat("temp", 0.7f),
            numCtx = prefs.getInt("ctx", 4096),
            keepAlive = prefs.getString("keep", "").orEmpty(),
            think = prefs.getBoolean("think", false),
        )
    )

    // ------------------------------------------------------------ Benchmark
    data class BenchResult(val model: String, val stats: GenStats?, val error: String? = null)

    private val _bench = MutableStateFlow<List<BenchResult>>(emptyList())
    val bench: StateFlow<List<BenchResult>> = _bench
    private val _benchRunning = MutableStateFlow<String?>(null)
    val benchRunning: StateFlow<String?> = _benchRunning

    fun reset() {
        client = null
        pullJobs.values.forEach { it.cancel() }
        pullJobs.clear()
        chatJob?.cancel()
        _streaming.value = false
        _pulls.value = emptyMap()
        _status.value = null
        _apiVersion.value = ""
        _models.value = emptyList()
        _running.value = emptyList()
    }

    private suspend fun api(): OllamaClient {
        client?.let { return it }
        val c = vm.connection ?: throw IllegalStateException("Nicht verbunden")
        val port = c.forwardLocal(11434)
        return OllamaClient("http://127.0.0.1:$port").also { client = it }
    }

    fun refresh() {
        scope.launch {
            val r = vm.run(OllamaScripts.STATUS)
            val st = OllamaStatus.parse(r.out)
            _status.value = st
            if (st.installed && st.serviceActive) refreshApi() else {
                _apiVersion.value = ""
                _models.value = emptyList(); _running.value = emptyList()
                _apiError.value = null
            }
        }
    }

    suspend fun refreshApi() {
        try {
            val a = api()
            _apiVersion.value = a.version()
            _models.value = a.tags()
            _running.value = a.ps()
            _apiError.value = null
            if (chatModel.value.isBlank() || _models.value.none { it.name == chatModel.value }) {
                _models.value.firstOrNull()?.let { setModel(it.name) }
            }
        } catch (e: Exception) {
            client = null
            _apiError.value = "API nicht erreichbar: ${e.message}"
        }
    }

    suspend fun refreshRunning() {
        try { _running.value = api().ps() } catch (_: Exception) { }
    }

    fun pull(name: String) {
        val tag = name.trim()
        if (tag.isBlank() || pullJobs[tag]?.isActive == true) return
        pullJobs[tag] = scope.launch {
            _pulls.value = _pulls.value + (tag to PullProgress("startet …", 0, 0))
            var failed: String? = null
            try {
                api().pull(tag).collect { p ->
                    if (p.error != null) failed = p.error
                    _pulls.value = _pulls.value + (tag to p)
                }
            } catch (e: Exception) {
                failed = e.message
            }
            if (failed != null) {
                _pulls.value = _pulls.value + (tag to PullProgress("Fehler", 0, 0, failed))
                vm.toast("Download $tag fehlgeschlagen: $failed")
            } else {
                _pulls.value = _pulls.value - tag
                vm.toast("$tag geladen")
                refreshApi()
            }
            pullJobs.remove(tag)
        }
    }

    fun cancelPull(name: String) {
        pullJobs.remove(name)?.cancel()
        _pulls.value = _pulls.value - name
    }

    fun dismissPull(name: String) { _pulls.value = _pulls.value - name }

    fun delete(name: String) {
        scope.launch {
            _busy.value = "Lösche $name …"
            val r = vm.run(OllamaScripts.rm(name), timeoutMs = 60_000)
            _busy.value = null
            vm.toast(if (r.ok) "$name gelöscht" else "Fehler: ${r.text}")
            refreshApi()
        }
    }

    fun load(name: String, keepAlive: String = "30m") {
        scope.launch {
            _busy.value = "Lade $name in den Speicher …"
            try { api().setLoaded(name, keepAlive); vm.toast("$name geladen") }
            catch (e: Exception) { vm.toast("Fehler: ${e.message}") }
            _busy.value = null
            refreshRunning()
        }
    }

    fun unload(name: String) {
        scope.launch {
            try { api().setLoaded(name, "0") } catch (e: Exception) { vm.toast("Fehler: ${e.message}") }
            refreshRunning()
        }
    }

    suspend fun details(name: String): String = try {
        val o = api().show(name)
        buildString {
            o.optJSONObject("details")?.let { d ->
                appendLine("Familie:        ${d.optString("family")}")
                appendLine("Parameter:      ${d.optString("parameter_size")}")
                appendLine("Quantisierung:  ${d.optString("quantization_level")}")
                appendLine("Format:         ${d.optString("format")}")
            }
            o.optJSONArray("capabilities")?.let { c ->
                append("Fähigkeiten:    ")
                appendLine((0 until c.length()).joinToString(", ") { c.optString(it) })
            }
            o.optJSONObject("model_info")?.let { mi ->
                mi.keys().asSequence().filter { it.endsWith("context_length") }.firstOrNull()?.let {
                    appendLine("Max. Kontext:   ${mi.optLong(it)} Token")
                }
            }
            val params = o.optString("parameters")
            if (params.isNotBlank()) { appendLine(); appendLine("Parameter:"); appendLine(params.trim()) }
            val sys = o.optString("system")
            if (sys.isNotBlank()) { appendLine(); appendLine("System-Prompt:"); appendLine(sys.trim().take(800)) }
            val lic = o.optString("license")
            if (lic.isNotBlank()) { appendLine(); appendLine("Lizenz (Auszug):"); appendLine(lic.trim().take(400)) }
        }
    } catch (e: Exception) {
        "Fehler: ${e.message}"
    }

    // ------------------------------------------------------------ Chat

    fun setModel(name: String) {
        chatModel.value = name
        prefs.edit().putString("model", name).apply()
    }

    fun setSystem(s: String) {
        systemPrompt.value = s
        prefs.edit().putString("system", s).apply()
    }

    fun setOptions(o: ChatOptions) {
        options.value = o
        prefs.edit().putFloat("temp", o.temperature).putInt("ctx", o.numCtx)
            .putString("keep", o.keepAlive).putBoolean("think", o.think).apply()
    }

    fun send(text: String, images: List<String> = emptyList()) {
        val model = chatModel.value
        if (model.isBlank() || _streaming.value || (text.isBlank() && images.isEmpty())) return
        val history = _chat.value + ChatMessage("user", text.trim(), images = images)
        _chat.value = history + ChatMessage("assistant", "")
        _streaming.value = true
        chatJob = scope.launch {
            val content = StringBuilder()
            val thinking = StringBuilder()
            var stats: GenStats? = null
            var lastUi = 0L
            fun publish(force: Boolean = false) {
                val now = System.currentTimeMillis()
                // höchstens ~20 UI-Updates pro Sekunde – spart Recompositions bei schnellen Modellen
                if (!force && now - lastUi < 50) return
                lastUi = now
                _chat.value = history + ChatMessage("assistant", content.toString(), thinking.toString(), stats = stats)
            }
            try {
                api().chat(model, history, systemPrompt.value, options.value).collect { ch ->
                    if (ch.error != null) content.append("\n[Fehler] ").append(ch.error)
                    content.append(ch.content)
                    thinking.append(ch.thinking)
                    if (ch.stats != null) stats = ch.stats
                    publish(ch.done)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                content.append(" ⏹")
            } catch (e: Exception) {
                content.append("\n[Fehler] ").append(e.message)
            } finally {
                _chat.value = history + ChatMessage("assistant", content.toString().ifBlank { "(keine Antwort)" }, thinking.toString(), stats = stats)
                _streaming.value = false
                scope.launch { refreshRunning() }
            }
        }
    }

    fun stop() { chatJob?.cancel() }

    fun clearChat() {
        stop()
        _chat.value = emptyList()
    }

    /** letzte Antwort neu erzeugen */
    fun regenerate() {
        if (_streaming.value) return
        val msgs = _chat.value
        val lastUser = msgs.indexOfLast { it.role == "user" }
        if (lastUser < 0) return
        val u = msgs[lastUser]
        _chat.value = msgs.take(lastUser)
        send(u.content, u.images)
    }

    // ------------------------------------------------------------ Benchmark

    fun benchmark(models: List<String>) {
        if (_benchRunning.value != null) return
        scope.launch {
            val prompt = "Erkläre in etwa 150 Wörtern, wie ein Transformer-Sprachmodell funktioniert."
            for (m in models) {
                _benchRunning.value = m
                val res = try {
                    // erster Lauf lädt das Modell, zweiter misst die reine Geschwindigkeit
                    val first = api().generate(m, "Hallo", 8)
                    val s = api().generate(m, prompt, 200)
                    BenchResult(m, s.copy(loadMs = first.loadMs))
                } catch (e: Exception) {
                    BenchResult(m, null, e.message)
                }
                _bench.value = _bench.value.filterNot { it.model == m } + res
            }
            _benchRunning.value = null
            refreshRunning()
        }
    }
}
