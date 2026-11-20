package showreel

import rivc.ArtSpec
import rivc.ArtboardSpec
import rivc.PipSource
import java.awt.Font
import java.awt.font.FontRenderContext
import java.awt.font.LineBreakMeasurer
import java.awt.font.TextAttribute
import java.io.File
import java.text.AttributedString
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * Small artboards the converter draws itself, for things the Kotlin renderer paints inline and no
 * set piece covers: the flashes, Pip's ground shadow, and speech-bubble boxes at each text's size
 * (fx-bubble's geometry, colours and stroke; its `textWidth`/`textHeight` inputs are prose in the
 * draft, and its rect has no id to key, so each box is drawn at its size instead).
 */
object Generated {
    const val FLASH_PANEL = "gen-flash-panel"
    const val FLASH_SCREEN = "gen-flash-screen"
    const val SHADOW = "gen-shadow"
    const val BUBBLE_TAIL = "gen-bubble-tail"

    /** fx-bubble's measurements (Kotlin dp at 420 dpi). */
    const val DENSITY = 2.625
    const val BUBBLE_TEXT = 19 * DENSITY
    const val BUBBLE_LINE = 58.35
    const val BUBBLE_PAD = 14 * DENSITY
    const val BUBBLE_GAP = 14 * DENSITY
    const val BUBBLE_MAX_W = 260 * DENSITY
    const val BUBBLE_MIN_W = 40 * DENSITY
    const val BUBBLE_R = 18 * DENSITY
    const val BUBBLE_STROKE = 3 * DENSITY

    class Piece(val name: String, val svg: String, val width: Double, val height: Double, val anchorX: Double, val anchorY: Double)

    fun bubbleBoxName(w: Double, h: Double) = "gen-bubble-box-${w.toInt()}x${h.toInt()}"

    /** Writes [p] as `<dir>/<name>.svg` plus a rivc source `<name>.json`; returns the source file. */
    fun write(dir: File, p: Piece): File {
        dir.mkdirs()
        File(dir, "${p.name}.svg").writeText(p.svg)
        val src = PipSource(artboard = ArtboardSpec(p.name, p.width, p.height), art = ArtSpec(svg = "${p.name}.svg"))
        val f = File(dir, "${p.name}.json")
        f.writeText(PipSource.json.encodeToString(PipSource.serializer(), src) + "\n")
        return f
    }

    fun flashPanel() = Piece(
        FLASH_PANEL,
        """<svg xmlns="http://www.w3.org/2000/svg" viewBox="-470 -380 940 760" width="940" height="760">
<rect id="flash" x="-470" y="-380" width="940" height="760" rx="64" fill="#FFFFFF"/>
</svg>
""",
        940.0, 760.0, 470.0, 380.0,
    )

    fun flashScreen() = Piece(
        FLASH_SCREEN,
        """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 1080 2424" width="1080" height="2424">
<rect id="flash" x="0" y="0" width="1080" height="2424" fill="#FFFFFF"/>
</svg>
""",
        1080.0, 2424.0, 0.0, 0.0,
    )

    /** PipRenderer's ground shadow: a radial gradient disc of radius 100 the host scales into an ellipse. */
    fun shadow() = Piece(
        SHADOW,
        """<svg xmlns="http://www.w3.org/2000/svg" viewBox="-100 -100 200 200" width="200" height="200">
<defs><radialGradient id="g" cx="0" cy="0" r="100" gradientUnits="userSpaceOnUse">
<stop offset="0" stop-color="#3A2A26" stop-opacity="0.3725"/><stop offset="0.5" stop-color="#3A2A26" stop-opacity="0.2157"/>
<stop offset="1" stop-color="#3A2A26" stop-opacity="0"/></radialGradient></defs>
<circle id="shadow" cx="0" cy="0" r="100" fill="url(#g)"/>
</svg>
""",
        200.0, 200.0, 100.0, 100.0,
    )

    /** The tail, tip at (0, 0): its fill covers the box's bottom stroke, then the two stroked edges. */
    fun bubbleTail(): Piece {
        val hw = 10 * DENSITY
        val g = BUBBLE_GAP
        return Piece(
            BUBBLE_TAIL,
            """<svg xmlns="http://www.w3.org/2000/svg" viewBox="-40 -45 80 55" width="80" height="55">
<path id="tail-fill" d="M${-hw},${-g - 1} L0,0 L$hw,${-g - 1} Z" fill="#FFFFFF"/>
<path id="tail-edge" d="M${-hw},${-g} L0,0 L$hw,${-g}" fill="none" stroke="#F05248" stroke-width="$BUBBLE_STROKE" stroke-linecap="round" stroke-linejoin="round"/>
</svg>
""",
            80.0, 55.0, 40.0, 45.0,
        )
    }

    /** A bubble box [w] x [h], its top-left at (0, 0), with fx-bubble's soft shadow. */
    fun bubbleBox(w: Double, h: Double): Piece {
        val d = DENSITY
        val m = 8.0
        return Piece(
            bubbleBoxName(w, h),
            """<svg xmlns="http://www.w3.org/2000/svg" viewBox="${-m} ${-m} ${w + 2 * m + 2 * d} ${h + 2 * m + 4 * d}" width="${w + 2 * m + 2 * d}" height="${h + 2 * m + 4 * d}">
<rect id="box-shadow" x="${2 * d}" y="${4 * d}" width="$w" height="$h" rx="$BUBBLE_R" fill="#3A2A26" fill-opacity="0.18"/>
<rect id="box" x="0" y="0" width="$w" height="$h" rx="$BUBBLE_R" fill="#FFFFFF" stroke="#F05248" stroke-width="$BUBBLE_STROKE"/>
</svg>
""",
            w + 2 * m + 2 * d, h + 2 * m + 4 * d, m, m,
        )
    }
}

/** Measures text in the font the .riv embeds, so boxes fit what Rive will draw. */
class TextMeasure(fontFile: File) {
    private val base: Font = Font.createFont(Font.TRUETYPE_FONT, fontFile)
    private val frc = FontRenderContext(null, true, true)

    fun width(text: String, size: Double): Double = base.deriveFont(size.toFloat()).getStringBounds(text, frc).width

    /** Ascent and descent at [size], from the font's line metrics. */
    fun ascent(size: Double): Double = base.deriveFont(size.toFloat()).getLineMetrics("Ag", frc).ascent.toDouble()
    fun descent(size: Double): Double = base.deriveFont(size.toFloat()).getLineMetrics("Ag", frc).descent.toDouble()

    /** Lines [text] breaks into at [width] (StaticLayout-like greedy word wrap). */
    fun lines(text: String, size: Double, width: Double): Int {
        if (text.isEmpty()) return 1
        val attr = AttributedString(text).apply { addAttribute(TextAttribute.FONT, base.deriveFont(size.toFloat())) }
        val m = LineBreakMeasurer(attr.iterator, frc)
        var n = 0
        while (m.position < text.length) { m.nextLayout(width.toFloat() + 0.5f); n++ }
        return max(1, n)
    }

    /** The bubble's text box width: the text's own width, capped at 260 dp, at least 40 dp. */
    fun bubbleTextWidth(text: String): Double =
        ceil(min(Generated.BUBBLE_MAX_W, width(text, Generated.BUBBLE_TEXT))).coerceAtLeast(Generated.BUBBLE_MIN_W)
}
