package com.kurzyakov.nfctagemulation

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.nfc.NfcAdapter
import android.nfc.cardemulation.CardEmulation
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.kurzyakov.nfctagemulation.hce.TagHostApduService
import com.kurzyakov.nfctagemulation.ui.MainScreen
import com.kurzyakov.nfctagemulation.ui.MainViewModel
import com.kurzyakov.nfctagemulation.ui.theme.NfcTagTheme

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            NfcTagTheme {
                MainScreen(viewModel = viewModel, onOpenNfcSettings = ::openNfcSettings)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.refreshNfcStatus()
        setPreferredService(true)
    }

    override fun onPause() {
        setPreferredService(false)
        super.onPause()
    }

    /**
     * Пока приложение на экране, наш сервис выбирается для NDEF AID даже при конфликте
     * с другими приложениями, которые тоже эмулируют метки.
     */
    private fun setPreferredService(preferred: Boolean) {
        val adapter = NfcAdapter.getDefaultAdapter(this) ?: return
        runCatching {
            val cardEmulation = CardEmulation.getInstance(adapter)
            if (preferred) {
                cardEmulation.setPreferredService(this, ComponentName(this, TagHostApduService::class.java))
            } else {
                cardEmulation.unsetPreferredService(this)
            }
        }
    }

    private fun openNfcSettings() {
        try {
            startActivity(Intent(Settings.ACTION_NFC_SETTINGS))
        } catch (e: ActivityNotFoundException) {
            startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS))
        }
    }
}
