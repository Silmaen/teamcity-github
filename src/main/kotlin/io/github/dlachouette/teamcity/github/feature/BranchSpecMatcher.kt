package io.github.dlachouette.teamcity.github.feature

import java.util.regex.Pattern

// Parses a TC-style branch filter spec, one rule per line.
//   +:pattern  -> include pattern
//   -:pattern  -> exclude pattern
//   pattern    -> include pattern (bare line; same as +:)
//   # comment  -> ignored
//   (blank)    -> ignored
//
// Pattern grammar: glob-like.
//   *   matches any sequence of characters (including '/')
//   ?   matches exactly one character
//   /…/ enclosed in slashes => interpreted as a literal Java regex
//
// matches(branch) returns true iff:
//   - no rule at all          -> ALL branches match (default-open)
//   - only exclude rules      -> match unless excluded
//   - any include rules       -> must match at least one include AND
//                                not match any exclude
//
// matches(branchName, headRefIfPr) uses the headRef for `pull/*`
// branches and the branch name directly otherwise: TC's PR branches
// are named `pull/N` but operators write filter rules against the
// PR's source branch ("Feature/foo") because that's what GitHub
// shows them.
class BranchSpecMatcher private constructor(
    private val includePatterns: List<Pattern>,
    private val excludePatterns: List<Pattern>,
    private val rawSpec: String,
) {
    fun matches(branchName: String): Boolean {
        if (excludePatterns.any { it.matcher(branchName).matches() }) return false
        if (includePatterns.isEmpty()) return true
        return includePatterns.any { it.matcher(branchName).matches() }
    }

    // For PR-style branches (`pull/N`), match against the PR's source
    // branch (headRef) when available; falls back to the raw branch
    // name otherwise so a PR with an unresolved headRef still
    // participates in matching.
    fun matches(branchName: String, headRefIfPr: String?): Boolean {
        val target = if (branchName.startsWith("pull/") && !headRefIfPr.isNullOrBlank()) {
            headRefIfPr
        } else {
            branchName
        }
        return matches(target)
    }

    fun isEmpty(): Boolean = includePatterns.isEmpty() && excludePatterns.isEmpty()

    fun asString(): String = rawSpec

    companion object {
        fun parse(spec: String?): BranchSpecMatcher {
            if (spec.isNullOrBlank()) return BranchSpecMatcher(emptyList(), emptyList(), spec.orEmpty())
            val include = mutableListOf<Pattern>()
            val exclude = mutableListOf<Pattern>()
            spec.lineSequence()
                .map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("#") }
                .forEach { line ->
                    when {
                        line.startsWith("+:") -> include.add(parsePattern(line.substring(2)))
                        line.startsWith("-:") -> exclude.add(parsePattern(line.substring(2)))
                        else -> include.add(parsePattern(line))
                    }
                }
            return BranchSpecMatcher(include, exclude, spec)
        }

        // Returns null if the spec parses cleanly; otherwise a human-
        // readable error string for use by the BuildFeature's
        // parameters processor.
        fun validate(spec: String?): String? {
            if (spec.isNullOrBlank()) return null
            val errors = mutableListOf<String>()
            spec.lineSequence()
                .map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("#") }
                .forEachIndexed { idx, line ->
                    val pattern = when {
                        line.startsWith("+:") -> line.substring(2)
                        line.startsWith("-:") -> line.substring(2)
                        else -> line
                    }
                    if (pattern.isBlank()) {
                        errors.add("Line ${idx + 1}: empty pattern")
                    } else if (pattern.startsWith("/") && pattern.endsWith("/") && pattern.length >= 2) {
                        // explicit regex form; validate it compiles
                        try {
                            Pattern.compile(pattern.substring(1, pattern.length - 1))
                        } catch (e: Exception) {
                            errors.add("Line ${idx + 1}: invalid regex (${e.message})")
                        }
                    }
                }
            return if (errors.isEmpty()) null else errors.joinToString("; ")
        }

        private fun parsePattern(raw: String): Pattern {
            val pattern = raw.trim()
            if (pattern.startsWith("/") && pattern.endsWith("/") && pattern.length >= 2) {
                return Pattern.compile(pattern.substring(1, pattern.length - 1))
            }
            val sb = StringBuilder()
            sb.append('^')
            for (c in pattern) {
                when (c) {
                    '*' -> sb.append(".*")
                    '?' -> sb.append('.')
                    '.', '(', ')', '[', ']', '{', '}', '|', '+', '\\', '^', '$' -> sb.append('\\').append(c)
                    else -> sb.append(c)
                }
            }
            sb.append('$')
            return Pattern.compile(sb.toString())
        }
    }
}
