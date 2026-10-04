package net.ninebolt.ketto

import org.junit.jupiter.api.Test
import org.mockbukkit.mockbukkit.MockBukkit
import java.io.File
import java.util.jar.JarFile
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class DependencyAlignmentTest {

    @Test
    fun `mockbukkit is built for the paper version in the catalog`() {
        val mockbukkitJar = File(MockBukkit::class.java.protectionDomain.codeSource.location.toURI())
        val builtFor = JarFile(mockbukkitJar).use { it.manifest.mainAttributes.getValue("Paper-Version") }

        val paperJar = assertNotNull(
            System.getProperty("java.class.path").split(File.pathSeparator)
                .map(::File)
                .firstOrNull { it.name.startsWith("paper-api-") && it.extension == "jar" },
            "paper-api jar not found on the test classpath",
        )
        val paperVersion = paperJar.name.removePrefix("paper-api-").removeSuffix(".jar")

        assertEquals(paperVersion, builtFor)
    }
}
