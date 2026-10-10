package com.leafbt.ui

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.leafbt.LeafService
import com.leafbt.log.DataLogStore
import com.leafbt.log.LeafSpyLog
import com.leafbt.poll.BatteryPoller
import com.leafbt.poll.BatteryState
import com.leafbt.transport.Transport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Owns the dongle session and the LeafSpy-format data log. */
class BatteryViewModel(app: Application) : AndroidViewModel(app) {

    private val prefs = app.getSharedPreferences("leafbt", Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(BatteryState())
    val state: StateFlow<BatteryState> = _state.asStateFlow()

    private val _lastDevice = MutableStateFlow(prefs.getString("last_device", null))
    val lastDevice: StateFlow<String?> = _lastDevice.asStateFlow()

    private val _dataLogOn = MutableStateFlow(prefs.getBoolean("data_log", false))
    val dataLogOn: StateFlow<Boolean> = _dataLogOn.asStateFlow()

    /** Whether to auto-reconnect (disabled by a manual disconnect). */
    @Volatile var autoReconnect = true
        private set

    private val logStore = DataLogStore(app.getExternalFilesDir(null) ?: app.filesDir)
    private val logIo = Dispatchers.IO.limitedParallelism(1)
    private var poller: BatteryPoller? = null
    private var sessionJob: Job? = null
    private var collectJob: Job? = null
    private var lastLeaf = com.leafbt.can.LeafState()   // kept on screen across reconnects

    fun rememberDevice(address: String) {
        _lastDevice.value = address
        prefs.edit().putString("last_device", address).apply()
    }

    fun connect(transport: Transport) {
        if (sessionJob?.isActive == true) return
        autoReconnect = true
        runCatching { LeafService.start(getApplication()) }
        sessionJob = viewModelScope.launch {
            val p = BatteryPoller(transport)
            poller = p
            collectJob?.cancel()
            collectJob = viewModelScope.launch {
                p.state.collect { ps ->
                    // identity read once per session: carry the last known values
                    // through the empty state of a reconnect
                    val leaf = if (ps.leaf == com.leafbt.can.LeafState()) lastLeaf else ps.leaf
                    lastLeaf = leaf
                    _state.value = ps.copy(leaf = leaf)
                    if (ps.connected) logRow(ps)
                }
            }
            withContext(Dispatchers.IO) { p.runBlocking() }
        }
    }

    fun disconnect() {
        autoReconnect = false
        runCatching { LeafService.stop(getApplication()) }
        poller?.stop()
        collectJob?.cancel()
        _state.value = _state.value.copy(connected = false, connecting = false)
    }

    private fun logRow(ps: BatteryState) {
        if (!_dataLogOn.value) return
        val now = System.currentTimeMillis()
        val bm = getApplication<Application>().getSystemService(android.os.BatteryManager::class.java)
        val phone = bm?.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)?.takeIf { it in 0..100 }
        val row = LeafSpyLog.row(now, ps.leaf, ps.leaf.meterOdoKm?.toDouble(), phone)
        val vin = ps.leaf.vin
        viewModelScope.launch(logIo) { runCatching { logStore.append(now, vin, row) } }
    }

    /** Start/stop the data log: each start begins a new file (with the first row). */
    fun setDataLog(on: Boolean) {
        _dataLogOn.value = on
        if (!on) logStore.stop()
        prefs.edit().putBoolean("data_log", on).apply()
    }

    fun latestDataLogFile(): java.io.File? = logStore.latest()

    override fun onCleared() {
        disconnect()
        super.onCleared()
    }
}
