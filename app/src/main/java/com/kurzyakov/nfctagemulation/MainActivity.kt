package com.kurzyakov.nfctagemulation

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var broadcastReceiver: BroadcastReceiver
    private lateinit var logReceiver: BroadcastReceiver
    private lateinit var logTextView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_nfc_emulation_card)


        // Настраиваем BroadcastReceiver для сообщений
        broadcastReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                val message = intent?.getStringExtra("message") ?: "No message"
                Toast.makeText(this@MainActivity, message, Toast.LENGTH_SHORT).show()
                Log.d("NFCEmulationCardActivity", "Received broadcast with message: $message")

                // Логируем все данные, полученные в intent
                intent?.extras?.keySet()?.forEach { key ->
                    Log.d("NFCEmulationCardActivity", "Key: $key, Value: ${intent.extras?.get(key)}")
                }
            }
        }

        // Настраиваем BroadcastReceiver для логов
        logReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                val logMessage = intent?.getStringExtra("log") ?: "No log"
                Log.d("NFCEmulationCardActivity", "Received log: $logMessage")

                // Логируем все данные, полученные в intent
                intent?.extras?.keySet()?.forEach { key ->
                    Log.d("NFCEmulationCardActivity", "Log Key: $key, Value: ${intent.extras?.get(key)}")
                }

                logTextView.append("$logMessage\n")
            }
        }
    }

    override fun onResume() {
        super.onResume()

        // Глобальная регистрация BroadcastReceiver для сообщений с флагом безопасности
        registerReceiver(
            broadcastReceiver,
            IntentFilter("com.kurzyakov.ACTION_NFC"),
            Context.RECEIVER_NOT_EXPORTED // Указание флага безопасности
        )
        Log.d("NFCEmulationCardActivity", "BroadcastReceiver registered for ACTION_NFC")

        // Глобальная регистрация BroadcastReceiver для логов с флагом безопасности
        registerReceiver(
            logReceiver,
            IntentFilter("com.kurzyakov.ACTION_NFC_LOG"),
            Context.RECEIVER_NOT_EXPORTED // Указание флага безопасности
        )
        Log.d("NFCEmulationCardActivity", "BroadcastReceiver registered for ACTION_NFC_LOG")
    }

    override fun onPause() {
        super.onPause()

        // Отменяем регистрацию BroadcastReceiver для сообщений
        unregisterReceiver(broadcastReceiver)
        Log.d("NFCEmulationCardActivity", "BroadcastReceiver unregistered for ACTION_NFC")

        // Отменяем регистрацию BroadcastReceiver для логов
        unregisterReceiver(logReceiver)
        Log.d("NFCEmulationCardActivity", "BroadcastReceiver unregistered for ACTION_NFC_LOG")
    }

    // Метод для запуска NFC эмуляции
    fun openNFCEmulation(view: View) {
        Toast.makeText(this, "NFC эмуляция запущена", Toast.LENGTH_SHORT).show()
        Log.d("NFCEmulationCardActivity", "NFC Emulation started")

        // Отправляем глобальный Broadcast
        val intent = Intent("com.kurzyakov.ACTION_NFC")
        intent.putExtra("message", "NFC эмуляция активирована")
        sendBroadcast(intent)
    }
}