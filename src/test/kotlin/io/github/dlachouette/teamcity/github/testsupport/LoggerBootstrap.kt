package io.github.dlachouette.teamcity.github.testsupport

import com.intellij.openapi.diagnostic.DefaultLogger
import com.intellij.openapi.diagnostic.Logger

// IntelliJ openapi Logger.getInstance returns a no-op once a Factory is
// installed. In a real TeamCity server that Factory is installed during
// startup. In unit tests we have to install one ourselves before any
// class with `private val LOG = Logger.getInstance(...)` is loaded.
object LoggerBootstrap {
    init {
        try {
            // `setFactory` is deprecated in the IntelliJ openapi we compile
            // against, but it is the only way to install a factory from a
            // plain unit test — there is no server to do it for us.
            @Suppress("DEPRECATION")
            Logger.setFactory(Logger.Factory { category -> DefaultLogger(category) })
        } catch (_: Throwable) {
            // already installed or running inside a host that owns logging
        }
    }

    fun install() = Unit
}
