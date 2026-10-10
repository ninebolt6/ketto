import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.power.assert)
    alias(libs.plugins.ktlint.gradle)
    alias(libs.plugins.kover)
}

group = "net.ninebolt"
// Release builds pass -Prelease; the pushed tag must equal "v" + version
version = "1.0.0-alpha.1" + if (hasProperty("release")) "" else "-SNAPSHOT"

val paperNext = providers.gradleProperty("paperNext").isPresent

// Paper 1.21.x servers run on Java 21; Paper 26.x servers run on Java 25
val jvmRelease = if (paperNext) 25 else 21

val mcApiVersion = "1.21.11"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
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

@OptIn(ExperimentalKotlinGradlePluginApi::class)
powerAssert {
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
