package de.marlon.orinpilot.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

data class OllamaModel(
    val name: String,
    val sizeBytes: Long,
    val family: String,
    val paramSize: String,
    val quant: String,
    val modified: String,
)

data class RunningModel(
    val name: String,
    val sizeBytes: Long,
    val vramBytes: Long,
    val expiresAt: String,
    val contextLength: Int,
) {
    /** Anteil, der auf der GPU liegt (Jetson: gemeinsamer Speicher, trotzdem aussagekräftig) */
    val gpuPct: Int get() = if (sizeBytes > 0) (vramBytes * 100 / sizeBytes).toInt() else 0
}

data class PullProgress(val status: String, val total: Long, val completed: Long, val error: String? = null) {
    val pct: Float get() = if (total > 0) completed.toFloat() / total else 0f
}

data class ChatMessage(
    val role: String,
    val content: String,
    val thinking: String = "",
    /** Base64-kodierte Bilder (für Vision-Modelle) */
    val images: List<String> = emptyList(),
    val stats: GenStats? = null,
)

data class GenStats(
    val promptTokens: Int,
    val promptMs: Long,
    val evalTokens: Int,
    val evalMs: Long,
    val loadMs: Long,
    val totalMs: Long,
) {
    val tokPerSec: Float get() = if (evalMs > 0) evalTokens * 1000f / evalMs else 0f
    val promptTokPerSec: Float get() = if (promptMs > 0) promptTokens * 1000f / promptMs else 0f

    companion object {
        fun from(o: JSONObject) = GenStats(
            promptTokens = o.optInt("prompt_eval_count"),
            promptMs = o.optLong("prompt_eval_duration") / 1_000_000,
            evalTokens = o.optInt("eval_count"),
            evalMs = o.optLong("eval_duration") / 1_000_000,
            loadMs = o.optLong("load_duration") / 1_000_000,
            totalMs = o.optLong("total_duration") / 1_000_000,
        )
    }
}

/** Ein Stück der Streaming-Antwort */
data class ChatChunk(val content: String = "", val thinking: String = "", val done: Boolean = false, val stats: GenStats? = null, val error: String? = null)

data class ChatOptions(
    val temperature: Float = 0.7f,
    val numCtx: Int = 4096,
    val keepAlive: String = "",
    val think: Boolean = false,
)

/**
 * Minimaler Client für die Ollama-REST-API. Läuft über den SSH-Tunnel (http://127.0.0.1:<lokaler Port>),
 * dadurch muss Ollama auf dem Jetson nicht im Netz freigegeben werden.
 */
class OllamaClient(private val base: String) {

    private fun open(path: String, method: String = "GET", timeoutMs: Int = 15_000): HttpURLConnection =
        (URL(base + path).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = timeoutMs
            readTimeout = timeoutMs
            setRequestProperty("Accept", "application/json")
            if (method != "GET") {
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
            }
        }

    private fun request(path: String, method: String = "GET", body: JSONObject? = null, timeoutMs: Int = 15_000): String {
        val c = open(path, method, timeoutMs)
        try {
            if (body != null) c.outputStream.use { it.write(body.toString().toByteArray()) }
            val code = c.responseCode
            val stream = if (code in 200..299) c.inputStream else c.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) throw IllegalStateException(errorText(text, code))
            return text
        } finally {
            c.disconnect()
        }
    }

    private fun errorText(text: String, code: Int): String =
        runCatching { JSONObject(text).optString("error") }.getOrNull()?.takeIf { it.isNotBlank() } ?: "HTTP $code $text".trim()

    suspend fun version(): String = withContext(Dispatchers.IO) {
        JSONObject(request("/api/version", timeoutMs = 4_000)).optString("version")
    }

    suspend fun tags(): List<OllamaModel> = withContext(Dispatchers.IO) {
        val arr = JSONObject(request("/api/tags")).optJSONArray("models") ?: JSONArray()
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            val d = o.optJSONObject("details") ?: JSONObject()
            OllamaModel(
                name = o.optString("name"),
                sizeBytes = o.optLong("size"),
                family = d.optString("family"),
                paramSize = d.optString("parameter_size"),
                quant = d.optString("quantization_level"),
                modified = o.optString("modified_at").take(10),
            )
        }.sortedBy { it.name }
    }

    suspend fun ps(): List<RunningModel> = withContext(Dispatchers.IO) {
        val arr = JSONObject(request("/api/ps")).optJSONArray("models") ?: JSONArray()
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            RunningModel(
                name = o.optString("name"),
                sizeBytes = o.optLong("size"),
                vramBytes = o.optLong("size_vram"),
                expiresAt = o.optString("expires_at"),
                contextLength = o.optInt("context_length"),
            )
        }
    }

    suspend fun show(name: String): JSONObject = withContext(Dispatchers.IO) {
        JSONObject(request("/api/show", "POST", JSONObject().put("model", name)))
    }

    /** Modell laden (keepAlive z. B. "30m", "-1") bzw. entladen ("0") */
    suspend fun setLoaded(name: String, keepAlive: String) = withContext(Dispatchers.IO) {
        request(
            "/api/generate", "POST",
            JSONObject().put("model", name).put("keep_alive", keepAlive.toIntOrNull() ?: keepAlive),
            timeoutMs = 300_000,
        )
        Unit
    }

    /** Liest eine NDJSON-Streaming-Antwort zeilenweise; Schließen des Flows trennt die Verbindung. */
    private fun ndjson(path: String, body: JSONObject): Flow<JSONObject> = callbackFlow {
        val c = open(path, "POST", timeoutMs = 600_000)
        val t = thread(name = "ollama-stream", isDaemon = true) {
            try {
                c.outputStream.use { it.write(body.toString().toByteArray()) }
                val code = c.responseCode
                if (code !in 200..299) {
                    val txt = c.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                    trySend(JSONObject().put("error", errorText(txt, code)))
                } else {
                    c.inputStream.bufferedReader().use { r ->
                        while (true) {
                            val line = r.readLine() ?: break
                            if (line.isBlank()) continue
                            val o = runCatching { JSONObject(line) }.getOrNull() ?: continue
                            if (trySend(o).isClosed) break
                        }
                    }
                }
            } catch (e: Exception) {
                trySend(JSONObject().put("error", e.message ?: e.toString()))
            }
            close()
        }
        awaitClose {
            runCatching { c.disconnect() }
            t.interrupt()
        }
    }.flowOn(Dispatchers.IO)

    fun pull(name: String): Flow<PullProgress> = callbackFlow {
        ndjson("/api/pull", JSONObject().put("model", name).put("stream", true)).collect { o ->
            send(
                PullProgress(
                    status = o.optString("status"),
                    total = o.optLong("total"),
                    completed = o.optLong("completed"),
                    error = o.optString("error").takeIf { it.isNotBlank() },
                )
            )
        }
        close()
        awaitClose { }
    }

    fun chat(model: String, messages: List<ChatMessage>, system: String, opts: ChatOptions): Flow<ChatChunk> = callbackFlow {
        val msgs = JSONArray()
        if (system.isNotBlank()) msgs.put(JSONObject().put("role", "system").put("content", system))
        messages.forEach { m ->
            val o = JSONObject().put("role", m.role).put("content", m.content)
            if (m.images.isNotEmpty()) o.put("images", JSONArray(m.images))
            msgs.put(o)
        }
        val body = JSONObject()
            .put("model", model)
            .put("messages", msgs)
            .put("stream", true)
            .put("options", JSONObject().put("temperature", opts.temperature.toDouble()).put("num_ctx", opts.numCtx))
        if (opts.think) body.put("think", true)
        if (opts.keepAlive.isNotBlank()) body.put("keep_alive", opts.keepAlive.toIntOrNull() ?: opts.keepAlive)

        ndjson("/api/chat", body).collect { o ->
            val err = o.optString("error").takeIf { it.isNotBlank() }
            if (err != null) { send(ChatChunk(error = err, done = true)); return@collect }
            val msg = o.optJSONObject("message")
            val done = o.optBoolean("done")
            send(
                ChatChunk(
                    content = msg?.optString("content").orEmpty(),
                    thinking = msg?.optString("thinking").orEmpty(),
                    done = done,
                    stats = if (done) GenStats.from(o) else null,
                )
            )
        }
        close()
        awaitClose { }
    }

    /** Einmalige Generierung ohne Streaming (für den Benchmark) */
    suspend fun generate(model: String, prompt: String, numPredict: Int): GenStats = withContext(Dispatchers.IO) {
        val o = JSONObject(
            request(
                "/api/generate", "POST",
                JSONObject().put("model", model).put("prompt", prompt).put("stream", false)
                    .put("options", JSONObject().put("num_predict", numPredict).put("temperature", 0)),
                timeoutMs = 600_000,
            )
        )
        GenStats.from(o)
    }

    companion object {
        /** Gute Startmodelle für den Orin Nano (8 GB gemeinsamer Speicher) */
        val SUGGESTED = listOf(
            Suggestion("llama3.2:3b", "Meta Llama 3.2 · 3B", "2,0 GB", "Allrounder, schnell"),
            Suggestion("qwen3:4b", "Qwen3 · 4B", "2,5 GB", "stark, mit Denkmodus"),
            Suggestion("gemma3:4b", "Gemma 3 · 4B", "3,3 GB", "Text + Bilder"),
            Suggestion("phi4-mini", "Phi-4 mini · 3,8B", "2,5 GB", "Logik/Mathe"),
            Suggestion("qwen2.5-coder:3b", "Qwen2.5 Coder · 3B", "1,9 GB", "Programmieren"),
            Suggestion("deepseek-r1:1.5b", "DeepSeek-R1 · 1,5B", "1,1 GB", "Reasoning, sehr klein"),
            Suggestion("llama3.2:1b", "Llama 3.2 · 1B", "1,3 GB", "minimal, sehr schnell"),
            Suggestion("moondream", "Moondream 2", "1,7 GB", "Vision, Bildbeschreibung"),
            Suggestion("llama3.1:8b", "Llama 3.1 · 8B", "4,9 GB", "groß – nur headless"),
            Suggestion("nomic-embed-text", "Nomic Embed", "0,3 GB", "Embeddings für RAG"),
        )
    }
}

data class Suggestion(val tag: String, val title: String, val size: String, val note: String)

/** Einstellungen des Ollama-Dienstes (systemd-Umgebungsvariablen) */
data class OllamaEnv(
    val host: String = "",
    val keepAlive: String = "",
    val flashAttention: Boolean = false,
    val kvCacheType: String = "",
    val maxLoaded: String = "",
    val numParallel: String = "",
    val contextLength: String = "",
) {
    val lanExposed: Boolean get() = host.startsWith("0.0.0.0")

    fun toEnvLines(): List<String> = buildList {
        if (host.isNotBlank()) add("OLLAMA_HOST=$host")
        if (keepAlive.isNotBlank()) add("OLLAMA_KEEP_ALIVE=$keepAlive")
        if (flashAttention) add("OLLAMA_FLASH_ATTENTION=1")
        if (kvCacheType.isNotBlank()) add("OLLAMA_KV_CACHE_TYPE=$kvCacheType")
        if (maxLoaded.isNotBlank()) add("OLLAMA_MAX_LOADED_MODELS=$maxLoaded")
        if (numParallel.isNotBlank()) add("OLLAMA_NUM_PARALLEL=$numParallel")
        if (contextLength.isNotBlank()) add("OLLAMA_CONTEXT_LENGTH=$contextLength")
    }

    companion object {
        /** Ausgabe von `systemctl show ollama -p Environment` */
        fun parse(envLine: String): OllamaEnv {
            val m = HashMap<String, String>()
            Regex("""(OLLAMA_[A-Z_]+)=("[^"]*"|\S+)""").findAll(envLine).forEach {
                m[it.groupValues[1]] = it.groupValues[2].trim('"')
            }
            return OllamaEnv(
                host = m["OLLAMA_HOST"].orEmpty(),
                keepAlive = m["OLLAMA_KEEP_ALIVE"].orEmpty(),
                flashAttention = m["OLLAMA_FLASH_ATTENTION"].let { it == "1" || it.equals("true", true) },
                kvCacheType = m["OLLAMA_KV_CACHE_TYPE"].orEmpty(),
                maxLoaded = m["OLLAMA_MAX_LOADED_MODELS"].orEmpty(),
                numParallel = m["OLLAMA_NUM_PARALLEL"].orEmpty(),
                contextLength = m["OLLAMA_CONTEXT_LENGTH"].orEmpty(),
            )
        }
    }
}

data class OllamaStatus(
    val installed: Boolean = false,
    val cliVersion: String = "",
    val serviceActive: Boolean = false,
    val serviceEnabled: Boolean = false,
    val env: OllamaEnv = OllamaEnv(),
    val modelsDir: String = "",
    val diskFree: String = "",
    val gpuInfo: String = "",
) {
    companion object {
        fun parse(out: String): OllamaStatus {
            val m = out.lines().mapNotNull { l ->
                val i = l.indexOf('='); if (i <= 0) null else l.substring(0, i) to l.substring(i + 1).trim()
            }.toMap()
            return OllamaStatus(
                installed = m["INSTALLED"] == "yes",
                cliVersion = m["VERSION"].orEmpty().substringAfterLast(' ').ifBlank { m["VERSION"].orEmpty() },
                serviceActive = m["ACTIVE"] == "active",
                serviceEnabled = m["ENABLED"] == "enabled",
                env = OllamaEnv.parse(m["ENV"].orEmpty()),
                modelsDir = m["MODELS"].orEmpty(),
                diskFree = m["FREE"].orEmpty(),
                gpuInfo = m["GPU"].orEmpty(),
            )
        }
    }
}
