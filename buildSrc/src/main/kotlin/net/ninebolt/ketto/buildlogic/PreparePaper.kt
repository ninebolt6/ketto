package net.ninebolt.ketto.buildlogic

import groovy.json.JsonSlurper
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.time.Duration
import java.util.concurrent.TimeUnit

@CacheableTask
abstract class PreparePaper : DefaultTask() {
    @get:Input
    abstract val paperVersion: Property<String>

    @get:Input
    abstract val paperBuild: Property<String>

    @get:OutputDirectory
    abstract val serverDirectory: DirectoryProperty

    @TaskAction
    fun prepare() {
        val directory = serverDirectory.get().asFile
        directory.mkdirs()
        FileChannel.open(
            directory.resolve("prepare.lock").toPath(),
            StandardOpenOption.CREATE,
            StandardOpenOption.WRITE,
        ).use { channel ->
            channel.lock().use {
                val client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).connectTimeout(Duration.ofSeconds(30)).build()
                val metadata = client.send(
                    request("https://fill.papermc.io/v3/projects/paper/versions/${paperVersion.get()}/builds/${paperBuild.get()}"),
                    HttpResponse.BodyHandlers.ofString(),
                )
                check(metadata.statusCode() == 200) { "Paper metadata HTTP ${metadata.statusCode()}: ${metadata.body()}" }
                val build = JsonSlurper().parseText(metadata.body()) as Map<*, *>
                val download = (build["downloads"] as Map<*, *>)["server:default"] as Map<*, *>
                val expected = (download["checksums"] as Map<*, *>)["sha256"] as String
                val jar = directory.resolve("paper.jar")
                if (!jar.isFile || checksum(jar) != expected) {
                    val temporary = directory.resolve("paper.jar.download").toPath()
                    try {
                        val response = client.send(request(download["url"] as String), HttpResponse.BodyHandlers.ofFile(temporary))
                        check(response.statusCode() == 200) { "Paper download HTTP ${response.statusCode()}" }
                        check(checksum(temporary.toFile()) == expected) { "Paper server SHA-256 mismatch" }
                        Files.move(temporary, jar.toPath(), StandardCopyOption.REPLACE_EXISTING)
                    } finally {
                        Files.deleteIfExists(temporary)
                    }
                }
                val marker = directory.resolve("patched.sha256")
                if (!marker.isFile || marker.readText() != expected || !directory.resolve("versions").isDirectory ||
                    !directory.resolve("libraries").isDirectory
                ) {
                    patch(directory, jar)
                    marker.writeText(expected)
                }
            }
        }
    }

    private fun request(url: String): HttpRequest = HttpRequest.newBuilder(URI(url))
        .header("User-Agent", "ketto-e2e/1.0 (https://github.com/ninebolt6/ketto)")
        .timeout(Duration.ofMinutes(5)).build()

    private fun checksum(file: File): String = file.inputStream().use { input ->
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(65536)
        var count = input.read(buffer)
        while (count != -1) {
            digest.update(buffer, 0, count)
            count = input.read(buffer)
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun patch(directory: File, jar: File) {
        val log = directory.resolve("paperclip.log")
        val process = ProcessBuilder(
            File(System.getProperty("java.home"), "bin/java").absolutePath,
            "-Dpaperclip.patchonly=true",
            "-jar",
            jar.absolutePath,
        ).directory(directory).redirectErrorStream(true).redirectOutput(log).start()
        if (!process.waitFor(5, TimeUnit.MINUTES)) {
            process.destroyForcibly().waitFor()
            error("Paperclip timed out: ${log.readText().takeLast(12000)}")
        }
        check(process.exitValue() == 0) { "Paperclip failed: ${log.readText().takeLast(12000)}" }
    }
}
