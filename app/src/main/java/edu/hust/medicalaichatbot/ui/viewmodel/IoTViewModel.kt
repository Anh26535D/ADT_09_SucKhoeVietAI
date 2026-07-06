package edu.hust.medicalaichatbot.ui.viewmodel

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.database.*
import edu.hust.medicalaichatbot.data.service.BleIoTService
import edu.hust.medicalaichatbot.domain.model.IoTData
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class IoTViewModel(application: Application) : AndroidViewModel(application) {
    private val bleService = BleIoTService(application)
    private val _firebaseData = MutableStateFlow(IoTData())
    val iotData: StateFlow<IoTData> = _firebaseData.asStateFlow()

    private val _historyData = MutableStateFlow<List<IoTData>>(emptyList())
    val historyData: StateFlow<List<IoTData>> = _historyData.asStateFlow()

    val bleConnectionState = bleService.connectionState
    val connectedDeviceAddress = bleService.discoveredAddress
    val provisioningStatus = bleService.provisioningStatus

    private val database = FirebaseDatabase.getInstance("https://caromaster-default-rtdb.asia-southeast1.firebasedatabase.app")
    private val prefs = application.getSharedPreferences("iot_prefs", Context.MODE_PRIVATE)

    // NEW: Expose the active ID to the UI
    private val _currentDeviceId = MutableStateFlow(prefs.getString("last_device_id", "1020BA49D1C8") ?: "1020BA49D1C8")
    val currentDeviceId: StateFlow<String> = _currentDeviceId.asStateFlow()

    private var activeDeviceRef: DatabaseReference? = null
    private var activeHistoryQuery: Query? = null

    private val _currentSsid = MutableStateFlow("")
    val currentSsid: StateFlow<String> = _currentSsid.asStateFlow()

    private val TAG = "IoTViewModel"

    private val valueEventListener = object : ValueEventListener {
        override fun onDataChange(snapshot: DataSnapshot) {
            val data = parseIoTData(snapshot) ?: return
            if (data.timestamp <= _firebaseData.value.timestamp) return

            val currentHistory = _firebaseData.value.heartRateHistory.toMutableList()
            if (data.heartRate > 0) {
                currentHistory.add(data.heartRate)
                if (currentHistory.size > 20) currentHistory.removeAt(0)
            }
            _firebaseData.value = data.copy(heartRateHistory = currentHistory)
        }
        override fun onCancelled(error: DatabaseError) {
            Log.e(TAG, "Firebase error: ${error.message}")
        }
    }

    private val historyEventListener = object : ValueEventListener {
        override fun onDataChange(snapshot: DataSnapshot) {
            val historyList = snapshot.children.mapNotNull { parseIoTData(it) }
            _historyData.value = historyList.sortedByDescending { it.timestamp }.take(50)
        }
        override fun onCancelled(error: DatabaseError) {}
    }

    init {
        try {
            database.setPersistenceEnabled(true)
        } catch (e: Exception) {
            Log.w(TAG, "Persistence setting: ${e.message}")
        }

        _currentSsid.value = bleService.getCurrentSsid() ?: ""
        setupFirebaseListeners()

        database.getReference(".info/connected").addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val connected = snapshot.getValue(Boolean::class.java) ?: false
                Log.d(TAG, "Firebase connection status: ${if (connected) "Connected" else "Disconnected"}")
                if (connected) activeDeviceRef?.keepSynced(true)
            }
            override fun onCancelled(error: DatabaseError) {}
        })

        viewModelScope.launch {
            bleService.discoveredAddress
                .filterNotNull()
                .distinctUntilChanged()
                .collect { newAddress ->
                    Log.i(TAG, "New device discovered: $newAddress. Updating listeners...")
                    prefs.edit().putString("last_device_id", newAddress).apply()
                    _currentDeviceId.value = newAddress // Update the state
                    setupFirebaseListeners()
                }
        }
    }

    private fun setupFirebaseListeners() {
        activeDeviceRef?.removeEventListener(valueEventListener)
        activeHistoryQuery?.removeEventListener(historyEventListener)

        val deviceId = _currentDeviceId.value
        val newDeviceRef = database.getReference("devices/$deviceId/latest")
        val newHistoryQuery = database.getReference("devices/$deviceId/history").limitToLast(50)

        newDeviceRef.keepSynced(true)
        newDeviceRef.addValueEventListener(valueEventListener)
        newHistoryQuery.addValueEventListener(historyEventListener)

        activeDeviceRef = newDeviceRef
        activeHistoryQuery = newHistoryQuery
        
        Log.i(TAG, "Auto-sync active for path: devices/$deviceId")
    }

    private fun parseIoTData(snapshot: DataSnapshot): IoTData? {
        val timestamp = snapshot.child("timestamp").getValue(Long::class.java) ?: 0L
        if (timestamp == 0L) return null
        return IoTData(
            heartRate = snapshot.child("heartRate").getValue(Int::class.java) ?: 0,
            spo2 = snapshot.child("spo2").getValue(Int::class.java) ?: 0,
            temperature = snapshot.child("temperature").getValue(Double::class.java) ?: 0.0,
            humidity = snapshot.child("humidity").getValue(Double::class.java) ?: 0.0,
            hasFinger = snapshot.child("hasFinger").getValue(Boolean::class.java) ?: false,
            status = snapshot.child("status").getValue(String::class.java) ?: "IDLE",
            timestamp = timestamp
        )
    }

    fun connectBle() = bleService.startScanning()
    fun disconnectBle() = bleService.disconnect()
    fun sendWifiCredentials(s: String, p: String) = bleService.sendWifiCredentials(s, p)

    fun refreshFirebaseData() {
        activeDeviceRef?.addListenerForSingleValueEvent(valueEventListener)
    }

    override fun onCleared() {
        super.onCleared()
        activeDeviceRef?.removeEventListener(valueEventListener)
        activeHistoryQuery?.removeEventListener(historyEventListener)
        bleService.disconnect()
    }
}
