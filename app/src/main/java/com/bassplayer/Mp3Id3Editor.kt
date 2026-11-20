package com.bassplayer

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.Charset

/** Updates text frames in an MP3 ID3v2.3/v2.4 tag while preserving all other frames (including artwork). */
object Mp3Id3Editor {
    fun update(input: ByteArray, fields: Map<String, String>): ByteArray? {
        if (fields.isEmpty()) return null
        val hasTag = input.size >= 10 && input[0] == 'I'.code.toByte() && input[1] == 'D'.code.toByte() && input[2] == '3'.code.toByte()
        val major = if (hasTag) input[3].toInt() and 0xff else 4
        if (major != 3 && major != 4) return null
        val flags = if (hasTag) input[5].toInt() and 0xff else 0
        if (flags and 0x80 != 0) return null // Whole-tag unsynchronization needs a full decode/re-encode.
        val oldSize = if (hasTag) syncSafe(input, 6) else 0
        if (oldSize < 0 || 10L + oldSize > input.size) return null
        val body = if (hasTag) input.copyOfRange(10, 10 + oldSize) else ByteArray(0)
        var frameStart = 0
        if (flags and 0x40 != 0) {
            if (body.size < 4) return null
            val extSize = if (major == 3) ByteBuffer.wrap(body, 0, 4).order(ByteOrder.BIG_ENDIAN).int + 4 else syncSafe(body, 0)
            if (extSize !in 4..body.size) return null
            frameStart = extSize
        }

        val replacementIds = fields.keys
        val preserved = ByteArrayOutputStream()
        var cursor = frameStart
        val frameHeaderSize = 10
        while (cursor + frameHeaderSize <= body.size) {
            if (body[cursor] == 0.toByte()) break
            val id = String(body, cursor, 4, Charsets.US_ASCII)
            if (!id.matches(Regex("[A-Z0-9]{4}"))) return null
            val size = if (major == 3) ByteBuffer.wrap(body, cursor + 4, 4).order(ByteOrder.BIG_ENDIAN).int else syncSafe(body, cursor + 4)
            if (size < 0 || cursor + frameHeaderSize.toLong() + size > body.size) return null
            if (id !in replacementIds) preserved.write(body, cursor, frameHeaderSize + size)
            cursor += frameHeaderSize + size
        }

        val updatedFrames = ByteArrayOutputStream()
        fields.forEach { (id, value) ->
            if (id.length != 4 || value.isEmpty()) return@forEach
            val payload = if (major == 3) {
                byteArrayOf(1, 0xff.toByte(), 0xfe.toByte()) + value.toByteArray(Charset.forName("UTF-16LE"))
            } else byteArrayOf(3) + value.toByteArray(Charsets.UTF_8)
            updatedFrames.write(id.toByteArray(Charsets.US_ASCII))
            updatedFrames.write(if (major == 3) intBytes(payload.size) else syncSafeBytes(payload.size))
            updatedFrames.write(byteArrayOf(0, 0))
            updatedFrames.write(payload)
        }

        val newBodyStream = ByteArrayOutputStream()
        newBodyStream.write(preserved.toByteArray())
        newBodyStream.write(updatedFrames.toByteArray())
        newBodyStream.write(ByteArray(1024))
        val newBody = newBodyStream.toByteArray()
        val header = byteArrayOf('I'.code.toByte(), 'D'.code.toByte(), '3'.code.toByte(), major.toByte(), if (hasTag) input[4] else 0, 0) + syncSafeBytes(newBody.size)
        val rest = if (hasTag) input.copyOfRange(10 + oldSize, input.size) else input
        return header + newBody + rest
    }

    fun fields(values: Map<String, String>): Map<String, String> = buildMap {
        values["artist"]?.let { put("TPE1", it) }
        values["album_artist"]?.let { put("TPE2", it) }
        values["title"]?.let { put("TIT2", it) }
        values["album"]?.let { put("TALB", it) }
    }

    private fun syncSafe(data: ByteArray, offset: Int): Int {
        if (offset + 4 > data.size) return -1
        return ((data[offset].toInt() and 0x7f) shl 21) or
            ((data[offset + 1].toInt() and 0x7f) shl 14) or
            ((data[offset + 2].toInt() and 0x7f) shl 7) or
            (data[offset + 3].toInt() and 0x7f)
    }

    private fun syncSafeBytes(value: Int) = byteArrayOf(
        ((value ushr 21) and 0x7f).toByte(), ((value ushr 14) and 0x7f).toByte(),
        ((value ushr 7) and 0x7f).toByte(), (value and 0x7f).toByte()
    )

    private fun intBytes(value: Int) = byteArrayOf((value ushr 24).toByte(), (value ushr 16).toByte(), (value ushr 8).toByte(), value.toByte())
}
