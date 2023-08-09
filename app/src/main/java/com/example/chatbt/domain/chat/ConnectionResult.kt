package com.example.chatbt.domain.chat

sealed interface ConnectionResult {
    object ConnectionEstablished: ConnectionResult
    data class Error(val message:String): ConnectionResult
}