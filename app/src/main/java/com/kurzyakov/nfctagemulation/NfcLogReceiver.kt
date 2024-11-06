package com.kurzyakov.nfctagemulation

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class NfcLogReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "NfcLogReceiver"
    }

    override fun onReceive(context: Context?, intent: Intent?) {
        val message = intent?.getStringExtra("log") ?: "No log message"
        Log.d(TAG, "Received NFC log: $message")
    }
}