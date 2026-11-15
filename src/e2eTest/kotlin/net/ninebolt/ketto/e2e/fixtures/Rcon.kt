package net.ninebolt.ketto.e2e.fixtures

import java.io.DataInputStream
import java.io.EOFException
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder

class Rcon(host: String, port: Int, password: String) : AutoCloseable {
    private val socket = Socket()
    private val input: DataInputStream
    private var nextId = 1

    init {
        try {
            socket.connect(InetSocketAddress(host, port), 5000)
            socket.soTimeout = 10000
            input = DataInputStream(socket.getInputStream())
            val id = nextId++
            send(id, 3, password)
            var response = receive()
            if (response.type == 0 && response.id == id) response = receive()
            check(response.id == id && response.type == 2) { "RCON authentication rejected" }
        } catch (failure: Throwable) {
            socket.close()
            throw failure
        }
    }

    @Synchronized
    fun command(command: String): String {
        val id = nextId++
        val boundary = nextId++
        send(id, 2, command)
        val first = receive()
        check(first.id == id && first.type == 0) { "Unexpected RCON response: $first" }
        send(boundary, 2, "")
        return buildString {
            append(first.body)
            while (true) {
                val response = try {
                    receive()
                } catch (failure: EOFException) {
                    if (isNotEmpty()) break else throw failure
                }
                if (response.id == boundary) break
                check(response.id == id && response.type == 0) { "Unexpected RCON response: $response" }
                append(response.body)
            }
        }
    }

    private fun send(id: Int, type: Int, body: String) {
        val payload = body.toByteArray(Charsets.UTF_8)
        require(payload.size <= 4096) { "RCON command is too long" }
        val packet = ByteBuffer.allocate(payload.size + 14).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(payload.size + 10).putInt(id).putInt(type).put(payload).put(0).put(0).array()
        socket.getOutputStream().apply {
            write(packet)
            flush()
        }
    }

    private fun receive(): Packet {
        val size = Integer.reverseBytes(input.readInt())
        check(size in 10..(4 * 1024 * 1024)) { "Invalid RCON packet size: $size" }
        val packet = ByteArray(size)
        input.readFully(packet)
        check(packet[size - 1] == 0.toByte() && packet[size - 2] == 0.toByte()) { "Invalid RCON packet terminator" }
        val buffer = ByteBuffer.wrap(packet).order(ByteOrder.LITTLE_ENDIAN)
        return Packet(buffer.int, buffer.int, String(packet, 8, size - 10, Charsets.UTF_8))
    }

    override fun close() = socket.close()

    private data class Packet(val id: Int, val type: Int, val body: String)
}
