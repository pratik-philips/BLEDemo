package com.example.connecitivtydemo

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.ui.graphics.Color
import androidx.core.app.ActivityCompat
import androidx.lifecycle.MutableLiveData
import java.util.UUID
import java.util.logging.Logger

@SuppressLint("MissingPermission")
class MainActivity : AppCompatActivity() {

    private lateinit var bluetoothEnableLauncher: ActivityResultLauncher<Intent>
    private lateinit var nearPermissionLauncher: ActivityResultLauncher<Array<String>>


    @SuppressLint("MutableCollectionMutableState")
    private var _logsLiveData: MutableLiveData<ArrayList<String>> = MutableLiveData(arrayListOf())

    private lateinit var bluetoothManager: BluetoothManager
    private lateinit var bluetoothAdapter: BluetoothAdapter
    private lateinit var bluetoothLeScanner: BluetoothLeScanner

    private val mainHandler: Handler = Handler(Looper.getMainLooper())
    private lateinit var internalHandler: Handler
    private lateinit var internalHandlerThread: HandlerThread

    private val scannedBluetoothDevices: ArrayList<Pair<BluetoothDevice, Int>> = arrayListOf()
    private var scannerEnable = true
    private var isConnected = false
    private var bluetoothGatt: BluetoothGatt? = null
    private var isPairingRequired = false

    companion object {
        private val MANIFEST_NEARBY_PERMISSION =
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        private val DEVICE_INFO_SERVICE_UUID =
            UUID.fromString("0000180A-0000-1000-8000-00805f9b34fb")
        private val MODEL_NUM_CHAR_UUID = UUID.fromString("00002A24-0000-1000-8000-00805f9b34fb")
    }

    private val bondStateReceiver = object : BroadcastReceiver() {
        @RequiresApi(Build.VERSION_CODES.TIRAMISU)
        override fun onReceive(context: Context?, intent: Intent?) {
            val action = intent?.action
            val state = intent?.extras?.getInt(BluetoothDevice.EXTRA_BOND_STATE)

            Log.i("DEMO", "onReceive state: $state")
            log("bond state changed bonded? = $state")
            if (BluetoothDevice.ACTION_BOND_STATE_CHANGED == action && !isConnected) {
                val bluetoothDevice: BluetoothDevice? = intent.getParcelableExtra(
                    BluetoothDevice.EXTRA_DEVICE,
                    BluetoothDevice::class.java
                )
                if (state == BluetoothDevice.BOND_BONDED || (state == BluetoothDevice.BOND_NONE && !isPairingRequired)) {
                    connect(bluetoothDevice!!, false)
                }
            }
        }
    }

    private val pairingRequestBroadcastReceiver: BroadcastReceiver = object : BroadcastReceiver() {
        // Redefining the hidden constant BluetoothDevice.PAIRING_VARIANT_CONSENT
        private val PAIRING_VARIANT_CONSENT = 3

        @RequiresApi(Build.VERSION_CODES.TIRAMISU)
        override fun onReceive(context: Context, intent: Intent) {
//            val device : BluetoothDevice?=
//                intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)

            val pairingVariant = intent.getIntExtra(
                BluetoothDevice.EXTRA_PAIRING_VARIANT,
                BluetoothDevice.ERROR
            )
            isPairingRequired = true
            log(
                "paring variant=${
                    when (pairingVariant) {
                        BluetoothDevice.PAIRING_VARIANT_PIN -> "PIN"
                        BluetoothDevice.PAIRING_VARIANT_PASSKEY_CONFIRMATION -> "PASSKEY"
                        3 -> "CONSENT"
                        else -> "other=$pairingVariant"
                    }
                }"
            )
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val logs by _logsLiveData.observeAsState()
            PrintLogs(logs)
        }
        initBT()
        registerReceiver(
            pairingRequestBroadcastReceiver,
            IntentFilter(BluetoothDevice.ACTION_PAIRING_REQUEST)
        )
        registerReceiver(bondStateReceiver, IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED))
    }

    override fun onDestroy() {
        super.onDestroy()
        internalHandlerThread.quitSafely()
        unregisterReceiver(bondStateReceiver)
        unregisterReceiver(pairingRequestBroadcastReceiver)
    }

    private fun enableBluetooth() {
        log("enabling BT")
        val intent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
        bluetoothEnableLauncher.launch(intent)
    }

    private fun checkSelfNearByPermission(): Boolean {
        return ActivityCompat.checkSelfPermission(
            this,
            MANIFEST_NEARBY_PERMISSION[0]
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestPermission() {
        nearPermissionLauncher.launch(MANIFEST_NEARBY_PERMISSION)
    }

    private fun log(log: String) {
        Log.i("DEMO", log)
        val newList = ArrayList<String>().apply {
            _logsLiveData.value?.let { addAll(it) }
            add(log)
        }
        _logsLiveData.postValue(newList)
    }

    @Composable
    fun PrintLogs(logs: List<String>?) {
        if (logs == null) return
        LazyColumn {
            items(logs) {
                Text(text = it, color = Color.White)
            }
        }
    }

    private fun initBT() {
        log("BT param init..")
        internalHandlerThread = HandlerThread("BLEThread")
        internalHandlerThread.start()
        internalHandler = Handler(internalHandlerThread.looper)
        bluetoothEnableLauncher =
            registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
                preScanReq()
            }
        nearPermissionLauncher =
            registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
                if (checkSelfNearByPermission()) {
                    preScanReq()
                }
            }
        bluetoothManager = getSystemService(BluetoothManager::class.java)
        bluetoothAdapter = bluetoothManager.adapter
        bluetoothLeScanner = bluetoothAdapter.bluetoothLeScanner
        preScanReq()
    }

    private val scanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            super.onScanResult(callbackType, result)
            Log.i("DEMO", "on scan result")
            log("scan result: ${result?.device?.name}-${result?.device?.address}")
            result?.device?.let {
                scannedBluetoothDevices.add(Pair(it, result.rssi))
            }
            if (scannerEnable) {
                stopScanAndConnect()
            }
        }


        @SuppressLint("MissingPermission")
        override fun onBatchScanResults(results: List<ScanResult>) {
            Log.i("DEMO", "onBatchScanResults")
            for (result in results) {
                result.let {
                    log("scan result: ${it.device?.name}-${it.device?.address}")
                }
            }
        }

        override fun onScanFailed(errorCode: Int) {
            super.onScanFailed(errorCode)
            log("scan failed $errorCode")

        }
    }

    private fun preScanReq() {
        if (!bluetoothAdapter.isEnabled) {
            log("Turn on BT and start over")
            enableBluetooth()
        } else if (!checkSelfNearByPermission()) {
            requestPermission()
        } else {
            startScan()
        }
    }

    @SuppressLint("MissingPermission")
    private fun startScan() {
        log("Scan started...")
        scannerEnable = true
        internalHandler.post {
            val scanFilter = ScanFilter.Builder().setDeviceName("Philips Sonicare").build()
            val scanSettings = ScanSettings.Builder()
                .setMatchMode(ScanSettings.MATCH_MODE_AGGRESSIVE)
                .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
                .setReportDelay(0L)
                .build()
            bluetoothLeScanner.startScan(listOf(scanFilter), scanSettings, scanCallback)
//            bluetoothLeScanner.startScan(null, scanSettings, scanCallback)
        }
    }

    @SuppressLint("MissingPermission")
    private fun stopScanAndConnect() {
        scannerEnable = false
        internalHandler.postDelayed({
            bluetoothLeScanner.stopScan(scanCallback)
            val nearestDevice =
                getMaxRSSIDeviceFromScannedList(scannedDevices = scannedBluetoothDevices)
            nearestDevice?.let {
                connect(nearestDevice)
            }
        }, 1000)
    }

    private fun connect(device: BluetoothDevice, requiredBond: Boolean = true) {
        log("connect to ${device.address}. ${device.uuids}")
        internalHandler.post {
            if (device.bondState == BluetoothDevice.BOND_BONDED || !requiredBond) {
                bluetoothGatt = device.connectGatt(this, false, object : BluetoothGattCallback() {
                    override fun onConnectionStateChange(
                        gatt: BluetoothGatt?,
                        status: Int,
                        newState: Int
                    ) {
                        super.onConnectionStateChange(gatt, status, newState)
                        log("connection state change: $newState")
                        when (newState) {
                            BluetoothProfile.STATE_CONNECTING -> {
                                log("connecting to ${device.address}...")
                            }

                            BluetoothProfile.STATE_CONNECTED -> {
                                log("connection success with ${device.address}! bond state: ${device.bondState}")
                                if (device.bondState != BluetoothDevice.BOND_BONDING) {
                                    onConnection(gatt)
                                }
                            }

                            BluetoothProfile.STATE_DISCONNECTING -> {
                                log("disconnecting to ${device.address}...")
                            }

                            BluetoothProfile.STATE_DISCONNECTED -> {
                                isConnected = false
                                log("device disconnected = ${device.address}...")
                                scannerEnable = true
                                preScanReq()
                            }
                        }
                    }

                    override fun onServicesDiscovered(gatt: BluetoothGatt?, status: Int) {
                        super.onServicesDiscovered(gatt, status)
                        log("services discovered")
                        gatt?.let {
                            readDeviceInformation(it)
                        } ?: log("GATT is null")
                    }

                    override fun onCharacteristicRead(
                        gatt: BluetoothGatt,
                        characteristic: BluetoothGattCharacteristic,
                        value: ByteArray,
                        status: Int
                    ) {
                        super.onCharacteristicRead(gatt, characteristic, value, status)
                        if (characteristic.uuid == MODEL_NUM_CHAR_UUID) {
                            log("model number read = " + String(value))
                        }
                    }
                })
            } else {
                isPairingRequired = false
                val bonding = device.createBond()
                log("creating bond...$bonding")
            }
        }
    }

    private fun onConnection(gatt: BluetoothGatt?) {
        isConnected = true
        gatt?.let {
            discoverServices(it)
        } ?: log("Gatt is null")
    }

    private fun discoverServices(gatt: BluetoothGatt) {
        log("discovering services....")
        gatt.discoverServices()
    }

    private fun readDeviceInformation(gatt: BluetoothGatt) {
        val dis = gatt.getService(DEVICE_INFO_SERVICE_UUID)
        val modelNoChar = dis.getCharacteristic(MODEL_NUM_CHAR_UUID)
        gatt.readCharacteristic(modelNoChar)
    }

    private fun getMaxRSSIDeviceFromScannedList(scannedDevices: List<Pair<BluetoothDevice, Int>>): BluetoothDevice? {
        if (scannedDevices.isEmpty()) return null
        log("getMaxRSSIDeviceFromScannedList")
        var maxRssiDevice: Pair<BluetoothDevice, Int> = scannedDevices[0]

        for (i in 1 until scannedDevices.size) {
            val device: Pair<BluetoothDevice, Int> = scannedDevices[i]
            val r: Int = device.second
            if (r > maxRssiDevice.second) {
                maxRssiDevice = device
            }
        }
        log("getMaxRSSIDeviceFromScannedList: nearest=${maxRssiDevice.first.name}-${maxRssiDevice.first.address}")
        return maxRssiDevice.first
    }

}