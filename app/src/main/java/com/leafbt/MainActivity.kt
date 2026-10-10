package com.leafbt

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions
import androidx.activity.viewModels
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.leafbt.transport.BtSppTransport
import com.leafbt.ui.BatteryViewModel
import com.leafbt.ui.MainScreen
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {

    private val vm: BatteryViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme { Surface { App(vm) } }
        }
    }

    @Composable
    private fun App(vm: BatteryViewModel) {
        val state by vm.state.collectAsState()
        val dataLogOn by vm.dataLogOn.collectAsState()
        val context = LocalContext.current
        var showPicker by remember { mutableStateOf(false) }

        // keep the screen on while connected (the test is read while driving)
        LaunchedEffect(state.connected) {
            window?.let { w ->
                if (state.connected) w.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                else w.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }

        val adapter: BluetoothAdapter? = remember { context.getSystemService(BluetoothManager::class.java)?.adapter }

        // auto-reconnect to the last used dongle every 10 s until connected
        val lastDevice by vm.lastDevice.collectAsState()
        LaunchedEffect(lastDevice) {
            val addr = lastDevice ?: return@LaunchedEffect
            if (adapter == null) return@LaunchedEffect
            while (true) {
                val s = vm.state.value
                if (vm.autoReconnect && !s.connected && !s.connecting && hasBtPermission()) {
                    runCatching { adapter.getRemoteDevice(addr) }.getOrNull()?.let { dev ->
                        vm.connect(BtSppTransport(dev, adapter))
                    }
                }
                delay(10_000)
            }
        }

        val permLauncher = rememberLauncherForActivityResult(RequestMultiplePermissions()) { result ->
            if (result[Manifest.permission.BLUETOOTH_CONNECT] == true) showPicker = true
        }
        fun onConnect() {
            if (hasBtPermission()) showPicker = true
            else permLauncher.launch(
                buildList {
                    add(Manifest.permission.BLUETOOTH_CONNECT)
                    add(Manifest.permission.BLUETOOTH_SCAN)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
                }.toTypedArray(),
            )
        }

        MainScreen(
            state = state,
            dataLogOn = dataLogOn,
            onConnect = ::onConnect,
            onDisconnect = { vm.disconnect() },
            onToggleLog = { vm.setDataLog(!dataLogOn) },
            onSendLog = { sendDataLog() },
        )

        if (showPicker && adapter != null) {
            DevicePicker(
                devices = bondedDevices(adapter),
                onDismiss = { showPicker = false },
                onPick = { dev ->
                    showPicker = false
                    vm.rememberDevice(dev.address)
                    vm.connect(BtSppTransport(dev, adapter))
                },
            )
        }
    }

    @Composable
    private fun DevicePicker(devices: List<BluetoothDevice>, onDismiss: () -> Unit, onPick: (BluetoothDevice) -> Unit) {
        AlertDialog(
            onDismissRequest = onDismiss,
            confirmButton = {},
            dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
            title = { Text("Paired devices") },
            text = {
                Column {
                    if (devices.isEmpty()) Text("No paired devices. Pair the ELM327 in Android Bluetooth settings first.")
                    devices.forEach { dev ->
                        Text(
                            deviceLabel(dev),
                            modifier = Modifier.fillMaxWidth().clickable { onPick(dev) }.padding(vertical = 12.dp),
                        )
                    }
                }
            },
        )
    }

    /** Share the running/latest data log file: Telegram if installed, else the share sheet. */
    private fun sendDataLog() {
        val file = vm.latestDataLogFile()
        if (file == null || !file.exists()) {
            Toast.makeText(this, "No data log yet", Toast.LENGTH_SHORT).show()
            return
        }
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/csv"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, file.name)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val telegram = listOf("org.telegram.messenger", "org.telegram.messenger.web", "org.thunderdog.challegram")
            .firstOrNull { pkg -> runCatching { packageManager.getPackageInfo(pkg, 0) }.isSuccess }
        if (telegram != null) send.setPackage(telegram)
        runCatching { startActivity(Intent.createChooser(send, "Send data log")) }
            .onFailure { Toast.makeText(this, "No app to send to", Toast.LENGTH_SHORT).show() }
    }

    private fun hasBtPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    @Suppress("MissingPermission") // gated by hasBtPermission before the picker opens
    private fun bondedDevices(adapter: BluetoothAdapter): List<BluetoothDevice> =
        runCatching { adapter.bondedDevices?.toList() ?: emptyList() }.getOrDefault(emptyList())

    @Suppress("MissingPermission")
    private fun deviceLabel(dev: BluetoothDevice): String =
        runCatching { dev.name ?: dev.address }.getOrDefault(dev.address)
}
