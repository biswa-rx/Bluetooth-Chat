package com.example.chatbt.data.chat

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.IntentFilter
import android.content.pm.PackageManager
import com.example.chatbt.domain.chat.BluetoothController
import com.example.chatbt.domain.chat.BluetoothDeviceDomain
import com.example.chatbt.domain.chat.ConnectionResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.IOException
import java.util.UUID


@SuppressLint("MissingPermission")
class AndroidBluetoothController(
    private val context: Context
) : BluetoothController {
    private val bluetoothManager by lazy {
        context.getSystemService(BluetoothManager::class.java)
    }
    private val bluetoothAdapter by lazy {
        bluetoothManager?.adapter
    }



    private val _scannedDevices = MutableStateFlow<List<BluetoothDeviceDomain>>(emptyList())
    override val scannedDevices: StateFlow<List<BluetoothDeviceDomain>>
        get() = _scannedDevices.asStateFlow()



    private val _pairedDevices = MutableStateFlow<List<BluetoothDeviceDomain>>(emptyList())
    override val pairedDevices: StateFlow<List<BluetoothDeviceDomain>>
        get() = _pairedDevices.asStateFlow()


    private val _isConnected = MutableStateFlow<Boolean>(false)
    override val isConnected: StateFlow<Boolean>
        get() = _isConnected.asStateFlow()

    private val _errors = MutableSharedFlow<String>()
    override val errors: SharedFlow<String>
        get() = _errors.asSharedFlow()

    private val foundDeviceReceiver = FoundDeviceReceiver{ device->
        _scannedDevices.update { devices->
            val newDevice = device.toBluetoothDeviceDomain()
            if(newDevice in devices) devices else devices + newDevice
        }
    }

    private val bluetoothStateReceiver = BluetoothStateReceiver {isConnected, bluetoothDevice ->
        //This condition checks if the provided bluetoothDevice (the one for which the connectivity state changed) is contained within the set of bonded (paired) Bluetooth devices. If the device is paired, the code inside the curly braces is executed.
        if (bluetoothAdapter?.bondedDevices?.contains(bluetoothDevice) == true) {
            _isConnected.update { isConnected }
        } else {
            CoroutineScope(Dispatchers.IO).launch {
                _errors.tryEmit("Can't connect to a non-paired device.")
            }
        }
    }

    private var currentServerSocket: BluetoothServerSocket? = null
    private var currentClientSocket: BluetoothSocket? = null
    init {
        updatePairedDevices()
        context.registerReceiver(bluetoothStateReceiver,
            IntentFilter().apply {
                addAction(BluetoothAdapter.ACTION_CONNECTION_STATE_CHANGED)
                addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
                addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
            }
            )
    }

    override fun startDiscovery() {
        if(!hasPermission(Manifest.permission.BLUETOOTH_SCAN)) {
            return
        }
        //Resister the broadcast receiver
        context.registerReceiver(
            foundDeviceReceiver,
            IntentFilter(BluetoothDevice.ACTION_FOUND)
        )
        updatePairedDevices()
        // Question mark is for if any device not support bluetooth in that cast it will null value
        //startDiscovery method for scanning bluetooth device which is request to android and android return it as broadcast to our application
        bluetoothAdapter
            ?.startDiscovery()
    }

    override fun stopDiscovery() {
        if(!hasPermission(Manifest.permission.BLUETOOTH_SCAN)) {
            return
        }
        bluetoothAdapter
            ?.cancelDiscovery()
    }

    override fun startBluetoothServer(): Flow<ConnectionResult> {
        return flow {
            if (!hasPermission(Manifest.permission.BLUETOOTH_CONNECT)){
                throw SecurityException("No BLUETOOTH_CONNECT permission")
            }
            currentServerSocket = bluetoothAdapter?.listenUsingRfcommWithServiceRecord(
                "bluetooth-server",
                UUID.fromString(SERVICE_UUID)
            )

            var shouldLoop = true
            while (shouldLoop){
                currentClientSocket = try { //We have required client Socket for data transfer
                    currentServerSocket?.accept() // Server socket is only for accept a connection not for data send
                }catch (e: IOException) {
                    shouldLoop = false
                    null
                }
//                if(currentClientSocket!= null) {
                    emit(ConnectionResult.ConnectionEstablished)
//                }
                currentClientSocket?.let {
                    currentServerSocket?.close() // We care about connect, with we care about chatting, we don't care about server socket any more
                }
            }
        }.onCompletion {
            closeConnection()
        }.flowOn(Dispatchers.IO)
//        Initially, the loop starts with shouldLoop set to true.
//        The try block attempts to accept an incoming Bluetooth connection using currentServerSocket?.accept().
//        If an exception (usually an IOException) occurs while trying to accept a connection, the catch block is executed. Inside the catch block, shouldLoop is set to false, which will exit the loop.
//        If the loop iteration completes successfully (no exception), the loop will continue to execute, trying to accept more connections.
//        In the scenario where currentServerSocket?.close() is executed (indicating that a client socket was accepted and processed), the currentServerSocket is closed, which means no more connections can be accepted.
//        Now, the loop will continue executing, but since the server socket is closed, the accept() call will likely throw an IOException in every iteration, repeatedly entering the catch block.
//        While inside the catch block, the value of shouldLoop remains false, and the loop effectively becomes an infinite loop as there is no mechanism to change the value of shouldLoop back to true and exit the loop.
    }

    override fun connectToDevice(device: BluetoothDeviceDomain): Flow<ConnectionResult> {
        return flow {
            if (!hasPermission(Manifest.permission.BLUETOOTH_CONNECT)){
                throw SecurityException("No BLUETOOTH_CONNECT permission")
            }
            val bluetoothDevice = bluetoothAdapter?.getRemoteDevice(device.address) //convert BluetoothDeviceDomain to android.BluetoothDevice

            currentClientSocket = bluetoothDevice?.createRfcommSocketToServiceRecord(UUID.fromString(
                SERVICE_UUID)) //In this way we establish a connection to our server

            stopDiscovery()


            currentClientSocket?.let { bluetoothSocket ->
                try {
                    bluetoothSocket.connect()
                    emit(ConnectionResult.ConnectionEstablished)
                }catch (e: IOException) {
                    bluetoothSocket.close()
                    currentClientSocket = null
                    emit(ConnectionResult.Error("Connection was interrupted"))
                }
            }
        }.onCompletion {
            closeConnection()
        }.flowOn(Dispatchers.IO)
    }

    override fun closeConnection() {
        currentClientSocket?.close()
        currentServerSocket?.close()
        currentClientSocket = null
        currentServerSocket = null
    }

    override fun release() {
        //Unregister broadcast receiver
        context.unregisterReceiver(foundDeviceReceiver)
        context.unregisterReceiver(bluetoothStateReceiver)
        closeConnection()
    }


    private fun updatePairedDevices() {
        if(!hasPermission(Manifest.permission.BLUETOOTH_CONNECT)){
            return
        }
        bluetoothAdapter
            ?.bondedDevices
            ?.map { it.toBluetoothDeviceDomain() }
            ?.also { devices->
                _pairedDevices.update { devices }
            }
    }

    private fun hasPermission(permission: String): Boolean {
        return context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
    }

    companion object {
        const val SERVICE_UUID = "27b7d1da-08c7-4505-a6d1-2459987e5e2d"
    }
}