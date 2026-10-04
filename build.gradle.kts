import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.power.assert)
    alias(libs.plugins.ktlint.gradle)
    alias(libs.plugins.kover)
}

group = "net.ninebolt"
// Release builds pass -Prelease; the pushed tag must equal "v" + version
version = "1.0.0-alpha.1" + if (hasProperty("release")) "" else "-SNAPSHOT"

val mcApiVersion = "1.21.3"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    compileOnly(libs.paper.api)
    // Provided by Paper at runtime; declared explicitly to avoid implicit reliance on transitive deps
    compileOnly(libs.adventure.minimessage)
    compileOnly(libs.sqlite.jdbc)

    testImplementation(libs.mockbukkit)
    testImplementation(libs.paper.api)
    testImplementation(libs.adventure.serializer.plain)
    testImplementation(libs.kotlin.test.junit5)
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
    testImplementation(libs.mockk)
    testImplementation(libs.archunit)
    testImplementation(libs.sqlite.jdbc)
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        allWarningsAsErrors = true
        freeCompilerArgs.addAll(
            "-Wextra",
            "-Xjspecify-annotations=strict",
            "-Xconsistent-data-class-copy-visibility",
        )
    }
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
