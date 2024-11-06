package com.kurzyakov.nfctagemulation

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.text.method.ScrollingMovementMethod
import android.util.Log
import android.view.View
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var broadcastReceiver: BroadcastReceiver
    private lateinit var logReceiver: BroadcastReceiver
    private lateinit var logTextView: TextView
    private lateinit var initialMessageTextView: TextView
    private lateinit var logScrollView: ScrollView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        logTextView = findViewById(R.id.logTextView)
        initialMessageTextView = findViewById(R.id.initialMessageTextView)
        logScrollView = findViewById(R.id.logScrollView)

        // Включаем прокрутку для TextView
        logTextView.movementMethod = ScrollingMovementMethod()

        // Инициализируем BroadcastReceiver для сообщений
        broadcastReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                val message = intent?.getStringExtra("message") ?: "No message"
                Toast.makeText(this@MainActivity, message, Toast.LENGTH_SHORT).show()
                Log.d("MainActivity", "Received broadcast with message: $message")
            }
        }

        // Инициализируем BroadcastReceiver для логов
        logReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                val logMessage = intent?.getStringExtra("log") ?: "No log"
                Log.d("MainActivity", "Received log: $logMessage")

                // Обновляем UI в основном потоке
                runOnUiThread {
                    // Прячем начальное сообщение и показываем ScrollView с логами
                    if (initialMessageTextView.visibility == View.VISIBLE) {
                        initialMessageTextView.visibility = View.GONE
                        logScrollView.visibility = View.VISIBLE
                    }

                    // Добавляем лог в TextView
                    logTextView.append("$logMessage\n")

                    // Прокручиваем ScrollView до конца
                    logScrollView.post {
                        logScrollView.fullScroll(View.FOCUS_DOWN)
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()

        // Регистрация BroadcastReceiver для сообщений
        val messageFilter = IntentFilter("com.kurzyakov.ACTION_NFC")
        registerReceiver(broadcastReceiver, messageFilter, Context.RECEIVER_NOT_EXPORTED)
        Log.d("MainActivity", "BroadcastReceiver registered for ACTION_NFC")

        // Регистрация BroadcastReceiver для логов
        val logFilter = IntentFilter("com.kurzyakov.ACTION_NFC_LOG")
        registerReceiver(logReceiver, logFilter, Context.RECEIVER_NOT_EXPORTED)
        Log.d("MainActivity", "BroadcastReceiver registered for ACTION_NFC_LOG")
    }

    override fun onPause() {
        super.onPause()

        // Отмена регистрации BroadcastReceiver для сообщений
        unregisterReceiver(broadcastReceiver)
        Log.d("MainActivity", "BroadcastReceiver unregistered for ACTION_NFC")

        // Отмена регистрации BroadcastReceiver для логов
        unregisterReceiver(logReceiver)
        Log.d("MainActivity", "BroadcastReceiver unregistered for ACTION_NFC_LOG")
    }

    // Метод для запуска NFC эмуляции
    fun openNFCEmulation(view: View) {
        Toast.makeText(this, "NFC эмуляция запущена", Toast.LENGTH_SHORT).show()
        Log.d("MainActivity", "NFC Emulation started")

        // Отправляем глобальный Broadcast
        val intent = Intent("com.kurzyakov.ACTION_NFC")
        intent.setPackage(packageName) // Указываем пакет приложения
        intent.putExtra("message", "NFC эмуляция активирована")
        sendBroadcast(intent)
    }
}