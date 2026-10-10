package com.leafbt.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.leafbt.BuildConfig
import com.leafbt.poll.BatteryState
import com.leafbt.power.PowerTest
import kotlinx.coroutines.delay

/**
 * The whole app on one screen: connection, car identity, live pack readings,
 * the guided step test and the data log buttons.
 */
@Composable
fun MainScreen(
    state: BatteryState,
    dataLogOn: Boolean,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onToggleLog: () -> Unit,
    onSendLog: () -> Unit,
) {
    val test = remember { PowerTest() }
    var rev by remember { mutableIntStateOf(0) }     // bump to recompose after mutations
    LaunchedEffect(state.cycle) {
        val a = state.leaf.packAmps
        val v = state.leaf.packVolts
        if (state.connected && a != null && v != null) { test.onSample(a, v, state.leaf.cellsMv); rev++ }
    }
    LaunchedEffect(Unit) {
        while (true) { delay(1000); test.onTick(); rev++ }
    }
    @Suppress("UNUSED_EXPRESSION") rev

    val leaf = state.leaf
    val danger = Color(0xFFC62828)
    val status = when {
        state.connecting -> state.connectMsg ?: "Connecting..."
        state.connected -> "Connected"
        state.error != null -> state.error
        else -> "Disconnected"
    }
    val statusColor = when {
        state.connecting -> Color(0xFFB58900)
        state.connected -> Color(0xFF2E7D32)
        else -> danger
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                "$status  v${BuildConfig.VERSION_NAME}",
                style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
                color = statusColor, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
            )
            if (state.connecting) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            if (state.connected || state.connecting) TextButton(onClick = onDisconnect) { Text("Disconnect") }
            else Button(onClick = onConnect) { Text("Connect") }
        }

        // car identity
        Info("VIN", leaf.vin ?: "--", Modifier.fillMaxWidth())
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Info("SOH", leaf.sohPercent?.let { "%.2f%%".format(it) } ?: "--", Modifier.weight(1f))
            Info("Hx", leaf.hx?.let { "%.2f%%".format(it) } ?: "--", Modifier.weight(1f))
            Info("Odo km", leaf.meterOdoKm?.toString() ?: "--", Modifier.weight(1f))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Info(
                "Bat temp",
                if (leaf.batteryTempsC.isEmpty()) "--" else leaf.batteryTempsC.joinToString(" / ") { "%.0f".format(it) } + " C",
                Modifier.weight(1.5f),
            )
            Info("Ext temp", leaf.ambientTempC?.let { "%.0f C".format(it) } ?: "--", Modifier.weight(1f))
        }

        // live readings, two per row, big enough to read while driving
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Live("Pack A", leaf.packAmps?.let { "%.0f".format(it) } ?: "--", Modifier.weight(1f))
            Live("Pack V", leaf.packVolts?.let { "%.1f".format(it) } ?: "--", Modifier.weight(1f))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Live("km/h", leaf.speedKmh?.let { "%.0f".format(it) } ?: "--", Modifier.weight(1f))
            Live("Cell min V", leaf.cellMinV?.let { "%.3f".format(it) } ?: "--", Modifier.weight(1f))
        }

        val big = test.phase == PowerTest.Phase.COUNTDOWN || test.phase == PowerTest.Phase.FLOOR ||
            test.phase == PowerTest.Phase.RELEASE
        Column(
            Modifier.fillMaxWidth().padding(vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                test.message,
                fontSize = if (big) 72.sp else 22.sp,
                lineHeight = if (big) 80.sp else 30.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                color = when {
                    test.phase == PowerTest.Phase.FLOOR -> danger
                    test.phase == PowerTest.Phase.RELEASE -> Color(0xFF2E7D32)
                    test.phase == PowerTest.Phase.RESULT && !test.ok -> danger
                    else -> Color.Unspecified
                },
            )
        }
        Button(onClick = { test.restart(); rev++ }, modifier = Modifier.fillMaxWidth()) { Text("Restart test") }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onToggleLog, modifier = Modifier.weight(1f)) {
                Text(
                    if (dataLogOn) "■ Stop log" else "● Start log",
                    color = if (dataLogOn) danger else MaterialTheme.colorScheme.primary,
                )
            }
            OutlinedButton(onClick = onSendLog, modifier = Modifier.weight(1f)) { Text("Send log") }
        }
    }
}

@Composable
private fun Info(label: String, value: String, modifier: Modifier = Modifier) {
    Card(modifier) {
        Column(Modifier.padding(8.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium)
            Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, maxLines = 1)
        }
    }
}

@Composable
private fun Live(label: String, value: String, modifier: Modifier = Modifier) {
    Card(modifier) {
        Column(Modifier.fillMaxWidth().padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(label, style = MaterialTheme.typography.titleMedium)
            Text(value, fontSize = 44.sp, lineHeight = 50.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        }
    }
}
