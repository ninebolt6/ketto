package net.ninebolt.ketto.e2e.fixtures

import java.io.IOException
import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.time.Duration
import java.util.Properties
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

class PaperServer(val directory: Path) : AutoCloseable {
    val host = "127.0.0.1"
    val port: Int
    val rconPort: Int
    val pluginDirectory: Path = directory.resolve("plugins/ketto")
    val log: Path = directory.resolve("server.log")
    private val password = UUID.randomUUID().toString()
    private var process: Process? = null
    private var rcon: Rcon? = null
    private var startOffset = 0
    private val bots = mutableListOf<Bot>()

    init {
        if (directory.exists()) {
            Files.walk(directory).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
        }
        pluginDirectory.createDirectories()
        ServerSocket(0).use { server ->
            port = server.localPort
            ServerSocket(0).use { remote -> rconPort = remote.localPort }
        }
        val prepared = Path.of(requiredProperty("ketto.e2e.paperDirectory"))
        Files.copy(prepared.resolve("paper.jar"), directory.resolve("paper.jar"), StandardCopyOption.REPLACE_EXISTING)
        Files.copy(prepared.resolve("paper-build.txt"), directory.resolve("paper-build.txt"), StandardCopyOption.REPLACE_EXISTING)
        listOf("libraries", "versions", "cache").forEach { name ->
            prepared.resolve(name).createDirectories()
            Files.createSymbolicLink(directory.resolve(name), prepared.resolve(name))
        }
        Files.copy(Path.of(requiredProperty("ketto.e2e.pluginJar")), directory.resolve("plugins/ketto.jar"))
        directory.resolve("eula.txt").writeText("eula=true\n")
        val properties = Properties().apply {
            putAll(
                mapOf(
                    "server-ip" to host,
                    "server-port" to port.toString(),
                    "online-mode" to "false",
                    "allow-flight" to "true",
                    "level-type" to "minecraft:flat",
                    "generate-structures" to "false",
                    "spawn-protection" to "0",
                    "pvp" to "true",
                    "spawn-monsters" to "false",
                    "enable-rcon" to "true",
                    "rcon.password" to password,
                    "rcon.port" to rconPort.toString(),
                    "broadcast-rcon-to-ops" to "false",
                    "view-distance" to "2",
                    "simulation-distance" to "2",
                    "max-players" to "10",
                    "network-compression-threshold" to "-1",
                    "sync-chunk-writes" to "false",
                    "enable-status" to "false",
                    "pause-when-empty-seconds" to "-1",
                ),
            )
        }
        Files.newOutputStream(directory.resolve("server.properties")).use { properties.store(it, null) }
        directory.resolve("bukkit.yml").writeText("settings:\n  allow-end: false\n  connection-throttle: -1\n")
    }

    fun configurePlugin(yaml: String) {
        check(process?.isAlive != true) { "Stop the server before configuring ketto" }
        pluginDirectory.resolve("config.yml").writeText(yaml)
    }

    fun start(timeout: Duration = Duration.ofMinutes(3)) {
        check(process?.isAlive != true) { "Paper is already running" }
        startOffset = if (log.exists()) log.readText().length else 0
        val java = Path.of(System.getProperty("java.home"), "bin", "java").toString()
        process = ProcessBuilder(
            java,
            "-Xms256M",
            "-Xmx1G",
            "-XX:ActiveProcessorCount=2",
            "-Dcom.mojang.eula.agree=true",
            "-jar",
            "paper.jar",
            "--nogui",
        ).directory(directory.toFile()).redirectErrorStream(true)
            .redirectOutput(ProcessBuilder.Redirect.appendTo(log.toFile())).start()
        try {
            await("Paper startup", timeout) { currentLog().contains("Done (") }
            await("RCON startup", Duration.ofSeconds(20)) {
                try {
                    rcon = Rcon(host, rconPort, password)
                    true
                } catch (_: IOException) {
                    false
                }
            }
        } catch (failure: Throwable) {
            kill()
            throw failure
        }
    }

    fun command(command: String): String {
        val response = checkNotNull(rcon) { "Paper is not started" }.command(command)
        Files.writeString(directory.resolve("rcon.log"), "> $command\n$response\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND)
        return response
    }

    fun bot(name: String): Bot {
        check(process?.isAlive == true) { "Start Paper before connecting a bot" }
        return Bot(this, name).also {
            bots.add(it)
            it.connect()
        }
    }

    fun await(description: String, timeout: Duration = Duration.ofSeconds(20), condition: () -> Boolean) {
        val deadline = System.nanoTime() + timeout.toNanos()
        while (System.nanoTime() < deadline) {
            check(process?.isAlive == true) { "Paper exited while waiting for $description\n${logTail()}" }
            if (condition()) return
            Thread.sleep(100)
        }
        error("Timed out waiting for $description\n${logTail()}")
    }

    fun awaitLog(text: String, timeout: Duration = Duration.ofSeconds(20)) = await("log: $text", timeout) { currentLog().contains(text) }

    fun stop() {
        val running = process ?: return
        try {
            if (running.isAlive) {
                command("stop")
                check(running.waitFor(30, TimeUnit.SECONDS)) { "Paper did not stop gracefully\n${logTail()}" }
                check(running.exitValue() == 0) { "Paper exited with ${running.exitValue()}\n${logTail()}" }
            }
        } finally {
            if (running.isAlive) kill()
            rcon?.close()
            rcon = null
        }
    }

    fun kill() {
        process?.let { running ->
            if (running.isAlive) {
                running.destroyForcibly()
                check(running.waitFor(10, TimeUnit.SECONDS)) { "Could not kill Paper\n${logTail()}" }
            }
        }
        rcon?.close()
        rcon = null
    }

    fun assertHealthy() {
        val lines = if (log.exists()) log.readText().lines() else emptyList()
        val timestamp = Regex("^\\[\\d{2}:\\d{2}:\\d{2}")
        val incidents = mutableListOf<String>()
        var block = mutableListOf<String>()
        fun inspect() {
            val message = block.joinToString("\n")
            val attributed = message.contains("ketto", ignoreCase = true) || message.contains("net.ninebolt")
            val failure = Regex("ERROR|Exception|Error occurred|Could not load|Failed to (load|enable)", RegexOption.IGNORE_CASE)
            val loadFailure = Regex("(?:Could not|Failed to|Error (?:while|when)) (?:load|enable).*plugin|Error occurred while enabling", RegexOption.IGNORE_CASE)
            if ((attributed && failure.containsMatchIn(message)) || loadFailure.containsMatchIn(message)) incidents.add(message)
        }
        lines.forEach { line ->
            if (timestamp.containsMatchIn(line)) {
                inspect()
                block = mutableListOf()
            }
            block.add(line)
        }
        inspect()
        check(incidents.isEmpty()) { "Ketto server errors in $log:\n${incidents.joinToString("\n")}" }
    }

    fun logTail(): String = if (log.exists()) log.readText().lines().takeLast(80).joinToString("\n") else "No server output at $log"

    private fun currentLog(): String = if (log.exists()) log.readText().drop(startOffset) else ""

    override fun close() {
        try {
            try {
                val failures = bots.map { runCatching(it::close).exceptionOrNull() }.filterNotNull()
                failures.firstOrNull()?.let { first ->
                    failures.drop(1).forEach(first::addSuppressed)
                    throw first
                }
            } finally {
                stop()
            }
        } finally {
            assertHealthy()
        }
    }

    companion object {
        fun requiredProperty(name: String): String = checkNotNull(System.getProperty(name)) { "Missing system property: $name; run ./gradlew e2eTest" }
    }
}
