package br.com.instaladortv.sdb

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Conexão SDB com uma TV. Chamadas bloqueantes: use em `Dispatchers.IO`.
 * Um serviço por vez, que é o que o instalador precisa.
 */
class SdbClient(
    private val host: String,
    private val port: Int = Sdb.PORT,
    private val timeoutMs: Int = 15_000,
    private val trace: ((String, SdbMessage) -> Unit)? = null,
) : Closeable {

    var maxPayload = 4096
        private set
    var deviceVersion = 0
        private set
    var banner: DeviceBanner? = null
        private set

    private var socket: Socket? = null
    private lateinit var input: InputStream
    private lateinit var output: OutputStream
    private var nextId = 1

    fun connect(): DeviceBanner {
        val s = Socket()
        try {
            s.connect(InetSocketAddress(host, port), timeoutMs)
        } catch (e: IOException) {
            s.close()
            throw SdbException("não foi possível abrir $host:$port: ${e.message}", e)
        }
        s.soTimeout = timeoutMs
        s.tcpNoDelay = true
        socket = s
        input = BufferedInputStream(s.getInputStream())
        output = BufferedOutputStream(s.getOutputStream())
        send(SdbMessage(Sdb.A_CNXN, Sdb.HOST_VERSION, Sdb.HOST_MAXDATA, Sdb.HOST_BANNER))
        val msg = receive()
        if (msg.command != Sdb.A_CNXN) throw SdbException("resposta inesperada ao CNXN: ${msg.describe()}")
        deviceVersion = msg.arg0
        maxPayload = (if (msg.arg1 <= 0) 4096 else msg.arg1).coerceIn(256, Sdb.HOST_MAXDATA)
        return parseBanner(msg.data).also { banner = it }
    }

    override fun close() {
        socket?.close()
        socket = null
    }

    internal fun send(msg: SdbMessage) {
        if (socket == null) throw SdbException("conexão SDB não está aberta")
        trace?.invoke(">>", msg)
        try {
            output.write(msg.pack())
            output.flush()
        } catch (e: IOException) {
            throw SdbException("a TV encerrou a conexão: ${e.message}", e)
        }
    }

    internal fun receive(): SdbMessage {
        if (socket == null) throw SdbException("conexão SDB não está aberta")
        val msg = try {
            SdbMessage.read(input)
        } catch (e: SocketTimeoutException) {
            throw SdbException("a TV não respondeu a tempo", e)
        } catch (e: SdbException) {
            throw e
        } catch (e: IOException) {
            throw SdbException("a TV encerrou a conexão: ${e.message}", e)
        }
        trace?.invoke("<<", msg)
        return msg
    }

    /** Próxima mensagem para o canal [localId]; descarta restos de canais antigos. */
    internal fun receiveFor(localId: Int): SdbMessage {
        while (true) {
            val msg = receive()
            if (msg.arg1 == localId) return msg
        }
    }

    fun open(service: String): SdbStream {
        val localId = nextId++
        send(SdbMessage(Sdb.A_OPEN, localId, 0, (service + "\u0000").toByteArray(Charsets.UTF_8)))
        while (true) {
            val msg = receiveFor(localId)
            when (msg.command) {
                Sdb.A_OKAY -> return SdbStream(this, localId, msg.arg0, service)
                Sdb.A_CLSE -> throw SdbServiceRefusedException(service)
            }
        }
    }

    fun capability(): Map<String, String> = parseCapability(open("capability:").readAll())

    fun shell(command: String, timeoutMs: Int? = null): String = withTimeout(timeoutMs) {
        String(open("shell:$command").readAll(), Charsets.UTF_8)
    }

    /** Envia um arquivo pelo protocolo sync (SEND/DATA/DONE), como `sdb push`. */
    fun push(file: File, remotePath: String, mode: Int = 493, progress: ((Long, Long) -> Unit)? = null) {
        val total = file.length()
        val stream = open("sync:")
        try {
            try {
                stream.write(syncPacket("SEND", "$remotePath,$mode".toByteArray(Charsets.UTF_8)))
                var sent = 0L
                file.inputStream().buffered().use { fileInput ->
                    val chunk = ByteArray(Sdb.SYNC_CHUNK)
                    while (true) {
                        val n = fileInput.read(chunk)
                        if (n < 0) break
                        stream.write(syncPacket("DATA", chunk.copyOf(n)))
                        sent += n
                        progress?.invoke(sent, total)
                    }
                }
                stream.write(syncHeader("DONE", (System.currentTimeMillis() / 1000).toInt()))
            } catch (e: SdbException) {
                syncFailure(stream.pending())?.let { throw SdbException("a TV recusou o arquivo: $it", e) }
                throw e
            }
            val reply = ByteBuffer.wrap(stream.readExact(8)).order(ByteOrder.LITTLE_ENDIAN)
            val status = ByteArray(4).also { reply.get(it) }.toString(Charsets.US_ASCII)
            val size = reply.int
            when (status) {
                "OKAY" -> Unit
                "FAIL" -> throw SdbException("a TV recusou o arquivo: ${String(stream.readExact(size), Charsets.UTF_8)}")
                else -> throw SdbException("resposta inesperada do sync: $status")
            }
            runCatching { stream.write(syncHeader("QUIT", 0)) }
        } finally {
            stream.close()
        }
    }

    private fun <T> withTimeout(timeoutMs: Int?, block: () -> T): T {
        val s = socket ?: throw SdbException("conexão SDB não está aberta")
        val previous = s.soTimeout
        if (timeoutMs != null) s.soTimeout = timeoutMs
        try {
            return block()
        } finally {
            if (timeoutMs != null) socket?.soTimeout = previous
        }
    }

    private fun syncHeader(id: String, value: Int): ByteArray =
        ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).put(id.toByteArray(Charsets.US_ASCII)).putInt(value).array()

    private fun syncPacket(id: String, payload: ByteArray): ByteArray = syncHeader(id, payload.size) + payload

    private fun syncFailure(pending: ByteArray): String? {
        if (pending.size < 8 || String(pending, 0, 4, Charsets.US_ASCII) != "FAIL") return null
        val size = ByteBuffer.wrap(pending, 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
        return String(pending, 8, minOf(size, pending.size - 8), Charsets.UTF_8)
    }
}

/** Um serviço aberto na conexão (shell:, sync:, capability:). */
class SdbStream internal constructor(
    private val client: SdbClient,
    val localId: Int,
    val remoteId: Int,
    val service: String,
) {
    var closed = false
        private set
    private val buffer = ByteArrayOutputStream()
    private var readOffset = 0

    fun write(data: ByteArray) {
        var offset = 0
        while (offset < data.size) {
            if (closed) throw SdbException("a TV fechou o canal '$service' durante o envio")
            val end = minOf(offset + client.maxPayload, data.size)
            client.send(SdbMessage(Sdb.A_WRTE, localId, remoteId, data.copyOfRange(offset, end)))
            awaitOkay()
            offset = end
        }
    }

    private fun awaitOkay() {
        while (true) {
            val msg = client.receiveFor(localId)
            when (msg.command) {
                Sdb.A_OKAY -> return
                Sdb.A_WRTE -> accept(msg)
                Sdb.A_CLSE -> {
                    closed = true
                    throw SdbException("a TV fechou o canal '$service' durante o envio")
                }
            }
        }
    }

    private fun accept(msg: SdbMessage) {
        buffer.write(msg.data)
        client.send(SdbMessage(Sdb.A_OKAY, localId, remoteId))
    }

    private fun fill(): Boolean {
        if (closed) return false
        while (true) {
            val msg = client.receiveFor(localId)
            when (msg.command) {
                Sdb.A_WRTE -> {
                    accept(msg)
                    return true
                }
                Sdb.A_CLSE -> {
                    closed = true
                    return false
                }
            }
        }
    }

    private fun available(): Int = buffer.size() - readOffset

    fun pending(): ByteArray = buffer.toByteArray().copyOfRange(readOffset, buffer.size())

    fun readExact(size: Int): ByteArray {
        while (available() < size) {
            if (!fill()) throw SdbException("o canal '$service' fechou antes da resposta completa")
        }
        val bytes = buffer.toByteArray().copyOfRange(readOffset, readOffset + size)
        readOffset += size
        return bytes
    }

    fun readAll(): ByteArray {
        while (fill()) Unit
        return pending().also { readOffset = buffer.size() }
    }

    fun close() {
        if (closed) return
        closed = true
        runCatching { client.send(SdbMessage(Sdb.A_CLSE, localId, remoteId)) }
    }
}
