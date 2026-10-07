package de.marlon.orinpilot.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.marlon.orinpilot.ConnState
import de.marlon.orinpilot.MainViewModel
import de.marlon.orinpilot.ui.Busy
import de.marlon.orinpilot.ui.KeyValue
import de.marlon.orinpilot.ui.LoadBar
import de.marlon.orinpilot.ui.MetricTile
import de.marlon.orinpilot.ui.OP
import de.marlon.orinpilot.ui.SectionCard
import de.marlon.orinpilot.ui.loadColor
import de.marlon.orinpilot.ui.tempColor
import java.util.Locale

@Composable
fun DashboardScreen(vm: MainViewModel, onOpenRemote: () -> Unit) {
    val stats by vm.stats.collectAsState()
    val err by vm.statsError.collectAsState()
    val cpuH by vm.cpuHist.collectAsState()
    val gpuH by vm.gpuHist.collectAsState()
    val tempH by vm.tempHist.collectAsState()
    val powH by vm.powerHist.collectAsState()
    val info by vm.sysInfo.collectAsState()
    val state by vm.state.collectAsState()
    val profile = (state as? ConnState.Connected)?.profile

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Hinweis, solange kein Fernzugriff eingerichtet ist
        if (profile != null && profile.remoteHost.isBlank()) {
            SectionCard("Fernzugriff") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Public, null, tint = OP.Cyan)
                    Spacer(Modifier.width(10.dp))
                    Text(
                        "Noch nicht eingerichtet – aktuell klappt die Verbindung nur im selben Netz.",
                        color = OP.Text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f)
                    )
                }
                TextButton(onClick = onOpenRemote) { Text("Jetzt einrichten") }
            }
        }

        val s = stats
        if (s == null) {
            if (err != null) {
                SectionCard("Live-Werte") {
                    Text(err ?: "", color = OP.Amber, fontSize = 13.sp)
                    TextButton(onClick = { vm.startStats() }) { Text("Erneut versuchen") }
                }
            } else Busy()
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                MetricTile(
                    "CPU", "${s.cpuAvg}", "%", loadColor(s.cpuAvg.toFloat()), cpuH, Modifier.weight(1f),
                    sub = s.cpuFreqs.filterNotNull().maxOrNull()?.let { "bis $it MHz" } ?: "", histMax = 100f
                )
                MetricTile(
                    "GPU", "${s.gpuLoad ?: 0}", "%", OP.Green, gpuH, Modifier.weight(1f),
                    sub = s.gpuFreq?.let { "$it MHz" } ?: "", histMax = 100f
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                val t = s.hotTemp
                MetricTile(
                    "Temperatur", t?.let { String.format(Locale.GERMANY, "%.1f", it) } ?: "–", "°C",
                    tempColor(t ?: 0f), tempH, Modifier.weight(1f),
                    sub = if (s.temps.containsKey("tj")) "Junction (tj)" else "max. Sensor", histMax = null
                )
                val pw = s.totalPowerMw
                MetricTile(
                    "Leistung", pw?.let { String.format(Locale.GERMANY, "%.1f", it / 1000f) } ?: "–", "W",
                    OP.Amber, powH, Modifier.weight(1f),
                    sub = "Eingang (VDD_IN)", histMax = null
                )
            }

            SectionCard("Speicher") {
                val ramPct = if (s.ramTotalMb > 0) s.ramUsedMb * 100f / s.ramTotalMb else 0f
                LabeledBar("RAM", "${s.ramUsedMb} / ${s.ramTotalMb} MB", ramPct)
                Spacer(Modifier.height(10.dp))
                val swPct = if (s.swapTotalMb > 0) s.swapUsedMb * 100f / s.swapTotalMb else 0f
                LabeledBar("Swap", "${s.swapUsedMb} / ${s.swapTotalMb} MB", swPct)
                s.emcLoad?.let {
                    Spacer(Modifier.height(10.dp))
                    LabeledBar("EMC (Speicherbus)", "$it %" + (s.emcFreq?.let { f -> " @ $f MHz" } ?: ""), it.toFloat())
                }
            }

            SectionCard("CPU-Kerne") {
                s.cpuLoads.forEachIndexed { i, load ->
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 3.dp)) {
                        Text("Kern $i", color = OP.TextDim, fontSize = 12.sp, modifier = Modifier.width(52.dp))
                        if (load == null) {
                            Text("aus", color = OP.TextDim, fontSize = 12.sp)
                        } else {
                            LoadBar(load.toFloat(), loadColor(load.toFloat()), Modifier.weight(1f))
                            Text(
                                "$load% " + (s.cpuFreqs.getOrNull(i)?.let { "· $it" } ?: ""),
                                color = OP.Text, fontSize = 12.sp, fontFamily = FontFamily.Monospace,
                                modifier = Modifier.width(92.dp).padding(start = 8.dp)
                            )
                        }
                    }
                }
            }

            if (s.temps.isNotEmpty()) {
                SectionCard("Temperaturen") {
                    s.temps.entries.chunked(2).forEach { pair ->
                        Row {
                            pair.forEach { (k, v) ->
                                Row(Modifier.weight(1f).padding(vertical = 3.dp)) {
                                    Text(k, color = OP.TextDim, fontSize = 13.sp, modifier = Modifier.width(56.dp))
                                    Text(
                                        String.format(Locale.GERMANY, "%.1f °C", v),
                                        color = tempColor(v), fontSize = 13.sp, fontFamily = FontFamily.Monospace
                                    )
                                }
                            }
                            if (pair.size == 1) Spacer(Modifier.weight(1f))
                        }
                    }
                }
            }

            if (s.rails.isNotEmpty()) {
                SectionCard("Stromschienen") {
                    s.rails.forEach { r ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                            Text(r.name, color = OP.TextDim, fontSize = 12.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f))
                            Text(
                                "${r.currentMw} mW  (Ø ${r.averageMw})",
                                color = OP.Text, fontSize = 12.sp, fontFamily = FontFamily.Monospace
                            )
                        }
                    }
                }
            }
        }

        SectionCard("System", trailing = {
            IconButton(onClick = { vm.refreshSysInfo() }) { Icon(Icons.Default.Refresh, "Aktualisieren", tint = OP.TextDim) }
        }) {
            val i = info
            if (i == null) Busy() else {
                KeyValue("Modell", i.model)
                KeyValue("Hostname", i.hostname)
                KeyValue("System", i.os)
                KeyValue("L4T", i.l4t)
                KeyValue("JetPack", i.jetpack)
                KeyValue("Kernel", i.kernel)
                KeyValue("Laufzeit", i.uptime)
                KeyValue("Last (1/5/15)", i.load)
                KeyValue("Speicher /", i.disk)
                KeyValue("IP-Adressen", i.ips)
                KeyValue("Verbunden über", vm.connectedVia + if (profile?.remoteHost == vm.connectedVia && vm.connectedVia.isNotBlank()) " (Fernzugriff)" else " (LAN)")
                KeyValue("Modus", if (i.defaultTarget.startsWith("multi-user")) "Headless (ohne Desktop)" else "Mit Desktop-GUI")
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun LabeledBar(label: String, value: String, pct: Float) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, color = OP.TextDim, fontSize = 13.sp, modifier = Modifier.weight(1f))
        Text(value, color = OP.Text, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
    }
    Spacer(Modifier.height(5.dp))
    LoadBar(pct, loadColor(pct))
}
