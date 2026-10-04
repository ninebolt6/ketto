package net.ninebolt.ketto

import org.junit.jupiter.api.extension.DynamicTestInvocationContext
import org.junit.jupiter.api.extension.ExtensionContext
import org.junit.jupiter.api.extension.InvocationInterceptor
import org.junit.jupiter.api.extension.ReflectiveInvocationContext
import org.mockbukkit.mockbukkit.exception.ReflectionAccessException
import org.mockbukkit.mockbukkit.exception.UnimplementedOperationException
import org.opentest4j.AssertionFailedError
import org.opentest4j.TestAbortedException
import java.lang.reflect.Method

// MockBukkit raises its own limitations as TestAbortedException subclasses, which JUnit reports as "skipped";
// only those are converted into failures, while @Disabled and assumption-based skips stay untouched
class FailOnAbortedTestExtension : InvocationInterceptor {

    override fun interceptBeforeAllMethod(
        invocation: InvocationInterceptor.Invocation<Void?>,
        invocationContext: ReflectiveInvocationContext<Method>,
        extensionContext: ExtensionContext,
    ) {
        failOnAbort(invocation)
    }

    override fun interceptBeforeEachMethod(
        invocation: InvocationInterceptor.Invocation<Void?>,
        invocationContext: ReflectiveInvocationContext<Method>,
        extensionContext: ExtensionContext,
    ) {
        failOnAbort(invocation)
    }

    override fun interceptTestMethod(
        invocation: InvocationInterceptor.Invocation<Void?>,
        invocationContext: ReflectiveInvocationContext<Method>,
        extensionContext: ExtensionContext,
    ) {
        failOnAbort(invocation)
    }

    override fun interceptTestTemplateMethod(
        invocation: InvocationInterceptor.Invocation<Void?>,
        invocationContext: ReflectiveInvocationContext<Method>,
        extensionContext: ExtensionContext,
    ) {
        failOnAbort(invocation)
    }

    override fun interceptDynamicTest(
        invocation: InvocationInterceptor.Invocation<Void?>,
        invocationContext: DynamicTestInvocationContext,
        extensionContext: ExtensionContext,
    ) {
        failOnAbort(invocation)
    }

    override fun interceptAfterEachMethod(
        invocation: InvocationInterceptor.Invocation<Void?>,
        invocationContext: ReflectiveInvocationContext<Method>,
        extensionContext: ExtensionContext,
    ) {
        failOnAbort(invocation)
    }

    override fun interceptAfterAllMethod(
        invocation: InvocationInterceptor.Invocation<Void?>,
        invocationContext: ReflectiveInvocationContext<Method>,
        extensionContext: ExtensionContext,
    ) {
        failOnAbort(invocation)
    }

    override fun <T> interceptTestFactoryMethod(
        invocation: InvocationInterceptor.Invocation<T>,
        invocationContext: ReflectiveInvocationContext<Method>,
        extensionContext: ExtensionContext,
    ): T = failOnAbort(invocation)

    private fun <T> failOnAbort(invocation: InvocationInterceptor.Invocation<T>): T = try {
        invocation.proceed()
    } catch (e: TestAbortedException) {
        if (e is UnimplementedOperationException || e is ReflectionAccessException) {
            throw AssertionFailedError("Unimplemented MockBukkit API must not silently skip a test: ${e.message}", e)
        }
        throw e
    }
}
