package dev.leyline.app

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.leyline.app.mesh.MeshService
import dev.leyline.app.ui.LeylineMark
import dev.leyline.app.ui.LeylineTheme
import dev.leyline.app.ui.MeshColors
import dev.leyline.app.ui.MeshScreen
import dev.leyline.app.ui.MeshViewModel
import dev.leyline.app.ui.MessagesScreen
import dev.leyline.app.ui.MessagesViewModel
import dev.leyline.app.ui.RescueScreen
import dev.leyline.app.ui.liveSos
import dev.leyline.app.ui.Pill
import dev.leyline.app.ui.SettingsScreen
import dev.leyline.app.ui.SettingsViewModel
import dev.leyline.ble.BlePermissions

class MainActivity : ComponentActivity() {
    private val container get() = (application as LeylineApp).container
    private var openSos by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        openSos = intent?.getBooleanExtra(EXTRA_SOS, false) == true
        setContent {
            LeylineTheme { Root() }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.getBooleanExtra(EXTRA_SOS, false)) openSos = true
    }

    override fun onStart() {
        super.onStart()
        container.mesh.setUiVisible(true)
    }

    override fun onStop() {
        container.mesh.setUiVisible(false)
        super.onStop()
    }

    private enum class Tab(val label: String, val icon: ImageVector) {
        MESSAGES("Messages", Icons.Filled.Email),
        RESCUE("Rescue", Icons.Filled.Warning),
        MESH("Mesh", Icons.Filled.Share),
        SETTINGS("Settings", Icons.Filled.Settings),
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun Root() {
        var granted by rememberSaveable { mutableStateOf(hasBlePermissions()) }
        var tab by rememberSaveable { mutableIntStateOf(0) }
        val radio by container.mesh.radio.collectAsStateWithLifecycle()
        val carryingSos by container.mesh.carryingSos.collectAsStateWithLifecycle()

        val requestPermissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            granted = hasBlePermissions()
        }
        val enableBluetooth = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {}

        LaunchedEffect(granted) {
            if (granted) MeshService.start(this@MainActivity)
        }
        // Permissions can change outside the app (system settings, a grant that raced the
        // dialog), so check again every time the screen comes back.
        LifecycleResumeEffect(Unit) {
            granted = hasBlePermissions()
            onPauseOrDispose {}
        }

        if (!granted) {
            Onboarding { requestPermissions.launch(permissionsToRequest()) }
            return
        }

        // Shared by Messages and Rescue: one view of the same SOS, responses and drafts.
        val messages = viewModel { MessagesViewModel(container) }
        val messagesState by messages.state.collectAsStateWithLifecycle()
        val openSosCount = liveSos(messagesState).count { !it.found && !it.message.outgoing }

        Scaffold(
            topBar = {
                CenterAlignedTopAppBar(
                    title = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            LeylineMark(24.dp)
                            Spacer(Modifier.width(8.dp))
                            Text("Leyline", style = MaterialTheme.typography.titleLarge)
                        }
                    },
                    actions = { if (carryingSos) Pill("SOS LIVE", MeshColors.sos, filled = true, modifier = Modifier.padding(end = 12.dp)) },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                )
            },
            bottomBar = {
                NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
                    Tab.entries.forEachIndexed { index, item ->
                        NavigationBarItem(
                            selected = tab == index,
                            onClick = { tab = index },
                            icon = {
                                if (item == Tab.RESCUE && openSosCount > 0) {
                                    BadgedBox(badge = { Badge(containerColor = MeshColors.sos) { Text("$openSosCount") } }) {
                                        Icon(item.icon, contentDescription = null)
                                    }
                                } else {
                                    Icon(item.icon, contentDescription = null)
                                }
                            },
                            label = { Text(item.label) },
                        )
                    }
                }
            },
        ) { padding ->
            // consumeWindowInsets stops the messages screen's imePadding from adding the keyboard on top
            // of the bottom bar space the Scaffold already reserved (the gap above the keyboard).
            Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
                if (!radio.bluetoothOn && !bluetoothEnabled()) {
                    Warning("Bluetooth is off. The mesh needs it, even in airplane mode.", "Turn on") {
                        enableBluetooth.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
                    }
                } else if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S && !locationServicesOn()) {
                    // Android 11 and below return no BLE scan results with location services off.
                    Warning("Android ${Build.VERSION.RELEASE} needs Location on to find nearby phones.", null) {}
                }
                when (Tab.entries[tab]) {
                    Tab.MESSAGES -> MessagesScreen(
                        messages,
                        openSos = openSos,
                        onSosHandled = { openSos = false },
                    )
                    Tab.RESCUE -> RescueScreen(messages)
                    Tab.MESH -> MeshScreen(viewModel { MeshViewModel(container) })
                    Tab.SETTINGS -> SettingsScreen(
                        viewModel { SettingsViewModel(container) },
                        onStartMesh = { MeshService.start(this@MainActivity) },
                        onStopMesh = {
                            startService(Intent(this@MainActivity, MeshService::class.java).setAction(MeshService.ACTION_STOP))
                        },
                    )
                }
            }
        }
    }

    @Composable
    private fun Onboarding(onAllow: () -> Unit) {
        Column(
            Modifier.fillMaxSize().padding(28.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.Start,
        ) {
            LeylineMark(72.dp)
            Spacer(Modifier.height(20.dp))
            Text("Help that travels\nphone to phone", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onBackground)
            Spacer(Modifier.height(12.dp))
            Text(
                "When the network is down, Leyline passes messages between nearby phones over Bluetooth. No internet, no SIM.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(24.dp))
            Point("Nearby devices", "to find and link with other Leyline phones")
            Point("Location", "only to attach your GPS position to an SOS you send")
            Point("Notifications", "so an incoming SOS can reach you")
            Spacer(Modifier.height(28.dp))
            Button(onClick = onAllow, modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(26.dp)) {
                Text("Allow and join the mesh")
            }
        }
    }

    @Composable
    private fun Point(title: String, detail: String) {
        Row(Modifier.padding(vertical = 6.dp), verticalAlignment = Alignment.Top) {
            Text("●", color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(end = 10.dp))
            Column {
                Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onBackground)
                Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }

    @Composable
    private fun Warning(text: String, action: String?, onAction: () -> Unit) {
        Card(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
            colors = CardDefaults.cardColors(containerColor = MeshColors.warning.copy(alpha = 0.15f)),
            shape = RoundedCornerShape(16.dp),
        ) {
            Row(Modifier.padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                if (action != null) TextButton(onClick = onAction) { Text(action) }
            }
        }
    }

    private fun hasBlePermissions() =
        BlePermissions.required().all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    private fun permissionsToRequest(): Array<String> {
        val extra = buildList {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
        }
        return (BlePermissions.required().toList() + extra).distinct().toTypedArray()
    }

    private fun bluetoothEnabled() = getSystemService(BluetoothManager::class.java)?.adapter?.isEnabled == true

    private fun locationServicesOn(): Boolean {
        val manager = getSystemService(LocationManager::class.java) ?: return false
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            manager.isLocationEnabled
        } else {
            manager.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        }
    }

    companion object {
        const val EXTRA_SOS = "dev.leyline.SOS"
    }
}
