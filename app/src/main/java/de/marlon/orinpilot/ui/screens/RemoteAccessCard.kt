package de.marlon.orinpilot.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.marlon.orinpilot.ConnState
import de.marlon.orinpilot.MainViewModel
import de.marlon.orinpilot.data.LanScanner
import de.marlon.orinpilot.ui.OP
import de.marlon.orinpilot.ui.SectionCard
import kotlinx.coroutines.delay

private const val TAILSCALE_PKG = "com.tailscale.ipn"

/**
 * Fernzugriff per Tailscale: Jetson und Handy landen im selben privaten (verschlüsselten)
 * WireGuard-Netz und erreichen sich überall – ohne Portfreigabe am Router.
 */
@Composable
fun RemoteAccessCard(vm: MainViewModel) {
    val ctx = LocalContext.current
    val remote by vm.remote.collectAsState()
    val state by vm.state.collectAsState()
    val profile = (state as? ConnState.Connected)?.profile
    val st = remote.status
    var phoneTs by remember { mutableStateOf(LanScanner.tailscaleActive(ctx)) }
    var authKey by remember { mutableStateOf("") }
    var manual by remember { mutableStateOf(false) }
    var manualHost by remember { mutableStateOf(profile?.remoteHost ?: "") }
    var manualPort by remember { mutableStateOf((profile?.remotePort ?: 22).toString()) }

    // Handy-VPN-Status regelmäßig prüfen
    LaunchedEffect(Unit) {
        while (true) {
            phoneTs = LanScanner.tailscaleActive(ctx)
            delay(3000)
        }
    }

    fun openUrl(url: String) {
        runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    fun openTailscaleApp() {
        val launch = ctx.packageManager.getLaunchIntentForPackage(TAILSCALE_PKG)
        if (launch != null) ctx.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        else openUrl("https://play.google.com/store/apps/details?id=$TAILSCALE_PKG")
    }

    SectionCard("Fernzugriff (überall erreichbar)") {
        Text(
            "Damit du den Jetson auch unterwegs (mobile Daten, fremdes WLAN) steuern kannst, kommen Jetson und Handy " +
                "in ein privates Tailscale-Netz. Die App verbindet sich danach automatisch: zu Hause per LAN, unterwegs über Tailscale.",
            color = OP.TextDim, fontSize = 12.sp
        )
        Spacer(Modifier.height(10.dp))

        StatusRow("Jetson", when {
            st == null -> "prüfe …" to OP.TextDim
            !st.installed -> "Tailscale nicht installiert" to OP.Amber
            st.running -> "online · ${st.ip}" to OP.Green
            st.state == "NeedsLogin" -> "Anmeldung nötig" to OP.Amber
            st.state.isNotBlank() -> st.state to OP.Amber
            else -> "installiert, nicht aktiv" to OP.Amber
        })
        StatusRow("Handy", if (phoneTs) "Tailscale-VPN aktiv" to OP.Green else "Tailscale-VPN aus / nicht installiert" to OP.Amber)
        StatusRow(
            "Profil",
            if (profile?.remoteHost.isNullOrBlank()) "keine Fernzugriff-Adresse" to OP.TextDim
            else "${profile?.remoteHost}:${profile?.remotePort}" to OP.Cyan
        )
        if (st?.dnsName?.isNotBlank() == true) {
            Text("MagicDNS: ${st.dnsName}", color = OP.TextDim, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
        }

        if (remote.step.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (remote.busy) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = OP.Cyan)
                    Spacer(Modifier.width(8.dp))
                }
                Text(remote.step, color = OP.Text, fontSize = 13.sp)
            }
        }

        // Login-Link anzeigen, sobald vorhanden
        if (st != null && !st.running && st.authUrl.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(st.authUrl, color = OP.Cyan, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
            Button(onClick = { openUrl(st.authUrl) }, modifier = Modifier.fillMaxWidth()) {
                Text("Login-Link öffnen & Jetson freigeben")
            }
        }

        Spacer(Modifier.height(10.dp))
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            when {
                st == null -> {}
                !st.installed -> Button(
                    onClick = { vm.installTailscale() }, enabled = !remote.busy, modifier = Modifier.fillMaxWidth()
                ) { Text("1. Tailscale auf dem Jetson installieren") }
                !st.running -> {
                    Button(
                        onClick = { vm.loginTailscale(null) }, enabled = !remote.busy, modifier = Modifier.fillMaxWidth()
                    ) { Text("2. Jetson anmelden (Login-Link)") }
                    OutlinedTextField(
                        authKey, { authKey = it.trim() },
                        label = { Text("…oder Auth-Key (tskey-auth-…)") }, singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (authKey.isNotBlank()) {
                        OutlinedButton(
                            onClick = { vm.loginTailscale(authKey) }, enabled = !remote.busy, modifier = Modifier.fillMaxWidth()
                        ) { Text("Mit Auth-Key anmelden") }
                    }
                }
                else -> {}
            }
            if (!phoneTs) {
                OutlinedButton(onClick = { openTailscaleApp() }, modifier = Modifier.fillMaxWidth()) {
                    Text("3. Tailscale-App auf dem Handy öffnen / installieren")
                }
                Text(
                    "Mit demselben Konto anmelden wie beim Jetson und den VPN-Schalter aktivieren.",
                    color = OP.TextDim, fontSize = 11.sp
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = { vm.refreshRemote() }, enabled = !remote.busy) { Text("Status prüfen") }
                if (remote.busy) TextButton(onClick = { vm.cancelRemoteSetup() }) { Text("Abbrechen") }
                if (st?.running == true) TextButton(onClick = { vm.disableTailscale() }) {
                    Text("Tailscale trennen", color = OP.Red)
                }
            }
        }

        if (st?.running == true && phoneTs && profile?.remoteHost == st.ip) {
            Text(
                "✓ Fertig. Du kannst den Jetson jetzt von überall erreichen.",
                color = OP.Green, fontSize = 13.sp
            )
        }

        TextButton(onClick = { manual = !manual }) {
            Text(if (manual) "Eigene Adresse ausblenden" else "Eigene Adresse statt Tailscale (DynDNS, ZeroTier …)")
        }
        if (manual) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(manualHost, { manualHost = it.trim() }, label = { Text("Adresse") }, singleLine = true, modifier = Modifier.weight(1f))
                OutlinedTextField(
                    manualPort, { manualPort = it.filter(Char::isDigit).take(5) }, label = { Text("Port") },
                    singleLine = true, modifier = Modifier.width(80.dp)
                )
            }
            TextButton(onClick = { vm.setRemoteHost(manualHost, manualPort.toIntOrNull() ?: 22); vm.toast("Gespeichert") }) {
                Text("Speichern")
            }
            Text(
                "Achtung: Bei Portfreigabe am Router ist SSH öffentlich erreichbar – dann unbedingt Schlüssel-Login nutzen " +
                    "und Passwort-Login abschalten. Tailscale ist die sicherere Variante.",
                color = OP.Amber, fontSize = 11.sp
            )
        }

        if (remote.log.isNotBlank()) {
            Spacer(Modifier.height(6.dp))
            Box(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Color(0xFF0A0C0B)).padding(8.dp)
            ) {
                Text(remote.log.takeLast(1200), color = OP.TextDim, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
            }
        }
    }
}

@Composable
private fun StatusRow(label: String, value: Pair<String, Color>) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 3.dp)) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(value.second))
        Spacer(Modifier.width(8.dp))
        Text(label, color = OP.TextDim, fontSize = 13.sp, modifier = Modifier.width(56.dp))
        Text(value.first, color = OP.Text, fontSize = 13.sp)
    }
}
