package com.hamhuo.tplanner

import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/** Length-prefixed UTF-8 framing shared by the phone and watch RFCOMM transports. */
object ScheduleRfcommProtocol {
    const val MAX_PAYLOAD_BYTES = 256 * 1024
    const val ACK_BYTE = 0x06
    const val NAK_BYTE = 0x15

    fun encodePayload(payload: String): ByteArray =
        payload.toByteArray(StandardCharsets.UTF_8).also { bytes ->
            require(bytes.size <= MAX_PAYLOAD_BYTES) {
                "Payload is ${bytes.size} bytes; maximum is $MAX_PAYLOAD_BYTES"
            }
        }

    fun writeFrame(output: OutputStream, payload: ByteArray) {
        require(payload.size <= MAX_PAYLOAD_BYTES) {
            "Payload is ${payload.size} bytes; maximum is $MAX_PAYLOAD_BYTES"
        }
        output.write(
            byteArrayOf(
                (payload.size ushr 24).toByte(),
                (payload.size ushr 16).toByte(),
                (payload.size ushr 8).toByte(),
                payload.size.toByte(),
            ),
        )
        output.write(payload)
        output.flush()
    }

    fun readFrame(input: InputStream): String {
        val header = ByteArray(FRAME_HEADER_BYTES)
        input.readFully(header)
        val payloadSize =
            ((header[0].toInt() and 0xff) shl 24) or
                ((header[1].toInt() and 0xff) shl 16) or
                ((header[2].toInt() and 0xff) shl 8) or
                (header[3].toInt() and 0xff)
        require(payloadSize in 0..MAX_PAYLOAD_BYTES) {
            "Invalid payload size: $payloadSize"
        }

        val payload = ByteArray(payloadSize)
        input.readFully(payload)
        return StandardCharsets.UTF_8
            .newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(payload))
            .toString()
    }

    private fun InputStream.readFully(destination: ByteArray) {
        var offset = 0
        while (offset < destination.size) {
            val count = read(destination, offset, destination.size - offset)
            if (count < 0) {
                throw EOFException(
                    "Unexpected end of stream: read $offset of ${destination.size} bytes",
                )
            }
            if (count == 0) {
                val next = read()
                if (next < 0) {
                    throw EOFException(
                        "Unexpected end of stream: read $offset of ${destination.size} bytes",
                    )
                }
                destination[offset++] = next.toByte()
            } else {
                offset += count
            }
        }
    }

    private const val FRAME_HEADER_BYTES = 4
}
