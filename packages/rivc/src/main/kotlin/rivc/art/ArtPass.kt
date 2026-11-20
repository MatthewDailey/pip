package rivc.art

import rivc.ArtSpec
import rivc.DrawRuleSpec
import rivc.ArtboardSpec
import rivc.Argb
import rivc.NodeSpec
import rivc.PipSource
import rivc.Pivot
import rivc.RivGraph
import rivc.RivNode
import rivc.anim.AnimTargets
import rivc.spike.CompileException
import java.io.File
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.min
import kotlin.math.sqrt

/**
 * What the art pass made, for the passes that follow it.
 *
 * @property targets animatable objects by name, as [AnimTargets] names them: rig node, group,
 *   shape and text ids; `"<id>/path"` (a shape's single path or primitive) and `"<id>/v<i>"`
 *   (its vertices, numbered across subpaths); `"<id>/fill"` and `"<id>/stroke"` (the
 *   SolidColor or gradient), `"<id>/fill/paint"` and `"<id>/stroke/paint"` (the Fill and
 *   Stroke), `"<id>/fill/stop<i>"` (gradient stops), `"<id>/stroke/trim"` (a TrimPath), and
 *   `"<id>/drawRules"` with its targets `"<id>/drawRules/<name>"`.
 * @property data each element's `data-*` attributes (keyed without the prefix), by id, for
 *   example the scenery's `data-text` placeholders.
 */
class ArtResult(
    val targets: Map<String, RivNode>,
    val data: Map<String, Map<String, String>>,
    val warnings: List<String>,
    private val morphs: (String, String) -> List<Pair<RivNode, Map<String, Double>>>? = { _, _ -> null },
) : AnimTargets {
    override fun target(name: String) = targets[name]

    override fun morph(shape: String, svgId: String) = morphs(shape, svgId)
}

/**
 * The art half of `pip.json`: imports [ArtSpec.svg] with [SvgImport] and emits the
 * artboard's nodes, shapes, paths and paints into a [RivGraph].
 *
 * **Which elements.** With [ArtSpec.shapes] listed, only those shapes (and the [ArtSpec.groups]
 * listed) are emitted, each under its `parent` or its nearest kept SVG group. With no shapes
 * listed the whole SVG is emitted: every `<g>` with an id (or with a clip or opacity) becomes a
 * Node, every shape a Shape, so every id is a name clips can target. Rig [ArtSpec.nodes] wrap
 * whatever names them as `parent`.
 *
 * **Coordinates.** Every emitted node gets the transform that keeps the art exactly where the
 * SVG draws it, relative to whatever Rive parent it ends up under: group transforms become node
 * `x/y/rotation/scaleX/scaleY`, a rig node or group pivot becomes the node's origin, and the
 * viewBox plus [ArtboardSpec.padding] map SVG units onto the artboard through one unnamed top
 * node (so a clip keying a rig node's `y` to 0 means "at rest", not "ignore the padding"). A
 * transform Rive nodes can't hold (a skew) is baked into the geometry below instead.
 *
 * **Shapes.** Ellipses, circles and rects become Rive Ellipse and Rectangle primitives when the
 * transform keeps them exact; everything else becomes PointsPaths, one per subpath, with
 * straight, mirrored or detached cubic vertices. Fills and strokes carry a SolidColor, a
 * LinearGradient or a RadialGradient with its GradientStops. A `clip-path` becomes a paintless
 * clip-source Node of Shapes plus a ClippingShape on the clipped node.
 *
 * **Order.** Nodes first, parents before children; then shapes in reversed SVG paint order,
 * because Rive draws an artboard's drawables last to first; then the ClippingShapes.
 */
class ArtPass(private val warn: (String) -> Unit = { System.err.println("rivc: warning: $it") }) {
    companion object {
        /** Sets the artboard's own properties: name, size grown by the padding, and clip. */
        fun artboardProperties(artboard: RivNode, spec: ArtboardSpec) {
            val pad = spec.padding
            artboard["name"] = spec.name
            artboard["width"] = spec.width + pad.left + pad.right
            artboard["height"] = spec.height + pad.top + pad.bottom
            artboard["clip"] = spec.clip
        }

        private const val EPS = 1e-9
    }

    /** Emits [src]'s art into [artboard] (already added); relative paths resolve against [baseDir]. */
    fun emit(g: RivGraph, artboard: RivNode, src: PipSource, baseDir: File): ArtResult {
        val art = src.art ?: return ArtResult(emptyMap(), emptyMap(), emptyList())
        val file = File(baseDir, art.svg)
        if (!file.isFile) throw CompileException("art.svg: no file at $file")
        val doc = try {
            SvgImport.load(file)
        } catch (e: SvgException) {
            throw CompileException("art.svg: ${e.message}")
        }
        val warnings = doc.warnings.toMutableList()
        // Shapes a clip morphs, and the SVG elements it morphs them into (geometry sources, not drawn).
        val vertexTracks = src.clips.flatMap { c -> c.tracks.filter { it.property == "vertices" } }
        val morphKeys = vertexTracks.groupBy { it.target }.mapValues { (_, ts) ->
            ts.flatMap { t -> t.keys.mapNotNull { k -> k.morph ?: k.path?.let { AnimTargets.PATH_DATA + it } } }.distinct()
        }
        val morphSources = vertexTracks.flatMap { t -> t.keys.mapNotNull { it.morph } }.toSet()
        // Shapes whose stroke a clip trims.
        val trimmed = src.clips.flatMap { c -> c.tracks.map { it.target } }.filter { it.endsWith("/stroke/trim") }
            .map { it.removeSuffix("/stroke/trim") }.toSet()
        val result = Run(g, artboard, src.artboard, art, doc, warnings, morphKeys, morphSources, trimmed).run()
        warnings.forEach(warn)
        return result
    }

    private fun fail(msg: String): Nothing = throw CompileException(msg)

    /** A container in the emitted node tree: a rig node or a kept SVG element. */
    private sealed interface Key {
        data class Rig(val spec: NodeSpec) : Key
        data class El(val el: SvgElement) : Key
    }

    /** A PointsPath and its vertex nodes, with the geometry they were written from. */
    private class PathNode(val node: RivNode, val sub: SubPath, val vertices: List<RivNode>)

    private data class Morphable(val el: SvgShape, val shape: RivNode, val paths: List<Pair<SubPath, List<RivNode>>>)

    private class ClipUse(val clip: String, val refCtm: Affine, val refBounds: Box?)

    private sealed interface Paint {
        data class Solid(val argb: Long) : Paint
        class Gradient(val g: SvgGradient) : Paint
    }

    private inner class Run(
        val g: RivGraph,
        val artboard: RivNode,
        val ab: ArtboardSpec,
        val art: ArtSpec,
        val doc: SvgDocument,
        val warnings: MutableList<String>,
        val morphKeys: Map<String, List<String>>,
        val morphSources: Set<String>,
        val trimmed: Set<String>,
    ) {
        val morphBases = morphKeys.keys
        val frames = HashMap<RivNode, Affine>().apply { put(artboard, Affine.IDENTITY) }
        val targets = LinkedHashMap<String, RivNode>()
        val data = LinkedHashMap<String, Map<String, String>>()
        val nodeOf = HashMap<Key, RivNode>()
        val clippings = mutableListOf<Triple<RivNode, RivNode, Boolean>>() // clipped, source, evenOdd
        val morphable = HashMap<String, Morphable>()
        val selective = art.shapes.isNotEmpty()
        val shapeSpecs = art.shapes.associateBy { it.id }
        val groupSpecs = art.groups.associateBy { it.id }
        val viewBox = doc.viewBox ?: Box(0.0, 0.0, ab.width, ab.height)

        /** SVG root user space to artboard space: the viewBox fitted to width x height, then the padding. */
        val toArtboard: Affine = run {
            val sx = ab.width / viewBox.width
            val sy = ab.height / viewBox.height
            val s = min(sx, sy)
            if (abs(sx - sy) > 1e-9) {
                warnings += "art.svg: viewBox aspect differs from the artboard's ${ab.width}x${ab.height}; fitted and centred"
            }
            val ox = (ab.width - viewBox.width * s) / 2
            val oy = (ab.height - viewBox.height * s) / 2
            Affine.translate(ab.padding.left + ox, ab.padding.top + oy) * Affine.scale(s) * Affine.translate(-viewBox.minX, -viewBox.minY)
        }

        fun kept(el: SvgElement): Boolean = when {
            el.id in morphSources && el.id !in shapeSpecs && el.id !in morphBases -> false
            else -> keptKind(el)
        }

        fun keptKind(el: SvgElement): Boolean = when (el) {
            is SvgGroup -> el.parent != null && (el.id in groupSpecs || (!selective && (el.id != null || el.clipPath != null || el.opacity < 1)))
            is SvgShape -> el.style.visible && (!selective || el.id in shapeSpecs)
            is SvgText -> !selective || el.id in shapeSpecs
        }

        val elements = doc.elements
        val keptElements = elements.filter { kept(it) }
        val ids = HashMap<String, Key>()

        /**
         * What top-level art hangs from. When the viewBox or padding moves the art, an unnamed node
         * carries that placement, so the nodes clips key keep SVG coordinates: a clip that keys
         * `root.y = 0` means "at rest", not "at the artboard's top edge, ignoring the padding".
         */
        lateinit var artRoot: RivNode

        fun run(): ArtResult {
            validate()
            artRoot = if (toArtboard.isNear(Affine.IDENTITY)) artboard else {
                g.add("Node", owner = artboard) { this["parentId"] = artboard }.also { place(it, artboard, toArtboard) }
            }
            // Phase 1: rig nodes, groups and text placeholders, parents first.
            val children = LinkedHashMap<Key?, MutableList<Key>>()
            for (n in art.nodes) children.getOrPut(parentKey(Key.Rig(n))) { mutableListOf() } += Key.Rig(n)
            for (el in keptElements) if (el !is SvgShape) children.getOrPut(parentKey(Key.El(el))) { mutableListOf() } += Key.El(el)
            val visiting = HashSet<Key>()
            fun visit(parent: Key?) {
                for (k in children[parent].orEmpty()) {
                    if (!visiting.add(k)) fail("art: '${keyName(k)}' is its own ancestor")
                    emitNode(k)
                    visit(k)
                }
            }
            visit(null)
            val unreached = children.keys.filterNotNull().filter { it !in nodeOf }
            if (unreached.isNotEmpty()) fail("art: parent cycle through ${unreached.joinToString { "'${keyName(it)}'" }}")

            // Clip sources go under the clipped group, or beside a clipped shape.
            val clipUses = keptElements.associateWith { clipUses(it) }.filterValues { it.isNotEmpty() }
            for ((el, uses) in clipUses) if (el !is SvgShape) emitClips(nodeOf.getValue(Key.El(el)), nodeOf.getValue(Key.El(el)), uses)

            // Phase 2: shapes, last painted first.
            for (el in keptElements.filterIsInstance<SvgShape>().sortedByDescending { it.order }) emitShape(el, clipUses[el].orEmpty())

            // Phase 3: clipping shapes (they may name sources written before or after them).
            for ((clipped, source, evenOdd) in clippings) {
                g.add("ClippingShape", owner = artboard) {
                    this["parentId"] = clipped
                    this["sourceId"] = source
                    if (evenOdd) this["fillRule"] = 1
                }
            }
            for (rule in art.drawRules) drawRules(rule)
            for (el in keptElements) {
                if (el.data.isEmpty()) continue
                if (el.id == null) warnings += "art: <${el.tag}> has data-* attributes but no id, so later passes can't find it"
                else data[el.id!!] = el.data
            }
            return ArtResult(targets, data, warnings, ::morph)
        }

        fun validate() {
            for (n in art.nodes) {
                if (n.id in ids) fail("art.nodes: duplicate id '${n.id}'")
                ids[n.id] = Key.Rig(n)
            }
            for (s in art.shapes) {
                val el = doc.byId(s.id) ?: fail("art.shapes.${s.id}: no element with that id in ${art.svg}")
                if (el is SvgGroup) fail("art.shapes.${s.id}: is a <${el.tag}>; list it under art.groups")
                if (el is SvgShape && !el.style.visible) warnings += "art.shapes.${s.id}: is hidden in the SVG; not emitted"
            }
            for (gs in art.groups) {
                val el = doc.byId(gs.id) ?: fail("art.groups.${gs.id}: no element with that id in ${art.svg}")
                if (el !is SvgGroup) fail("art.groups.${gs.id}: is a <${el.tag}>, not a group")
            }
            for (el in keptElements) {
                val id = el.id ?: continue
                if (id in ids) fail("art: id '$id' is used by a rig node and an SVG element (or twice)")
                ids[id] = Key.El(el)
            }
        }

        fun keyName(k: Key) = when (k) {
            is Key.Rig -> k.spec.id
            is Key.El -> k.el.id ?: "<${k.el.tag}>"
        }

        /** The kept container [k] hangs from, or null for the artboard. */
        fun parentKey(k: Key): Key? {
            val explicit = when (k) {
                is Key.Rig -> k.spec.parent
                is Key.El -> k.el.id?.let { shapeSpecs[it]?.parent ?: groupSpecs[it]?.parent }
            }
            if (explicit != null) {
                val where = when (k) {
                    is Key.Rig -> "art.nodes.${k.spec.id}"
                    is Key.El -> if (k.el is SvgGroup) "art.groups.${k.el.id}" else "art.shapes.${k.el.id}"
                }
                val p = ids[explicit] ?: fail("$where: parent '$explicit' is not a rig node or kept group")
                if (p is Key.El && p.el !is SvgGroup) fail("$where: parent '$explicit' is a shape; shapes can't have children")
                return p
            }
            if (k is Key.El) for (a in k.el.ancestors()) if (kept(a)) return Key.El(a)
            return null
        }

        fun parentNode(k: Key): RivNode = parentKey(k)?.let { nodeOf[it] ?: fail("art: '${keyName(k)}' is emitted before its parent") } ?: artRoot

        /** Opacity of [el] times that of the ancestors that weren't kept (their own nodes would have carried it). */
        fun opacity(el: SvgElement): Double {
            var o = el.opacity
            for (a in el.ancestors()) { if (kept(a)) break; o *= a.opacity }
            return o
        }

        fun clipUses(el: SvgElement): List<ClipUse> {
            val out = mutableListOf<ClipUse>()
            el.clipPath?.let { out += ClipUse(it, el.ctm, el.localBounds()) }
            for (a in el.ancestors()) {
                if (kept(a)) break
                a.clipPath?.let { out += ClipUse(it, a.ctm, a.localBounds()) }
            }
            return out
        }

        /**
         * Gives [node] (a child of [parent]) the local transform that puts its frame at [desired]
         * (node space to artboard space), or the nearest a Rive node can hold: without a skew the
         * match is exact; with one the node only translates and its contents absorb the rest.
         * Returns the frame it actually got.
         */
        fun place(node: RivNode, parent: RivNode, desired: Affine): Affine {
            val p = frames.getValue(parent)
            val local = p.inverse() * desired
            val trs = local.decompose() ?: Trs(local.e, local.f, 0.0, 1.0, 1.0)
            if (abs(trs.x) > EPS) node["x"] = trs.x
            if (abs(trs.y) > EPS) node["y"] = trs.y
            if (abs(trs.rotation) > EPS) node["rotation"] = trs.rotation
            if (abs(trs.scaleX - 1) > EPS) node["scaleX"] = trs.scaleX
            if (abs(trs.scaleY - 1) > EPS) node["scaleY"] = trs.scaleY
            val actual = p * Trs(
                if (abs(trs.x) > EPS) trs.x else 0.0,
                if (abs(trs.y) > EPS) trs.y else 0.0,
                if (abs(trs.rotation) > EPS) trs.rotation else 0.0,
                if (abs(trs.scaleX - 1) > EPS) trs.scaleX else 1.0,
                if (abs(trs.scaleY - 1) > EPS) trs.scaleY else 1.0,
            ).toAffine()
            frames[node] = actual
            return actual
        }

        fun setOpacity(node: RivNode, o: Double) {
            if (abs(o - 1) > EPS) node["opacity"] = o
        }

        fun emitNode(k: Key) {
            val parent = parentNode(k)
            val node = g.add("Node", owner = artboard) {
                keyName(k).takeIf { k is Key.Rig || (k as Key.El).el.id != null }?.let { this["name"] = it }
                this["parentId"] = parent
            }
            when (k) {
                is Key.Rig -> place(node, parent, toArtboard * Affine.translate(k.spec.x, k.spec.y))
                is Key.El -> {
                    val el = k.el
                    val origin = when (el) {
                        is SvgText -> Pt(el.x, el.y)
                        else -> el.id?.let { groupSpecs[it]?.pivot }?.let { el.ctm.inverse().apply(Pt(it.x, it.y)) } ?: Pt(0.0, 0.0)
                    }
                    place(node, parent, toArtboard * el.ctm * Affine.translate(origin))
                    setOpacity(node, opacity(el))
                }
            }
            nodeOf[k] = node
            keyName(k).let { if (k is Key.Rig || (k as Key.El).el.id != null) targets[it] = node }
        }

        fun emitClips(clipped: RivNode, container: RivNode, uses: List<ClipUse>) {
            for (use in uses) {
                val clip = doc.clipPaths[use.clip]
                if (clip == null) { warnings += "art: clip-path '#${use.clip}' doesn't name a <clipPath>; not clipped"; continue }
                val units = if (clip.boundingBox) {
                    val b = use.refBounds
                    if (b == null || b.width <= 0 || b.height <= 0) { warnings += "art: clipPath '${clip.id}' needs a bounding box; skipped"; continue }
                    Affine.translate(b.minX, b.minY) * Affine.scale(b.width, b.height)
                } else Affine.IDENTITY
                val frame = toArtboard * use.refCtm * clip.transform * units
                val source = g.add("Node", owner = artboard) { this["name"] = clip.id; this["parentId"] = container }
                place(source, container, frame)
                for (s in clip.shapes) {
                    val shape = g.add("Shape", owner = artboard) { s.id?.let { this["name"] = it }; this["parentId"] = source }
                    val actual = place(shape, source, frame)
                    geometry(shape, s.geometry, actual.inverse() * frame * s.ctm, false)
                }
                clippings += Triple(clipped, source, clip.shapes.firstOrNull()?.style?.clipEvenOdd ?: false)
            }
        }

        /** Where a shape's transform origin sits in its own user space. */
        fun center(el: SvgShape): Pt = when (val geom = el.geometry) {
            is SvgGeometry.Ellipse -> Pt(geom.cx, geom.cy)
            is SvgGeometry.Rect -> Pt(geom.x + geom.w / 2, geom.y + geom.h / 2)
            // Vertex bounds, not curve bounds: what the spike rig used, and close enough for a pivot.
            is SvgGeometry.Path -> Box.of(geom.paths.flatMap { sp -> sp.vertices.map { it.p } })?.center ?: Pt(0.0, 0.0)
        }

        fun emitShape(el: SvgShape, clips: List<ClipUse>) {
            val key = Key.El(el)
            val parent = parentNode(key)
            val pivot = el.id?.let { shapeSpecs[it]?.pivot } ?: Pivot.ORIGIN
            val origin = if (pivot == Pivot.CENTER) center(el) else Pt(0.0, 0.0)
            val desired = toArtboard * el.ctm * Affine.translate(origin)
            // A line encloses nothing, so SVG never fills it.
            val fill = if (el.tag == "line") null else resolve(el.style.fill, el)
            val stroke = if (el.style.strokeWidth > 0) resolve(el.style.stroke, el) else null
            // A radial gradient stretched into an ellipse can only be drawn exactly in a frame where it
            // is a circle. That frame goes on an inner shape, so the named node keeps a plain transform.
            val gradientFrame = (fill as? Paint.Gradient)?.takeIf { it.g.radial && stroke == null }?.let { gradientMatrix(it.g, el) }
                ?.takeIf { !it.isConformal() }
            val shape: RivNode
            val target: RivNode
            if (gradientFrame != null) {
                target = g.add("Node", owner = artboard) { el.id?.let { this["name"] = it }; this["parentId"] = parent }
                place(target, parent, desired)
                setOpacity(target, opacity(el))
                shape = g.add("Shape", owner = artboard) { this["parentId"] = target }
                place(shape, target, toArtboard * el.ctm * gradientFrame)
            } else {
                shape = g.add("Shape", owner = artboard) { el.id?.let { this["name"] = it }; this["parentId"] = parent }
                place(shape, parent, desired)
                setOpacity(shape, opacity(el))
                target = shape
            }
            nodeOf[key] = target
            el.id?.let { targets[it] = target }
            // Element user space to shape space.
            val residual = frames.getValue(shape).inverse() * toArtboard * el.ctm
            val morphBase = el.id != null && el.id in morphBases
            val counts = if (morphBase) morphCounts(el) else null
            val parts = geometry(shape, el.geometry, residual, morphBase, counts)
            el.id?.let { id ->
                parts.singleOrNull()?.let { targets["$id/path"] = (it as? PathNode)?.node ?: it as RivNode }
                parts.flatMap { (it as? PathNode)?.vertices.orEmpty() }.forEachIndexed { i, v -> targets["$id/v$i"] = v }
                if (morphBase) morphable[id] = Morphable(el, shape, parts.map { p ->
                    (p as? PathNode ?: fail("art: '$id' is morphed but isn't a path")).let { it.sub to it.vertices }
                })
            }
            if (fill != null) {
                val node = g.add("Fill", owner = artboard) { this["parentId"] = shape; if (el.style.evenOdd) this["fillRule"] = 1 }
                val m = mutator(node, fill, el.style.fillOpacity, el, residual)
                el.id?.let { registerPaint("$it/fill", node, m) }
            }
            if (stroke != null) {
                val nonScaling = el.style.nonScalingStroke
                val node = g.add("Stroke", owner = artboard) {
                    this["parentId"] = shape
                    // A non-scaling stroke is drawn on the world-space path, so its width is in artboard units.
                    this["thickness"] = if (nonScaling) el.style.strokeWidth else el.style.strokeWidth * residual.meanScale
                    if (el.style.cap != LineCap.BUTT) this["cap"] = el.style.cap.rive
                    if (el.style.join != LineJoin.MITER) this["join"] = el.style.join.rive
                    if (nonScaling) this["transformAffectsStroke"] = false
                }
                val m = mutator(node, stroke, el.style.strokeOpacity, el, residual)
                el.id?.let { registerPaint("$it/stroke", node, m) }
                trim(el, node)
            } else if (el.id in trimmed || el.data.keys.any { it.startsWith("trim-") }) {
                warnings += "art: '${el.id ?: el.tag}' is trimmed but has no stroke; no TrimPath"
            }
            if (clips.isNotEmpty()) emitClips(target, parent, clips)
        }

        /** A TrimPath on [stroke] when a clip keys `<id>/stroke/trim` or [el] has `data-trim-*` (its rest values). */
        fun trim(el: SvgShape, stroke: RivNode) {
            val data = el.data.filterKeys { it.startsWith("trim-") }
            if (el.id !in trimmed && data.isEmpty()) return
            fun frac(key: String, dflt: Double): Double {
                val v = data["trim-$key"] ?: return dflt
                return v.trim().toDoubleOrNull() ?: dflt.also { warnings += "art: '${el.id}': data-trim-$key='$v' isn't a number; $dflt" }
            }
            data.keys.filter { it !in setOf("trim-start", "trim-end", "trim-offset") }
                .forEach { warnings += "art: '${el.id}': unknown data-$it (trims take start, end and offset)" }
            val node = g.add("TrimPath", owner = artboard) {
                this["parentId"] = stroke
                this["start"] = frac("start", 0.0)
                this["end"] = frac("end", 1.0)
                this["offset"] = frac("offset", 0.0)
                this["modeValue"] = 1 // sequential: one window along every contour in turn
            }
            el.id?.let { targets["$it/stroke/trim"] = node }
        }

        /** The DrawRules for [rule] on its node, one DrawTarget per placement. */
        fun drawRules(rule: DrawRuleSpec) {
            val where = "art.drawRules.${rule.id}"
            val subject = targets[rule.id] ?: fail("$where: no node, group or shape '${rule.id}'")
            val rules = g.add("DrawRules", owner = artboard) { this["parentId"] = subject }
            targets["${rule.id}/drawRules"] = rules
            val names = HashMap<String, RivNode>()
            for (t in rule.targets) {
                val at = "$where.${t.name}"
                if ((t.above == null) == (t.below == null)) fail("$at: set exactly one of above and below")
                val ref = t.above ?: t.below!!
                val other = targets[ref] ?: fail("$at: no node, group or shape '$ref'")
                val drawables = g.objects.filter { g.schema.isA(it.cls, "Drawable") && isUnder(it, other) }
                if (drawables.isEmpty()) fail("$at: '$ref' draws nothing")
                if (drawables.any { isUnder(it, subject) }) fail("$at: '$ref' is inside '${rule.id}'")
                val drawable = if (t.above != null) drawables.first() else drawables.last()
                val target = g.add("DrawTarget", owner = artboard) {
                    this["parentId"] = rules
                    this["drawableId"] = drawable
                    // Rive draws its drawable list last to first: "before" a drawable is above it.
                    this["placementValue"] = if (t.above != null) 0 else 1
                }
                if (names.put(t.name, target) != null) fail("$at: duplicate target name")
                targets["${rule.id}/drawRules/${t.name}"] = target
            }
            rule.active?.let { rules["drawTargetId"] = names[it] ?: fail("$where: active '$it' is not one of its targets") }
        }

        fun isUnder(n: RivNode, ancestor: RivNode): Boolean {
            var c: RivNode? = n
            while (c != null) {
                if (c === ancestor) return true
                c = if (g.schema.properties(c.type).containsKey("parentId")) (c["parentId"] as? rivc.GValue.Ref)?.target else null
            }
            return false
        }

        fun registerPaint(name: String, paint: RivNode, mutator: RivNode) {
            targets[name] = mutator
            targets["$name/paint"] = paint
            g.objects.filter { it.type == "GradientStop" && (it["parentId"] as? rivc.GValue.Ref)?.target === mutator }
                .forEachIndexed { i, stop -> targets["$name/stop$i"] = stop }
        }

        /**
         * A morph source's subpaths and the matrix from its user space to the artboard: an SVG path
         * element by id, or path data ([AnimTargets.PATH_DATA]) drawn in [base]'s own user space.
         */
        fun morphSource(base: SvgShape, src: String): Pair<List<SubPath>, Affine> {
            if (src.startsWith(AnimTargets.PATH_DATA)) {
                val d = src.removePrefix(AnimTargets.PATH_DATA)
                val subs = try { PathData.parse(d) } catch (e: PathDataException) { throw CompileException("morph of '${base.id}': ${e.message}") }
                return subs to toArtboard * base.ctm
            }
            val other = (doc.byId(src) as? SvgShape)?.takeIf { it.geometry is SvgGeometry.Path }
                ?: throw CompileException("morph: '$src' is not an SVG path in ${art.svg}")
            return other.geometry.subPaths() to toArtboard * other.ctm
        }

        fun describe(src: String) = if (src.startsWith(AnimTargets.PATH_DATA)) "path data '${src.drop(2).take(40)}…'" else "'$src'"

        /**
         * The vertex count of each subpath every pose of morph base [el] is subdivided to: the
         * largest across the base and its keys. Subpath counts and closedness must agree.
         */
        fun morphCounts(el: SvgShape): List<Int> {
            val base = el.geometry.subPaths()
            val counts = base.map { it.vertices.size }.toMutableList()
            for (src in morphKeys[el.id].orEmpty()) {
                val subs = morphSource(el, src).first
                if (subs.size != base.size) {
                    throw CompileException("morph: ${describe(src)} has ${subs.size} subpaths, '${el.id}' has ${base.size}")
                }
                if (subs.zip(base).any { (a, b) -> a.closed != b.closed }) {
                    throw CompileException("morph: ${describe(src)} and '${el.id}' differ in which subpaths are closed")
                }
                subs.forEachIndexed { i, s -> counts[i] = maxOf(counts[i], s.vertices.size) }
            }
            return counts
        }

        /** A `vertices` morph of shape [shapeId] into [svgId] (an element id or path data), in the shape's own space. */
        fun morph(shapeId: String, svgId: String): List<Pair<RivNode, Map<String, Double>>>? {
            val (el, shape, paths) = morphable[shapeId] ?: return null
            val (raw, m) = morphSource(el, svgId)
            if (raw.size != paths.size || raw.zip(paths).any { (a, b) -> a.vertices.size > b.first.vertices.size }) {
                throw CompileException(
                    "morph: ${describe(svgId)} has subpaths of ${raw.map { it.vertices.size }} vertices, '$shapeId' was built for ${paths.map { it.first.vertices.size }}",
                )
            }
            if (raw.zip(paths).any { (a, b) -> a.closed != b.first.closed }) throw CompileException("morph: ${describe(svgId)} and '$shapeId' differ in which subpaths are closed")
            val subs = raw.zip(paths).map { (sub, base) -> sub.transformed(frames.getValue(shape).inverse() * m).subdivided(base.first.vertices.size) }
            return subs.zip(paths).flatMap { (sub, base) ->
                sub.vertices.zip(base.second).map { (v, node) ->
                    val inV = v.inH - v.p
                    val outV = v.outH - v.p
                    node to linkedMapOf(
                        "x" to v.p.x, "y" to v.p.y,
                        "inRotation" to atan2(inV.y, inV.x), "inDistance" to inV.length,
                        "outRotation" to atan2(outV.y, outV.x), "outDistance" to outV.length,
                    )
                }
            }
        }

        fun resolve(p: SvgPaint, el: SvgShape): Paint? = when (p) {
            SvgPaint.None -> null
            is SvgPaint.Solid -> Paint.Solid(p.argb)
            is SvgPaint.Url -> {
                val grad = doc.gradients[p.id]
                when {
                    grad == null -> {
                        warnings += "art: <${el.tag}${el.id?.let { " id=$it" } ?: ""}> paints with '#${p.id}', which isn't a gradient"
                        p.fallback?.let { resolve(it, el) }
                    }
                    grad.stops.isEmpty() -> null
                    grad.stops.size == 1 -> Paint.Solid(grad.stops[0].argb)
                    else -> Paint.Gradient(grad)
                }
            }
        }

        /** Gradient space to [el]'s user space: the bounding box (for objectBoundingBox units), then gradientTransform. */
        fun gradientMatrix(grad: SvgGradient, el: SvgShape): Affine? {
            if (grad.userSpace) return grad.transform
            val b = el.localBounds() ?: return null
            if (b.width <= 0 || b.height <= 0) return null
            return Affine.translate(b.minX, b.minY) * Affine.scale(b.width, b.height) * grad.transform
        }

        fun mutator(paintNode: RivNode, paint: Paint, opacity: Double, el: SvgShape, residual: Affine): RivNode = when (paint) {
            is Paint.Solid -> g.add("SolidColor", owner = artboard) {
                this["parentId"] = paintNode
                val alpha = ((paint.argb ushr 24) and 0xFF) / 255.0 * opacity
                this["colorValue"] = Argb(SvgImport.withAlpha(paint.argb, alpha))
            }
            is Paint.Gradient -> gradient(paintNode, paint.g, opacity, el, residual)
        }

        fun gradient(paintNode: RivNode, grad: SvgGradient, opacity: Double, el: SvgShape, residual: Affine): RivNode {
            val gm = gradientMatrix(grad, el)
            if (gm == null) {
                warnings += "art: gradient '${grad.id}' on a shape with no area; drawn with its last stop"
                return mutator(paintNode, Paint.Solid(grad.stops.last().argb), opacity, el, residual)
            }
            val bbox = !grad.userSpace
            val w = viewBox.width
            val h = viewBox.height
            val m = residual * gm
            val start: Pt
            val end: Pt
            if (grad.radial) {
                val c = Pt(grad.cx.resolve(w, bbox), grad.cy.resolve(h, bbox))
                val r = grad.r.resolve(sqrt((w * w + h * h) / 2), bbox)
                val fx = grad.fx?.resolve(w, bbox)
                val fy = grad.fy?.resolve(h, bbox)
                if ((fx != null && abs(fx - c.x) > 1e-9) || (fy != null && abs(fy - c.y) > 1e-9)) {
                    warnings += "art: radialGradient '${grad.id}': focal point (fx, fy) isn't supported; centred"
                }
                if (!m.isConformal(1e-4)) {
                    warnings += "art: radialGradient '${grad.id}' is elliptical on a stroked shape (or under a skew); drawn as a circle"
                }
                start = m.apply(c)
                end = start + Pt(r * m.meanScale, 0.0)
            } else {
                val p1 = Pt(grad.x1.resolve(w, bbox), grad.y1.resolve(h, bbox))
                val p2 = Pt(grad.x2.resolve(w, bbox), grad.y2.resolve(h, bbox))
                start = m.apply(p1)
                // Isolines stay perpendicular to the gradient vector in Rive, so under a non-uniform
                // transform the end point is placed along the transformed normal, not at m(p2).
                val d = p2 - p1
                val inv = m.linear.inverse()
                val n = Pt(inv.a * d.x + inv.b * d.y, inv.c * d.x + inv.d * d.y)
                val nn = n.dot(n)
                end = if (nn < 1e-24) start else start + n * (d.dot(d) / nn)
            }
            val node = g.add(if (grad.radial) "RadialGradient" else "LinearGradient", owner = artboard) {
                this["parentId"] = paintNode
                this["startX"] = start.x; this["startY"] = start.y
                this["endX"] = end.x; this["endY"] = end.y
                if (abs(opacity - 1) > EPS) this["opacity"] = opacity
            }
            for (s in grad.stops) g.add("GradientStop", owner = artboard) {
                this["parentId"] = node
                this["colorValue"] = Argb(s.argb)
                this["position"] = s.offset
            }
            return node
        }

        /**
         * Emits [geom] under [shape]; [residual] maps the element's user space to the shape's.
         * A [morphBase] is always a path of detached vertices, so any morph target fits it.
         * Returns the paths (or the primitive) it wrote.
         */
        fun geometry(shape: RivNode, geom: SvgGeometry, residual: Affine, morphBase: Boolean, counts: List<Int>? = null): List<Any> {
            if (!morphBase) when (geom) {
                is SvgGeometry.Ellipse -> {
                    val trs = (residual * Affine.translate(geom.cx, geom.cy)).decompose()
                    if (trs != null) {
                        return listOf(g.add("Ellipse", owner = artboard) {
                            this["parentId"] = shape
                            primitiveTransform(trs)
                            this["width"] = geom.rx * 2 * abs(trs.scaleX)
                            this["height"] = geom.ry * 2 * abs(trs.scaleY)
                        })
                    }
                }
                is SvgGeometry.Rect -> {
                    val trs = (residual * Affine.translate(geom.x + geom.w / 2, geom.y + geom.h / 2)).decompose()
                    val rx = geom.rx * abs(trs?.scaleX ?: 0.0)
                    val ry = geom.ry * abs(trs?.scaleY ?: 0.0)
                    if (trs != null && abs(rx - ry) < 1e-6) {
                        return listOf(g.add("Rectangle", owner = artboard) {
                            this["parentId"] = shape
                            primitiveTransform(trs)
                            this["width"] = geom.w * abs(trs.scaleX)
                            this["height"] = geom.h * abs(trs.scaleY)
                            if (rx > EPS) this["cornerRadiusTL"] = rx
                        })
                    }
                }
                is SvgGeometry.Path -> {}
            }
            return geom.subPaths().mapIndexed { i, sp ->
                val t = sp.transformed(residual)
                path(shape, counts?.let { t.subdivided(it[i]) } ?: t, morphBase)
            }
        }

        /** A primitive's own x, y and rotation; its scale goes into its width and height. */
        fun RivNode.primitiveTransform(trs: Trs) {
            if (abs(trs.x) > EPS) this["x"] = trs.x
            if (abs(trs.y) > EPS) this["y"] = trs.y
            if (abs(trs.rotation) > EPS) this["rotation"] = trs.rotation
        }

        fun path(shape: RivNode, sp: SubPath, detachedOnly: Boolean): PathNode {
            val path = g.add("PointsPath", owner = artboard) { this["parentId"] = shape; this["isClosed"] = sp.closed }
            val vertices = sp.vertices.map { v ->
                val inV = v.inH - v.p
                val outV = v.outH - v.p
                val tol = 1e-6 * maxOf(1.0, inV.length, outV.length)
                when {
                    detachedOnly -> null
                    v.inH == v.p && v.outH == v.p -> g.add("StraightVertex", owner = artboard) {
                        this["parentId"] = path; this["x"] = v.p.x; this["y"] = v.p.y
                    }
                    outV.length > tol && abs(inV.x + outV.x) <= tol && abs(inV.y + outV.y) <= tol ->
                        g.add("CubicMirroredVertex", owner = artboard) {
                            this["parentId"] = path; this["x"] = v.p.x; this["y"] = v.p.y
                            this["rotation"] = atan2(outV.y, outV.x); this["distance"] = outV.length
                        }
                    else -> null
                } ?: g.add("CubicDetachedVertex", owner = artboard) {
                    this["parentId"] = path; this["x"] = v.p.x; this["y"] = v.p.y
                    this["inRotation"] = atan2(inV.y, inV.x); this["inDistance"] = inV.length
                    this["outRotation"] = atan2(outV.y, outV.x); this["outDistance"] = outV.length
                }
            }
            return PathNode(path, sp, vertices)
        }
    }
}
