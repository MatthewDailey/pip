package rivc

import rivc.art.PathData
import rivc.art.SubPath
import rivc.spike.CompileException
import java.io.File
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Path morphs between unequal vertex counts, trim paths, non-scaling strokes and keyed draw
 * order, on the `src/test/resources/shapes/` fixture. Run with `RIVC_UPDATE_GOLDENS=1` to
 * rewrite the golden listing after an intended change.
 */
class ShapeGapsTest {
    private val project = File(System.getProperty("rivc.projectDir") ?: ".")
    private val dir = File(project, "src/test/resources/shapes")
    private val source = File(dir, "shapes.json")

    private fun compile(json: String = source.readText()) = compileGraph(PipSource.parse(json), dir)

    private fun dump(json: String = source.readText()) = RivDump.lines(RivReader().read(compile(json).encode()))

    @Test
    fun `the shapes fixture matches its golden listing`() {
        val actual = dump()
        val golden = File(dir, "shapes.dump.txt")
        if (System.getenv("RIVC_UPDATE_GOLDENS") != null || !golden.exists()) golden.writeText(actual.joinToString("\n", postfix = "\n"))
        assertEquals(golden.readLines(), actual, "rerun with RIVC_UPDATE_GOLDENS=1 if the change is intended")
    }

    @Test
    fun `the shapes fixture round-trips through write and read`() {
        val resolved = compile().resolve()
        val bytes = RivWriter.write(resolved)
        val read = RivReader().read(bytes)
        assertEquals(resolved, read)
        assertContentEquals(bytes, RivWriter.write(read))
    }

    @Test
    fun `a morph across 3, 6 and 5 vertices builds the base at 6 and keys all of them`() {
        val lines = dump()
        val blob = lines.indexOfFirst { "Shape" in it && "name=\"blob\"" in it }
        val vertices = lines.drop(blob + 2).takeWhile { "CubicDetachedVertex" in it }
        assertEquals(6, vertices.size)
        val clip = lines.drop(lines.indexOfFirst { "LinearAnimation name=\"go\"" in it })
        // Six vertices, six geometry properties each, three keys per property.
        assertEquals(6 * 6 * 3, morphKeyCount(clip, vertices.size))
    }

    private fun morphKeyCount(clip: List<String>, vertices: Int): Int {
        // The morph's keyed objects come first in the clip, in vertex order.
        var objects = 0
        var keys = 0
        for (l in clip.drop(1)) {
            if ("KeyedObject" in l) { objects++; if (objects > vertices) break; continue }
            if ("KeyFrameDouble" in l) keys++
        }
        return keys
    }

    @Test
    fun `subdividing keeps the curve and spreads the new vertices`() {
        val tri = PathData.parse("M0,0 L90,0 L0,30 Z").single()
        val six = tri.subdivided(6)
        assertEquals(6, six.vertices.size)
        assertTrue(six.closed)
        // Every new vertex lies on the original outline; the short 30-unit edge is never split.
        for (v in six.vertices) assertTrue(onOutline(tri, v.p.x, v.p.y), "vertex ${v.p} is off the triangle")
        assertEquals(2, six.vertices.count { abs(it.p.x) < 1e-9 })
        assertEquals(tri.bounds(), six.bounds())
    }

    private fun onOutline(p: SubPath, x: Double, y: Double): Boolean = p.segments().any { s ->
        val (a, b) = s[0] to s[3]
        val cross = (b.x - a.x) * (y - a.y) - (b.y - a.y) * (x - a.x)
        val len = Math.hypot(b.x - a.x, b.y - a.y)
        abs(cross) / len < 1e-6 && x in minOf(a.x, b.x) - 1e-9..maxOf(a.x, b.x) + 1e-9 && y in minOf(a.y, b.y) - 1e-9..maxOf(a.y, b.y) + 1e-9
    }

    @Test
    fun `a trimmed stroke gets a sequential TrimPath at its data-trim rest values, keyed by the clip`() {
        val lines = dump()
        val trim = lines.single { "TrimPath" in it }
        assertTrue(trim.contains("start=0.0") && trim.contains("end=0.5") && trim.contains("offset=0.0") && trim.contains("modeValue=1"), trim)
        val stroke = lines.indexOfFirst { "Stroke" in it && "thickness=4.0" in it }
        assertTrue(lines.indexOf(trim) > stroke, "the TrimPath follows its stroke")
        // KeyedProperty 115 (end) and 116 (offset) on the TrimPath.
        val clip = lines.drop(lines.indexOfFirst { "LinearAnimation" in it })
        assertTrue(clip.any { it.endsWith("KeyedProperty propertyKey=115") })
        assertTrue(clip.any { it.endsWith("KeyedProperty propertyKey=116") })
    }

    @Test
    fun `a non-scaling stroke is drawn in world space at its SVG width`() {
        val lines = dump()
        val ring = lines.single { "Stroke" in it && "transformAffectsStroke=false" in it }
        assertContains(ring, "thickness=3.0")
        assertEquals(1, lines.count { "transformAffectsStroke" in it })
    }

    @Test
    fun `draw rules place front below or above back and the clip switches them with id keys`() {
        val lines = dump()
        val rules = lines.single { "DrawRules" in it }
        val targets = lines.filter { "DrawTarget" in it }
        assertEquals(2, targets.size)
        assertTrue(targets[0].endsWith("placementValue=1"), targets[0])
        assertTrue(targets[1].endsWith("placementValue=0"), targets[1])
        val ids = lines.filter { "KeyFrameId" in it }
        assertEquals(2, ids.size)
        assertTrue(ids.all { "interpolationType=0" in it })
        assertTrue("parentId=" in rules)
    }

    @Test
    fun `bad shape sources fail with a message naming the item`() {
        fun failure(edit: (String) -> String) = assertFailsWith<CompileException> { compile(edit(source.readText())) }.message!!
        assertContains(
            failure { it.replace("M40,10 L66,25 L66,55 L40,70 L14,55 L14,25 Z", "M40,10 L66,25 L66,55 Z M0,0 L1,1 Z") },
            "has 2 subpaths, 'blob' has 1",
        )
        assertContains(failure { it.replace("\"ref\": \"front/drawRules/above-back\"", "\"ref\": \"front/drawRules/nowhere\"") }, "no art target 'front/drawRules/nowhere'")
        assertContains(failure { it.replace("\"below\": \"back\"", "\"below\": \"front\"") }, "'front' is inside 'front'")
    }
}
