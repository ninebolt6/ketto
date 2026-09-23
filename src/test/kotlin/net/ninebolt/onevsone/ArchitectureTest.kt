package net.ninebolt.onevsone

import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Static checks over compiled production and test bytecode. Inner-layer rules
 * use a whitelist so new external dependencies are caught; test rules protect
 * the same boundaries at the test seam.
 */
class ArchitectureTest {

    private val classes by lazy {
        ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("net.ninebolt.onevsone")
    }

    private val testClasses by lazy {
        ClassFileImporter().importPath("build/classes/kotlin/test")
    }

    // Allow compiler-generated references such as java.lang, kotlin.jvm.internal, @NotNull
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
    fun `infrastructure only references repository ports it implements`() {
        // Repositories are the persistence seam: application services consume
        // them and adapters implement them; other infrastructure must not.
        val offenders = classes
            .filter { it.packageName.startsWith("net.ninebolt.onevsone.infrastructure") }
            .flatMap { clazz ->
                val implemented = clazz.allRawInterfaces.map { it.name }.toSet()
                clazz.directDependenciesFromSelf
                    .map { it.targetClass }
                    .filter {
                        it.packageName == "net.ninebolt.onevsone.application.port" &&
                            it.simpleName.endsWith("Repository") &&
                            it.name !in implemented
                    }
                    .map { "${clazz.name} -> ${it.name}" }
            }
        assertTrue(offenders.isEmpty(), "repository ports consumed without implementing them: $offenders")
    }

    @Test
    fun `domain and application tests stay independent of infrastructure and platform APIs`() {
        noClasses().that().resideInAnyPackage(
            "net.ninebolt.onevsone.domain..",
            "net.ninebolt.onevsone.application.."
        ).should().dependOnClassesThat().resideInAnyPackage(
            "net.ninebolt.onevsone.infrastructure..",
            "org.bukkit..",
            "io.papermc.paper..",
            "net.kyori..",
            "org.mockbukkit..",
            "io.mockk.."
        ).check(testClasses)
    }

    @Test
    fun `infrastructure tests use events instead of calling progression entrypoints`() {
        val forbiddenNames = setOf("defeat", "quit", "restorePending")
        // Kotlin appends a hash to JVM method names that accept value classes.
        val directCalls = testClasses
            .filter { it.packageName.startsWith("net.ninebolt.onevsone.infrastructure") }
            .flatMap { it.methodCallsFromSelf }
            .filter {
                it.targetOwner.name == "net.ninebolt.onevsone.application.ArenaApplicationService" &&
                    it.name.substringBefore('-') in forbiddenNames
            }
        assertTrue(directCalls.isEmpty(), "progression entrypoints called directly: $directCalls")
    }

    @Test
    fun `tests use kotlin test assertions`() {
        noClasses().should().dependOnClassesThat()
            .haveFullyQualifiedName("org.junit.jupiter.api.Assertions")
            .check(testClasses)
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
