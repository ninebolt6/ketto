import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.powerassert.gradle.PowerAssertCompilationFilter
import xyz.jpenilla.runpaper.task.RunServer

plugins {
    `jvm-test-suite`
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.power.assert)
    alias(libs.plugins.ktlint.gradle)
    alias(libs.plugins.kover)
    alias(libs.plugins.run.paper)
}

abstract class PreparePaper : RunServer() {
    @get:OutputFile
    abstract val paperJar: RegularFileProperty

    @TaskAction
    override fun exec() {
        super.exec()
        val jar = classpath.singleFile
        val target = paperJar.get().asFile
        jar.copyTo(target, overwrite = true)
        val resolved = "Paper ${version.get()} build ${jar.nameWithoutExtension}"
        logger.lifecycle(resolved)
        target.resolveSibling("paper-build.txt").writeText("$resolved\n")
    }
}

val paperNext = providers.gradleProperty("paperNext").isPresent
val (e2ePaperVersion, e2ePaperBuild) = if (paperNext) {
    val version = libs.versions.next.paper.get()
    val match = requireNotNull(Regex("""(\d+\.\d+(?:\.\d+)?)\.build\.(\d+)-stable""").matchEntire(version)) {
        "Invalid next-paper version '$version': expected <version>.build.<build>-stable"
    }
    match.groupValues[1] to match.groupValues[2]
} else {
    val version = libs.versions.paper.get()
    val match = requireNotNull(Regex("""(\d+\.\d+(?:\.\d+)?)-R\d+\.\d+-SNAPSHOT""").matchEntire(version)) {
        "Invalid paper version '$version': expected <version>-R<revision>-SNAPSHOT"
    }
    match.groupValues[1] to null
}
val e2ePaperDirectory = layout.buildDirectory.dir("paper-e2e/$e2ePaperVersion")

val e2ePrepare = tasks.register<PreparePaper>("e2ePrepare") {
    group = "verification"
    description = "Prepare Paper for E2E tests using run-paper"
    minecraftVersion(e2ePaperVersion)
    e2ePaperBuild?.let { build(it.toInt()) }
    runDirectory.set(e2ePaperDirectory)
    paperJar.set(e2ePaperDirectory.map { it.file("paper.jar") })
    systemProperty("paperclip.patchonly", "true")
    outputs.upToDateWhen { false }
}

testing {
    suites {
        register<JvmTestSuite>("e2eTest") {
            useJUnitJupiter(libs.versions.junit.get())
            dependencies {
                implementation(libs.kotlin.test.junit5)
                implementation(if (paperNext) libs.next.mcprotocollib else libs.mcprotocollib)
                implementation(libs.adventure.serializer.plain)
                runtimeOnly(libs.sqlite.jdbc)
                runtimeOnly(libs.junit.platform.launcher)
            }
            targets.all {
                testTask.configure {
                    dependsOn(tasks.jar, e2ePrepare)
                    systemProperty("ketto.e2e.pluginJar", tasks.jar.get().archiveFile.get().asFile.absolutePath)
                    systemProperty("ketto.e2e.paperDirectory", e2ePaperDirectory.get().asFile.absolutePath)
                    systemProperty("ketto.e2e.directory", layout.buildDirectory.dir("e2e").get().asFile.absolutePath)
                    outputs.upToDateWhen { false }
                    maxParallelForks = 1
                    testLogging { events("passed", "failed", "skipped") }
                }
            }
        }
    }
}

group = "net.ninebolt"
// Release builds pass -Prelease; the pushed tag must equal "v" + version
version = "1.0.0-alpha.1" + if (hasProperty("release")) "" else "-SNAPSHOT"

// Paper 1.21.x servers run on Java 21; Paper 26.x servers run on Java 25
val jvmRelease = if (paperNext) 25 else 21

val mcApiVersion = "1.21.11"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://repo.opencollab.dev/maven-releases/") {
        content {
            includeGroup("org.geysermc.mcprotocollib")
            includeGroup("org.cloudburstmc")
            includeGroup("org.cloudburstmc.math")
            includeGroup("com.nukkitx.fastutil")
        }
    }
}

dependencies {
    compileOnly(if (paperNext) libs.next.paper.api else libs.paper.api)
    // Provided by Paper at runtime; declared explicitly to avoid implicit reliance on transitive deps
    compileOnly(libs.adventure.minimessage)

    testImplementation(if (paperNext) libs.next.mockbukkit else libs.mockbukkit)
    testImplementation(if (paperNext) libs.next.paper.api else libs.paper.api)
    testImplementation(libs.adventure.serializer.plain)
    testImplementation(libs.kotlin.test.junit5)
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
    testImplementation(libs.mockk)
    testImplementation(libs.archunit)
    testRuntimeOnly(libs.sqlite.jdbc)
}

kotlin {
    sourceSets.named("e2eTest") {
        kotlin.srcDir(if (paperNext) "src/e2eTest/kotlin-paperNext" else "src/e2eTest/kotlin-paper")
    }
    compilerOptions {
        allWarningsAsErrors = true
        jvmTarget = JvmTarget.fromTarget(jvmRelease.toString())
        freeCompilerArgs.addAll(
            "-Wextra",
            "-Xjspecify-annotations=strict",
            "-Xconsistent-data-class-copy-visibility",
            "-Xjdk-release=$jvmRelease",
        )
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.release = jvmRelease
}

ktlint {
    version.set(libs.versions.ktlint.engine.get())
}

kover {
    currentProject {
        sources {
            excludedSourceSets.add("e2eTest")
        }
        instrumentation {
            disabledForTestTasks.add("e2eTest")
        }
    }
}

@OptIn(ExperimentalKotlinGradlePluginApi::class)
powerAssert {
    compilationFilter.set(PowerAssertCompilationFilter { it.name == "test" || it.name == "e2eTest" })
    functions = listOf(
        "kotlin.assert",
        "kotlin.test.assertEquals",
        "kotlin.test.assertNotEquals",
        "kotlin.test.assertTrue",
        "kotlin.test.assertFalse",
        "kotlin.test.assertNull",
        "kotlin.test.assertNotNull",
        "kotlin.test.assertSame",
        "kotlin.test.assertNotSame",
        "kotlin.test.assertContentEquals",
        "kotlin.test.assertFailsWith",
    )
}

tasks.test {
    useJUnitPlatform()
    finalizedBy("koverXmlReport")
}

tasks.processResources {
    val projectVersion = project.version.toString()
    val apiVersion = mcApiVersion
    inputs.property("version", projectVersion)
    inputs.property("apiVersion", apiVersion)
    filesMatching("plugin.yml") {
        expand("version" to projectVersion, "apiVersion" to apiVersion)
    }
}

tasks.jar {
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    from(
        configurations.runtimeClasspath.get().map {
            if (it.isDirectory) it else zipTree(it)
        },
    )
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")
}

tasks.register("printVersion") {
    val v = project.version.toString()
    doLast {
        println(v)
    }
}
