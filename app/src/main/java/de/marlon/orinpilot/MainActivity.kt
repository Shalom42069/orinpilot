package de.marlon.orinpilot

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.marlon.orinpilot.ui.OP
import de.marlon.orinpilot.ui.OrinTheme
import de.marlon.orinpilot.ui.screens.ControlScreen
import de.marlon.orinpilot.ui.screens.DashboardScreen
import de.marlon.orinpilot.ui.screens.FilesScreen
import de.marlon.orinpilot.ui.screens.HostsScreen
import de.marlon.orinpilot.ui.screens.SystemScreen
import de.marlon.orinpilot.ui.screens.TerminalScreen

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            OrinTheme {
                Box(Modifier.fillMaxSize().background(OP.Bg)) {
                    App(vm)
                }
            }
        }
    }
}

private data class Tab(val label: String, val icon: ImageVector)

private val TABS = listOf(
    Tab("Übersicht", Icons.Default.Dashboard),
    Tab("Steuerung", Icons.Default.Tune),
    Tab("Terminal", Icons.Default.Code),
    Tab("Dateien", Icons.Default.Folder),
    Tab("System", Icons.Default.Memory),
)

@Composable
fun App(vm: MainViewModel) {
    val ctx = LocalContext.current
    val state by vm.state.collectAsState()

    LaunchedEffect(Unit) {
        vm.messages.collect { Toast.makeText(ctx, it, Toast.LENGTH_SHORT).show() }
    }

    val connected = state as? ConnState.Connected
    if (connected == null) {
        Box(Modifier.fillMaxSize().safeDrawingPadding()) { HostsScreen(vm) }
        return
    }

    var tab by rememberSaveable { mutableIntStateOf(0) }
    BackHandler(enabled = tab != 0) { tab = 0 }

    val imeVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0

    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
        // ---------- Kopfzeile
        Row(
            Modifier.fillMaxWidth().background(OP.Bg).padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(Modifier.size(9.dp).clip(CircleShape).background(OP.Green))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(connected.profile.name, color = OP.Text, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, maxLines = 1)
                val via = vm.connectedVia
                val remote = via.isNotBlank() && via == connected.profile.remoteHost && via != connected.profile.host
                Text(
                    "${connected.profile.user}@$via" + if (remote) "  · Fernzugriff" else "  · LAN",
                    color = if (remote) OP.Cyan else OP.TextDim,
                    fontSize = 11.sp, fontFamily = FontFamily.Monospace, maxLines = 1
                )
            }
            IconButton(onClick = { vm.disconnect() }) { Icon(Icons.Default.LinkOff, "Trennen", tint = OP.TextDim) }
        }

        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (tab) {
                0 -> DashboardScreen(vm, onOpenRemote = { tab = 1 })
                1 -> ControlScreen(vm)
                2 -> TerminalScreen(vm)
                3 -> FilesScreen(vm)
                else -> SystemScreen(vm, onOpenTerminal = { tab = 2 })
            }
        }

        // Navigationsleiste ausblenden, solange die Tastatur offen ist (mehr Platz fürs Terminal)
        if (!imeVisible) {
            NavigationBar(containerColor = OP.Surface, windowInsets = WindowInsets(0, 0, 0, 0)) {
                TABS.forEachIndexed { i, t ->
                    NavigationBarItem(
                        selected = tab == i,
                        onClick = { tab = i },
                        icon = { Icon(t.icon, t.label) },
                        label = { Text(t.label, fontSize = 11.sp, maxLines = 1) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = OP.Green,
                            selectedTextColor = OP.Green,
                            indicatorColor = OP.Green.copy(alpha = 0.14f),
                            unselectedIconColor = OP.TextDim,
                            unselectedTextColor = OP.TextDim,
                        )
                    )
                }
            }
        }
    }
}
