import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi

plugins {
    kotlin("jvm") version "2.4.20"
    kotlin("plugin.power-assert") version "2.4.20"
}

group = "net.ninebolt"
version = "1.0.0"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT")
    // Paper が実行時提供するが、推移依存への暗黙依存を避けるため明示する
    compileOnly("net.kyori:adventure-text-minimessage:4.17.0")

    // MockBukkit は bukkit 提供側(paper-api)より先に置く必要がある
    // manifest の Paper-Version(1.21.11)と組み合わせる
    testImplementation("org.mockbukkit.mockbukkit:mockbukkit-v1.21:4.103.1")
    testImplementation("io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT")
    testImplementation("net.kyori:adventure-text-serializer-plain:4.17.0")
    testImplementation(kotlin("test-junit5"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.13.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.13.4")
    testImplementation("io.mockk:mockk:1.14.11")
    testImplementation("com.tngtech.archunit:archunit:1.5.0")
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        allWarningsAsErrors = true
        freeCompilerArgs.add("-Wextra")
        freeCompilerArgs.add("-Xconsistent-data-class-copy-visibility")
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
    inputs.property("version", projectVersion)
    filesMatching("plugin.yml") {
        expand("version" to projectVersion)
    }
}

tasks.jar {
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    from(configurations.runtimeClasspath.get().map {
        if (it.isDirectory) it else zipTree(it)
    })
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")
}
