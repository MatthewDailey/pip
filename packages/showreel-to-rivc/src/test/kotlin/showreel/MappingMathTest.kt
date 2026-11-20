package showreel

import rivc.Ease
import kotlin.math.PI
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MappingMathTest {
    private fun near(expected: Double, actual: Double, tol: Double = 1e-6, what: String = "") =
        assertTrue(abs(expected - actual) <= tol, "$what expected $expected, got $actual")

    @Test
    fun bezierMatchesCssEndpointsAndLinear() {
        near(0.0, Bezier.ease(0.42, 0.0, 0.58, 1.0, 0.0))
        near(1.0, Bezier.ease(0.42, 0.0, 0.58, 1.0, 1.0))
        near(0.5, Bezier.ease(0.42, 0.0, 0.58, 1.0, 0.5), 1e-6, "easeInOut is symmetric")
        near(0.3, Bezier.ease(0.0, 0.0, 1.0, 1.0, 0.3))
        // ease (0.25, 0.1, 0.25, 1) at 0.5 is about 0.8024 (CSS reference value).
        near(0.8024, Bezier.ease(0.25, 0.1, 0.25, 1.0, 0.5), 2e-4)
    }

    @Test
    fun quadInBezierIsExact() {
        val q = Ease.Cubic(1.0 / 3, 0.0, 2.0 / 3, 1.0 / 3)
        for (u in listOf(0.1, 0.37, 0.5, 0.9)) near(u * u, Bezier.ease(q.x1, q.y1, q.x2, q.y2, u), 1e-7, "quadIn at $u")
    }

    @Test
    fun placeNodePutsTheAnchorOnThePoint() {
        // A piece whose (0, 0) sits at artboard (100, 50), placed at (500, 600), rotated 90 degrees and scaled 2.
        val p = Place(500.0, 600.0, PI / 2, 2.0, 2.0)
        val node = p.node(100.0, 50.0)
        // The node draws artboard point (100, 50) at node + R S (100, 50), which must be (500, 600).
        val (x, y) = node.point(100.0, 50.0)
        near(500.0, x, 1e-9); near(600.0, y, 1e-9)
        near(500.0 + 2 * 50, node.x, 1e-9, "x")
        near(600.0 - 2 * 100, node.y, 1e-9, "y")
    }

    @Test
    fun pipFeetPivotWithFacingAndSquash() {
        // Pip at (540, 2000), size 300, facing left, squashed 1.2 x 0.8, tilted 10 degrees.
        val pip = mapOf<String, Any?>("x" to 540.0, "y" to 2000.0, "size" to 300.0, "facing" to -1.0, "squashX" to 1.2, "squashY" to 0.8, "rotation" to 10.0)
        val f = Frame(0.0, ShowExport.Scene(0, "s", 0.0, 1.0, emptyMap(), emptyList(), emptyList()), pip, emptyMap())
        val body = PipGeometry.body(f)
        near(-300.0 / 540 * 1.2, body.sx, 1e-12, "facing flips scaleX")
        near(300.0 / 540 * 0.8, body.sy, 1e-12)
        // The rig artboard is 720 x 820 with his feet at (360, 770): the node puts the feet on (x, y).
        val node = body.scaled(540.0 / 540).node(360.0, 770.0)
        val (fx, fy) = node.point(360.0, 770.0)
        near(540.0, fx, 1e-9); near(2000.0, fy, 1e-9)
        // The box helper agrees with the Kotlin pipPoint: the top centre of his box.
        val (tx, ty) = PipGeometry.box(f).point(270.0, 0.0)
        val r = 10 * PI / 180
        near(540.0 + 660 * 300.0 / 540 * 0.8 * kotlin.math.sin(r), tx, 1e-9)
        near(2000.0 - 660 * 300.0 / 540 * 0.8 * kotlin.math.cos(r), ty, 1e-9)
    }

    @Test
    fun keyFitKeepsJumpsAsHoldsAndStaysWithinTolerance() {
        val v = DoubleArray(100) { i -> if (i < 50) i * 0.5 else 100.0 + (i - 50) * 0.25 * (i - 50) * 0.02 }
        val jumps = BooleanArray(100).also { it[49] = true }
        val keys = KeyFit.linear(Channel(10, v, jumps), 0.1)
        val hold = keys.single { it.ease == Ease.Hold }
        assertEquals(10 + 49, hold.frame)
        assertTrue(keys.any { it.frame == 10 + 50 })
        // Every sample is within tolerance of the linear interpolation of the keys.
        for (i in v.indices) {
            val f = 10 + i
            val a = keys.last { it.frame <= f }
            val b = keys.firstOrNull { it.frame > f }
            val est = if (b == null || a.ease == Ease.Hold) a.value!! else a.value!! + (b.value!! - a.value!!) * (f - a.frame) / (b.frame - a.frame)
            near(v[i], est, 0.1 + 1e-9, "frame $f")
        }
        assertTrue(keys.size < 30, "fitted ${keys.size} keys")
    }

    @Test
    fun dontCareStretchesTakeTheNextVisibleValue() {
        val ch = Channel(0, doubleArrayOf(Double.NaN, Double.NaN, 5.0, 6.0, Double.NaN)).fillDontCare()
        assertEquals(listOf(5.0, 5.0, 5.0, 6.0, 6.0), ch.values.toList())
    }

    @Test
    fun cssOrderColoursBecomeArgb() {
        assertEquals("#FFF05248", Normaliser.color("#F05248FF"))
        assertEquals("#80112233", Normaliser.color("#11223380"))
        assertEquals("#F05248", Normaliser.color("#F05248"))
    }

    @Test
    fun pipInputsFollowKotlinOrdinalsAndFoldBlink() {
        val f = Frame(0.0, ShowExport.Scene(0, "s", 0.0, 1.0, emptyMap(), emptyList(), emptyList()),
            mapOf("mood" to "ANGRY", "mouth" to "SHOUT", "eyesOpen" to 0.8, "blink" to 0.5, "rightHand" to "SWORD", "rightX" to 480.0, "leftHand" to "NONE", "leftX" to 0.0, "life" to 0.7),
            emptyMap())
        assertEquals(3.0, PipInputs.mapping("mood", false)!!.fn(f))
        assertEquals(4.0, PipInputs.mapping("mouth", false)!!.fn(f))
        near(0.4, PipInputs.mapping("eyesOpen", false)!!.fn(f), 1e-12)
        assertEquals(5.0, PipInputs.mapping("handR", false)!!.fn(f))
        assertEquals(480.0, PipInputs.mapping("handRX", false)!!.fn(f))
        assertTrue(PipInputs.mapping("handLX", false)!!.fn(f).isNaN(), "a hidden hand's position is don't-care")
        near(0.7, PipInputs.mapping("life", false)!!.fn(f))
    }
}
