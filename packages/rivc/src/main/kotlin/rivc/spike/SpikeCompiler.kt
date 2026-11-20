package rivc.spike

import rivc.Argb
import rivc.PipSource
import rivc.Pivot
import rivc.RivGraph
import rivc.RivNode
import rivc.Schema
import rivc.anim.AnimTargets
import java.io.File

/** A source the compiler can't build; the message names the section and item. */
class CompileException(message: String) : RuntimeException(message)

/**
 * The spike's art logic: SVG paths and ellipses with solid fills under rig nodes, emitted
 * into an open artboard. The art pass (`rivc/art/`) replaces it; the clips and state
 * machine now come from `rivc/anim/`.
 *
 * Shapes are written in reversed SVG paint order (Rive draws last-to-first). It returns
 * the targets clips can animate, named as [AnimTargets] describes.
 */
class SpikeArt(private val schema: Schema = Schema.bundled) {
    private fun fail(msg: String): Nothing = throw CompileException(msg)

    private class Targets : AnimTargets {
        val map = HashMap<String, RivNode>()
        val paths = HashMap<String, Pair<SvgPath, Pair<Double, Double>>>() // shape -> (geometry, pivot)
        val vertices = HashMap<String, List<RivNode>>()
        var svg: Map<String, SvgPart> = emptyMap()

        override fun target(name: String) = map[name]

        override fun morph(shape: String, svgId: String): List<Pair<RivNode, Map<String, Double>>>? {
            val (base, pivot) = paths[shape] ?: return null
            val other = svg[svgId] as? SvgPath ?: throw CompileException("morph: '$svgId' is not an SVG path")
            val nodes = vertices.getValue(shape)
            if (other.vertices.size != base.vertices.size) {
                throw CompileException("morph: '$svgId' has ${other.vertices.size} vertices, '$shape' has ${base.vertices.size}")
            }
            return nodes.mapIndexed { i, node ->
                val v = other.vertices[i]
                val values = linkedMapOf("x" to v.x - pivot.first, "y" to v.y - pivot.second)
                if (node.type == "StraightVertex") {
                    if (!v.straight) throw CompileException("morph: vertex $i of '$shape' is straight, but '$svgId' curves there")
                } else {
                    values += mapOf(
                        "inRotation" to v.inRot(), "inDistance" to v.inDist(),
                        "outRotation" to v.outRot(), "outDistance" to v.outDist(),
                    )
                }
                node to values
            }
        }
    }

    /** Emits [src]'s art into [artboard] (already added) and returns its animation targets. */
    fun build(g: RivGraph, artboard: RivNode, src: PipSource, baseDir: File): AnimTargets {
        val t = Targets()
        val art = src.art ?: return t
        val pad = src.artboard.padding
        if (art.groups.isNotEmpty()) fail("art.groups: SVG groups aren't compiled yet")
        val targets = t.map
        fun parentOf(where: String, id: String?): RivNode =
            if (id == null) artboard else targets[id] ?: fail("$where: parent '$id' is not an earlier node")
        fun RivNode.placeTopLevel(parent: String?, x: Double, y: Double) {
            val ox = x + if (parent == null) pad.left else 0.0
            val oy = y + if (parent == null) pad.top else 0.0
            if (ox != 0.0) this["x"] = ox
            if (oy != 0.0) this["y"] = oy
        }
        for (n in art.nodes) {
            if (n.id in targets) fail("art.nodes: duplicate id '${n.id}'")
            targets[n.id] = g.add("Node", owner = artboard) {
                this["name"] = n.id
                this["parentId"] = parentOf("art.nodes.${n.id}", n.parent)
                placeTopLevel(n.parent, n.x, n.y)
            }
        }
        val svgFile = File(baseDir, art.svg)
        if (!svgFile.isFile) fail("art.svg: no file at $svgFile")
        val all = Svg.parse(svgFile)
        val parts = all.associateBy { it.id }
        t.svg = parts
        val specs = art.shapes.associateBy { it.id }
        for (s in art.shapes) if (s.id !in parts) fail("art.shapes.${s.id}: no element with that id in ${art.svg}")
        // Rive draws the artboard's drawables last-to-first, so SVG paint order is reversed.
        for (part in all.reversed()) {
            val spec = specs[part.id] ?: continue
            if (part.id in targets) fail("art.shapes: duplicate id '${part.id}'")
            val parent = parentOf("art.shapes.${part.id}", spec.parent)
            val shape = g.add("Shape", owner = artboard) { this["name"] = part.id; this["parentId"] = parent }
            when (part) {
                is SvgEllipse -> {
                    val (sx, sy) = if (spec.pivot == Pivot.CENTER) part.cx to part.cy else 0.0 to 0.0
                    shape.placeTopLevel(spec.parent, sx, sy)
                    targets["${part.id}/path"] = g.add("Ellipse", owner = artboard) {
                        this["parentId"] = shape
                        if (spec.pivot == Pivot.ORIGIN) { this["x"] = part.cx; this["y"] = part.cy }
                        this["width"] = part.rx * 2
                        this["height"] = part.ry * 2
                    }
                }
                is SvgPath -> {
                    var cx = 0.0
                    var cy = 0.0
                    if (spec.pivot == Pivot.CENTER) {
                        cx = (part.vertices.minOf { it.x } + part.vertices.maxOf { it.x }) / 2
                        cy = (part.vertices.minOf { it.y } + part.vertices.maxOf { it.y }) / 2
                    }
                    shape.placeTopLevel(spec.parent, cx, cy)
                    val path = g.add("PointsPath", owner = artboard) { this["parentId"] = shape; this["isClosed"] = part.closed }
                    targets["${part.id}/path"] = path
                    val vs = part.vertices.map { v ->
                        if (v.straight) {
                            g.add("StraightVertex", owner = artboard) {
                                this["parentId"] = path; this["x"] = v.x - cx; this["y"] = v.y - cy
                            }
                        } else {
                            g.add("CubicDetachedVertex", owner = artboard) {
                                this["parentId"] = path
                                this["x"] = v.x - cx; this["y"] = v.y - cy
                                this["inRotation"] = v.inRot(); this["inDistance"] = v.inDist()
                                this["outRotation"] = v.outRot(); this["outDistance"] = v.outDist()
                            }
                        }
                    }
                    vs.forEachIndexed { i, v -> targets["${part.id}/v$i"] = v }
                    t.vertices[part.id] = vs
                    t.paths[part.id] = part to (cx to cy)
                }
            }
            val fill = g.add("Fill", owner = artboard) { this["parentId"] = shape }
            targets["${part.id}/fill/paint"] = fill
            targets["${part.id}/fill"] = g.add("SolidColor", owner = artboard) {
                this["parentId"] = fill; this["colorValue"] = Argb(part.fill)
            }
            targets[part.id] = shape
        }
        return t
    }
}
