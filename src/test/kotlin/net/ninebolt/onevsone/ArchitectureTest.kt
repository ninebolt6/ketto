package net.ninebolt.onevsone

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * 依存方向の静的検査。追加ライブラリなしでソーステキストを走査し、
 * domain/application から Bukkit・Adventure・YAML・ファイル I/O・
 * infrastructure 参照への漏れを検出する。完全修飾参照も対象。
 */
class ArchitectureTest {

    private val sourceRoot = File("src/main/kotlin/net/ninebolt/onevsone")

    private val domainDir = File(sourceRoot, "domain")
    private val applicationDir = File(sourceRoot, "application")

    private val forbiddenInInnerLayers = listOf(
        "org.bukkit",
        "io.papermc",
        "net.kyori",
        "YamlConfiguration",
        "java.io.File",
        "java.nio.file",
        "net.ninebolt.onevsone.infrastructure"
    )

    private fun kotlinFiles(dir: File): List<File> =
        dir.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

    private fun violations(dir: File): List<String> {
        val result = mutableListOf<String>()
        kotlinFiles(dir).forEach { file ->
            file.readLines().forEachIndexed { index, line ->
                forbiddenInInnerLayers.forEach { token ->
                    if (line.contains(token)) {
                        result.add("${file.path}:${index + 1}: '$token' in '${line.trim()}'")
                    }
                }
            }
        }
        return result
    }

    @Test
    fun `domain has no forbidden references`() {
        val found = violations(domainDir)
        assertTrue(found.isEmpty(), "domain layer violations:\n" + found.joinToString("\n"))
    }

    @Test
    fun `application has no forbidden references`() {
        val found = violations(applicationDir)
        assertTrue(found.isEmpty(), "application layer violations:\n" + found.joinToString("\n"))
    }

    @Test
    fun `plugin main class stays at original FQCN`() {
        val main = File(sourceRoot, "OneVsOnePlugin.kt")
        assertTrue(main.isFile, "OneVsOnePlugin.kt must exist at the root package")
        assertTrue(
            main.readText().contains("class OneVsOnePlugin : JavaPlugin"),
            "OneVsOnePlugin must remain a JavaPlugin"
        )
    }
}
