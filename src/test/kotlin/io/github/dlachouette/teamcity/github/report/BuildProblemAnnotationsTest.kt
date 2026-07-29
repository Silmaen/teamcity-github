package io.github.dlachouette.teamcity.github.report

import io.github.dlachouette.teamcity.github.api.CheckRunAnnotationLevel
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

// G10: compiler diagnostics become Check Run annotations, pinned to the file
// and line in the PR's diff.
class BuildProblemAnnotationsTest {

    private val checkout = "/opt/agent/work/a1b2c3"

    // --- clang / gcc

    @Test
    fun `parses a clang error with column`() {
        val a = BuildProblemAnnotations.parseLine(
            "/opt/agent/work/a1b2c3/src/render/ray.cpp:42:7: error: no member named 'trace'",
            checkout,
        )!!
        assertEquals("src/render/ray.cpp", a.path)
        assertEquals(42, a.startLine)
        assertEquals(42, a.endLine)
        assertEquals(CheckRunAnnotationLevel.FAILURE, a.level)
        assertEquals("no member named 'trace'", a.message)
    }

    @Test
    fun `maps the diagnostic level`() {
        fun level(text: String) = BuildProblemAnnotations.parseLine("src/a.cpp:1: $text: m", checkout)?.level
        assertEquals(CheckRunAnnotationLevel.FAILURE, level("error"))
        assertEquals(CheckRunAnnotationLevel.FAILURE, level("fatal error"))
        assertEquals(CheckRunAnnotationLevel.WARNING, level("warning"))
        assertEquals(CheckRunAnnotationLevel.NOTICE, level("note"))
    }

    // --- MSVC

    @Test
    fun `parses an MSVC diagnostic and normalises the separators`() {
        val a = BuildProblemAnnotations.parseLine(
            """C:\agent\work\a1b2c3\src\win\dialog.cpp(88,12): error C2065: undeclared identifier""",
            """C:\agent\work\a1b2c3""",
        )!!
        assertEquals("src/win/dialog.cpp", a.path)
        assertEquals(88, a.startLine)
        assertEquals(CheckRunAnnotationLevel.FAILURE, a.level)
        assertEquals("undeclared identifier", a.message)
    }

    // --- what must NOT produce an annotation

    @Test
    fun `an ordinary log line is not a diagnostic`() {
        listOf(
            "Process exited with code 1",
            "[Step 2/3] Compiling...",
            "make: *** [all] Error 2",
            "",
        ).forEach { assertNull(BuildProblemAnnotations.parseLine(it, checkout), "line=$it") }
    }

    @Test
    fun `a file outside the checkout is skipped`() {
        // A system header or a toolchain file is not in the repository, and
        // GitHub rejects an annotation whose path is not.
        assertNull(
            BuildProblemAnnotations.parseLine("/usr/include/c++/13/vector:214:5: error: template argument", checkout),
        )
        assertNull(
            BuildProblemAnnotations.parseLine("""C:\VS\include\xmemory(120): error C2338: bad""", checkout),
        )
    }

    @Test
    fun `a relative path is kept as is`() {
        assertEquals(
            "src/a.cpp",
            BuildProblemAnnotations.parseLine("./src/a.cpp:3: warning: unused", checkout)?.path,
        )
        // Also when no checkout directory is known.
        assertEquals(
            "src/a.cpp",
            BuildProblemAnnotations.parseLine("src/a.cpp:3: warning: unused", null)?.path,
        )
    }

    @Test
    fun `a line number must be a positive integer`() {
        assertNull(BuildProblemAnnotations.parseLine("src/a.cpp:0: error: nope", checkout))
        assertNull(BuildProblemAnnotations.parseLine("src/a.cpp:x: error: nope", checkout))
    }

    // --- multi-problem parsing

    @Test
    fun `parses every diagnostic line of every description`() {
        val problems = listOf(
            "Compilation error\nsrc/a.cpp:10: error: first\nsrc/b.cpp:20:3: warning: second",
            "src/c.cpp:30: error: third",
        )
        val out = BuildProblemAnnotations.parse(problems, checkout)
        assertEquals(listOf("src/a.cpp", "src/b.cpp", "src/c.cpp"), out.map { it.path })
        assertEquals(listOf(10, 20, 30), out.map { it.startLine })
    }

    @Test
    fun `the same diagnostic repeated across targets is emitted once`() {
        val repeated = List(5) { "src/a.cpp:10: error: same" }
        assertEquals(1, BuildProblemAnnotations.parse(repeated, checkout).size)
    }

    @Test
    fun `the number of annotations is capped at what GitHub accepts`() {
        val many = (1..80).map { "src/a.cpp:$it: error: e$it" }
        assertEquals(
            BuildProblemAnnotations.MAX_ANNOTATIONS,
            BuildProblemAnnotations.parse(many, checkout).size,
        )
    }

    @Test
    fun `a build with no problem produces nothing`() {
        assertEquals(emptyList<Any>(), BuildProblemAnnotations.parse(emptyList(), checkout))
    }
}
