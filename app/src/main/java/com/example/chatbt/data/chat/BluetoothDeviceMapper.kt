package com.example.chatbt.data.chat

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import com.example.chatbt.domain.chat.BluetoothDeviceDomain

@SuppressLint("MissingPermission")
fun BluetoothDevice.toBluetoothDeviceDomain(): BluetoothDeviceDomain {
    return BluetoothDeviceDomain(
        name = name,
        address = address

    )
}
