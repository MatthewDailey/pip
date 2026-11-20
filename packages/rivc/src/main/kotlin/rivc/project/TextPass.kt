package rivc.project

import rivc.Argb
import rivc.AssetSpec
import rivc.RivGraph
import rivc.RivNode
import rivc.Schema
import rivc.TextSpec
import rivc.anim.ViewModelResult
import rivc.anim.parseColor
import rivc.spike.CompileException
import java.io.File

/**
 * Fonts and text boxes.
 *
 * Recipe (showreel spike, feature 2, both runtimes):
 * - `FontAsset` then `FileAssetContents` holding the whole font file, at file level before
 *   the first artboard. The runtime shapes text with HarfBuzz from those bytes, so no glyph
 *   preparation and no asset loader are needed. A text style names its font by asset index
 *   (stream order among the file's assets).
 * - `Text` (sizing, width, height, align, wrap, overflow, vertical align) > `TextStylePaint`
 *   (size, font, line height) > `Fill` > `SolidColor` for the colour, then a `TextValueRun`
 *   naming the style and holding the string.
 * - A text change is a held `KeyFrameString` on the run's `text`, appended to its clip by
 *   [CueKeys]. Before a track's first key the runtime applies that key's value, so a change
 *   list that doesn't start at frame 0 gets a frame-0 key with the box's own text.
 * - A `TextStylePaint` is a shape-paint container and draws its paints in child order, so a
 *   `Stroke` written before the `Fill` is a real outline under the glyphs (37b3bc7 and the
 *   web runtime both draw it). A `LinearGradient` fill is in the box's space (0, 0 at its top
 *   left); two stops at one position make a hard edge.
 * - A run per font: a text whose font lacks a character draws that stretch in a run styled
 *   with the font's fallback (the runtime has no fallback of its own for embedded fonts).
 */
class TextPass(private val schema: Schema = Schema.bundled) {
    private fun fail(msg: String): Nothing = throw CompileException(msg)

    /** An embedded font: its asset, which characters it has, and the asset to draw the rest in. */
    class Font(val node: RivNode, private val awt: java.awt.Font, val fallback: String?) {
        fun covers(codePoint: Int) = awt.canDisplay(codePoint)
    }

    /** Embeds [assets]' fonts. Call after the Backboard and before any artboard. Returns them by name. */
    fun assets(g: RivGraph, assets: List<AssetSpec>, baseDir: File): Map<String, Font> {
        val out = LinkedHashMap<String, Font>()
        assets.forEachIndexed { i, a ->
            val where = "assets.${a.name}"
            if (a.name in out) fail("assets: duplicate asset '${a.name}'")
            val file = File(baseDir, a.font)
            if (!file.isFile) fail("$where: no font file at $file")
            val bytes = file.readBytes()
            val magic = if (bytes.size >= 4) bytes.copyOfRange(0, 4) else ByteArray(0)
            val known = listOf(byteArrayOf(0, 1, 0, 0), "OTTO".toByteArray(), "true".toByteArray())
            if (known.none { it.contentEquals(magic) }) fail("$where: $file is not a TrueType or OpenType font")
            val awt = runCatching { java.awt.Font.createFont(java.awt.Font.TRUETYPE_FONT, file) }
                .getOrElse { fail("$where: can't read $file: ${it.message}") }
            val asset = g.add("FontAsset") { this["name"] = a.name; this["assetId"] = i }
            g.add("FileAssetContents", owner = asset) { this["bytes"] = bytes }
            out[a.name] = Font(asset, awt, a.fallback)
        }
        for (a in assets) a.fallback?.let { fb ->
            if (fb !in out) fail("assets.${a.name}.fallback: no font asset '$fb'")
            if (fb == a.name) fail("assets.${a.name}.fallback: a font can't fall back to itself")
        }
        return out
    }

    /**
     * A placed text box: the box (`<id>`), its colour (`<id>/fill`: the solid colour, or with a
     * gloss the stop below the line), its stroke colour (`<id>/stroke`) and its first run
     * (`<id>/run`), as clips target them.
     */
    class Placed(val box: RivNode, val color: RivNode, val stroke: RivNode?, val run: RivNode)

    /**
     * Emits [t] into [artboard] and registers its text changes with [keys]. [layout] binds
     * its view-model properties right after the box.
     */
    fun text(
        g: RivGraph,
        artboard: RivNode,
        t: TextSpec,
        fonts: Map<String, Font>,
        vm: ViewModelResult,
        layout: LayoutBinding,
        keys: CueKeys,
        where: String,
    ): Placed {
        if (t.font !in fonts) fail("$where.font: no font asset '${t.font}' (assets: ${fonts.keys.joinToString()})")
        if (t.size <= 0) fail("$where.size: must be positive")
        if (t.height != null && t.width == null) fail("$where: a fixed height needs a width too")
        t.stroke?.let { if (it.width <= 0) fail("$where.stroke.width: must be positive") }
        val sizing = when {
            t.width != null && t.height != null -> 2 // fixed
            t.width != null -> 1 // auto height
            else -> 0 // auto width
        }
        val runs = runs(t.text, t.font, fonts)
        val fallsBack = { s: String -> runs(s, t.font, fonts).any { it.first != t.font } }
        if (t.changes.isNotEmpty() && (fallsBack(t.text) || t.changes.any { fallsBack(it.text) })) {
            fail("$where.changes: a text that changes can't mix fonts (one run is keyed); split it into texts")
        }
        val box = g.add("Text", owner = artboard) {
            this["name"] = t.id
            this["parentId"] = artboard
            this["x"] = t.x; this["y"] = t.y
            if (t.opacity != 1.0) this["opacity"] = t.opacity
            this["sizingValue"] = sizing
            t.width?.let { this["width"] = it }
            t.height?.let { this["height"] = it }
            this["alignValue"] = t.align.value
            this["wrapValue"] = if (t.wrap) 0 else 1
            this["overflowValue"] = 0
            this["verticalAlignValue"] = t.verticalAlign.value
        }
        layout.bind(g, box, t.bind, vm, where)

        val styles = LinkedHashMap<String, Style>()
        for (name in (listOf(t.font) + runs.map { it.first }).distinct()) {
            styles[name] = style(g, artboard, box, t, fonts.getValue(name).node, where)
        }
        val runNodes = runs.map { (name, text) ->
            g.add("TextValueRun", owner = artboard) {
                this["parentId"] = box; this["styleId"] = styles.getValue(name).style; this["text"] = text
            }
        }
        val run = runNodes.first()

        val text = schema.property("TextValueRun", "text")
        for ((clip, changes) in t.changes.groupBy { it.clip }) {
            if (changes.none { it.frame == 0 }) keys.add(clip, run, text, CueKeys.Text(0, t.text), "$where.changes")
            for (c in changes) keys.add(clip, run, text, CueKeys.Text(c.frame, c.text), "$where.changes")
        }
        val main = styles.getValue(t.font)
        return Placed(box, main.color, main.stroke, run)
    }

    private class Style(val style: RivNode, val color: RivNode, val stroke: RivNode?)

    /** One style of [t] in font asset [font], with its paints. */
    private fun style(g: RivGraph, artboard: RivNode, box: RivNode, t: TextSpec, font: RivNode, where: String): Style {
        val style = g.add("TextStylePaint", owner = artboard) {
            this["parentId"] = box
            this["fontSize"] = t.size
            this["fontAssetId"] = font
            t.lineHeight?.let { this["lineHeight"] = it }
            t.letterSpacing?.let { this["letterSpacing"] = it }
        }
        // Paints draw in child order: the stroke first, so the fill covers its inner half.
        val strokeColor = t.stroke?.let { s ->
            val stroke = g.add("Stroke", owner = artboard) {
                this["parentId"] = style; this["thickness"] = s.width
                this["join"] = ROUND; this["cap"] = ROUND
            }
            g.add("SolidColor", owner = artboard) { this["parentId"] = stroke; this["colorValue"] = Argb(parseColor(s.color, "$where.stroke.color")) }
        }
        val fill = g.add("Fill", owner = artboard) { this["parentId"] = style }
        val fillArgb = Argb(parseColor(t.color, "$where.color"))
        val gloss = t.gloss
        val color = if (gloss == null) {
            g.add("SolidColor", owner = artboard) { this["parentId"] = fill; this["colorValue"] = fillArgb }
        } else {
            if (gloss.y <= 0) fail("$where.gloss.y: must be below the box's top")
            // The gloss colour from the top to the line, the fill below it: the gradient spans 2 y, so the edge is at 0.5.
            val grad = g.add("LinearGradient", owner = artboard) {
                this["parentId"] = fill; this["startX"] = 0.0; this["startY"] = 0.0; this["endX"] = 0.0; this["endY"] = 2 * gloss.y
            }
            val hi = Argb(parseColor(gloss.color, "$where.gloss.color"))
            for (pos in listOf(0.0, 0.5)) g.add("GradientStop", owner = artboard) {
                this["parentId"] = grad; this["colorValue"] = hi; this["position"] = pos
            }
            val below = g.add("GradientStop", owner = artboard) { this["parentId"] = grad; this["colorValue"] = fillArgb; this["position"] = 0.5 }
            g.add("GradientStop", owner = artboard) { this["parentId"] = grad; this["colorValue"] = fillArgb; this["position"] = 1.0 }
            below
        }
        return Style(style, color, strokeColor)
    }

    /** [text] split into (font asset, string) runs: characters [font] lacks go to its fallback when that has them. */
    private fun runs(text: String, font: String, fonts: Map<String, Font>): List<Pair<String, String>> {
        val main = fonts.getValue(font)
        val fallback = main.fallback ?: return listOf(font to text)
        val fb = fonts.getValue(fallback)
        val out = mutableListOf<Pair<String, StringBuilder>>()
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            val name = if (!main.covers(cp) && fb.covers(cp)) fallback else font
            if (out.lastOrNull()?.first != name) out += name to StringBuilder()
            out.last().second.appendCodePoint(cp)
            i += Character.charCount(cp)
        }
        return if (out.isEmpty()) listOf(font to text) else out.map { it.first to it.second.toString() }
    }

    private companion object {
        /** Stroke `join` and `cap` round (Rive's enums: join miter 0, round 1, bevel 2; cap butt 0, round 1, square 2). */
        const val ROUND = 1
    }
}
