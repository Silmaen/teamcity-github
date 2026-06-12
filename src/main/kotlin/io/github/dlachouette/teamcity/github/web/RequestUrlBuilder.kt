package io.github.dlachouette.teamcity.github.web

import javax.servlet.http.HttpServletRequest

// Single home for reconstructing the externally-visible absolute URL of
// a plugin endpoint behind TeamCity's reverse proxy. Three web classes
// (AdminConsolePage, WebhookInfoController, AdminTestController) used to
// each carry their own copy of this `X-Forwarded-*` handling, and the
// copies had already drifted (one dropped the blank-guard on the host
// header). Keep the proxy-header logic here only.
object RequestUrlBuilder {

    // Scheme as seen by the external client: honour `X-Forwarded-Proto`
    // (first value if comma-separated), else the servlet scheme.
    fun resolvedScheme(request: HttpServletRequest): String {
        val forwarded = request.getHeader("X-Forwarded-Proto")?.substringBefore(',')?.trim()
        return forwarded?.lowercase()?.takeIf { it.isNotBlank() } ?: request.scheme
    }

    // Absolute URL for `path` (an app-rooted path like
    // "/app/teamcity-github-bridge/webhook"), honouring the proxy's
    // forwarded host/port and eliding the default port for the scheme.
    fun absoluteUrl(request: HttpServletRequest, path: String): String {
        val scheme = resolvedScheme(request)
        val ctx = request.contextPath.trimEnd('/')
        val hostHeader = request.getHeader("X-Forwarded-Host")?.substringBefore(',')?.trim()
        val authority = if (!hostHeader.isNullOrBlank()) {
            hostHeader
        } else {
            val name = request.serverName
            val port = request.getHeader("X-Forwarded-Port")?.toIntOrNull() ?: request.serverPort
            val portPart = when {
                scheme == "http" && port == 80 -> ""
                scheme == "https" && port == 443 -> ""
                else -> ":$port"
            }
            "$name$portPart"
        }
        return "$scheme://$authority$ctx$path"
    }
}
