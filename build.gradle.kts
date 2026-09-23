import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.power.assert)
}

group = "net.ninebolt"
// Release builds override with -PreleaseVersion=<tag without "v">
version = (findProperty("releaseVersion") as String?) ?: "1.0.0"

val mcApiVersion = "1.21.3"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    compileOnly(libs.paper.api)
    // Provided by Paper at runtime; declared explicitly to avoid implicit reliance on transitive deps
    compileOnly(libs.adventure.minimessage)

    // MockBukkit must come before the bukkit provider (paper-api)
    // pairs with the Paper-Version (1.21.11) in the manifest
    testImplementation(libs.mockbukkit)
    testImplementation(libs.paper.api)
    testImplementation(libs.adventure.serializer.plain)
    testImplementation(libs.kotlin.test.junit5)
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
    testImplementation(libs.mockk)
    testImplementation(libs.archunit)
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
    from(configurations.runtimeClasspath.get().map {
        if (it.isDirectory) it else zipTree(it)
    })
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")
}
