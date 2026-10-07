package de.marlon.orinpilot.data

import android.content.Context
import android.net.ConnectivityManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket

data class FoundHost(val ip: String, val banner: String, val hostname: String) {
    /** Ubuntu-SSH-Banner = sehr wahrscheinlich ein Jetson / Linux-Board */
    val looksLikeJetson: Boolean
        get() = hostname.contains("jetson", true) || hostname.contains("orin", true) ||
            hostname.contains("nano", true) || ip == "192.168.55.1"
}

/** Sucht im lokalen /24-Netz (und am USB-Gerätemodus-Port 192.168.55.1) nach offenen SSH-Ports. */
object LanScanner {

    fun localIpv4(context: Context): String? {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val candidates = mutableListOf<String>()
        cm.allNetworksCompat().forEach { net ->
            cm.getLinkProperties(net)?.linkAddresses?.forEach { la ->
                val a = la.address
                if (a is Inet4Address && !a.isLoopbackAddress) candidates.add(a.hostAddress ?: "")
            }
        }
        return candidates.firstOrNull { it.startsWith("192.168.") || it.startsWith("10.") || it.startsWith("172.") }
            ?: candidates.firstOrNull()
    }

    /** Läuft auf dem Handy ein VPN mit Tailscale-Adresse (100.64.0.0/10)? */
    fun tailscaleActive(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        return cm.allNetworksCompat().any { net ->
            val caps = cm.getNetworkCapabilities(net)
            val isVpn = caps?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN) == true
            isVpn && (cm.getLinkProperties(net)?.linkAddresses?.any { la ->
                val b = la.address.address
                b.size == 4 && (b[0].toInt() and 0xFF) == 100 && (b[1].toInt() and 0xC0) == 64
            } == true)
        }
    }

    @Suppress("DEPRECATION")
    private fun ConnectivityManager.allNetworksCompat() =
        (listOfNotNull(activeNetwork) + allNetworks.toList()).distinct()

    suspend fun scan(
        context: Context,
        port: Int = 22,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): List<FoundHost> = withContext(Dispatchers.IO) {
        val ip = localIpv4(context)
        val targets = mutableListOf("192.168.55.1")
        if (ip != null) {
            val prefix = ip.substringBeforeLast('.')
            for (i in 1..254) {
                val t = "$prefix.$i"
                if (t != ip) targets.add(t)
            }
        }
        val total = targets.size
        var done = 0
        val lock = Any()
        val sem = Semaphore(64)
        coroutineScope {
            targets.map { t ->
                async {
                    sem.withPermit {
                        val r = probe(t, port)
                        synchronized(lock) { done++; onProgress(done, total) }
                        r
                    }
                }
            }.awaitAll().filterNotNull()
        }.sortedWith(compareByDescending<FoundHost> { it.looksLikeJetson }.thenBy { lastOctet(it.ip) })
    }

    private fun lastOctet(ip: String) = ip.substringAfterLast('.').toIntOrNull() ?: 0

    private fun probe(ip: String, port: Int): FoundHost? {
        return try {
            Socket().use { s ->
                s.connect(InetSocketAddress(ip, port), 350)
                s.soTimeout = 900
                val banner = runCatching {
                    val buf = ByteArray(256)
                    val n = s.getInputStream().read(buf)
                    if (n > 0) String(buf, 0, n).trim() else ""
                }.getOrDefault("")
                val name = runCatching {
                    val h = InetAddress.getByName(ip).canonicalHostName
                    if (h == ip) "" else h
                }.getOrDefault("")
                FoundHost(ip, banner, name)
            }
        } catch (_: Exception) {
            null
        }
    }
}
