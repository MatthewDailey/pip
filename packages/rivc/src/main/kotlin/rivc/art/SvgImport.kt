package rivc.art

import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.tan

/** An SVG the importer can't read; the message names the element. */
class SvgException(message: String) : RuntimeException(message)

/** A colour or gradient reference, as `fill` and `stroke` take them. */
sealed interface SvgPaint {
    data object None : SvgPaint

    /** ARGB, with the colour's own alpha (`#RRGGBBAA`, `rgba()`), before any `*-opacity`. */
    data class Solid(val argb: Long) : SvgPaint

    /** `url(#id)`; [fallback] is the paint after it, used when [id] doesn't resolve. */
    data class Url(val id: String, val fallback: SvgPaint?) : SvgPaint
}

enum class LineCap(val rive: Int) { BUTT(0), ROUND(1), SQUARE(2) }

enum class LineJoin(val rive: Int) { MITER(0), ROUND(1), BEVEL(2) }

/** The computed presentation style of one element (inherited properties already applied). */
data class SvgStyle(
    val fill: SvgPaint = SvgPaint.Solid(0xFF000000),
    val fillOpacity: Double = 1.0,
    val evenOdd: Boolean = false,
    val stroke: SvgPaint = SvgPaint.None,
    val strokeOpacity: Double = 1.0,
    val strokeWidth: Double = 1.0,
    val cap: LineCap = LineCap.BUTT,
    val join: LineJoin = LineJoin.MITER,
    val clipEvenOdd: Boolean = false,
    val visible: Boolean = true,
    val color: Long = 0xFF000000,
    val dashed: Boolean = false,
    /** `vector-effect: non-scaling-stroke`: the stroke keeps its width whatever the transforms (not inherited). */
    val nonScalingStroke: Boolean = false,
)

/** A shape's geometry in its own user space (before its `transform`). */
sealed interface SvgGeometry {
    fun subPaths(): List<SubPath>
    fun bounds(): Box?

    data class Path(val paths: List<SubPath>) : SvgGeometry {
        override fun subPaths() = paths
        override fun bounds() = paths.bounds()
    }

    data class Ellipse(val cx: Double, val cy: Double, val rx: Double, val ry: Double) : SvgGeometry {
        override fun subPaths(): List<SubPath> {
            val k = KAPPA
            val vs = mutableListOf(
                Vtx(Pt(cx + rx, cy), Pt(cx + rx, cy - ry * k), Pt(cx + rx, cy + ry * k)),
                Vtx(Pt(cx, cy + ry), Pt(cx + rx * k, cy + ry), Pt(cx - rx * k, cy + ry)),
                Vtx(Pt(cx - rx, cy), Pt(cx - rx, cy + ry * k), Pt(cx - rx, cy - ry * k)),
                Vtx(Pt(cx, cy - ry), Pt(cx - rx * k, cy - ry), Pt(cx + rx * k, cy - ry)),
            )
            return listOf(SubPath(vs, true))
        }
        override fun bounds() = Box(cx - rx, cy - ry, cx + rx, cy + ry)
    }

    /** [rx] and [ry] are already resolved and clamped by the SVG rules. */
    data class Rect(val x: Double, val y: Double, val w: Double, val h: Double, val rx: Double, val ry: Double) : SvgGeometry {
        override fun subPaths(): List<SubPath> {
            if (rx <= 0 || ry <= 0) {
                return listOf(SubPath(mutableListOf(Vtx(Pt(x, y)), Vtx(Pt(x + w, y)), Vtx(Pt(x + w, y + h)), Vtx(Pt(x, y + h))), true))
            }
            val kx = rx * KAPPA
            val ky = ry * KAPPA
            val r = x + w
            val b = y + h
            val vs = mutableListOf(
                Vtx(Pt(x + rx, y), inH = Pt(x + rx - kx, y)),
                Vtx(Pt(r - rx, y), outH = Pt(r - rx + kx, y)),
                Vtx(Pt(r, y + ry), inH = Pt(r, y + ry - ky)),
                Vtx(Pt(r, b - ry), outH = Pt(r, b - ry + ky)),
                Vtx(Pt(r - rx, b), inH = Pt(r - rx + kx, b)),
                Vtx(Pt(x + rx, b), outH = Pt(x + rx - kx, b)),
                Vtx(Pt(x, b - ry), inH = Pt(x, b - ry + ky)),
                Vtx(Pt(x, y + ry), outH = Pt(x, y + ry - ky)),
            )
            return listOf(SubPath(vs, true))
        }
        override fun bounds() = Box(x, y, x + w, y + h)
    }

    companion object {
        /** Control-point distance for a quarter circle of radius 1. */
        val KAPPA = 4.0 / 3.0 * tan(PI / 8)
    }
}

/**
 * One rendered element. [transform] is its own `transform`; [ctm] maps its user space to
 * the root SVG user space (the viewBox's coordinates), including [transform].
 * [data] holds its `data-*` attributes, keyed without the prefix.
 */
sealed class SvgElement {
    abstract val id: String?
    abstract val tag: String
    abstract val ctm: Affine
    abstract val style: SvgStyle
    abstract val opacity: Double
    abstract val clipPath: String?
    abstract val data: Map<String, String>
    var parent: SvgGroup? = null
        internal set

    /** Position in paint order (document order of rendered elements). */
    var order: Int = 0
        internal set

    /** Bounds in this element's own user space. */
    abstract fun localBounds(): Box?

    fun ancestors(): Sequence<SvgGroup> = generateSequence(parent) { it.parent }
}

class SvgGroup(
    override val id: String?,
    override val tag: String,
    override val ctm: Affine,
    override val style: SvgStyle,
    override val opacity: Double,
    override val clipPath: String?,
    override val data: Map<String, String>,
    val children: MutableList<SvgElement> = mutableListOf(),
) : SvgElement() {
    override fun localBounds(): Box? {
        val inv = runCatching { ctm.inverse() }.getOrNull() ?: return null
        return children.mapNotNull { c ->
            c.localBounds()?.let { b -> Box.of(b.corners().map { (inv * c.ctm).apply(it) }) }
        }.reduceOrNull { x, y -> x.union(y) }
    }

    fun descendants(): Sequence<SvgElement> = children.asSequence().flatMap { c ->
        sequenceOf(c) + if (c is SvgGroup) c.descendants() else emptySequence()
    }
}

class SvgShape(
    override val id: String?,
    override val tag: String,
    override val ctm: Affine,
    override val style: SvgStyle,
    override val opacity: Double,
    override val clipPath: String?,
    override val data: Map<String, String>,
    val geometry: SvgGeometry,
) : SvgElement() {
    override fun localBounds() = geometry.bounds()
}

/** A `<text>`: not drawn, kept as a placeholder at ([x], [y]) for later passes. */
class SvgText(
    override val id: String?,
    override val ctm: Affine,
    override val style: SvgStyle,
    override val opacity: Double,
    override val clipPath: String?,
    override val data: Map<String, String>,
    val x: Double,
    val y: Double,
    val text: String,
) : SvgElement() {
    override val tag get() = "text"
    override fun localBounds(): Box? = null
}

/** A length that may be a percentage (`50%` is stored as 0.5 with [percent]). */
data class Len(val value: Double, val percent: Boolean = false) {
    /** Resolves against [ref] (bounding-box units take fractions as they are). */
    fun resolve(ref: Double, fractionUnits: Boolean) = if (percent && !fractionUnits) value * ref else value
}

data class GradientStop(val offset: Double, val argb: Long)

class SvgGradient(
    val id: String,
    val radial: Boolean,
    val userSpace: Boolean,
    val transform: Affine,
    val x1: Len, val y1: Len, val x2: Len, val y2: Len,
    val cx: Len, val cy: Len, val r: Len, val fx: Len?, val fy: Len?,
    val stops: List<GradientStop>,
)

class SvgClipPath(
    val id: String,
    /** clipPathUnits="objectBoundingBox". */
    val boundingBox: Boolean,
    val transform: Affine,
    /** Content shapes; their [SvgElement.ctm] maps into the clipPath's coordinate system. */
    val shapes: List<SvgShape>,
)

class SvgDocument(
    val viewBox: Box?,
    val width: Double?,
    val height: Double?,
    val root: SvgGroup,
    val gradients: Map<String, SvgGradient>,
    val clipPaths: Map<String, SvgClipPath>,
    val warnings: List<String>,
) {
    /** Every rendered element, in paint order. */
    val elements: List<SvgElement> get() = root.descendants().toList()

    fun byId(id: String): SvgElement? = elements.firstOrNull { it.id == id }
}

/**
 * Reads an SVG into an [SvgDocument]: nested groups with transforms, the basic shapes and
 * paths, presentation attributes, inline `style` and simple `<style>` rules (tag, `.class`
 * and `#id` selectors), linear and radial gradients (with `href` inheritance), clip paths,
 * `<use>`, and `data-*` attributes. Anything it can't represent is skipped with a warning.
 */
object SvgImport {
    fun load(file: File): SvgDocument = parse(file.readText(), file.name)

    fun parse(text: String, name: String = "svg"): SvgDocument = Reader(text, name).read()

    private val INHERITED = setOf(
        "fill", "fill-opacity", "fill-rule", "stroke", "stroke-opacity", "stroke-width", "stroke-linecap",
        "stroke-linejoin", "stroke-dasharray", "clip-rule", "visibility", "color",
    )
    private val PRESENTATION = INHERITED + setOf("opacity", "clip-path", "display", "stop-color", "stop-opacity", "transform", "vector-effect")
    private val DEFS = setOf("linearGradient", "radialGradient", "clipPath")
    private val SKIPPED = setOf("title", "desc", "metadata", "defs", "style", "linearGradient", "radialGradient", "clipPath", "symbol")

    private class Reader(val text: String, val name: String) {
        val warnings = mutableListOf<String>()
        val byId = HashMap<String, Element>()
        // Gradients and clip paths are looked up only by url(#id) and gradient href, so they keep their
        // own table: a <g> that shares an id with a gradient (common in exported art) can't hide it.
        val defsById = HashMap<String, Element>()
        val css = mutableListOf<Pair<String, Map<String, String>>>() // selector, declarations, in source order
        var order = 0

        fun warn(msg: String) { warnings += "$name: $msg" }

        fun read(): SvgDocument {
            val factory = DocumentBuilderFactory.newInstance().apply {
                isNamespaceAware = false
                isValidating = false
                setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
                setFeature("http://xml.org/sax/features/external-general-entities", false)
                setFeature("http://xml.org/sax/features/external-parameter-entities", false)
            }
            val doc = try {
                factory.newDocumentBuilder().parse(text.byteInputStream())
            } catch (e: Exception) {
                throw SvgException("$name: not well-formed XML: ${e.message}")
            }
            val svg = doc.documentElement
            if (svg.tagName != "svg") throw SvgException("$name: root element is <${svg.tagName}>, not <svg>")
            index(svg)
            val viewBox = svg.attr("viewBox")?.let { vb ->
                val n = numbers(vb)
                if (n.size != 4 || n[2] <= 0 || n[3] <= 0) throw SvgException("$name: bad viewBox \"$vb\"")
                Box(n[0], n[1], n[0] + n[2], n[1] + n[3])
            }
            val width = svg.attr("width")?.let { lengthOrNull(it) }
            val height = svg.attr("height")?.let { lengthOrNull(it) }
            val rootStyle = computeStyle(svg, SvgStyle())
            val root = SvgGroup(svg.attr("id"), "svg", Affine.IDENTITY, rootStyle, opacity(svg), clipRef(svg), data(svg))
            children(svg, root, root.ctm, rootStyle, mutableSetOf())
            val gradients = defsById.values.filter { it.tagName == "linearGradient" || it.tagName == "radialGradient" }
                .mapNotNull { g -> gradient(g, mutableSetOf())?.let { g.getAttribute("id") to it } }.toMap()
            val clips = defsById.values.filter { it.tagName == "clipPath" }.associate { it.getAttribute("id") to clipPath(it) }
            return SvgDocument(viewBox, width, height, root, gradients, clips, warnings)
        }

        fun index(e: Element) {
            e.attr("id")?.let { id ->
                val table = if (e.tagName in DEFS) defsById else byId
                if (table.putIfAbsent(id, e) != null) warn("duplicate id '$id'; the first one wins")
            }
            if (e.tagName == "style") parseCss(e.textContent)
            for (c in e.childElements()) index(c)
        }

        // ---- styles ---------------------------------------------------------------------------

        fun parseCss(src: String) {
            val clean = src.replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")
            for (m in Regex("([^{}]+)\\{([^}]*)\\}").findAll(clean)) {
                val decls = declarations(m.groupValues[2])
                for (sel in m.groupValues[1].split(',').map { it.trim() }.filter { it.isNotEmpty() }) {
                    if (!Regex("[A-Za-z][\\w-]*|\\.[\\w-]+|#[\\w-]+|[A-Za-z][\\w-]*\\.[\\w-]+").matches(sel)) {
                        warn("CSS selector '$sel' isn't supported (only tag, .class, tag.class and #id); its rules are ignored")
                        continue
                    }
                    css += sel to decls
                }
            }
        }

        fun declarations(s: String): Map<String, String> = s.split(';').mapNotNull { d ->
            val k = d.substringBefore(':', "").trim()
            val v = d.substringAfter(':', "").replace("!important", "").trim()
            if (k.isEmpty() || v.isEmpty()) null else k to v
        }.toMap()

        fun specificity(sel: String) = when {
            sel.startsWith("#") -> 3
            sel.contains('.') -> 2
            else -> 1
        }

        fun matches(sel: String, e: Element): Boolean {
            val classes = e.attr("class")?.split(Regex("\\s+"))?.toSet() ?: emptySet()
            return when {
                sel.startsWith("#") -> e.attr("id") == sel.substring(1)
                sel.startsWith(".") -> sel.substring(1) in classes
                sel.contains('.') -> e.tagName == sel.substringBefore('.') && sel.substringAfter('.') in classes
                else -> e.tagName == sel
            }
        }

        /** Presentation attributes, then matching `<style>` rules by specificity, then `style`. */
        fun declared(e: Element): Map<String, String> {
            val out = LinkedHashMap<String, String>()
            for (p in PRESENTATION) e.attr(p)?.let { out[p] = it.trim() }
            css.withIndex().filter { matches(it.value.first, e) }
                .sortedWith(compareBy({ specificity(it.value.first) }, { it.index }))
                .forEach { out.putAll(it.value.second) }
            e.attr("style")?.let { out.putAll(declarations(it)) }
            return out
        }

        fun computeStyle(e: Element, parent: SvgStyle): SvgStyle {
            val d = declared(e)
            var s = parent.copy(visible = parent.visible)
            fun v(k: String) = d[k]?.takeIf { it != "inherit" }
            v("color")?.let { c -> color(c, parent.color)?.let { s = s.copy(color = it) } }
            v("fill")?.let { s = s.copy(fill = paint(it, s.color, "fill")) }
            v("stroke")?.let { s = s.copy(stroke = paint(it, s.color, "stroke")) }
            v("fill-opacity")?.let { s = s.copy(fillOpacity = fraction(it)) }
            v("stroke-opacity")?.let { s = s.copy(strokeOpacity = fraction(it)) }
            v("fill-rule")?.let { s = s.copy(evenOdd = it == "evenodd") }
            v("clip-rule")?.let { s = s.copy(clipEvenOdd = it == "evenodd") }
            v("stroke-width")?.let { w -> lengthOrNull(w)?.let { s = s.copy(strokeWidth = it) } }
            v("stroke-linecap")?.let { s = s.copy(cap = when (it) { "round" -> LineCap.ROUND; "square" -> LineCap.SQUARE; else -> LineCap.BUTT }) }
            v("stroke-linejoin")?.let {
                s = s.copy(join = when (it) { "round" -> LineJoin.ROUND; "bevel" -> LineJoin.BEVEL; else -> LineJoin.MITER })
            }
            v("stroke-dasharray")?.let { s = s.copy(dashed = it != "none") }
            v("visibility")?.let { s = s.copy(visible = it == "visible") }
            s = s.copy(nonScalingStroke = d["vector-effect"] == "non-scaling-stroke")
            return s
        }

        fun opacity(e: Element): Double = declared(e)["opacity"]?.let { fraction(it) } ?: 1.0

        fun displayed(e: Element) = declared(e)["display"] != "none"

        fun clipRef(e: Element): String? = declared(e)["clip-path"]?.let { v ->
            if (v == "none") null else Regex("url\\(\\s*['\"]?#([^)'\"]+)['\"]?\\s*\\)").find(v)?.groupValues?.get(1)
                ?: null.also { warn("<${e.tagName}${e.attr("id")?.let { " id=$it" } ?: ""}>: clip-path '$v' isn't a url(#id); ignored") }
        }

        fun data(e: Element): Map<String, String> {
            val out = LinkedHashMap<String, String>()
            val attrs = e.attributes
            for (i in 0 until attrs.length) {
                val a = attrs.item(i)
                if (a.nodeName.startsWith("data-")) out[a.nodeName.removePrefix("data-")] = a.nodeValue
            }
            return out
        }

        fun paint(v: String, current: Long, what: String): SvgPaint {
            val t = v.trim()
            if (t == "none") return SvgPaint.None
            val url = Regex("^url\\(\\s*['\"]?#([^)'\"]+)['\"]?\\s*\\)\\s*(.*)$").find(t)
            if (url != null) {
                val rest = url.groupValues[2].trim()
                return SvgPaint.Url(url.groupValues[1], if (rest.isEmpty()) null else paint(rest, current, what))
            }
            return color(t, current)?.let { SvgPaint.Solid(it) } ?: run {
                warn("$what '$v' isn't a colour rivc knows; using none")
                SvgPaint.None
            }
        }

        // ---- elements -------------------------------------------------------------------------

        fun children(e: Element, into: SvgGroup, ctm: Affine, style: SvgStyle, using: MutableSet<String>) {
            for (c in e.childElements()) element(c, into, ctm, style, using)?.let {
                it.parent = into
                into.children += it
            }
        }

        fun describe(e: Element) = "<${e.tagName}${e.attr("id")?.let { " id=\"$it\"" } ?: ""}>"

        fun element(e: Element, parent: SvgGroup?, parentCtm: Affine, parentStyle: SvgStyle, using: MutableSet<String>): SvgElement? {
            val tag = e.tagName
            if (tag in SKIPPED || tag.contains(':')) return null
            if (!displayed(e)) return null
            val style = computeStyle(e, parentStyle)
            val own = e.attr("transform")?.let { transform(it, describe(e)) } ?: Affine.IDENTITY
            val ctm = parentCtm * own
            val id = e.attr("id")
            val op = opacity(e)
            val clip = clipRef(e)
            val data = data(e)
            fun shape(geom: SvgGeometry?): SvgShape? {
                if (geom == null) return null
                if (style.dashed) warn("${describe(e)}: stroke-dasharray isn't supported; drawn solid")
                return SvgShape(id, tag, ctm, style, op, clip, data, geom).also { it.order = order++ }
            }
            return when (tag) {
                "g", "a", "switch" -> SvgGroup(id, tag, ctm, style, op, clip, data).also { g ->
                    g.order = order++
                    children(e, g, ctm, style, using)
                }
                "svg" -> {
                    warn("${describe(e)}: nested <svg> is drawn as a group without its viewport")
                    val x = e.num("x"); val y = e.num("y")
                    val inner = ctm * Affine.translate(x, y)
                    SvgGroup(id, tag, inner, style, op, clip, data).also { g -> g.order = order++; children(e, g, inner, style, using) }
                }
                "use" -> {
                    val ref = (e.attr("href") ?: e.attr("xlink:href"))?.removePrefix("#")
                    val target = ref?.let { byId[it] }
                    if (target == null) {
                        warn("${describe(e)}: href '${ref ?: ""}' doesn't name an element; skipped")
                        null
                    } else if (ref in using) {
                        warn("${describe(e)}: <use> refers to itself; skipped")
                        null
                    } else {
                        val inner = ctm * Affine.translate(e.num("x"), e.num("y"))
                        val g = SvgGroup(id, tag, inner, style, op, clip, data)
                        g.order = order++
                        using += ref
                        val content = if (target.tagName == "symbol") {
                            SvgGroup(null, "symbol", inner, style, 1.0, null, emptyMap()).also { sg ->
                                sg.order = order++
                                children(target, sg, inner, style, using)
                            }
                        } else {
                            // The referenced element is copied without its id, so ids stay unique.
                            element(target, g, inner, style, using)?.let { stripId(it) }
                        }
                        using -= ref
                        content?.let { it.parent = g; g.children += it }
                        g
                    }
                }
                "path" -> shape(e.attr("d")?.let { d ->
                    try {
                        SvgGeometry.Path(PathData.parse(d))
                    } catch (x: PathDataException) {
                        throw SvgException("$name: ${describe(e)}: ${x.message}")
                    }
                })
                "rect" -> {
                    val w = e.num("width"); val h = e.num("height")
                    if (w <= 0 || h <= 0) return null
                    var rx = e.attr("rx")?.let { lengthOrNull(it) }
                    var ry = e.attr("ry")?.let { lengthOrNull(it) }
                    if (rx == null) rx = ry
                    if (ry == null) ry = rx
                    shape(SvgGeometry.Rect(e.num("x"), e.num("y"), w, h, min(rx ?: 0.0, w / 2), min(ry ?: 0.0, h / 2)))
                }
                "circle" -> {
                    val r = e.num("r")
                    if (r <= 0) null else shape(SvgGeometry.Ellipse(e.num("cx"), e.num("cy"), r, r))
                }
                "ellipse" -> {
                    val rx = e.num("rx"); val ry = e.num("ry")
                    if (rx <= 0 || ry <= 0) null else shape(SvgGeometry.Ellipse(e.num("cx"), e.num("cy"), rx, ry))
                }
                "line" -> shape(
                    SvgGeometry.Path(listOf(SubPath(mutableListOf(Vtx(Pt(e.num("x1"), e.num("y1"))), Vtx(Pt(e.num("x2"), e.num("y2")))), false))),
                )
                "polyline", "polygon" -> {
                    val n = numbers(e.attr("points") ?: "")
                    if (n.size < 4) return null
                    val vs = n.chunked(2).filter { it.size == 2 }.mapTo(mutableListOf()) { Vtx(Pt(it[0], it[1])) }
                    shape(SvgGeometry.Path(listOf(SubPath(vs, tag == "polygon"))))
                }
                "text" -> {
                    val x = e.attr("x")?.let { numbers(it).firstOrNull() } ?: 0.0
                    val y = e.attr("y")?.let { numbers(it).firstOrNull() } ?: 0.0
                    warn("${describe(e)}: text isn't drawn; kept as a placeholder node")
                    SvgText(id, ctm, style, op, clip, data, x, y, e.textContent.trim()).also { it.order = order++ }
                }
                else -> {
                    warn("${describe(e)}: <$tag> isn't supported; skipped")
                    null
                }
            }
        }

        fun stripId(el: SvgElement): SvgElement = when (el) {
            is SvgShape -> SvgShape(null, el.tag, el.ctm, el.style, el.opacity, el.clipPath, el.data, el.geometry).also { it.order = el.order }
            is SvgText -> SvgText(null, el.ctm, el.style, el.opacity, el.clipPath, el.data, el.x, el.y, el.text).also { it.order = el.order }
            is SvgGroup -> SvgGroup(null, el.tag, el.ctm, el.style, el.opacity, el.clipPath, el.data).also { g ->
                g.order = el.order
                el.children.forEach { c -> stripId(c).also { it.parent = g; g.children += it } }
            }
        }

        // ---- paint servers and clips ----------------------------------------------------------

        fun gradient(e: Element, seen: MutableSet<String>): SvgGradient? {
            val id = e.getAttribute("id")
            if (!seen.add(id)) { warn("gradient '$id' inherits from itself"); return null }
            // href inheritance: attributes and stops this element doesn't set come from the one it names.
            val chain = mutableListOf(e)
            var cur = e
            while (true) {
                val ref = (cur.attr("href") ?: cur.attr("xlink:href"))?.removePrefix("#") ?: break
                val next = defsById[ref]
                if (next == null || next in chain || (next.tagName != "linearGradient" && next.tagName != "radialGradient")) {
                    if (next == null) warn("gradient '$id': href '#$ref' doesn't name a gradient")
                    break
                }
                chain += next
                cur = next
            }
            fun a(name: String) = chain.firstNotNullOfOrNull { it.attr(name) }
            fun len(name: String, default: String) = parseLen(a(name) ?: default)
            val stopsFrom = chain.firstOrNull { el -> el.childElements().any { it.tagName == "stop" } }
            var last = 0.0
            val stops = stopsFrom?.childElements()?.filter { it.tagName == "stop" }?.map { s ->
                val d = declared(s)
                val off = parseLen(s.attr("offset") ?: "0").let { if (it.percent) it.value else it.value }.coerceIn(0.0, 1.0)
                last = maxOf(last, off)
                val base = d["stop-color"]?.let { color(it, 0xFF000000) } ?: 0xFF000000
                val alpha = ((base ushr 24) and 0xFF) / 255.0 * (d["stop-opacity"]?.let { fraction(it) } ?: 1.0)
                GradientStop(last, withAlpha(base, alpha))
            } ?: emptyList()
            val radial = e.tagName == "radialGradient"
            return SvgGradient(
                id = id,
                radial = radial,
                userSpace = a("gradientUnits") == "userSpaceOnUse",
                transform = a("gradientTransform")?.let { transform(it, "gradient '$id'") } ?: Affine.IDENTITY,
                x1 = len("x1", "0%"), y1 = len("y1", "0%"), x2 = len("x2", "100%"), y2 = len("y2", "0%"),
                cx = len("cx", "50%"), cy = len("cy", "50%"), r = len("r", "50%"),
                fx = a("fx")?.let { parseLen(it) }, fy = a("fy")?.let { parseLen(it) },
                stops = stops,
            )
        }

        fun clipPath(e: Element): SvgClipPath {
            val id = e.getAttribute("id")
            val holder = SvgGroup(null, "clipPath", Affine.IDENTITY, computeStyle(e, SvgStyle()), 1.0, null, emptyMap())
            val saveOrder = order
            children(e, holder, Affine.IDENTITY, holder.style, mutableSetOf())
            order = saveOrder
            val shapes = holder.descendants().filterIsInstance<SvgShape>().toList()
            if (holder.descendants().any { it.clipPath != null }) warn("clipPath '$id': nested clip-path inside a clipPath is ignored")
            return SvgClipPath(
                id = id,
                boundingBox = e.attr("clipPathUnits") == "objectBoundingBox",
                transform = e.attr("transform")?.let { transform(it, "clipPath '$id'") } ?: Affine.IDENTITY,
                shapes = shapes,
            )
        }
    }

    // ---- values ---------------------------------------------------------------------------------

    private fun Element.attr(name: String): String? = if (hasAttribute(name)) getAttribute(name) else null

    private fun Element.num(name: String): Double = attr(name)?.let { lengthOrNull(it) } ?: 0.0

    private fun Element.childElements(): List<Element> {
        val out = mutableListOf<Element>()
        val nodes = childNodes
        for (i in 0 until nodes.length) (nodes.item(i) as? Element)?.takeIf { it.nodeType == Node.ELEMENT_NODE }?.let { out += it }
        return out
    }

    private val NUMBER = Regex("[+-]?(?:\\d+\\.?\\d*|\\.\\d+)(?:[eE][+-]?\\d+)?")

    fun numbers(s: String): List<Double> = NUMBER.findAll(s).map { it.value.toDouble() }.toList()

    /** A length in user units; `px` and unitless are the same, other units are rejected. */
    fun lengthOrNull(s: String): Double? {
        val m = Regex("^\\s*([+-]?(?:\\d+\\.?\\d*|\\.\\d+)(?:[eE][+-]?\\d+)?)\\s*(px)?\\s*$").find(s) ?: return null
        return m.groupValues[1].toDouble()
    }

    fun parseLen(s: String): Len {
        val t = s.trim()
        return if (t.endsWith("%")) Len(t.removeSuffix("%").trim().toDouble() / 100, percent = true)
        else Len(lengthOrNull(t) ?: t.toDoubleOrNull() ?: 0.0)
    }

    fun fraction(s: String): Double {
        val t = s.trim()
        val v = if (t.endsWith("%")) t.removeSuffix("%").toDouble() / 100 else t.toDoubleOrNull() ?: 1.0
        return v.coerceIn(0.0, 1.0)
    }

    fun withAlpha(argb: Long, alpha: Double): Long =
        ((Math.round(alpha.coerceIn(0.0, 1.0) * 255) shl 24) or (argb and 0xFFFFFF))

    /** A transform list: matrix, translate, scale, rotate (with optional centre), skewX, skewY. */
    fun transform(s: String, where: String): Affine {
        var m = Affine.IDENTITY
        val fn = Regex("([a-zA-Z]+)\\s*\\(([^)]*)\\)")
        val consumed = fn.replace(s, "").replace(",", "").trim()
        if (consumed.isNotEmpty()) throw SvgException("$where: bad transform \"$s\"")
        for (f in fn.findAll(s)) {
            val a = numbers(f.groupValues[2])
            fun need(vararg counts: Int) {
                if (a.size !in counts) throw SvgException("$where: ${f.groupValues[1]}() takes ${counts.joinToString(" or ")} numbers in \"$s\"")
            }
            m *= when (f.groupValues[1]) {
                "matrix" -> { need(6); Affine(a[0], a[1], a[2], a[3], a[4], a[5]) }
                "translate" -> { need(1, 2); Affine.translate(a[0], a.getOrElse(1) { 0.0 }) }
                "scale" -> { need(1, 2); Affine.scale(a[0], a.getOrElse(1) { a[0] }) }
                "rotate" -> {
                    need(1, 3)
                    val r = Affine.rotate(Math.toRadians(a[0]))
                    if (a.size == 3) Affine.translate(a[1], a[2]) * r * Affine.translate(-a[1], -a[2]) else r
                }
                "skewX" -> { need(1); Affine(c = tan(Math.toRadians(a[0]))) }
                "skewY" -> { need(1); Affine(b = tan(Math.toRadians(a[0]))) }
                else -> throw SvgException("$where: unknown transform ${f.groupValues[1]}() in \"$s\"")
            }
        }
        return m
    }

    /** `#rgb`, `#rgba`, `#rrggbb`, `#rrggbbaa`, `rgb()`/`rgba()` (numbers or %), named colours, `currentColor`. */
    fun color(s: String, current: Long): Long? {
        val t = s.trim().lowercase()
        if (t == "currentcolor") return current
        if (t == "transparent") return 0x00000000
        if (t.startsWith("#")) {
            val h = t.substring(1)
            if (!h.all { it.isDigit() || it in 'a'..'f' }) return null
            return when (h.length) {
                3 -> 0xFF000000 or h.map { "$it$it" }.joinToString("").toLong(16)
                4 -> h.map { "$it$it" }.joinToString("").let { (it.substring(6).toLong(16) shl 24) or it.substring(0, 6).toLong(16) }
                6 -> 0xFF000000 or h.toLong(16)
                8 -> (h.substring(6).toLong(16) shl 24) or h.substring(0, 6).toLong(16)
                else -> null
            }
        }
        val fn = Regex("^rgba?\\(([^)]*)\\)$").find(t)
        if (fn != null) {
            val parts = fn.groupValues[1].split(Regex("[,\\s/]+")).filter { it.isNotEmpty() }
            if (parts.size < 3) return null
            fun channel(p: String) = if (p.endsWith("%")) p.removeSuffix("%").toDouble() * 2.55 else p.toDouble()
            val (r, g, b) = parts.take(3).map { Math.round(channel(it).coerceIn(0.0, 255.0)) }
            val alpha = parts.getOrNull(3)?.let { fraction(it) } ?: 1.0
            return withAlpha((r shl 16) or (g shl 8) or b, alpha)
        }
        return NAMED[t]?.let { 0xFF000000 or it }
    }

    private val NAMED: Map<String, Long> = mapOf(
        "black" to 0x000000, "white" to 0xFFFFFF, "red" to 0xFF0000, "green" to 0x008000, "blue" to 0x0000FF,
        "yellow" to 0xFFFF00, "cyan" to 0x00FFFF, "aqua" to 0x00FFFF, "magenta" to 0xFF00FF, "fuchsia" to 0xFF00FF,
        "gray" to 0x808080, "grey" to 0x808080, "silver" to 0xC0C0C0, "maroon" to 0x800000, "olive" to 0x808000,
        "lime" to 0x00FF00, "teal" to 0x008080, "navy" to 0x000080, "purple" to 0x800080, "orange" to 0xFFA500,
        "pink" to 0xFFC0CB, "brown" to 0xA52A2A, "gold" to 0xFFD700, "beige" to 0xF5F5DC, "tan" to 0xD2B48C,
        "coral" to 0xFF7F50, "salmon" to 0xFA8072, "khaki" to 0xF0E68C, "ivory" to 0xFFFFF0, "skyblue" to 0x87CEEB,
        "lightblue" to 0xADD8E6, "lightgray" to 0xD3D3D3, "lightgrey" to 0xD3D3D3, "darkgray" to 0xA9A9A9,
        "darkgrey" to 0xA9A9A9, "dimgray" to 0x696969, "dimgrey" to 0x696969, "whitesmoke" to 0xF5F5F5,
        "gainsboro" to 0xDCDCDC, "darkgreen" to 0x006400, "forestgreen" to 0x228B22, "seagreen" to 0x2E8B57,
        "lightgreen" to 0x90EE90, "darkblue" to 0x00008B, "royalblue" to 0x4169E1, "steelblue" to 0x4682B4,
        "slategray" to 0x708090, "slategrey" to 0x708090, "indigo" to 0x4B0082, "violet" to 0xEE82EE,
        "orchid" to 0xDA70D6, "plum" to 0xDDA0DD, "lavender" to 0xE6E6FA, "crimson" to 0xDC143C,
        "firebrick" to 0xB22222, "darkred" to 0x8B0000, "tomato" to 0xFF6347, "orangered" to 0xFF4500,
        "chocolate" to 0xD2691E, "sienna" to 0xA0522D, "peru" to 0xCD853F, "goldenrod" to 0xDAA520,
        "wheat" to 0xF5DEB3, "linen" to 0xFAF0E6, "snow" to 0xFFFAFA, "honeydew" to 0xF0FFF0, "mintcream" to 0xF5FFFA,
        "azure" to 0xF0FFFF, "aliceblue" to 0xF0F8FF, "ghostwhite" to 0xF8F8FF, "seashell" to 0xFFF5EE,
        "oldlace" to 0xFDF5E6, "cornsilk" to 0xFFF8DC, "lemonchiffon" to 0xFFFACD, "turquoise" to 0x40E0D0,
        "darkorange" to 0xFF8C00, "hotpink" to 0xFF69B4, "deeppink" to 0xFF1493, "midnightblue" to 0x191970,
        "darkslategray" to 0x2F4F4F, "darkslategrey" to 0x2F4F4F, "olivedrab" to 0x6B8E23, "yellowgreen" to 0x9ACD32,
        "darkolivegreen" to 0x556B2F, "saddlebrown" to 0x8B4513, "rosybrown" to 0xBC8F8F, "burlywood" to 0xDEB887,
        "sandybrown" to 0xF4A460, "peachpuff" to 0xFFDAB9, "moccasin" to 0xFFE4B5, "mistyrose" to 0xFFE4E1,
        "lightyellow" to 0xFFFFE0, "lightpink" to 0xFFB6C1, "lightcoral" to 0xF08080, "cornflowerblue" to 0x6495ED,
        "dodgerblue" to 0x1E90FF, "deepskyblue" to 0x00BFFF, "lightskyblue" to 0x87CEFA, "powderblue" to 0xB0E0E6,
        "cadetblue" to 0x5F9EA0, "darkcyan" to 0x008B8B, "lightseagreen" to 0x20B2AA, "mediumseagreen" to 0x3CB371,
        "springgreen" to 0x00FF7F, "lawngreen" to 0x7CFC00, "chartreuse" to 0x7FFF00, "greenyellow" to 0xADFF2F,
        "palegreen" to 0x98FB98, "darkkhaki" to 0xBDB76B, "palegoldenrod" to 0xEEE8AA, "thistle" to 0xD8BFD8,
        "mediumpurple" to 0x9370DB, "rebeccapurple" to 0x663399, "darkviolet" to 0x9400D3, "darkmagenta" to 0x8B008B,
        "mediumvioletred" to 0xC71585, "palevioletred" to 0xDB7093, "indianred" to 0xCD5C5C,
        "darksalmon" to 0xE9967A, "lightsalmon" to 0xFFA07A, "antiquewhite" to 0xFAEBD7, "bisque" to 0xFFE4C4,
        "blanchedalmond" to 0xFFEBCD, "navajowhite" to 0xFFDEAD, "papayawhip" to 0xFFEFD5, "floralwhite" to 0xFFFAF0,
        "lavenderblush" to 0xFFF0F5, "darkslateblue" to 0x483D8B, "slateblue" to 0x6A5ACD, "mediumslateblue" to 0x7B68EE,
        "lightsteelblue" to 0xB0C4DE, "lightslategray" to 0x778899, "lightslategrey" to 0x778899, "mediumblue" to 0x0000CD,
        "darkturquoise" to 0x00CED1, "mediumturquoise" to 0x48D1CC, "paleturquoise" to 0xAFEEEE, "aquamarine" to 0x7FFFD4,
        "mediumaquamarine" to 0x66CDAA, "darkseagreen" to 0x8FBC8F, "limegreen" to 0x32CD32, "lightcyan" to 0xE0FFFF,
        "mediumorchid" to 0xBA55D3, "darkorchid" to 0x9932CC, "blueviolet" to 0x8A2BE2, "mediumspringgreen" to 0x00FA9A,
        "lightgoldenrodyellow" to 0xFAFAD2, "darkgoldenrod" to 0xB8860B,
    )
}
