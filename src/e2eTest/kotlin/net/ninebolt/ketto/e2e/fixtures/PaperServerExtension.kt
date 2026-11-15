package net.ninebolt.ketto.e2e.fixtures

import org.junit.jupiter.api.extension.AfterEachCallback
import org.junit.jupiter.api.extension.BeforeEachCallback
import org.junit.jupiter.api.extension.ExtensionContext
import org.junit.jupiter.api.extension.ParameterContext
import org.junit.jupiter.api.extension.ParameterResolver
import java.nio.file.Path

class PaperServerExtension :
    BeforeEachCallback,
    AfterEachCallback,
    ParameterResolver {
    private val namespace = ExtensionContext.Namespace.create(PaperServerExtension::class.java)

    override fun beforeEach(context: ExtensionContext) {
        val directory = Path.of(
            PaperServer.requiredProperty("ketto.e2e.directory"),
            context.requiredTestClass.simpleName,
            context.requiredTestMethod.name,
        )
        context.getStore(namespace).put("server", PaperServer(directory))
    }

    override fun afterEach(context: ExtensionContext) {
        context.getStore(namespace).remove("server", PaperServer::class.java)?.close()
    }

    override fun supportsParameter(parameterContext: ParameterContext, extensionContext: ExtensionContext): Boolean = parameterContext.parameter.type == PaperServer::class.java

    override fun resolveParameter(parameterContext: ParameterContext, extensionContext: ExtensionContext): PaperServer = checkNotNull(extensionContext.getStore(namespace).get("server", PaperServer::class.java))
}
