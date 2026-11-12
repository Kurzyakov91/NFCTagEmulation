package com.kurzyakov.nfctagemulation.ui

import android.content.Context
import com.kurzyakov.nfctagemulation.R
import com.kurzyakov.nfctagemulation.data.EndReason
import com.kurzyakov.nfctagemulation.data.Session
import com.kurzyakov.nfctagemulation.tag.StatusWord
import com.kurzyakov.nfctagemulation.tag.TagOperation
import com.kurzyakov.nfctagemulation.tag.Type4Tag
import com.kurzyakov.nfctagemulation.tag.toHex
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Человекочитаемые подписи для журнала. Общие для экрана и экспорта в текст. */
object Labels {

    private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault())
    private val preciseTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss.SSS").withZone(ZoneId.systemDefault())
    private val dateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss").withZone(ZoneId.systemDefault())

    fun time(millis: Long): String = timeFormatter.format(Instant.ofEpochMilli(millis))

    fun preciseTime(millis: Long): String = preciseTimeFormatter.format(Instant.ofEpochMilli(millis))

    fun dateTime(millis: Long): String = dateTimeFormatter.format(Instant.ofEpochMilli(millis))

    fun operation(context: Context, operation: TagOperation): String = when (operation) {
        is TagOperation.SelectApplication -> operation.ndefVersion
            ?.let { context.getString(R.string.op_select_ndef, it) }
            ?: context.getString(R.string.op_select_app, operation.aid)
        is TagOperation.SelectFile -> when (operation.fileId) {
            Type4Tag.CC_FILE_ID -> context.getString(R.string.op_select_cc)
            Type4Tag.NDEF_FILE_ID -> context.getString(R.string.op_select_ndef_file)
            else -> context.getString(R.string.op_select_file, operation.fileId?.let { "%04X".format(it) } ?: "?")
        }
        is TagOperation.ReadBinary -> context.getString(
            R.string.op_read, fileName(context, operation.fileId), operation.length, operation.offset,
        )
        is TagOperation.UpdateBinary -> context.getString(
            R.string.op_update, fileName(context, operation.fileId), operation.length, operation.offset,
        )
        is TagOperation.Unsupported -> context.getString(R.string.op_unsupported, operation.header)
        TagOperation.Malformed -> context.getString(R.string.op_malformed)
    }

    fun statusWord(context: Context, statusWord: Int): String = context.getString(
        when (statusWord) {
            StatusWord.OK -> R.string.sw_ok
            StatusWord.WRONG_LENGTH -> R.string.sw_wrong_length
            StatusWord.SECURITY_STATUS_NOT_SATISFIED -> R.string.sw_security
            StatusWord.NO_CURRENT_EF -> R.string.sw_no_current_ef
            StatusWord.FUNCTION_NOT_SUPPORTED -> R.string.sw_function_not_supported
            StatusWord.FILE_NOT_FOUND -> R.string.sw_file_not_found
            StatusWord.INCORRECT_P1P2 -> R.string.sw_incorrect_p1p2
            StatusWord.OFFSET_OUTSIDE_EF -> R.string.sw_offset_outside
            StatusWord.INS_NOT_SUPPORTED -> R.string.sw_ins_not_supported
            else -> R.string.sw_error
        },
    )

    fun endReason(context: Context, reason: EndReason): String = context.getString(
        when (reason) {
            EndReason.LinkLoss -> R.string.end_link_loss
            EndReason.Deselected -> R.string.end_deselected
            EndReason.Finished -> R.string.end_finished
        },
    )

    /** «1 команда», «3 команды», «5 команд» независимо от языка системы. */
    fun commandCount(context: Context, count: Int): String {
        val mod10 = count % 10
        val mod100 = count % 100
        val res = when {
            mod10 == 1 && mod100 != 11 -> R.string.session_commands_one
            mod10 in 2..4 && mod100 !in 12..14 -> R.string.session_commands_few
            else -> R.string.session_commands_many
        }
        return context.getString(res, count)
    }

    fun sessionTitle(context: Context, session: Session): String =
        context.getString(if (session.simulated) R.string.session_simulated else R.string.session_reader)

    /** Журнал в виде текста для буфера обмена. */
    fun exportLog(context: Context, sessions: List<Session>): String = buildString {
        appendLine(context.getString(R.string.log_export_header))
        sessions.asReversed().forEach { session ->
            appendLine()
            append("== ").append(sessionTitle(context, session)).append(", ").appendLine(dateTime(session.startedAt))
            session.exchanges.forEach { exchange ->
                append(preciseTime(exchange.time)).append("  ").appendLine(operation(context, exchange.operation))
                append("  → ").appendLine(exchange.command.toHex())
                append("  ← ").append(exchange.response.toHex())
                append("  (").append(statusWord(context, exchange.statusWord)).appendLine(")")
            }
            session.endReason?.let { appendLine(endReason(context, it)) }
        }
    }

    private fun fileName(context: Context, fileId: Int?): String = context.getString(
        when (fileId) {
            Type4Tag.CC_FILE_ID -> R.string.file_cc
            Type4Tag.NDEF_FILE_ID -> R.string.file_ndef
            else -> R.string.file_none
        },
    )
}
