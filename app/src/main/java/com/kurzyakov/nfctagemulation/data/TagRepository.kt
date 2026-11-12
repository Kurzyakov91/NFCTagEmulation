package com.kurzyakov.nfctagemulation.data

import android.nfc.cardemulation.HostApduService
import android.util.Log
import androidx.core.util.AtomicFile
import com.kurzyakov.nfctagemulation.tag.NdefMessageCodec
import com.kurzyakov.nfctagemulation.tag.NdefRecord
import com.kurzyakov.nfctagemulation.tag.NdefTagReader
import com.kurzyakov.nfctagemulation.tag.TagOperation
import com.kurzyakov.nfctagemulation.tag.Type4Tag
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException

/** Откуда взялось текущее содержимое метки. */
enum class ContentOrigin { None, Reader, App }

class TagContent(
    val message: ByteArray,
    /** Разобранные записи; `null`, если сообщение не является корректным NDEF. */
    val records: List<NdefRecord>?,
    val capacity: Int,
    val origin: ContentOrigin,
    val updatedAt: Long?,
) {
    val isEmpty: Boolean get() = message.isEmpty()
}

class Exchange(
    val id: Long,
    val time: Long,
    val operation: TagOperation,
    val command: ByteArray,
    val response: ByteArray,
    val statusWord: Int,
)

enum class EndReason { LinkLoss, Deselected, Finished }

/** Один сеанс связи: от первой команды считывателя до ухода из поля. */
data class Session(
    val id: Long,
    val simulated: Boolean,
    val startedAt: Long,
    val exchanges: List<Exchange> = emptyList(),
    val endedAt: Long? = null,
    val endReason: EndReason? = null,
    val messageWritten: Boolean = false,
) {
    val isActive: Boolean get() = endedAt == null
}

sealed interface TagEvent {
    data object MessageReceived : TagEvent
    data class SelfTestFinished(val success: Boolean, val bytesRead: Int) : TagEvent
}

/**
 * Единое состояние метки для HCE-сервиса и интерфейса: эмулируемая метка, журнал сеансов
 * и сохранение содержимого на диск, чтобы оно не пропадало при перезапуске.
 */
class TagRepository(
    storageFile: File,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val storage = AtomicFile(storageFile)
    private val tag: Type4Tag

    private val _content: MutableStateFlow<TagContent>
    val content: StateFlow<TagContent>

    private val _sessions = MutableStateFlow<List<Session>>(emptyList())
    /** Сеансы от новых к старым. */
    val sessions: StateFlow<List<Session>> = _sessions.asStateFlow()

    private val _readerPresent = MutableStateFlow(false)
    val readerPresent: StateFlow<Boolean> = _readerPresent.asStateFlow()

    private val _events = MutableSharedFlow<TagEvent>(extraBufferCapacity = 16)
    val events: SharedFlow<TagEvent> = _events.asSharedFlow()

    private var nextId = 1L

    init {
        val stored = load()
        tag = Type4Tag(stored?.message ?: ByteArray(0))
        _content = MutableStateFlow(
            buildContent(tag.message, stored?.origin ?: ContentOrigin.None, stored?.updatedAt),
        )
        content = _content.asStateFlow()
    }

    val capacity: Int get() = tag.maxMessageSize

    /** Команда от считывателя через HCE. */
    @Synchronized
    fun process(command: ByteArray): ByteArray {
        if (_sessions.value.firstOrNull()?.isActive != true) startSession(simulated = false)
        return exchange(command)
    }

    /** Считыватель ушёл из поля или выбрал другое приложение. */
    @Synchronized
    fun onDeactivated(reason: Int) {
        tag.deactivate()?.let(::commitFromReader)
        finishSession(
            when (reason) {
                HostApduService.DEACTIVATION_DESELECTED -> EndReason.Deselected
                else -> EndReason.LinkLoss
            },
        )
    }

    /** Задаёт содержимое метки из приложения. */
    @Synchronized
    fun setRecords(records: List<NdefRecord>) {
        val message = NdefMessageCodec.encode(records)
        tag.setMessage(message)
        updateContent(message, ContentOrigin.App)
    }

    @Synchronized
    fun clearContent() {
        tag.setMessage(ByteArray(0))
        updateContent(ByteArray(0), ContentOrigin.None)
    }

    @Synchronized
    fun clearLog() {
        _sessions.value = _sessions.value.filter { it.isActive }
    }

    /** Читает метку встроенным считывателем: видно, что получит настоящий ридер. */
    @Synchronized
    fun runSelfTest() {
        if (_readerPresent.value) return
        startSession(simulated = true)
        val result = runCatching { NdefTagReader.readMessage(::exchange) }
        tag.deactivate()
        finishSession(EndReason.Finished)
        val bytes = result.getOrNull()
        _events.tryEmit(
            TagEvent.SelfTestFinished(
                success = bytes != null && bytes.contentEquals(tag.message),
                bytesRead = bytes?.size ?: 0,
            ),
        )
    }

    private fun exchange(command: ByteArray): ByteArray {
        val result = tag.process(command)
        val entry = Exchange(
            id = nextId++,
            time = clock(),
            operation = result.operation,
            command = command,
            response = result.response,
            statusWord = result.statusWord,
        )
        updateActiveSession { session ->
            val exchanges = (session.exchanges + entry).takeLast(MAX_EXCHANGES_PER_SESSION)
            session.copy(exchanges = exchanges)
        }
        result.committedMessage?.let(::commitFromReader)
        return result.response
    }

    private fun commitFromReader(message: ByteArray) {
        updateContent(message, if (message.isEmpty()) ContentOrigin.None else ContentOrigin.Reader)
        updateActiveSession { it.copy(messageWritten = true) }
        if (message.isNotEmpty()) _events.tryEmit(TagEvent.MessageReceived)
    }

    private fun startSession(simulated: Boolean) {
        finishSession(EndReason.LinkLoss)
        val session = Session(id = nextId++, simulated = simulated, startedAt = clock())
        _sessions.value = (listOf(session) + _sessions.value).take(MAX_SESSIONS)
        if (!simulated) _readerPresent.value = true
    }

    private fun finishSession(reason: EndReason) {
        updateActiveSession { it.copy(endedAt = clock(), endReason = reason) }
        _readerPresent.value = false
    }

    private inline fun updateActiveSession(transform: (Session) -> Session) {
        val sessions = _sessions.value
        val active = sessions.firstOrNull()?.takeIf { it.isActive } ?: return
        _sessions.value = listOf(transform(active)) + sessions.drop(1)
    }

    private fun updateContent(message: ByteArray, origin: ContentOrigin) {
        val now = clock()
        _content.value = buildContent(message, origin, now)
        save(message, origin, now)
    }

    private fun buildContent(message: ByteArray, origin: ContentOrigin, updatedAt: Long?) = TagContent(
        message = message,
        records = if (message.isEmpty()) emptyList() else runCatching { NdefMessageCodec.decode(message) }.getOrNull(),
        capacity = tag.maxMessageSize,
        origin = origin,
        updatedAt = updatedAt,
    )

    private class Stored(val message: ByteArray, val origin: ContentOrigin, val updatedAt: Long)

    private fun load(): Stored? = try {
        DataInputStream(storage.openRead().buffered()).use { input ->
            if (input.readByte().toInt() != STORAGE_VERSION) return null
            val origin = ContentOrigin.entries.getOrElse(input.readByte().toInt()) { ContentOrigin.None }
            val updatedAt = input.readLong()
            val length = input.readInt()
            if (length !in 0..Type4Tag.DEFAULT_NDEF_FILE_SIZE - Type4Tag.NLEN_SIZE) return null
            val message = ByteArray(length).also(input::readFully)
            Stored(message, origin, updatedAt)
        }
    } catch (e: IOException) {
        null
    }

    private fun save(message: ByteArray, origin: ContentOrigin, updatedAt: Long) {
        val stream = try {
            storage.startWrite()
        } catch (e: IOException) {
            Log.w(TAG, "Не удалось сохранить содержимое метки", e)
            return
        }
        try {
            DataOutputStream(stream).apply {
                writeByte(STORAGE_VERSION)
                writeByte(origin.ordinal)
                writeLong(updatedAt)
                writeInt(message.size)
                write(message)
                flush()
            }
            storage.finishWrite(stream)
        } catch (e: IOException) {
            storage.failWrite(stream)
            Log.w(TAG, "Не удалось сохранить содержимое метки", e)
        }
    }

    private companion object {
        const val TAG = "TagRepository"
        const val STORAGE_VERSION = 1
        const val MAX_SESSIONS = 30
        const val MAX_EXCHANGES_PER_SESSION = 300
    }
}
