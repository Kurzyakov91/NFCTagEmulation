package com.kurzyakov.nfctagemulation.hce

import android.nfc.cardemulation.HostApduService
import android.os.Bundle
import com.kurzyakov.nfctagemulation.NfcTagApp
import com.kurzyakov.nfctagemulation.tag.StatusWord

/**
 * HCE-сервис: система передаёт сюда команды считывателя, выбравшего NDEF-приложение
 * (AID перечислены в res/xml/apduservice.xml). Логика метки живёт в [com.kurzyakov.nfctagemulation.data.TagRepository],
 * поэтому её состояние переживает пересоздание сервиса.
 */
class TagHostApduService : HostApduService() {

    private val repository get() = (application as NfcTagApp).repository

    override fun processCommandApdu(commandApdu: ByteArray?, extras: Bundle?): ByteArray {
        if (commandApdu == null) return StatusWord.toBytes(StatusWord.WRONG_LENGTH)
        return repository.process(commandApdu)
    }

    override fun onDeactivated(reason: Int) {
        repository.onDeactivated(reason)
    }
}
