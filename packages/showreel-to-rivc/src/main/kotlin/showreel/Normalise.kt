package showreel

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import rivc.ArtSpec
import rivc.ArtboardSpec
import rivc.ClipSpec
import rivc.Control
import rivc.DirectClipSpec
import rivc.DrawRuleSpec
import rivc.DrawTargetSpec
import rivc.Ease
import rivc.GroupSpec
import rivc.InputSpec
import rivc.KeySpec
import rivc.LayerSpec
import rivc.Loop
import rivc.PipSource
import rivc.Point
import rivc.StateMachineSpec
import rivc.StateSpec
import rivc.TrackSpec
import rivc.TransitionSpec
import rivc.ValueType
import rivc.art.Pt
import rivc.art.SvgDocument
import rivc.art.SvgElement
import rivc.art.SvgGeometry
import rivc.art.SvgGroup
import rivc.art.SvgImport
import rivc.art.SvgShape
import kotlin.math.PI
import kotlin.math.roundToInt

/** A draft turned into rivc's own artboard source, plus what didn't survive the trip. */
class NormalisedPiece(
    val draft: Draft,
    val source: PipSource,
    /** Where the piece's own (0, 0) sits on its artboard: the actor's anchor. */
    val anchor: Pt,
    val plays: Map<String, Play>,
    val synth: List<Synth>,
    val visible: ((Ctx) -> Boolean)?,
    val gaps: List<String>,
) {
    val name get() = source.artboard.name

    /** Clips the main timeline scrubs through remaps. */
    val remapped: List<String> get() = plays.filterValues { it is Play.Scrub }.keys.toList() + synth.map { it.name }
    val mixes: Map<String, Play.Mix> get() = plays.filterValues { it is Play.Mix }.mapValues { it.value as Play.Mix }
    fun clipSeconds(clip: String): Double = source.clips.first { it.name == clip }.let { it.frames.toDouble() / it.fps }
    fun clipLoops(clip: String): Boolean = source.clips.first { it.name == clip }.loop == Loop.LOOP
}

/**
 * Normalises set-piece motion drafts into rivc artboard sources (`<piece>.rivc.json`):
 *
 * - the SVG as art, every `<g id>` a node (rivc's scenery mode), a track `pivot` as a group pivot;
 * - each draft clip as a rivc clip at [FPS] (seconds become frames; keys closer than a frame are
 *   nudged apart so jumps stay jumps); degrees become radians, `scale` both axes, colours
 *   `<shape>/fill`, stroke widths `<shape>/stroke/paint` thickness, ellipse sizes `<shape>/path`;
 * - a state machine `Main` (inputs control) with one layer per free-running loop, a direct blend
 *   per mixed loop (input `<clip>Mix`, 0..100), and an empty `rest` state when there is neither;
 *   scrubbed clips stay out of it (the host remaps them);
 * - [Synth] clips for described-only inputs.
 *
 * - path-string keys as `vertices` morphs, trims as the stroke's `/stroke/trim`, draw-order
 *   swaps as DrawRules keyed by `drawTargetId`.
 *
 * What rivc can't express (colour inputs, data-text) is listed in [NormalisedPiece.gaps] and
 * left at the SVG's pose.
 */
object Normaliser {
    const val FPS = 600

    fun normalise(d: Draft, plan: PiecePlan, curves: (String) -> ((Double) -> Double)?): NormalisedPiece {
        val gaps = mutableListOf<String>()
        val doc = SvgImport.load(d.svg)
        val vb = doc.viewBox ?: error("${d.svg}: no viewBox")
        val pivots = LinkedHashMap<String, Point>()
        val positioned = d.clips.flatMap { c -> c.tracks.filter { it.property in setOf("x", "y") }.map { it.target } }.toSet()
        for (c in d.clips) for (t in c.tracks) if (t.pivot != null && t.property == "rotation") {
            val el = doc.byId(t.target)
            when {
                el !is SvgGroup -> gaps += "${c.name}/${t.target}: rotation pivot on a non-group; rotates about its origin"
                t.target in positioned -> gaps += "${c.name}/${t.target}: rotation pivot on a group that also moves; rotates about its origin"
                else -> pivots[t.target] = el.ctm.apply(Pt(t.pivot[0], t.pivot[1])).let { Point(it.x, it.y) }
            }
        }

        val drawRules = LinkedHashMap<String, DrawRuleSpec>()
        val clips = d.clips.map { c -> clip(d, c, doc, gaps, drawRules) }.toMutableList()
        for (s in plan.synth) {
            clips += ClipSpec(
                name = s.name, frames = FPS, fps = FPS,
                tracks = s.tracks.map { t ->
                    val (prop, k) = if (t.property == "rotation") "rotation" to PI / 180 else t.property to 1.0
                    TrackSpec(t.target, prop, listOf(KeySpec(0, t.from * k), KeySpec(FPS, t.to * k)))
                },
            )
        }

        val plays = LinkedHashMap<String, Play>()
        for (c in d.clips) plays[c.name] = plan.clips[c.name] ?: defaultPlay(d, c, curves, gaps)
        val inputs = mutableListOf<InputSpec>()
        val layers = mutableListOf<LayerSpec>()
        for ((name, p) in plays) when (p) {
            Play.Loop -> layers += LayerSpec(name, listOf(StateSpec(name, clip = name)), listOf(TransitionSpec("entry", name)))
            is Play.Mix -> {
                inputs += InputSpec("${name}Mix", ValueType.NUMBER, default = 0.0)
                layers += LayerSpec(name, listOf(StateSpec(name, direct = listOf(DirectClipSpec(name, by = "${name}Mix")))), listOf(TransitionSpec("entry", name)))
            }
            else -> {}
        }
        if (layers.isEmpty()) layers += LayerSpec("Rest", listOf(StateSpec("rest")), listOf(TransitionSpec("entry", "rest")))

        val source = PipSource(
            artboard = ArtboardSpec(name = d.name, width = vb.width, height = vb.height),
            art = ArtSpec(svg = d.svg.name, groups = pivots.map { (id, p) -> GroupSpec(id, pivot = p) }, drawRules = drawRules.values.toList()),
            clips = clips,
            stateMachine = StateMachineSpec(name = "Main", control = Control.INPUTS, inputs = inputs, layers = layers),
        )
        return NormalisedPiece(d, source, Pt(-vb.minX, -vb.minY), plays, plan.synth, plan.visible, gaps)
    }

    /** What the draft itself implies: loops run, mixes mix, input-driven clips scrub, panel one-shots belong to the actor. */
    fun defaultPlay(d: Draft, c: DraftClip, curves: (String) -> ((Double) -> Double)?, gaps: MutableList<String>): Play {
        fun input(name: String): ((Ctx) -> Double)? = when {
            name == "progress" -> { x -> x.progress }
            name.startsWith("1 - ") -> input(name.removePrefix("1 - "))?.let { f -> { x -> 1 - f(x) } }
            else -> curves(name)?.let { f -> { x -> f(x.sceneTime) } }
        }
        val mix = c.mixBy
        if (mix != null) {
            val w = input(mix) ?: run { gaps += "${c.name}: mixBy '$mix' has no source in the show; played at full weight"; return Play.Loop }
            return Play.Mix("loop mixed by $mix", w)
        }
        val driver = c.driver
        return when {
            driver == "time" && c.loop -> Play.Loop
            driver == "time" -> Play.Scrub("one-shot from the scene's start") { x -> x.sceneTime }
            driver != null && driver.startsWith("input:") -> {
                val name = driver.removePrefix("input:")
                val f = input(name) ?: return Play.Skip("driven by input '$name', which the show doesn't provide")
                Play.Scrub("time = $name") { x -> f(x) }
            }
            c.loop -> Play.Loop
            else -> Play.Skip("scene-level motion the actor's own tracks already carry")
        }
    }

    private fun ease(name: String, gaps: MutableList<String>, where: String): Ease = when (name) {
        "linear" -> Ease.Linear
        "hold", "step" -> Ease.Hold
        "easeIn" -> Ease.easeIn
        "easeOut" -> Ease.easeOut
        "easeInOut" -> Ease.easeInOut
        "sine" -> Ease.Cubic(0.37, 0.0, 0.63, 1.0)
        "quadIn" -> Ease.Cubic(1.0 / 3, 0.0, 2.0 / 3, 1.0 / 3)
        "quadOut" -> Ease.Cubic(1.0 / 3, 2.0 / 3, 2.0 / 3, 1.0)
        "outBack" -> Ease.Cubic(0.34, 1.56, 0.64, 1.0)
        "inBack" -> Ease.Cubic(0.36, 0.0, 0.66, -0.56)
        "outBounce" -> { gaps += "$where: outBounce has no Bezier; easeOut stands in"; Ease.easeOut }
        else -> { gaps += "$where: unknown ease '$name'; linear"; Ease.Linear }
    }

    private fun clip(d: Draft, c: DraftClip, doc: SvgDocument, gaps: MutableList<String>, drawRules: MutableMap<String, DrawRuleSpec>): ClipSpec {
        val frames = maxOf(1, (c.duration * FPS).roundToInt())
        val out = mutableListOf<TrackSpec>()
        val keyed = HashSet<Pair<String, String>>()
        fun add(target: String, prop: String, keys: List<KeySpec>, where: String) {
            if (!keyed.add(target to prop)) { gaps += "$where: $target.$prop is keyed twice in this clip; the first wins"; return }
            if (keys.isNotEmpty()) out += TrackSpec(target, prop, keys)
        }
        for (t in c.tracks) {
            val where = "${c.name}/${t.target}.${t.property}"
            val el = doc.byId(t.target)
            if (el == null) { gaps += "$where: no element '${t.target}' in ${d.svg.name}; dropped"; continue }
            val e = ease(t.ease, gaps, where)
            fun nums(scale: Double = 1.0) = frames(t.keys, frames, e) { v -> (v as? JsonPrimitive)?.doubleOrNull?.let { KeySpec(0, value = it * scale) } }
            when (t.property) {
                "x", "y", "opacity", "scaleX", "scaleY" -> add(t.target, t.property, nums(), where)
                "rotation" -> add(t.target, "rotation", nums(PI / 180), where)
                "scale" -> { val k = nums(); add(t.target, "scaleX", k, where); add(t.target, "scaleY", k, where) }
                "fill", "fillColor" -> {
                    val keys = frames(t.keys, frames, e) { v -> (v as? JsonPrimitive)?.content?.let { KeySpec(0, color = color(it)) } }
                    val shapes = paintTargets(el) { it.style.fill !is rivc.art.SvgPaint.None }
                    if (shapes.isEmpty()) gaps += "$where: no shape with an id under '${t.target}' to colour; dropped"
                    for (s in shapes) add("$s/fill", "colorValue", keys, where)
                }
                "strokeWidth" -> {
                    val shapes = paintTargets(el) { it.style.strokeWidth > 0 && it.style.stroke !is rivc.art.SvgPaint.None }
                    if (shapes.isEmpty()) gaps += "$where: no stroked shape with an id under '${t.target}'; dropped"
                    for (s in shapes) add("$s/stroke/paint", "thickness", nums(), where)
                }
                "width", "height" -> {
                    if (el is SvgShape && (el.geometry is SvgGeometry.Ellipse || el.geometry is SvgGeometry.Rect)) {
                        // Ellipse width is 2 rx; the draft keys the full size.
                        add("${t.target}/path", t.property, nums(), where)
                    } else gaps += "$where: size keys need an ellipse or rect primitive; dropped"
                }
                "path" -> {
                    if (el is SvgShape && el.geometry is SvgGeometry.Path) {
                        val keys = frames(t.keys, frames, e) { v -> (v as? JsonPrimitive)?.content?.let { KeySpec(0, path = it) } }
                        add(t.target, "vertices", keys, where)
                    } else gaps += "$where: path keys need an SVG <path>; dropped"
                }
                "trimOffset", "trimEnd", "trimStart" -> {
                    val prop = t.property.removePrefix("trim").lowercase()
                    val shapes = paintTargets(el) { it.style.strokeWidth > 0 && it.style.stroke !is rivc.art.SvgPaint.None }
                    if (shapes.isEmpty()) gaps += "$where: no stroked shape with an id under '${t.target}' to trim; dropped"
                    for (s in shapes) add("$s/stroke/trim", prop, nums(), where)
                }
                "drawOrder" -> drawOrder(t, frames, where, doc, gaps, drawRules)?.let { (target, keys) -> add(target, "drawTargetId", keys, where) }
                else -> gaps += "$where: unknown property '${t.property}'; dropped"
            }
        }
        return ClipSpec(name = c.name, frames = frames, fps = FPS, loop = if (c.loop) Loop.LOOP else Loop.ONE_SHOT, tracks = out)
    }

    /**
     * A `drawOrder` track (keys `"a above b"`) as a DrawRules on the first key's upper id, with one
     * DrawTarget per placement it needs, and hold keys switching between them.
     */
    private fun drawOrder(
        t: DraftTrack, total: Int, where: String, doc: SvgDocument, gaps: MutableList<String>, drawRules: MutableMap<String, DrawRuleSpec>,
    ): Pair<String, List<KeySpec>>? {
        val pair = Regex("^\\s*(\\S+)\\s+above\\s+(\\S+)\\s*$")
        fun parse(v: JsonElement) = (v as? JsonPrimitive)?.content?.let { pair.find(it)?.destructured?.let { (a, b) -> a to b } }
        val first = t.keys.firstNotNullOfOrNull { parse(it.second) } ?: run { gaps += "$where: no 'a above b' keys; dropped"; return null }
        val subject = first.first
        val targets = LinkedHashMap<String, DrawTargetSpec>()
        for ((_, v) in t.keys) {
            val (a, b) = parse(v) ?: continue
            for (id in listOf(a, b)) if (doc.byId(id) == null) { gaps += "$where: no element '$id'; dropped"; return null }
            when (subject) {
                a -> targets.getOrPut("above-$b") { DrawTargetSpec("above-$b", above = b) }
                b -> targets.getOrPut("below-$a") { DrawTargetSpec("below-$a", below = a) }
                else -> { gaps += "$where: '$a above $b' doesn't involve '$subject'; dropped"; return null }
            }
        }
        val keys = frames(t.keys, total, Ease.Hold) { v ->
            parse(v)?.let { (a, b) -> KeySpec(0, ref = "$subject/drawRules/" + if (a == subject) "above-$b" else "below-$a") }
        }
        val prev = drawRules[subject]
        val merged = (prev?.targets.orEmpty() + targets.values).distinctBy { it.name }
        drawRules[subject] = DrawRuleSpec(subject, merged)
        return "$subject/drawRules" to keys
    }

    /** [el] itself when it is a shape, otherwise its id'd descendant shapes matching [pred]. */
    private fun paintTargets(el: SvgElement, pred: (SvgShape) -> Boolean): List<String> = when (el) {
        is SvgShape -> listOfNotNull(el.id)
        is SvgGroup -> el.descendants().filterIsInstance<SvgShape>().filter(pred).mapNotNull { it.id }.toList()
        else -> emptyList()
    }

    /** `#RRGGBB`, or the drafts' CSS-order `#RRGGBBAA`, as rivc's `#AARRGGBB`. */
    fun color(s: String): String = when (s.length) {
        9 -> "#" + s.substring(7, 9) + s.substring(1, 7)
        else -> s
    }

    /**
     * Seconds to frames: clamped into the clip, and a key that lands on (or before) the previous
     * frame moves one frame later (or, at the clip's end, replaces it), so a jump keeps its order.
     */
    private fun frames(keys: List<Pair<Double, JsonElement>>, total: Int, ease: Ease, make: (JsonElement) -> KeySpec?): List<KeySpec> {
        val out = ArrayList<KeySpec>()
        for ((t, v) in keys.sortedBy { it.first }) {
            val k = make(v) ?: continue
            var f = (t * FPS).roundToInt().coerceIn(0, total)
            val prev = out.lastOrNull()
            if (prev != null && f <= prev.frame) {
                if (prev.frame + 1 <= total) f = prev.frame + 1 else { out.removeAt(out.size - 1) ; f = prev.frame }
            }
            out += k.copy(frame = f, ease = ease)
        }
        return out
    }
}
