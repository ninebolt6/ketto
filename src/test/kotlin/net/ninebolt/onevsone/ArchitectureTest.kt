package net.ninebolt.onevsone

import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * 依存方向の静的検査。コンパイル済みバイトコードを ArchUnit で解析し、
 * domain/application が許可パッケージ以外に依存しないことを強制する。
 * ブラックリストではなくホワイトリストなので、Bukkit・YAML・infrastructure
 * 以外の新たな外部依存の混入も検出できる。
 */
class ArchitectureTest {

    private val classes by lazy {
        ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("net.ninebolt.onevsone")
    }

    // java.lang や kotlin.jvm.internal、@NotNull 等のコンパイラ生成参照を許容する
    private val jdkPackages = arrayOf("java..", "kotlin..", "org.jetbrains..")

    @Test
    fun `domain depends only on itself and the jdk`() {
        noClasses().that().resideInAPackage("net.ninebolt.onevsone.domain..")
            .should().dependOnClassesThat()
            .resideOutsideOfPackages("net.ninebolt.onevsone.domain..", *jdkPackages)
            .check(classes)
    }

    @Test
    fun `application depends only on itself domain and the jdk`() {
        noClasses().that().resideInAPackage("net.ninebolt.onevsone.application..")
            .should().dependOnClassesThat()
            .resideOutsideOfPackages(
                "net.ninebolt.onevsone.application..",
                "net.ninebolt.onevsone.domain..",
                *jdkPackages
            ).check(classes)
    }

    @Test
    fun `inner layers do not touch file io`() {
        noClasses().that()
            .resideInAnyPackage("net.ninebolt.onevsone.domain..", "net.ninebolt.onevsone.application..")
            .should().dependOnClassesThat().resideInAnyPackage("java.io..", "java.nio..")
            .check(classes)
    }

    @Test
    fun `plugin main class stays at original FQCN`() {
        val main = File("src/main/kotlin/net/ninebolt/onevsone/OneVsOnePlugin.kt")
        assertTrue(main.isFile, "OneVsOnePlugin.kt must exist at the root package")
        assertTrue(
            main.readText().contains("class OneVsOnePlugin : JavaPlugin"),
            "OneVsOnePlugin must remain a JavaPlugin"
        )
    }
}
