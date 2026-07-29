package io.github.dlachouette.teamcity.github.report

import io.github.dlachouette.teamcity.github.api.CheckRunAnnotation
import io.github.dlachouette.teamcity.github.api.CheckRunAnnotationLevel

// Turns compiler-style diagnostics into GitHub Check Run annotations, so a
// failure is pinned to the line that caused it in the PR's diff instead of
// only linking out to TeamCity.
//
// The input is the text TeamCity already gives us — build-problem
// descriptions (`SBuild.failureReasons`), which is where TeamCity's compiler
// output processor puts the diagnostic line. No build-log scanning: it would
// cost a lot for a marginal gain, and a diagnostic that never reached a build
// problem is usually not the reason the build failed.
//
// Only diagnostics whose file can be expressed **relative to the checkout
// directory** are emitted: GitHub rejects an annotation whose path is not in
// the repository, and a path outside the checkout is not in it.
object BuildProblemAnnotations {

    // GitHub accepts at most 50 annotations per request.
    const val MAX_ANNOTATIONS: Int = 50

    // GNU/clang: `src/foo.cpp:42:7: error: no member named 'x'`
    private val UNIX = Regex(
        """^\s*(?<path>[^\s:][^:]*?):(?<line>\d+)(?::\d+)?:\s*(?<level>fatal error|error|warning|note)\s*:\s*(?<msg>.+)$""",
        RegexOption.IGNORE_CASE,
    )

    // MSVC: `src\foo.cpp(42,7): error C2065: undeclared identifier`
    private val MSVC = Regex(
        """^\s*(?<path>[^\s(][^(]*?)\((?<line>\d+)(?:,\d+)?\)\s*:\s*(?<level>fatal error|error|warning)\s+\w+\s*:\s*(?<msg>.+)$""",
        RegexOption.IGNORE_CASE,
    )

    // Parse every line of every description, keep the first MAX_ANNOTATIONS
    // distinct (path, line, message) triples — a failing build often repeats
    // the same diagnostic across targets.
    fun parse(descriptions: List<String>, checkoutDir: String?): List<CheckRunAnnotation> {
        val seen = LinkedHashMap<Triple<String, Int, String>, CheckRunAnnotation>()
        for (description in descriptions) {
            for (rawLine in description.lineSequence()) {
                val a = parseLine(rawLine, checkoutDir) ?: continue
                seen.putIfAbsent(Triple(a.path, a.startLine, a.message), a)
                if (seen.size >= MAX_ANNOTATIONS) return seen.values.toList()
            }
        }
        return seen.values.toList()
    }

    // Visible for tests: one diagnostic line, or null when the line is not one
    // (which is the common case — most log lines are not diagnostics).
    fun parseLine(line: String, checkoutDir: String?): CheckRunAnnotation? {
        val m = UNIX.find(line) ?: MSVC.find(line) ?: return null
        val lineNumber = m.groups["line"]!!.value.toIntOrNull()?.takeIf { it > 0 } ?: return null
        val path = relativise(m.groups["path"]!!.value, checkoutDir) ?: return null
        val message = m.groups["msg"]!!.value.trim().takeIf { it.isNotEmpty() } ?: return null
        return CheckRunAnnotation(
            path = path,
            startLine = lineNumber,
            endLine = lineNumber,
            level = levelOf(m.groups["level"]!!.value),
            message = message,
        )
    }

    private fun levelOf(raw: String): CheckRunAnnotationLevel = when {
        raw.equals("warning", ignoreCase = true) -> CheckRunAnnotationLevel.WARNING
        raw.equals("note", ignoreCase = true) -> CheckRunAnnotationLevel.NOTICE
        else -> CheckRunAnnotationLevel.FAILURE
    }

    // Repo-relative path, or null when the diagnostic points outside the
    // checkout (a system header, a toolchain file, an absolute path from
    // another agent). Separators are normalised: TeamCity reports Windows
    // paths with backslashes, GitHub wants forward slashes.
    private fun relativise(rawPath: String, checkoutDir: String?): String? {
        val path = rawPath.trim().replace('\\', '/').removePrefix("./")
        if (path.isEmpty()) return null
        val root = checkoutDir?.trim()?.replace('\\', '/')?.trimEnd('/')

        if (root != null && root.isNotEmpty() && path.startsWith("$root/", ignoreCase = true)) {
            return path.removeRange(0, root.length + 1).takeIf { it.isNotEmpty() }
        }
        // No checkout dir to compare against, or a relative path already: keep
        // it as long as it is not absolute (an absolute path we could not
        // relativise is not in the repository).
        return path.takeIf { !it.startsWith("/") && !ABSOLUTE_WINDOWS.matches(it) }
    }

    private val ABSOLUTE_WINDOWS = Regex("""^[A-Za-z]:/.*""")
}
