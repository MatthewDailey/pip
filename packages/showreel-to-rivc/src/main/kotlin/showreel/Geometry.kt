package showreel

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Where a piece's own (0, 0) lands on screen and how its axes are turned and scaled:
 * screen = T(x, y) R(rot) S(sx, sy) local. [rot] is in radians, clockwise (y down), as in Rive.
 */
data class Place(val x: Double, val y: Double, val rot: Double, val sx: Double, val sy: Double) {
    /** Screen position of [lx], [ly] in this frame. */
    fun point(lx: Double, ly: Double): Pair<Double, Double> {
        val c = cos(rot); val s = sin(rot)
        return (x + c * sx * lx - s * sy * ly) to (y + s * sx * lx + c * sy * ly)
    }

    /** The same axes with the origin moved to local ([lx], [ly]). */
    fun at(lx: Double, ly: Double): Place = point(lx, ly).let { (px, py) -> copy(x = px, y = py) }

    fun scaled(k: Double) = copy(sx = sx * k, sy = sy * k)

    /** The same origin with the axes turned by [r] radians more. */
    fun rotated(r: Double) = copy(rot = rot + r)

    /**
     * The NestedArtboard (or text) transform that puts an artboard's point ([ax], [ay]) here: Rive
     * draws a nested artboard with its top-left at the node, so the node goes to -[ax], -[ay] in this frame.
     */
    fun node(ax: Double, ay: Double): Place = at(-ax, -ay)

    companion object {
        const val DEG = PI / 180
    }
}

/** Pip's frame of reference, as the Kotlin renderer and its `pipPoint` / `pipTransform` helpers build it. */
object PipGeometry {
    /** The 540 x 660 box: Pip's (x, y) is its bottom centre and `size` its width. */
    const val BOX_W = 540.0
    const val BOX_H = 660.0

    /** `pipTransform`: box units to screen (no breathing). */
    fun box(f: Frame): Place {
        val k = f.pipNum("size") / BOX_W
        return Place(f.pipNum("x"), f.pipNum("y"), f.pipNum("rotation") * Place.DEG, k * f.pipNum("squashX") * f.pipNum("facing"), k * f.pipNum("squashY"))
            .at(-BOX_W / 2, -BOX_H)
    }

    /** `PipRenderer.draw` about his feet; the breathing stretch is left to the rig's own idle layer. */
    fun body(f: Frame, breath: Double = 0.0): Place {
        val k = f.pipNum("size") / BOX_W
        return Place(
            f.pipNum("x"), f.pipNum("y"), f.pipNum("rotation") * Place.DEG,
            k * f.pipNum("squashX") * (1 - breath * 0.6) * f.pipNum("facing"), k * f.pipNum("squashY") * (1 + breath),
        )
    }

    /** Where Pip-anchored actors hang: the top of his head, ignoring rotation (`ActorPainter.draw`). */
    fun headTop(f: Frame): Pair<Double, Double> =
        f.pipNum("x") to f.pipNum("y") - f.pipNum("size") * (BOX_H / BOX_W) * f.pipNum("squashY")

    fun visible(f: Frame): Boolean = f.pip["visible"] != false && f.pipNum("alpha") > 0

    /** `drawGroundShadow`: centre, half width, half height and alpha of the ground ellipse. */
    fun shadow(f: Frame): DoubleArray? {
        val s = f.pipNum("shadow")
        if (s <= 0 || s.isNaN()) return null
        val size = f.pipNum("size")
        val y = f.pipNum("y")
        val gy = (f.pip["groundY"] as? Double) ?: y
        val rise = (gy - y).coerceAtLeast(0.0)
        val near = 1 / (1 + rise / (size * 0.75))
        val hw = size * 0.37 * f.pipNum("squashX") * (0.45 + 0.55 * near)
        val hh = hw * 0.15
        val a = (s * near * near).coerceIn(0.0, 1.0)
        if (a <= 0.01) return null
        return doubleArrayOf(f.pipNum("x"), gy + hh * 0.15, hw, hh, a)
    }
}
