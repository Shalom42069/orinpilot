package de.marlon.orinpilot.data

data class ProcessInfo(
    val pid: Int,
    val user: String,
    val cpu: Float,
    val mem: Float,
    val rssKb: Long,
    val command: String,
)

data class ServiceInfo(
    val unit: String,
    val load: String,
    val active: String,
    val sub: String,
    val description: String,
    val enabled: String = "",
) {
    val name: String get() = unit.removeSuffix(".service")
}

data class ContainerInfo(
    val id: String,
    val name: String,
    val image: String,
    val status: String,
) {
    val running: Boolean get() = status.startsWith("Up")
}

data class PowerMode(val id: Int, val name: String)

data class WifiNetwork(val inUse: Boolean, val ssid: String, val signal: Int, val security: String)

data class SystemInfo(
    val hostname: String = "",
    val model: String = "",
    val os: String = "",
    val kernel: String = "",
    val l4t: String = "",
    val jetpack: String = "",
    val uptime: String = "",
    val ips: String = "",
    val disk: String = "",
    val defaultTarget: String = "",
    val load: String = "",
)

object Parsers {

    /** Ausgabe von [Scripts.PS] */
    fun processes(out: String): List<ProcessInfo> = out.lineSequence().mapNotNull { line ->
        val parts = line.trim().split(Regex("\\s+"), limit = 6)
        if (parts.size < 6) return@mapNotNull null
        val pid = parts[0].toIntOrNull() ?: return@mapNotNull null
        ProcessInfo(
            pid = pid,
            user = parts[1],
            cpu = parts[2].replace(',', '.').toFloatOrNull() ?: 0f,
            mem = parts[3].replace(',', '.').toFloatOrNull() ?: 0f,
            rssKb = parts[4].toLongOrNull() ?: 0L,
            command = parts[5],
        )
    }.toList()

    /**
     * Ausgabe von [Scripts.SERVICES]: erst list-units, dann Trenner "###", dann list-unit-files
     */
    fun services(out: String): List<ServiceInfo> {
        val unitsPart = out.substringBefore("###")
        val filesPart = out.substringAfter("###", "")
        val enabled = HashMap<String, String>()
        filesPart.lineSequence().forEach { line ->
            val p = line.trim().split(Regex("\\s+"))
            if (p.size >= 2 && p[0].endsWith(".service")) enabled[p[0]] = p[1]
        }
        return unitsPart.lineSequence().mapNotNull { raw ->
            val line = raw.trim().removePrefix("●").removePrefix("*").trim()
            val p = line.split(Regex("\\s+"), limit = 5)
            if (p.size < 4 || !p[0].endsWith(".service")) return@mapNotNull null
            ServiceInfo(
                unit = p[0], load = p[1], active = p[2], sub = p[3],
                description = p.getOrElse(4) { "" },
                enabled = enabled[p[0]] ?: "",
            )
        }.toList()
    }

    /** Ausgabe von docker ps -a --format '{{.ID}}\t{{.Names}}\t{{.Image}}\t{{.Status}}' */
    fun containers(out: String): List<ContainerInfo> = out.lineSequence().mapNotNull { line ->
        val p = line.split('\t')
        if (p.size < 4) null else ContainerInfo(p[0].trim(), p[1].trim(), p[2].trim(), p[3].trim())
    }.toList()

    private val POWER_MODEL = Regex("""<\s*POWER_MODEL\s+ID=(\d+)\s+NAME=([^\s>]+)\s*>""")

    /** Inhalt von /etc/nvpmodel.conf */
    fun powerModes(conf: String): List<PowerMode> =
        POWER_MODEL.findAll(conf).map { PowerMode(it.groupValues[1].toInt(), it.groupValues[2]) }
            .distinctBy { it.id }.toList()

    /** Ausgabe von `nvpmodel -q` -> aktuelle ID */
    fun currentPowerMode(out: String): Int? =
        out.lines().map { it.trim() }.lastOrNull { it.isNotEmpty() && it.all(Char::isDigit) }?.toIntOrNull()

    /** Ausgabe von `nmcli -t -f IN-USE,SSID,SIGNAL,SECURITY dev wifi list` */
    fun wifi(out: String): List<WifiNetwork> = out.lineSequence().mapNotNull { line ->
        val f = splitNmcli(line)
        if (f.size < 4) return@mapNotNull null
        val ssid = f[1]
        if (ssid.isBlank()) return@mapNotNull null
        WifiNetwork(f[0].trim() == "*", ssid, f[2].toIntOrNull() ?: 0, f[3])
    }.toList()
        .groupBy { it.ssid }
        .map { (_, list) -> list.firstOrNull { it.inUse } ?: list.maxBy { it.signal } }
        .sortedWith(compareByDescending<WifiNetwork> { it.inUse }.thenByDescending { it.signal })

    /** nmcli -t trennt mit ':' und maskiert ':' als '\:' */
    fun splitNmcli(line: String): List<String> {
        val out = mutableListOf<String>()
        val sb = StringBuilder()
        var i = 0
        while (i < line.length) {
            val c = line[i]
            if (c == '\\' && i + 1 < line.length) { sb.append(line[i + 1]); i += 2; continue }
            if (c == ':') { out.add(sb.toString()); sb.setLength(0) } else sb.append(c)
            i++
        }
        out.add(sb.toString())
        return out
    }

    /** KEY=VALUE-Zeilen aus [Scripts.SYSINFO] */
    fun systemInfo(out: String): SystemInfo {
        val m = out.lines().mapNotNull { l ->
            val i = l.indexOf('=')
            if (i <= 0) null else l.substring(0, i) to l.substring(i + 1).trim()
        }.toMap()
        return SystemInfo(
            hostname = m["HOST"].orEmpty(),
            model = m["MODEL"].orEmpty(),
            os = m["OS"].orEmpty(),
            kernel = m["KERNEL"].orEmpty(),
            l4t = prettyL4t(m["L4T"].orEmpty()),
            jetpack = m["JETPACK"].orEmpty(),
            uptime = m["UPTIME"].orEmpty().removePrefix("up "),
            ips = m["IP"].orEmpty(),
            disk = m["DISK"].orEmpty(),
            defaultTarget = m["TARGET"].orEmpty(),
            load = m["LOAD"].orEmpty(),
        )
    }

    /** "# R36 (release), REVISION: 4.0, ..." -> "R36.4.0" */
    fun prettyL4t(raw: String): String {
        val rel = Regex("""R(\d+)""").find(raw)?.groupValues?.get(1) ?: return raw
        val rev = Regex("""REVISION:\s*([\d.]+)""").find(raw)?.groupValues?.get(1) ?: return "R$rel"
        return "R$rel.$rev"
    }

    /** Shell-sicheres Quoting mit einfachen Anführungszeichen */
    fun q(s: String): String = "'" + s.replace("'", "'\\''") + "'"
}

data class TailscaleStatus(
    val installed: Boolean = false,
    val serviceActive: Boolean = false,
    val ip: String = "",
    val state: String = "",
    val authUrl: String = "",
    val dnsName: String = "",
) {
    val running: Boolean get() = state == "Running" && ip.isNotBlank()

    companion object {
        fun parse(out: String): TailscaleStatus {
            val m = out.lines().mapNotNull { l ->
                val i = l.indexOf('=')
                if (i <= 0) null else l.substring(0, i) to l.substring(i + 1).trim()
            }.toMap()
            return TailscaleStatus(
                installed = m["INSTALLED"] == "yes",
                serviceActive = m["SERVICE"] == "active",
                ip = m["IP"].orEmpty().takeIf { it.startsWith("100.") }.orEmpty(),
                state = m["STATE"].orEmpty(),
                authUrl = m["AUTHURL"].orEmpty().split(Regex("\\s+")).lastOrNull { it.startsWith("https://") }.orEmpty(),
                dnsName = m["DNS"].orEmpty(),
            )
        }
    }
}
