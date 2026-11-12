package br.com.instaladortv.sdb

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Collections
import kotlin.concurrent.thread

/** sdbd falso para testes: CNXN, capability:, shell: e sync: (SEND/DATA/DONE/QUIT). */
class FakeSdbd(
    private val capability: Map<String, String> = mapOf(
        "cpu_arch" to "armv7",
        "platform_version" to "6.5",
        "sdk_toolpath" to "/home/owner/share/tmp/sdk_tools",
    ),
    private val shellReplies: Map<String, String> = emptyMap(),
    private val maxdata: Int = 0x3FF9C,
    private val syncFail: String? = null,
    private val refuse: Set<String> = emptySet(),
) : AutoCloseable {
    val commands: MutableList<String> = Collections.synchronizedList(mutableListOf())
    val files: MutableMap<String, ByteArray> = Collections.synchronizedMap(mutableMapOf())
    @Volatile var maxWrte = 0
    @Volatile var hostCnxn: SdbMessage? = null

    private val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
    val port: Int = server.localPort

    init {
        thread(isDaemon = true) {
            runCatching {
                while (true) {
                    server.accept().use { socket ->
                        runCatching { handle(BufferedInputStream(socket.getInputStream()), socket.getOutputStream()) }
                    }
                }
            }
        }
    }

    override fun close() = server.close()

    private class SyncState(val id: Int) {
        val buf = ByteArrayOutputStream()
        var path = ""
        val data = ByteArrayOutputStream()
    }

    private fun handle(input: java.io.InputStream, output: OutputStream) {
        fun send(m: SdbMessage) = output.apply { write(m.pack()); flush() }
        hostCnxn = SdbMessage.read(input)
        send(SdbMessage(Sdb.A_CNXN, 0x02000000, maxdata, "device::QE55Q60::0\u0000".toByteArray()))
        var nextId = 100
        val syncs = mutableMapOf<Int, SyncState>()
        while (true) {
            val msg = SdbMessage.read(input)
            when (msg.command) {
                Sdb.A_OPEN -> {
                    val service = String(msg.data).trimEnd('\u0000')
                    val host = msg.arg0
                    if (service in refuse) {
                        send(SdbMessage(Sdb.A_CLSE, 0, host))
                        continue
                    }
                    val me = nextId++
                    send(SdbMessage(Sdb.A_OKAY, me, host))
                    when {
                        service == "capability:" -> {
                            val body = capability.entries.joinToString("") { "${it.key}:${it.value}\n" }.toByteArray()
                            val prefix = byteArrayOf((body.size and 0xFF).toByte(), (body.size shr 8).toByte())
                            send(SdbMessage(Sdb.A_WRTE, me, host, prefix + body))
                            send(SdbMessage(Sdb.A_CLSE, me, host))
                        }
                        service.startsWith("shell:") -> {
                            val command = service.removePrefix("shell:")
                            commands += command
                            val reply = shellReplies[command].orEmpty().toByteArray()
                            listOf(reply.copyOfRange(0, reply.size / 2), reply.copyOfRange(reply.size / 2, reply.size))
                                .filter { it.isNotEmpty() }
                                .forEach { send(SdbMessage(Sdb.A_WRTE, me, host, it)) }
                            send(SdbMessage(Sdb.A_CLSE, me, host))
                        }
                        service == "sync:" -> syncs[host] = SyncState(me)
                    }
                }
                Sdb.A_WRTE -> {
                    val state = syncs[msg.arg0] ?: continue
                    maxWrte = maxOf(maxWrte, msg.data.size)
                    send(SdbMessage(Sdb.A_OKAY, state.id, msg.arg0))
                    state.buf.write(msg.data)
                    if (sync(state, msg.arg0, ::send)) syncs.remove(msg.arg0)
                }
                Sdb.A_CLSE -> syncs.remove(msg.arg0)
            }
        }
    }

    /** Processa os pacotes sync completos; devolve true no QUIT. */
    private fun sync(state: SyncState, host: Int, send: (SdbMessage) -> Any): Boolean {
        var bytes = state.buf.toByteArray()
        while (bytes.size >= 8) {
            val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            val kind = String(bytes, 0, 4)
            bb.position(4)
            val size = bb.int
            if (kind == "DONE" || kind == "QUIT") {
                bytes = bytes.copyOfRange(8, bytes.size)
                if (kind == "QUIT") {
                    send(SdbMessage(Sdb.A_CLSE, state.id, host))
                    return true
                }
                val reply = if (syncFail != null) {
                    val text = syncFail.toByteArray()
                    "FAIL".toByteArray() + le(text.size) + text
                } else {
                    files[state.path] = state.data.toByteArray()
                    "OKAY".toByteArray() + le(0)
                }
                send(SdbMessage(Sdb.A_WRTE, state.id, host, reply))
                continue
            }
            if (bytes.size < 8 + size) break
            val payload = bytes.copyOfRange(8, 8 + size)
            bytes = bytes.copyOfRange(8 + size, bytes.size)
            when (kind) {
                "SEND" -> state.path = String(payload).substringBeforeLast(',')
                "DATA" -> state.data.write(payload)
            }
        }
        state.buf.reset()
        state.buf.write(bytes)
        return false
    }

    private fun le(value: Int) = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array()
}
