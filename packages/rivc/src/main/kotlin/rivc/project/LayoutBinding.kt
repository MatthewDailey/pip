package rivc.project

import rivc.Argb
import rivc.FieldType
import rivc.MaskShape
import rivc.MaskSpec
import rivc.RivGraph
import rivc.RivNode
import rivc.Schema
import rivc.ValueType
import rivc.anim.ViewModelResult
import rivc.anim.parseColor
import rivc.spike.CompileException

/**
 * Runtime layout: view-model numbers bound to node properties, and clip masks that follow them.
 *
 * Recipe (showreel spike, feature 4, both runtimes):
 * - A DataBindContext written directly after a node binds one of its properties
 *   (`propertyKey`, flags 0 = view model to node) to `sourcePathIds = [view model, property]`.
 *   Several binds in a row all bind the same node: each attaches to the latest non-bind object.
 * - A ClippingShape parented to a group clips every drawable under that group (a nested
 *   artboard included) to its `sourceId` shape. `fillRule` 0 (non-zero) shows the group only
 *   inside the shape; 1 (even-odd) over a huge rectangle plus the shape cuts a hole, hiding
 *   the group inside it.
 * - With `Fit.Fill` on a 1080x2424 artboard in a full-screen overlay, artboard units are
 *   screen pixels, so an icon's on-screen centre can go straight into the bound numbers.
 */
class LayoutBinding(private val schema: Schema = Schema.bundled) {
    private fun fail(msg: String): Nothing = throw CompileException(msg)

    /**
     * Binds [node]'s properties to view-model numbers: [bind] maps a property name (`x`,
     * `y`, `opacity`, ...) to a number property of [vm]. Call right after adding [node].
     */
    fun bind(g: RivGraph, node: RivNode, bind: Map<String, String>, vm: ViewModelResult, where: String) {
        for ((prop, name) in bind) {
            val at = "$where.bind.$prop"
            val def = runCatching { schema.property(node.type, prop) }.getOrElse { fail("$at: ${it.message}") }
            if (def.type != FieldType.Double) fail("$at: only number properties can be bound; ${def.name} is ${def.type}")
            val model = vm.viewModel ?: fail("$at: this artboard has no view model to bind '$name' from")
            val p = vm.props[name] ?: fail("$at: the view model has no property '$name' (has ${vm.props.keys.joinToString()})")
            if (p.type != ValueType.NUMBER) fail("$at: '$name' is a ${p.type.name.lowercase()}, not a number")
            g.add("DataBindContext", owner = node) {
                this["propertyKey"] = def
                this["flags"] = 0
                this["sourcePathIds"] = listOf(model, p.node)
            }
        }
    }

    /**
     * A mask's clip-source shapes, created before their users so they can name them; placed by
     * [mask]. One shape holds every path of a plain mask (non-zero fill: the union). An
     * inverted mask gets one even-odd shape per path, each cutting its own hole: the group's
     * ClippingShapes intersect, so it is hidden inside the union (one even-odd shape would show
     * it again where two paths overlap). A [MaskSpec.disjoint] inverted mask, whose paths never
     * overlap, cuts every hole from one even-odd shape instead: one clip however many parts.
     */
    fun create(g: RivGraph, artboard: RivNode, m: MaskSpec): List<RivNode> {
        val n = if (m.invert && !m.disjoint) 1 + m.parts.size else 1
        return List(n) { i -> g.create("Shape", owner = artboard, label = "${m.id}Clip${if (i == 0) "" else "$i"}") }
    }

    /**
     * Emits mask [m]: a node at its centre (bound as [MaskSpec.bind] says) holding the
     * clip-source [shapes] (from [create]) and, with [MaskSpec.marker], a visible copy.
     * Returns the node (what clips key to move, turn and scale the mask).
     */
    fun mask(g: RivGraph, artboard: RivNode, m: MaskSpec, shapes: List<RivNode>, vm: ViewModelResult, where: String): RivNode {
        if (m.width <= 0 || m.height <= 0) fail("$where: width and height must be positive")
        m.parts.forEachIndexed { i, p ->
            if (p.width <= 0 || p.height <= 0) fail("$where.parts[$i]: width and height must be positive")
            for (k in p.bind.keys) if (k !in PART) fail("$where.parts[$i].bind.$k: a part binds only x, y, width and height")
        }
        val node = g.add("Node", owner = artboard) {
            this["name"] = m.id; this["parentId"] = artboard; this["x"] = m.x; this["y"] = m.y
        }
        bind(g, node, m.bind.filterKeys { it !in SIZE }, vm, where)
        val paths = listOf(Part(m.shape, 0.0, 0.0, m.width, m.height, m.cornerRadius, 0.5, 0.5, m.bind.filterKeys { it in SIZE }, where)) +
            m.parts.mapIndexed { i, p -> Part(p.shape, p.x, p.y, p.width, p.height, p.cornerRadius, p.originX, p.originY, p.bind, "$where.parts[$i]") }
        if (m.marker != null) {
            val marker = g.add("Shape", owner = artboard) { this["name"] = "${m.id}Marker"; this["parentId"] = node }
            for (p in paths) path(g, artboard, marker, p, vm)
            val fill = g.add("Fill", owner = artboard) { this["parentId"] = marker }
            g.add("SolidColor", owner = artboard) { this["parentId"] = fill; this["colorValue"] = Argb(parseColor(m.marker, "$where.marker")) }
        }
        if (m.disjoint && !m.invert) fail("$where.disjoint: only an inverted mask cuts disjoint holes")
        val groups = if (m.invert && !m.disjoint) paths.map { listOf(it) } else listOf(paths)
        for ((shape, group) in shapes.zip(groups)) {
            g.add(shape.apply { this["name"] = shape.label ?: "${m.id}Clip"; this["parentId"] = node })
            if (m.invert) {
                // Even-odd over this and the path cuts the hole; big enough to cover the artboard from anywhere on it.
                g.add("Rectangle", owner = artboard) { this["parentId"] = shape; this["width"] = HOLE_FIELD; this["height"] = HOLE_FIELD }
            }
            for (p in group) path(g, artboard, shape, p, vm)
        }
        return node
    }

    private class Part(
        val shape: MaskShape, val x: Double, val y: Double, val width: Double, val height: Double, val cornerRadius: Double,
        val originX: Double, val originY: Double, val bind: Map<String, String>, val where: String,
    )

    private fun path(g: RivGraph, artboard: RivNode, shape: RivNode, p: Part, vm: ViewModelResult) {
        val node = g.add(if (p.shape == MaskShape.ELLIPSE) "Ellipse" else "Rectangle", owner = artboard) {
            this["parentId"] = shape; this["width"] = p.width; this["height"] = p.height
            if (p.x != 0.0) this["x"] = p.x
            if (p.y != 0.0) this["y"] = p.y
            if (p.originX != 0.5) this["originX"] = p.originX
            if (p.originY != 0.5) this["originY"] = p.originY
            if (p.shape == MaskShape.RECTANGLE && p.cornerRadius > 0) { this["linkCornerRadius"] = true; this["cornerRadiusTL"] = p.cornerRadius }
        }
        bind(g, node, p.bind, vm, p.where)
    }

    /** Clips [group]'s drawables to mask [m]'s [shapes]. Write it after the group's children. */
    fun clip(g: RivGraph, artboard: RivNode, group: RivNode, shapes: List<RivNode>, m: MaskSpec, name: String) {
        shapes.forEachIndexed { i, shape ->
            g.add("ClippingShape", owner = artboard) {
                this["name"] = if (i == 0) name else "$name$i"
                this["parentId"] = group
                this["sourceId"] = shape
                this["fillRule"] = if (m.invert) 1 else 0
                this["isVisible"] = true
            }
        }
    }

    companion object {
        /** Side of the rectangle an inverted mask cuts its hole from, in artboard units. */
        const val HOLE_FIELD = 8000.0

        /** Mask bind keys that size the shape rather than move its node. */
        private val SIZE = setOf("width", "height")

        /** What a mask part can bind: its place relative to the mask's centre and its size. */
        private val PART = SIZE + setOf("x", "y")
    }
}
