package rivc

import rivc.art.Affine
import rivc.art.ArtPass
import rivc.art.ArtResult
import rivc.art.PathData
import rivc.art.Pt
import rivc.art.SubPath
import rivc.art.SvgGeometry
import rivc.art.SvgImport
import rivc.art.SvgShape
import rivc.spike.CompileException
import java.io.File
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The art pass on small fixture SVGs (`src/test/resources/art/`), checked against golden
 * `rivc dump` listings. Run with `RIVC_UPDATE_GOLDENS=1` to rewrite the listings after an
 * intended change, then review the diff. Visual parity with a browser's SVG renderer is checked
 * outside the unit tests by `packages/rivc-preview/art-compare.mjs`.
 */
class ArtPassTest {
    private val project = File(System.getProperty("rivc.projectDir") ?: ".")
    private val dir = File(project, "src/test/resources/art")

    private fun compile(json: File): List<String> {
        val src = PipSource.load(json)
        return RivDump.lines(RivReader().read(compileGraph(src, json.parentFile).encode()))
    }

    private fun assertGolden(name: String) {
        val actual = compile(File(dir, "$name.json"))
        val golden = File(dir, "$name.dump.txt")
        if (System.getenv("RIVC_UPDATE_GOLDENS") != null || !golden.exists()) golden.writeText(actual.joinToString("\n", postfix = "\n"))
        assertEquals(golden.readLines(), actual, "$name: rerun with RIVC_UPDATE_GOLDENS=1 if the change is intended")
    }

    @Test fun `gradients fixture matches its golden dump`() = assertGolden("gradients")

    @Test fun `clip fixture matches its golden dump`() = assertGolden("clip")

    @Test fun `nested transforms fixture matches its golden dump`() = assertGolden("transforms")

    @Test fun `arcs fixture matches its golden dump`() = assertGolden("arcs")

    private fun emit(svg: String, json: String = """{ "artboard": { "name": "t", "width": 100, "height": 100 }, "art": { "svg": "t.svg" } }"""): Pair<ArtResult, List<String>> {
        val tmp = kotlin.io.path.createTempDirectory("artpass").toFile()
        File(tmp, "t.svg").writeText(svg)
        val src = PipSource.parse(json)
        val g = RivGraph()
        g.add("Backboard")
        val ab = g.add("Artboard") { ArtPass.artboardProperties(this, src.artboard) }
        val warnings = mutableListOf<String>()
        val result = ArtPass { warnings += it }.emit(g, ab, src, tmp)
        return result to RivDump.lines(RivReader().read(g.encode())).also { warnings.forEach(::println) }
    }

    @Test
    fun `arcs land on their ellipse and end where the path says`() {
        // A 90-degree arc of a circle of radius 50 about (50, 50): from (100, 50) to (50, 100).
        val sp = PathData.parse("M 100 50 A 50 50 0 0 1 50 100").single()
        assertEquals(2, sp.vertices.size)
        assertEquals(Pt(50.0, 100.0), sp.vertices.last().p)
        for (t in listOf(0.25, 0.5, 0.75)) {
            val q = SubPath.cubicAt(sp.segments().single(), t)
            assertEquals(50.0, hypot(q.x - 50, q.y - 50), 0.03, "radius at t=$t")
        }
        // Large-arc and sweep flags pick the other three ellipse arcs between the same points.
        val big = PathData.parse("M 0 0 A 10 10 0 1 1 20 0").single()
        assertEquals(3, big.vertices.size, "a 180+ degree arc splits into 90-degree cubics")
        val ys = big.segments().flatMap { s -> (1..9).map { SubPath.cubicAt(s, it / 10.0).y } }
        assertTrue(ys.all { it <= 1e-9 }, "sweep=1 from (0,0) to (20,0) goes over the top (negative y)")
        val other = PathData.parse("M 0 0 A 10 10 0 0 0 20 0").single()
        assertTrue(other.segments().all { s -> SubPath.cubicAt(s, 0.5).y > 0 }, "sweep=0 goes under")
        // A rotated elliptical arc whose radii are too small: scaled up until it fits (a half
        // ellipse), so its midpoint sits on the rotated ellipse about the chord's midpoint.
        val rot = PathData.parse("M220,60a60,30,30,0,1,120,60").single()
        assertEquals(Pt(340.0, 120.0), rot.vertices.last().p)
        val phi = PI / 6
        val c = Pt(280.0, 90.0)
        val pts = rot.segments().flatMap { s -> (0..8).map { SubPath.cubicAt(s, it / 8.0) } }
        // SVG F.6.6: radii scale by sqrt(lambda) when they can't span the chord.
        val x1 = cos(phi) * -60.0 + sin(phi) * -30.0
        val y1 = -sin(phi) * -60.0 + cos(phi) * -30.0
        val lambda = x1 * x1 / 3600 + y1 * y1 / 900
        assertTrue(lambda > 1)
        val rx = 60 * kotlin.math.sqrt(lambda)
        val ry = 30 * kotlin.math.sqrt(lambda)
        for (p in pts) {
            val d = p - c
            val u = (d.x * cos(phi) + d.y * sin(phi)) / rx
            val v = (-d.x * sin(phi) + d.y * cos(phi)) / ry
            assertEquals(1.0, u * u + v * v, 1e-3, "point $p on the ellipse")
        }
    }

    @Test
    fun `path data parses compact numbers, implicit commands and relative forms`() {
        val sp = PathData.parse("M.5.5.5 10m10-10e0 0 10")
        assertEquals(2, sp.size)
        assertEquals(listOf(Pt(0.5, 0.5), Pt(0.5, 10.0)), sp[0].vertices.map { it.p })
        assertEquals(listOf(Pt(10.5, 0.0), Pt(10.5, 10.0)), sp[1].vertices.map { it.p })
        val steps = PathData.parse("M200 290h20v-20h20V290Z").single()
        assertEquals(
            listOf(Pt(200.0, 290.0), Pt(220.0, 290.0), Pt(220.0, 270.0), Pt(240.0, 270.0), Pt(240.0, 290.0)),
            steps.vertices.map { it.p },
        )
        assertTrue(steps.closed)
        // T reflects the previous Q's control point: q20-40 40 0 t40 0 is symmetric about x=60.
        val wave = PathData.parse("M20 240q20-40 40 0t40 0").single()
        assertEquals(Pt(100.0, 240.0), wave.vertices.last().p)
        val mid = wave.vertices[1]
        assertEquals(0.0, (mid.inH - mid.p).x + (mid.outH - mid.p).x, 1e-9)
        assertEquals(0.0, (mid.inH - mid.p).y + (mid.outH - mid.p).y, 1e-9)
    }

    @Test
    fun `decomposition keeps a mirror as a flip, not a half turn`() {
        val trs = Affine.scale(-1.0, 1.0).decompose()!!
        assertEquals(0.0, trs.rotation, 1e-12)
        assertEquals(-1.0, trs.scaleX, 1e-12)
        assertEquals(1.0, trs.scaleY, 1e-12)
        val m = Affine.translate(5.0, 7.0) * Affine.rotate(0.7) * Affine.scale(2.0, -3.0)
        val back = m.decompose()!!.toAffine()
        listOf(m.a to back.a, m.b to back.b, m.c to back.c, m.d to back.d, m.e to back.e, m.f to back.f)
            .forEach { (x, y) -> assertEquals(x, y, 1e-12) }
        assertEquals(null, Affine(c = 0.3).decompose(), "a skew can't be a Rive node transform")
    }

    @Test
    fun `styles cascade from presentation attributes, CSS rules and inline style`() {
        val doc = SvgImport.parse(
            """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 10 10"><style>.a{fill:#00ff00} #b{fill:red}</style>
              <g fill="blue" stroke="black" stroke-width="3"><rect id="r" width="1" height="1"/>
              <rect id="c" class="a" width="1" height="1" fill="#123456"/><rect id="b" class="a" width="1" height="1"/>
              <rect id="s" class="a" width="1" height="1" style="fill: rgba(255, 0, 0, 0.5); stroke-linecap: round"/></g></svg>""",
        )
        fun fill(id: String) = (doc.byId(id) as SvgShape).style.fill
        assertEquals(rivc.art.SvgPaint.Solid(0xFF0000FF), fill("r"), "inherited from the group")
        assertEquals(rivc.art.SvgPaint.Solid(0xFF00FF00), fill("c"), "a class rule beats a presentation attribute")
        assertEquals(rivc.art.SvgPaint.Solid(0xFFFF0000), fill("b"), "an id rule beats a class rule")
        assertEquals(rivc.art.SvgPaint.Solid(0x80FF0000), fill("s"), "inline style wins")
        assertEquals(3.0, (doc.byId("s") as SvgShape).style.strokeWidth)
        assertEquals(rivc.art.LineCap.ROUND, (doc.byId("s") as SvgShape).style.cap)
    }

    @Test
    fun `a group id shared with a gradient doesn't hide the gradient`() {
        val (result, dump) = emit(
            """<svg viewBox="0 0 100 100"><defs><radialGradient id="glow" gradientUnits="userSpaceOnUse" cx="50" cy="50" r="40">
              <stop offset="0" stop-color="#fff"/><stop offset="1" stop-color="#000"/></radialGradient></defs>
              <g id="glow"><circle id="disc" cx="50" cy="50" r="40" fill="url(#glow)"/></g></svg>""",
        )
        assertContains(dump.joinToString("\n"), "RadialGradient")
        assertEquals("Node", result.target("glow")?.type)
        assertEquals("RadialGradient", result.target("disc/fill")?.type)
        assertEquals("Fill", result.target("disc/fill/paint")?.type)
        assertEquals("GradientStop", result.target("disc/fill/stop1")?.type)
        assertEquals("Ellipse", result.target("disc/path")?.type)
    }

    @Test
    fun `padding goes on an unnamed art root, so keyed rig nodes keep SVG coordinates`() {
        val (result, dump) = emit(
            """<svg viewBox="0 0 100 100"><path id="p" d="M10 10 L 90 10 L 50 90 Z" fill="#f00"/></svg>""",
            """{ "artboard": { "name": "t", "width": 100, "height": 100, "padding": { "top": 20 } },
                 "art": { "svg": "t.svg", "nodes": [{ "id": "root", "x": 50, "y": 50 }], "shapes": [{ "id": "p", "parent": "root" }] } }""",
        )
        assertEquals("#2 Node [1] parentId=0 y=20.0", dump[3], "the art root carries the padding")
        assertEquals("#3 Node [2] name=\"root\" parentId=1 x=50.0 y=50.0", dump[4], "root sits at its SVG position")
        assertEquals("#4 Shape [3] name=\"p\" parentId=2 x=-50.0 y=-50.0", dump[5], "the art stays where the SVG draws it")
        assertEquals("StraightVertex", result.target("p/v2")?.type)
    }

    @Test
    fun `listed groups become pivoted nodes and data attributes reach later passes`() {
        val (result, dump) = emit(
            """<svg viewBox="0 0 100 100"><g id="arm" transform="translate(40 40) rotate(90)" data-text="Hi" data-size="12">
              <rect id="bone" x="0" y="-5" width="30" height="10" fill="#333"/></g></svg>""",
            """{ "artboard": { "name": "t", "width": 100, "height": 100 },
                 "art": { "svg": "t.svg", "groups": [{ "id": "arm", "pivot": { "x": 40, "y": 40 } }], "shapes": [{ "id": "bone" }] } }""",
        )
        val arm = dump.single { "name=\"arm\"" in it }
        assertContains(arm, "x=40.0 y=40.0 rotation=1.5707964")
        assertEquals(dump.single { "name=\"bone\"" in it }.substringAfter("parentId="), "1", "bone hangs from arm, at its origin")
        assertEquals(mapOf("text" to "Hi", "size" to "12"), result.data["arm"])
    }

    @Test
    fun `a skewed group bakes its skew into the geometry below it`() {
        val (_, dump) = emit(
            """<svg viewBox="0 0 100 100"><g id="lean" transform="translate(10 10) skewX(45)"><rect id="box" width="10" height="10" fill="#000"/></g></svg>""",
        )
        assertContains(dump.single { "name=\"lean\"" in it }, "x=10.0 y=10.0")
        val xs = dump.filter { "StraightVertex" in it }.map { it.substringAfter(" x=").substringBefore(" ").toDouble() }
        assertEquals(listOf(0.0, 10.0, 20.0, 10.0), xs, "the bottom edge is sheared right by its height")
    }

    @Test
    fun `morphs map an SVG path into the base shape's vertices`() {
        val (result, _) = emit(
            """<svg viewBox="0 0 100 100"><path id="a" d="M0 0 L10 0 L10 10 Z" fill="#000"/>
              <path id="b" d="M0 0 C 5 -5 10 -5 10 0 L 0 10 Z" fill="#000" /></svg>""",
            """{ "artboard": { "name": "t", "width": 100, "height": 100 },
                 "art": { "svg": "t.svg", "shapes": [{ "id": "a" }] },
                 "clips": [{ "name": "m", "frames": 10, "tracks": [{ "target": "a", "property": "vertices",
                   "keys": [{ "frame": 0, "morph": "a" }, { "frame": 10, "morph": "b" }] }] }] }""",
        )
        val morph = assertNotNull(result.morph("a", "b"))
        assertEquals(3, morph.size)
        assertTrue(morph.all { it.first.type == "CubicDetachedVertex" }, "a morph base is all detached vertices")
        assertEquals(10.0, morph[1].second.getValue("x"))
        assertEquals(0.0, morph[1].second.getValue("y"))
        assertEquals(abs(hypot(0.0, 5.0)), morph[1].second.getValue("inDistance"), 1e-9)
        assertFailsWith<CompileException> { result.morph("a", "nope") }
    }

    @Test
    fun `bad art sources fail with a message naming the item`() {
        val svg = """<svg viewBox="0 0 10 10"><g id="g"><rect id="r" width="1" height="1"/></g></svg>"""
        fun failure(art: String) = assertFailsWith<CompileException> {
            emit(svg, """{ "artboard": { "name": "t", "width": 10, "height": 10 }, "art": { "svg": "t.svg", $art } }""")
        }.message!!
        assertContains(failure(""""shapes": [{ "id": "nope" }]"""), "art.shapes.nope: no element with that id")
        assertContains(failure(""""shapes": [{ "id": "g" }]"""), "art.shapes.g: is a <g>; list it under art.groups")
        assertContains(failure(""""shapes": [{ "id": "r", "parent": "ghost" }]"""), "art.shapes.r: parent 'ghost' is not a rig node or kept group")
        assertContains(failure(""""nodes": [{ "id": "a", "parent": "b" }, { "id": "b", "parent": "a" }]"""), "art: parent cycle")
    }

    @Test
    fun `an ellipse and a circle geometry close on themselves`() {
        val e = SvgGeometry.Ellipse(0.0, 0.0, 2.0, 1.0).subPaths().single()
        for (s in e.segments()) {
            val q = SubPath.cubicAt(s, 0.5)
            assertEquals(1.0, (q.x / 2) * (q.x / 2) + q.y * q.y, 1e-3)
        }
    }
}
