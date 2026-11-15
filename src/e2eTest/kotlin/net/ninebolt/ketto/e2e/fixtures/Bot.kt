package net.ninebolt.ketto.e2e.fixtures

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.cloudburstmc.math.vector.Vector3i
import org.geysermc.mcprotocollib.auth.GameProfile
import org.geysermc.mcprotocollib.network.Session
import org.geysermc.mcprotocollib.network.event.session.ConnectedEvent
import org.geysermc.mcprotocollib.network.event.session.DisconnectedEvent
import org.geysermc.mcprotocollib.network.event.session.PacketErrorEvent
import org.geysermc.mcprotocollib.network.event.session.SessionAdapter
import org.geysermc.mcprotocollib.network.factory.ClientNetworkSessionFactory
import org.geysermc.mcprotocollib.network.packet.Packet
import org.geysermc.mcprotocollib.protocol.MinecraftProtocol
import org.geysermc.mcprotocollib.protocol.data.ProtocolState
import org.geysermc.mcprotocollib.protocol.data.game.entity.RotationOrigin
import org.geysermc.mcprotocollib.protocol.data.game.entity.`object`.Direction
import org.geysermc.mcprotocollib.protocol.data.game.entity.player.Hand
import org.geysermc.mcprotocollib.protocol.data.game.entity.player.HandPreference
import org.geysermc.mcprotocollib.protocol.data.game.entity.player.PositionElement
import org.geysermc.mcprotocollib.protocol.data.game.setting.ChatVisibility
import org.geysermc.mcprotocollib.protocol.data.game.setting.ParticleStatus
import org.geysermc.mcprotocollib.protocol.packet.common.clientbound.ClientboundPingPacket
import org.geysermc.mcprotocollib.protocol.packet.common.serverbound.ServerboundClientInformationPacket
import org.geysermc.mcprotocollib.protocol.packet.common.serverbound.ServerboundPongPacket
import org.geysermc.mcprotocollib.protocol.packet.configuration.clientbound.ClientboundSelectKnownPacks
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundLoginPacket
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundRespawnPacket
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundSystemChatPacket
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.player.ClientboundPlayerLookAtPacket
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.player.ClientboundPlayerPositionPacket
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.player.ClientboundSetHealthPacket
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.level.ClientboundChunkBatchFinishedPacket
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.ServerboundChatCommandPacket
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.ServerboundClientTickEndPacket
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.ServerboundPlayerLoadedPacket
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.level.ServerboundAcceptTeleportationPacket
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.level.ServerboundChunkBatchReceivedPacket
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundMovePlayerPosRotPacket
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundSwingPacket
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundUseItemOnPacket
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.createDirectories
import kotlin.io.path.readLines
import kotlin.math.atan2
import kotlin.math.sqrt

class Bot internal constructor(private val server: PaperServer, val name: String) : AutoCloseable {
    val log: Path = server.directory.resolve("bots/$name.log")

    @Volatile
    var entityId: Int? = null
        private set

    @Volatile
    var position: Position? = null
        private set

    @Volatile
    var health: Float = Float.NaN
        private set

    @Volatile
    var disconnectReason: String? = null
        private set

    @Volatile
    var loaded = false
        private set

    private val receivedMessages = CopyOnWriteArrayList<String>()
    val messages: List<String>
        get() = receivedMessages.toList()

    private val executor = Executors.newSingleThreadScheduledExecutor { task -> Thread(task, "bot-$name").apply { isDaemon = true } }
    private val sequence = AtomicInteger()
    private val uuid = UUID.nameUUIDFromBytes("OfflinePlayer:$name".toByteArray(Charsets.UTF_8))
    private val protocol = MinecraftProtocol(GameProfile(uuid, name), null)
    private val session = ClientNetworkSessionFactory.factory().setAddress(server.host, server.port)
        .setProtocol(protocol).setPacketHandlerExecutor(executor).create()
    private val serializer = PlainTextComponentSerializer.plainText()
    private var messageCursor = 0
    private val logLock = Any()

    init {
        require(Regex("[A-Za-z0-9_]{1,16}").matches(name)) { "Invalid bot name: $name" }
        log.parent.createDirectories()
        session.addListener(object : SessionAdapter() {
            override fun connected(event: ConnectedEvent) {
                appendLog("Connected to ${server.host}:${server.port}")
            }

            override fun disconnected(event: DisconnectedEvent) {
                disconnectReason = serializer.serialize(event.reason)
                loaded = false
                appendLog("Disconnected: $disconnectReason${event.cause?.let { "\n${it.stackTraceToString()}" } ?: ""}")
            }

            override fun packetError(event: PacketErrorEvent) {
                appendLog("Packet error: ${event.cause.stackTraceToString()}")
            }

            override fun packetReceived(session: Session, packet: Packet) {
                receive(packet)
            }
        })
    }

    internal fun connect() {
        appendLog("Connecting as $name ($uuid)")
        session.connect(false)
        executor.scheduleAtFixedRate({
            try {
                if (loaded && session.isConnected && protocol.outboundState == ProtocolState.GAME) {
                    position?.let { move(it) }
                    session.send(ServerboundClientTickEndPacket.INSTANCE)
                }
            } catch (failure: Exception) {
                appendLog("Client tick failed: ${failure.stackTraceToString()}")
                session.disconnect(Component.text("Client tick failed"), failure)
            }
        }, 50, 50, TimeUnit.MILLISECONDS)
        await("player loaded") { loaded && entityId != null && position != null && !health.isNaN() }
        server.awaitLog("$name joined the game")
    }

    private fun receive(packet: Packet) {
        when (packet) {
            is ClientboundSelectKnownPacks -> {
                session.send(
                    ServerboundClientInformationPacket(
                        "en_us", 2, ChatVisibility.FULL, true, emptyList(), HandPreference.RIGHT_HAND, false, true, ParticleStatus.MINIMAL,
                    ),
                )
                appendLog("Configured locale en_us")
            }

            is ClientboundLoginPacket -> {
                entityId = packet.entityId
                appendLog("Entered game: entity $entityId")
            }

            is ClientboundPlayerPositionPacket -> teleport(packet)

            is ClientboundPlayerLookAtPacket -> {
                position?.let { current ->
                    val dx = packet.x - current.x
                    val dy = packet.y - current.y - if (packet.origin == RotationOrigin.EYES) 1.62 else 0.0
                    val dz = packet.z - current.z
                    val next = current.copy(
                        yaw = Math.toDegrees(atan2(-dx, dz)).toFloat(),
                        pitch = -Math.toDegrees(atan2(dy, sqrt(dx * dx + dz * dz))).toFloat(),
                    )
                    position = next
                    move(next)
                    appendLog("Facing: $next")
                }
            }

            is ClientboundSetHealthPacket -> {
                val previous = health
                health = packet.health
                appendLog("Health: $health")
                if (health <= 0 && (previous > 0 || previous.isNaN())) respawn()
            }

            is ClientboundRespawnPacket -> {
                loaded = false
                appendLog("Respawned")
            }

            is ClientboundPingPacket -> session.send(ServerboundPongPacket(packet.id))

            is ClientboundChunkBatchFinishedPacket -> session.send(ServerboundChunkBatchReceivedPacket(16f))

            is ClientboundSystemChatPacket -> chat(serializer.serialize(packet.content))
        }
    }

    private fun teleport(packet: ClientboundPlayerPositionPacket) {
        val previous = position ?: Position(0.0, 0.0, 0.0, 0f, 0f)
        val relative = packet.relatives
        val next = Position(
            packet.position.x + if (PositionElement.X in relative) previous.x else 0.0,
            packet.position.y + if (PositionElement.Y in relative) previous.y else 0.0,
            packet.position.z + if (PositionElement.Z in relative) previous.z else 0.0,
            packet.yRot + if (PositionElement.Y_ROT in relative) previous.yaw else 0f,
            packet.xRot + if (PositionElement.X_ROT in relative) previous.pitch else 0f,
        )
        session.send(ServerboundAcceptTeleportationPacket(packet.id))
        move(next)
        position = next
        if (!loaded) session.send(ServerboundPlayerLoadedPacket.INSTANCE)
        loaded = true
        appendLog("Teleport ${packet.id}: $next")
    }

    private fun move(position: Position) {
        session.send(ServerboundMovePlayerPosRotPacket(true, false, position.x, position.y, position.z, position.yaw, position.pitch))
    }

    fun command(command: String) {
        require(command.isNotBlank() && !command.startsWith('/')) { "Commands must not start with a slash" }
        send(ServerboundChatCommandPacket(command))
        appendLog("Command: /$command")
    }

    fun useBlock(x: Int, y: Int, z: Int, face: Direction = Direction.UP) {
        send(ServerboundUseItemOnPacket(Vector3i.from(x, y, z), face, Hand.MAIN_HAND, 0.5f, 0.5f, 0.5f, false, false, sequence.incrementAndGet()))
        send(ServerboundSwingPacket(Hand.MAIN_HAND))
    }

    fun attack(other: Bot) {
        send(BotProtocol.attack(checkNotNull(other.entityId) { "${other.name} has not joined" }))
        send(ServerboundSwingPacket(Hand.MAIN_HAND))
        appendLog("Attack: ${other.name}")
    }

    private fun respawn() {
        send(BotProtocol.respawn())
        appendLog("Requested respawn")
    }

    private fun send(packet: Packet) {
        check(session.isConnected && disconnectReason == null) { "$name is disconnected: $disconnectReason ($log)" }
        session.send(packet)
    }

    fun markMessages(): MessageMark = MessageMark(this, receivedMessages.size)

    fun messagesSince(mark: MessageMark): List<String> {
        require(mark.owner === this) { "Message mark belongs to another bot" }
        return receivedMessages.drop(mark.index)
    }

    @Synchronized
    fun awaitMessage(
        after: MessageMark? = null,
        timeout: Duration = Duration.ofSeconds(20),
        description: String = "chat message",
        predicate: (String) -> Boolean,
    ): String {
        require(after == null || after.owner === this) { "Message mark belongs to another bot" }
        val start = maxOf(messageCursor, after?.index ?: messageCursor)
        var matched: String? = null
        appendLog("Awaiting $description after message $start")
        await("$description after message $start", timeout) {
            val index = (start until receivedMessages.size).firstOrNull { predicate(receivedMessages[it]) }
            if (index != null) {
                matched = receivedMessages[index]
                messageCursor = index + 1
            }
            matched != null
        }
        return checkNotNull(matched)
    }

    fun await(description: String, timeout: Duration = Duration.ofSeconds(20), condition: () -> Boolean) {
        val deadline = System.nanoTime() + timeout.toNanos()
        while (System.nanoTime() < deadline) {
            check(disconnectReason == null) { "$name disconnected while waiting for $description: $disconnectReason\n${logTail()}" }
            if (condition()) return
            Thread.sleep(25)
        }
        error("$name timed out waiting for $description\n${logTail()}\n${server.logTail()}")
    }

    private fun chat(text: String) {
        receivedMessages.add(text)
        appendLog("Chat: $text")
    }

    private fun appendLog(text: String) {
        synchronized(logLock) {
            Files.writeString(log, "${Instant.now()} $text\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND)
        }
    }

    private fun logTail(): String = synchronized(logLock) { log.readLines().takeLast(40).joinToString("\n") }

    fun disconnect() {
        try {
            session.disconnect(Component.text("Bot disconnected"))
        } finally {
            executor.shutdownNow()
            check(executor.awaitTermination(5, TimeUnit.SECONDS)) { "Bot executor did not stop: $name" }
        }
    }

    override fun close() = disconnect()

    fun reconnect(): Bot {
        disconnect()
        return server.bot(name)
    }

    data class Position(val x: Double, val y: Double, val z: Double, val yaw: Float, val pitch: Float)

    class MessageMark internal constructor(internal val owner: Bot, internal val index: Int)
}
