package net.ninebolt.ketto

import com.tngtech.archunit.base.DescribedPredicate
import com.tngtech.archunit.core.domain.JavaClass
import com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage
import com.tngtech.archunit.core.domain.JavaClasses
import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noFields
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertTrue

class ArchitectureTest {

    companion object {
        // One import keeps dependency resolution shared between main and test rule sets
        private val allClasses: JavaClasses by lazy {
            ClassFileImporter().importPaths("build/classes/kotlin/main", "build/classes/kotlin/test")
        }

        private val inTestOutput = object : DescribedPredicate<JavaClass>("in test output") {
            override fun test(input: JavaClass): Boolean = input.source.map { "/classes/kotlin/test/" in it.uri.toString() }.orElse(false)
        }

        val classes: JavaClasses by lazy { allClasses.that(DescribedPredicate.not(inTestOutput)) }
        val testClasses: JavaClasses by lazy { allClasses.that(inTestOutput) }
    }

    // Allow compiler-generated references such as java.lang, kotlin.jvm.internal, @NotNull
    private val jdkPackages = arrayOf("java..", "kotlin..", "org.jetbrains..")

    @Test
    fun `domain depends only on itself and the jdk`() {
        noClasses().that().resideInAPackage("net.ninebolt.ketto.domain..")
            .should().dependOnClassesThat()
            .resideOutsideOfPackages("net.ninebolt.ketto.domain..", *jdkPackages)
            .check(classes)
    }

    @Test
    fun `application depends only on itself domain and the jdk`() {
        noClasses().that().resideInAPackage("net.ninebolt.ketto.application..")
            .should().dependOnClassesThat()
            .resideOutsideOfPackages(
                "net.ninebolt.ketto.application..",
                "net.ninebolt.ketto.domain..",
                *jdkPackages,
            ).check(classes)
    }

    @Test
    fun `inner layers do not touch file io`() {
        noClasses().that()
            .resideInAnyPackage("net.ninebolt.ketto.domain..", "net.ninebolt.ketto.application..")
            .should().dependOnClassesThat().resideInAnyPackage("java.io..", "java.nio..")
            .check(classes)
    }

    @Test
    fun `inner layers do not touch jdbc or sqlite`() {
        // java.sql sits inside the jdk whitelist, so the database boundary needs a dedicated rule
        noClasses().that()
            .resideInAnyPackage("net.ninebolt.ketto.domain..", "net.ninebolt.ketto.application..")
            .should().dependOnClassesThat().resideInAnyPackage("java.sql..", "javax.sql..", "org.sqlite..")
            .check(classes)
    }

    @Test
    fun `infrastructure only references repository ports it implements`() {
        val offenders = classes
            .filter { it.packageName.startsWith("net.ninebolt.ketto.infrastructure") }
            .flatMap { clazz ->
                val implemented = clazz.allRawInterfaces.map { it.name }.toSet()
                clazz.directDependenciesFromSelf
                    .map { it.targetClass }
                    .filter {
                        it.packageName == "net.ninebolt.ketto.application.port" &&
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
            "net.ninebolt.ketto.domain..",
            "net.ninebolt.ketto.application..",
        ).should().dependOnClassesThat().resideInAnyPackage(
            "net.ninebolt.ketto.infrastructure..",
            "org.bukkit..",
            "io.papermc.paper..",
            "net.kyori..",
            "org.mockbukkit..",
            "io.mockk..",
        ).check(testClasses)
    }

    @Test
    fun `infrastructure tests use events instead of calling progression entrypoints`() {
        val forbiddenNames = setOf("defeat", "quit", "restorePending")
        // Kotlin appends a hash to JVM method names that accept value classes.
        val directCalls = testClasses
            .filter { it.packageName.startsWith("net.ninebolt.ketto.infrastructure") }
            .flatMap { it.methodCallsFromSelf }
            .filter {
                it.targetOwner.name == "net.ninebolt.ketto.application.MatchParticipationService" &&
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
    fun `domain values are immutable`() {
        noFields().that().areDeclaredInClassesThat(resideInAPackage("net.ninebolt.ketto.domain.."))
            .should().notBeFinal()
            .check(classes)
    }

    @Test
    fun `test files mirror a main class`() {
        val mainNames = classes.map { it.simpleName }
            .filter { it.firstOrNull()?.isUpperCase() == true }
            .toSet()
        val metaTests = setOf("ArchitectureTest", "DependencyAlignmentTest")
        val scenarioTests = setOf(
            "MatchScenarioTest",
            "PaperArenaFailureTest",
            "PaperArenaMembershipTest",
            "PaperInventoryRecoveryTest",
            "PaperMatchProgressionTest",
        )
        val offenders = File("src/test/kotlin").walkTopDown()
            .filter { it.isFile && it.name.endsWith("Test.kt") }
            .filterNot { it.nameWithoutExtension in metaTests + scenarioTests }
            .filter { file ->
                val base = file.nameWithoutExtension.removeSuffix("Test")
                "/fixtures/" in file.path ||
                    mainNames.none { base == it || (base.startsWith(it) && base[it.length].isUpperCase()) }
            }
            .map { it.path }
            .toList()
        assertTrue(offenders.isEmpty(), "test files not mirroring a main class: $offenders")
    }

    @Test
    fun `test helpers live in a fixtures package`() {
        val testInfra = setOf("FailOnAbortedTestExtension.kt")
        val offenders = File("src/test/kotlin").walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filterNot { it.name.endsWith("Test.kt") || it.name in testInfra }
            .filterNot { "/fixtures/" in it.path }
            .map { it.path }
            .toList()
        assertTrue(offenders.isEmpty(), "test helper files outside a fixtures package: $offenders")
    }

    @Test
    fun `plugin main class stays at original FQCN`() {
        val main = File("src/main/kotlin/net/ninebolt/ketto/KettoPlugin.kt")
        assertTrue(main.isFile, "KettoPlugin.kt must exist at the root package")
        assertTrue(
            main.readText().contains("class KettoPlugin : JavaPlugin"),
            "KettoPlugin must remain a JavaPlugin",
        )
    }
}
