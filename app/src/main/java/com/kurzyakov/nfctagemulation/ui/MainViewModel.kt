package com.kurzyakov.nfctagemulation.ui

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.nfc.NfcAdapter
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import com.kurzyakov.nfctagemulation.NfcTagApp
import com.kurzyakov.nfctagemulation.data.TagContent
import com.kurzyakov.nfctagemulation.data.Session
import com.kurzyakov.nfctagemulation.data.TagEvent
import com.kurzyakov.nfctagemulation.tag.NdefMessageCodec
import com.kurzyakov.nfctagemulation.tag.NdefRecord
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

enum class NfcStatus { Ready, Disabled, NoHce, NoNfc }

/** Что пользователь задаёт в редакторе содержимого. */
enum class DraftKind { Text, Link }

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = (application as NfcTagApp).repository

    val content: StateFlow<TagContent> = repository.content
    val sessions: StateFlow<List<Session>> = repository.sessions
    val readerPresent: StateFlow<Boolean> = repository.readerPresent
    val events: SharedFlow<TagEvent> = repository.events
    val capacity: Int get() = repository.capacity

    private val _nfcStatus = MutableStateFlow(readNfcStatus())
    val nfcStatus: StateFlow<NfcStatus> = _nfcStatus.asStateFlow()

    private val adapterStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = refreshNfcStatus()
    }

    init {
        // Системная рассылка о включении и выключении NFC.
        ContextCompat.registerReceiver(
            application,
            adapterStateReceiver,
            IntentFilter(NfcAdapter.ACTION_ADAPTER_STATE_CHANGED),
            ContextCompat.RECEIVER_EXPORTED,
        )
    }

    fun refreshNfcStatus() {
        _nfcStatus.value = readNfcStatus()
    }

    /** Запись для черновика или `null`, если черновик пуст. */
    fun recordFor(kind: DraftKind, value: String): NdefRecord? = when (kind) {
        DraftKind.Text -> value.takeIf { it.isNotEmpty() }?.let { NdefRecord.text(it, languageCode()) }
        DraftKind.Link -> normalizeLink(value)?.let(NdefRecord::uri)
    }

    /** Размер NDEF-сообщения с этой записью. */
    fun encodedSize(record: NdefRecord): Int = NdefMessageCodec.encode(listOf(record)).size

    fun saveContent(record: NdefRecord) = repository.setRecords(listOf(record))

    fun clearContent() = repository.clearContent()

    fun clearLog() = repository.clearLog()

    fun runSelfTest() = repository.runSelfTest()

    override fun onCleared() {
        getApplication<Application>().unregisterReceiver(adapterStateReceiver)
    }

    private fun readNfcStatus(): NfcStatus {
        val app = getApplication<Application>()
        val adapter = NfcAdapter.getDefaultAdapter(app)
        return when {
            adapter == null -> NfcStatus.NoNfc
            !app.packageManager.hasSystemFeature(PackageManager.FEATURE_NFC_HOST_CARD_EMULATION) -> NfcStatus.NoHce
            !adapter.isEnabled -> NfcStatus.Disabled
            else -> NfcStatus.Ready
        }
    }

    private fun languageCode(): String = Locale.getDefault().language.ifBlank { "en" }

    companion object {
        /** Добавляет https://, если пользователь ввёл адрес без схемы. */
        fun normalizeLink(value: String): String? {
            val link = value.trim()
            if (link.isEmpty()) return null
            return if (':' in link) link else "https://$link"
        }
    }
}
