package io.github.dlachouette.teamcity.github.report

import io.github.dlachouette.teamcity.github.api.CheckRunAnnotation
import io.github.dlachouette.teamcity.github.api.CheckRunAnnotationLevel

// Turns compiler-style diagnostics into GitHub Check Run annotations, so a
// failure is pinned to the line that caused it in the PR's diff instead of
// only linking out to TeamCity.
//
// Two inputs, in order of cost:
//
//  1. **Build-problem descriptions** (`SBuild.failureReasons`) — where
//     TeamCity's compiler output processor puts the diagnostic, for the runners
//     that have one (MSBuild, Visual Studio, IDEA-based).
//  2. **The failed build's log**, when the first said nothing. This is not a
//     luxury: a **Command Line** runner reports one build problem, "Process
//     exited with code 1", and the compiler's own output never becomes a build
//     problem at all — so for a CMake/ninja C++ build, which is the shape that
//     needs annotations most, the first input is always empty.
//
// Only diagnostics whose file can be expressed **relative to the checkout
// directory** are emitted: GitHub rejects an annotation whose path is not in
// the repository, and a path outside the checkout is not in it.
object BuildProblemAnnotations {

    // GitHub accepts at most 50 annotations per request.
    const val MAX_ANNOTATIONS: Int = 50

    // How many log lines a scan looks at before giving up. A broken build shows
    // its first error early; a build that has produced 200 000 lines without one
    // matching diagnostic is not going to.
    const val MAX_SCANNED_LINES: Int = 200_000

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
    fun parse(descriptions: List<String>, checkoutDir: String?): List<CheckRunAnnotation> =
        collect(descriptions.asSequence().flatMap { it.lineSequence() }, checkoutDir, Int.MAX_VALUE)

    // Same, over a lazy sequence of log lines and with a line budget.
    //
    // The sequence must stay lazy all the way to the caller's iterator: that is
    // what keeps this from reading a 2 GB build log to find an error on line 900.
    fun scan(lines: Sequence<String>, checkoutDir: String?, maxLines: Int = MAX_SCANNED_LINES): List<CheckRunAnnotation> =
        collect(lines.flatMap { it.lineSequence() }, checkoutDir, maxLines)

    private fun collect(lines: Sequence<String>, checkoutDir: String?, maxLines: Int): List<CheckRunAnnotation> {
        val seen = LinkedHashMap<Triple<String, Int, String>, CheckRunAnnotation>()
        // `take` and not a counter inside the loop: a counter would already have
        // pulled the line past the budget out of the iterator.
        for (rawLine in lines.take(maxLines)) {
            val a = parseLine(rawLine, checkoutDir) ?: continue
            seen.putIfAbsent(Triple(a.path, a.startLine, a.message), a)
            if (seen.size >= MAX_ANNOTATIONS) break
        }
        return seen.values.toList()
    }

    // Visible for tests: one diagnostic line, or null when the line is not one
    // (which is the common case — most log lines are not diagnostics).
    fun parseLine(line: String, checkoutDir: String?): CheckRunAnnotation? {
        val m = UNIX.find(line) ?: MSVC.find(line) ?: return null
        val lineNumber = m.groups["line"]!!.value.toIntOrNull()?.takeIf { it > 0 } ?: return null
        val path = relativise(m.groups["path"]!!.value, checkoutDir) ?: return null
        val message = cleanMessage(m.groups["msg"]!!.value) ?: return null
        return CheckRunAnnotation(
            path = path,
            startLine = lineNumber,
            endLine = lineNumber,
            level = levelOf(m.groups["level"]!!.value),
            message = message,
        )
    }

    // The diagnostic text, minus what the build system appended to it.
    //
    // MSBuild ends every line with the project that was building —
    // `… [D:\BuildAgent\work\…\greeter.vcxproj]` — an absolute path on an agent,
    // meaningless to a reviewer reading the annotation on their diff, and long
    // enough to push the actual message out of view.
    private fun cleanMessage(raw: String): String? =
        raw.replace(MSBUILD_PROJECT_SUFFIX, "").trim().takeIf { it.isNotEmpty() }

    // A trailing bracketed path to a build-system project file. Anchored to the
    // end and requiring the extension, so a diagnostic that legitimately ends
    // in brackets (`[-Wunused-variable]`) is untouched.
    private val MSBUILD_PROJECT_SUFFIX =
        Regex("""\s*\[[^\[\]]+\.(vcx|cs|vb|fs|sql|nd)?proj(\.metaproj)?\]\s*$""", RegexOption.IGNORE_CASE)

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
