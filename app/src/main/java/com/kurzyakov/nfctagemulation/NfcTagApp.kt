package com.kurzyakov.nfctagemulation

import android.app.Application
import com.kurzyakov.nfctagemulation.data.TagRepository
import java.io.File

class NfcTagApp : Application() {

    lateinit var repository: TagRepository
        private set

    override fun onCreate() {
        super.onCreate()
        repository = TagRepository(File(filesDir, "tag_content.bin"))
    }
}
