package de.marlon.orinpilot.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class HostProfile(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "Jetson Orin Nano",
    val host: String = "",
    val port: Int = 22,
    val user: String = "",
    /** SSH-Passwort – wird auch für sudo verwendet */
    val password: String = "",
    /** optional: privater Schlüssel (PEM / OpenSSH) */
    val privateKey: String = "",
    val keyPassphrase: String = "",
    /** SHA256-Fingerprint des Host-Schlüssels (Trust on first use) */
    val hostKeyFingerprint: String = "",
    /** Fernzugriff: Tailscale-IP (100.x.y.z), MagicDNS-Name oder DynDNS-Adresse */
    val remoteHost: String = "",
    val remotePort: Int = 22,
    /** zuletzt erfolgreich genutzte Adresse (wird zuerst probiert) */
    val lastGoodHost: String = "",
) {
    /** Reihenfolge der Verbindungsversuche: zuletzt erfolgreich, LAN, Fernzugriff */
    fun candidates(): List<Pair<String, Int>> {
        val lan = host.trim() to port
        val remote = remoteHost.trim() to remotePort
        val list = mutableListOf<Pair<String, Int>>()
        if (lastGoodHost.isNotBlank()) list += if (lastGoodHost == remote.first) remote else lan
        list += lan
        if (remote.first.isNotBlank()) list += remote
        return list.filter { it.first.isNotBlank() }.distinct()
    }

    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("name", name).put("host", host).put("port", port)
        .put("user", user).put("password", password).put("privateKey", privateKey)
        .put("keyPassphrase", keyPassphrase).put("hostKeyFingerprint", hostKeyFingerprint)
        .put("remoteHost", remoteHost).put("remotePort", remotePort).put("lastGoodHost", lastGoodHost)

    companion object {
        fun fromJson(o: JSONObject) = HostProfile(
            id = o.optString("id", UUID.randomUUID().toString()),
            name = o.optString("name"),
            host = o.optString("host"),
            port = o.optInt("port", 22),
            user = o.optString("user"),
            password = o.optString("password"),
            privateKey = o.optString("privateKey"),
            keyPassphrase = o.optString("keyPassphrase"),
            hostKeyFingerprint = o.optString("hostKeyFingerprint"),
            remoteHost = o.optString("remoteHost"),
            remotePort = o.optInt("remotePort", 22),
            lastGoodHost = o.optString("lastGoodHost"),
        )
    }
}

data class Snippet(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val command: String,
    val sudo: Boolean = false,
)

/** Einfache lokale Ablage (app-privat, Backup deaktiviert). */
class ProfileStore(context: Context) {
    private val prefs = context.getSharedPreferences("orinpilot", Context.MODE_PRIVATE)

    fun loadProfiles(): List<HostProfile> {
        val raw = prefs.getString("profiles", null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { HostProfile.fromJson(arr.getJSONObject(it)) }
        }.getOrDefault(emptyList())
    }

    fun saveProfiles(list: List<HostProfile>) {
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        prefs.edit().putString("profiles", arr.toString()).apply()
    }

    fun loadSnippets(): List<Snippet> {
        val raw = prefs.getString("snippets", null) ?: return DEFAULT_SNIPPETS
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map {
                val o = arr.getJSONObject(it)
                Snippet(o.optString("id"), o.optString("title"), o.optString("command"), o.optBoolean("sudo"))
            }
        }.getOrDefault(DEFAULT_SNIPPETS)
    }

    fun saveSnippets(list: List<Snippet>) {
        val arr = JSONArray()
        list.forEach {
            arr.put(JSONObject().put("id", it.id).put("title", it.title).put("command", it.command).put("sudo", it.sudo))
        }
        prefs.edit().putString("snippets", arr.toString()).apply()
    }

    var fontSizeSp: Float
        get() = prefs.getFloat("fontSize", 12f)
        set(v) = prefs.edit().putFloat("fontSize", v).apply()

    companion object {
        val DEFAULT_SNIPPETS = listOf(
            Snippet(title = "Paketlisten aktualisieren", command = "apt-get update", sudo = true),
            Snippet(title = "Upgrades anzeigen", command = "apt list --upgradable 2>/dev/null | head -n 50"),
            Snippet(title = "System-Upgrade", command = "DEBIAN_FRONTEND=noninteractive apt-get -y upgrade", sudo = true),
            Snippet(title = "Speicherplatz", command = "df -h -x tmpfs -x devtmpfs"),
            Snippet(title = "Größte Ordner in ~", command = "du -h --max-depth=1 ~ 2>/dev/null | sort -hr | head -n 15"),
            Snippet(title = "USB-Geräte", command = "lsusb"),
            Snippet(title = "Kameras", command = "ls -l /dev/video* 2>/dev/null; v4l2-ctl --list-devices 2>/dev/null"),
            Snippet(title = "CUDA-Version", command = "/usr/local/cuda/bin/nvcc --version 2>/dev/null || cat /usr/local/cuda/version.json 2>/dev/null | head -n 5"),
            Snippet(title = "Kernel-Log (letzte 40)", command = "dmesg | tail -n 40", sudo = true),
            Snippet(title = "jtop installieren", command = "pip3 install -U jetson-stats", sudo = true),
            Snippet(title = "Journal-Fehler (Boot)", command = "journalctl -b -p err --no-pager | tail -n 60", sudo = true),
            Snippet(title = "Offene Ports", command = "ss -tulpn", sudo = true),
        )
    }
}
