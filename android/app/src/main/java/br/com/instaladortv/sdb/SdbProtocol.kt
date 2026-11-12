package br.com.instaladortv.sdb

import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Constantes do SDB. O formato é o do ADB (cabeçalho de 24 bytes, CNXN/OPEN/OKAY/WRTE/CLSE);
 * versão e maxdata do host são os que o sdb 4.2.36 do Tizen Studio envia. Ver docs/sdb-protocolo.md.
 */
object Sdb {
    const val PORT = 26101

    const val A_CNXN = 0x4E584E43
    const val A_OPEN = 0x4E45504F
    const val A_OKAY = 0x59414B4F
    const val A_CLSE = 0x45534C43
    const val A_WRTE = 0x45545257

    const val HOST_VERSION = 0x00100000
    const val HOST_MAXDATA = 0x40000
    const val HEADER_SIZE = 24
    const val SYNC_CHUNK = 64 * 1024
    const val DEFAULT_SDK_TOOLPATH = "/home/owner/share/tmp/sdk_tools"

    val HOST_BANNER: ByteArray = "host::\u0000".toByteArray(Charsets.US_ASCII)
}

open class SdbException(message: String, cause: Throwable? = null) : IOException(message, cause)

class SdbServiceRefusedException(service: String) : SdbException("a TV recusou o serviço '$service'")

class SdbMessage(val command: Int, val arg0: Int, val arg1: Int, val data: ByteArray = ByteArray(0)) {

    fun pack(): ByteArray {
        val buffer = ByteBuffer.allocate(Sdb.HEADER_SIZE + data.size).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(command)
            .putInt(arg0)
            .putInt(arg1)
            .putInt(data.size)
            .putInt(checksum(data))
            .putInt(command xor -1)
            .put(data)
        return buffer.array()
    }

    fun describe(preview: Int = 64): String {
        val text = String(data, 0, minOf(preview, data.size), Charsets.ISO_8859_1).replace("\u0000", "\\0")
        val more = if (data.size > preview) "…(+${data.size - preview})" else ""
        return "${commandName(command)} arg0=0x${arg0.toString(16)} arg1=0x${arg1.toString(16)} len=${data.size} '$text'$more"
    }

    companion object {
        fun checksum(data: ByteArray): Int {
            var sum = 0
            for (b in data) sum += b.toInt() and 0xFF
            return sum
        }

        fun commandName(command: Int): String = String(
            byteArrayOf(command.toByte(), (command shr 8).toByte(), (command shr 16).toByte(), (command shr 24).toByte()),
            Charsets.ISO_8859_1,
        )

        /** Lê uma mensagem completa; confere magic e, quando preenchido, o checksum. */
        fun read(input: InputStream): SdbMessage {
            val data = DataInputStream(input)
            val header = ByteArray(Sdb.HEADER_SIZE)
            try {
                data.readFully(header)
            } catch (e: EOFException) {
                throw SdbException("a TV fechou a conexão", e)
            }
            val h = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
            val command = h.int
            val arg0 = h.int
            val arg1 = h.int
            val length = h.int
            val crc = h.int
            val magic = h.int
            if (magic != command xor -1) throw SdbException("cabeçalho SDB inválido")
            if (length < 0 || length > 16 * 1024 * 1024) throw SdbException("tamanho de mensagem inválido: $length")
            val payload = ByteArray(length)
            try {
                data.readFully(payload)
            } catch (e: EOFException) {
                throw SdbException("a TV fechou a conexão", e)
            }
            if (crc != 0 && checksum(payload) != crc) throw SdbException("checksum inválido em ${commandName(command)}")
            return SdbMessage(command, arg0, arg1, payload)
        }
    }
}

data class DeviceBanner(val raw: String, val kind: String, val model: String, val properties: Map<String, String>)

/** Banner do CNXN da TV, por exemplo `device::<modelo>::0`. */
fun parseBanner(data: ByteArray): DeviceBanner {
    val end = data.indexOf(0.toByte()).let { if (it < 0) data.size else it }
    val text = String(data, 0, end, Charsets.UTF_8)
    val parts = text.split("::")
    val properties = linkedMapOf<String, String>()
    parts.drop(1).forEach { part ->
        part.split(';').forEach { item ->
            val i = item.indexOf('=')
            if (i > 0) properties[item.substring(0, i).trim()] = item.substring(i + 1).trim()
        }
    }
    val model = parts.getOrNull(1)?.takeUnless { '=' in it } ?: ""
    return DeviceBanner(text, parts.firstOrNull() ?: "", model, properties)
}

/** Resposta do serviço `capability:`: linhas `chave:valor`, com ou sem prefixo de tamanho. */
fun parseCapability(data: ByteArray): Map<String, String> {
    var body = data
    if (body.size >= 2) {
        val le = (body[0].toInt() and 0xFF) or ((body[1].toInt() and 0xFF) shl 8)
        val be = ((body[0].toInt() and 0xFF) shl 8) or (body[1].toInt() and 0xFF)
        if (body.size - 2 == le || body.size - 2 == be) body = body.copyOfRange(2, body.size)
    }
    if (body.size >= 4) {
        val head = String(body, 0, 4, Charsets.ISO_8859_1)
        if (head.all { it in "0123456789abcdefABCDEF" } && head.toInt(16) == body.size - 4) {
            body = body.copyOfRange(4, body.size)
        }
    }
    val result = linkedMapOf<String, String>()
    String(body, Charsets.UTF_8).replace("\u0000", "").lines().forEach { line ->
        val i = line.indexOf(':')
        if (i > 0) result[line.substring(0, i).trim()] = line.substring(i + 1).trim()
    }
    return result
}
