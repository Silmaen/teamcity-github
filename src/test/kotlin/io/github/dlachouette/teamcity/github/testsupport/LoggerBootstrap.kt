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
            Logger.setFactory(Logger.Factory { category -> DefaultLogger(category) })
        } catch (_: Throwable) {
            // already installed or running inside a host that owns logging
        }
    }

    fun install() = Unit
}
