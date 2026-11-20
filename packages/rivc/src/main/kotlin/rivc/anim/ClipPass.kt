package rivc.anim

import rivc.Argb
import rivc.ClipSpec
import rivc.Ease
import rivc.FieldType
import rivc.KeySpec
import rivc.PipSource
import rivc.PropertyDef
import rivc.RivGraph
import rivc.RivNode
import rivc.Schema
import rivc.spike.CompileException
import kotlin.math.PI

/**
 * What clips can animate. The art pass supplies it: every object it placed, by the name
 * clips use, plus path geometry for `vertices` morphs.
 *
 * Naming convention for sub-targets (the art pass registers the ones it builds):
 * `"<shape>"` the shape node; `"<shape>/fill"` its fill's SolidColor (`colorValue`);
 * `"<shape>/fill/paint"` the Fill (`isVisible`); `"<shape>/path"` its path (an ellipse's
 * `width` and `height`); `"<shape>/v<i>"` path vertex i; gradient stops as
 * `"<shape>/fill/stop<i>"` (`colorValue`, `position`) and the gradient as
 * `"<shape>/fill"` (`opacity`, `startX`...). Nothing here depends on the names beyond
 * looking them up.
 */
interface AnimTargets {
    /** The object called [name], or null when the art has no such target. */
    fun target(name: String): RivNode?

    /**
     * For a `vertices` morph of path shape [shape] to SVG element [svgId]: each vertex
     * node of the shape's path with the property values (`x`, `y`, and for cubic vertices
     * `inRotation`, `inDistance`, `outRotation`, `outDistance`) that draw [svgId] in the
     * shape's own space. Null when [shape] has no morphable path. Throws
     * [CompileException] when [svgId] doesn't fit (vertex count or kinds differ).
     */
    fun morph(shape: String, svgId: String): List<Pair<RivNode, Map<String, Double>>>? = null

    companion object {
        /** Prefix that marks [morph]'s `svgId` as SVG path data rather than an element id. */
        const val PATH_DATA = "d:"
    }
}

/** [AnimTargets] over a plain map, for art without morphable paths (and tests). */
class MapTargets(private val map: Map<String, RivNode>) : AnimTargets {
    override fun target(name: String): RivNode? = map[name]
}

/** What [ClipPass] placed, for the state machine pass to refer to. */
class ClipResult(
    val clips: Map<String, RivNode>,
    /** Event components by name. */
    val events: Map<String, RivNode>,
    /** Cubic interpolators, shared by keyframes and transitions. */
    val interpolators: Map<Ease.Cubic, RivNode>,
)

/**
 * Emits the clips half of a [PipSource] into the open artboard: Event components, shared
 * cubic interpolators, then one LinearAnimation per clip.
 *
 * Runtime facts this follows (rive-runtime at the pinned commit):
 * - `KeyFrame.interpolationType` is hold 0, linear 1, cubic 2. Cubic keys point
 *   `interpolatorId` at a CubicEaseInterpolator, an artboard object (it takes an artboard
 *   slot), shared by every key with the same curve. The curve on a key shapes the segment
 *   from that key to the next.
 * - A clip's KeyedObject owns its KeyedProperties, which own their keyframes; membership
 *   is by stream position. One KeyedObject per animated object, properties in source order.
 * - Events fire from clips through a KeyFrameCallback on the Event's `trigger` callback
 *   property; the state machine reports them to listeners as it plays the clip.
 * - Work area: `enableWorkArea` with `workStart`/`workEnd` in frames.
 */
class ClipPass(private val schema: Schema = Schema.bundled) {
    private fun fail(msg: String): Nothing = throw CompileException(msg)

    fun run(g: RivGraph, artboard: RivNode, src: PipSource, targets: AnimTargets): ClipResult {
        // Events: one Event component per distinct name, parented to the artboard.
        val events = LinkedHashMap<String, RivNode>()
        for (e in src.stateMachine?.events.orEmpty()) {
            events.getOrPut(e.name) {
                g.add("Event", owner = artboard) { this["name"] = e.name; this["parentId"] = artboard }
            }
        }

        // Interpolators, one per distinct cubic, in first-use order (clips, then transitions).
        val interpolators = LinkedHashMap<Ease.Cubic, RivNode>()
        fun interpolator(e: Ease) {
            if (e !is Ease.Cubic) return
            interpolators.getOrPut(e) {
                g.add("CubicEaseInterpolator", owner = artboard) {
                    this["x1"] = e.x1; this["y1"] = e.y1; this["x2"] = e.x2; this["y2"] = e.y2
                }
            }
        }
        for (c in src.clips) for (t in c.tracks) for (k in t.keys) interpolator(k.ease)
        for (l in src.stateMachine?.layers.orEmpty()) for (t in l.transitions) interpolator(t.ease)

        val clips = LinkedHashMap<String, RivNode>()
        for (c in src.clips) {
            if (c.name in clips) fail("clips: duplicate clip '${c.name}'")
            clips[c.name] = clip(g, artboard, c, src, targets, interpolators, events)
        }
        for (e in src.stateMachine?.events.orEmpty()) {
            if (e.clip != null && e.clip !in clips) fail("stateMachine.events.${e.name}: no clip '${e.clip}'")
        }
        return ClipResult(clips, events, interpolators)
    }

    /** A keyframe before it becomes an object: its value is a Double, Argb, Boolean or String. */
    private class Key(val frame: Int, val ease: Ease, val value: Any)

    private fun clip(
        g: RivGraph,
        artboard: RivNode,
        c: ClipSpec,
        src: PipSource,
        targets: AnimTargets,
        interpolators: Map<Ease.Cubic, RivNode>,
        events: Map<String, RivNode>,
    ): RivNode {
        val where = "clips.${c.name}"
        if (c.frames < 1) fail("$where: frames must be at least 1")
        if (c.fps < 1) fail("$where: fps must be at least 1")
        if ((c.workStart == null) != (c.workEnd == null)) fail("$where: set both workStart and workEnd, or neither")
        if (c.workStart != null && !(c.workStart in 0 until c.workEnd!! && c.workEnd <= c.frames)) {
            fail("$where: work area ${c.workStart}..${c.workEnd} must lie inside 0..${c.frames} and not be empty")
        }

        // Collect every (object, property) track first, so a vertex keyed both by a morph and
        // by hand is caught, and each object gets one KeyedObject in first-mention order.
        val byObject = LinkedHashMap<RivNode, LinkedHashMap<PropertyDef, List<Key>>>()
        fun put(node: RivNode, def: PropertyDef, keys: List<Key>, at: String) {
            val props = byObject.getOrPut(node) { LinkedHashMap() }
            if (def in props) fail("$at: ${node.describe()}.${def.name} is keyed twice in this clip")
            props[def] = keys
        }
        for (t in c.tracks) {
            val at = "$where.${t.target}.${t.property}"
            if (t.keys.isEmpty()) fail("$at: no keys")
            t.keys.zipWithNext().forEach { (a, b) ->
                if (b.frame <= a.frame) fail("$at: key frames must increase (${a.frame} then ${b.frame})")
            }
            t.keys.forEach { if (it.frame !in 0..c.frames) fail("$at@${it.frame}: frame is outside the clip (0..${c.frames})") }
            if (t.property == "vertices") {
                morph(t.target, t.keys, targets, at).forEach { (node, props) ->
                    props.forEach { (name, keys) -> put(node, schema.property(node.type, name), keys, at) }
                }
                continue
            }
            val node = targets.target(t.target) ?: fail("$at: no art target '${t.target}'")
            val def = runCatching { schema.property(node.type, t.property) }.getOrElse { fail("$at: ${it.message}") }
            put(node, def, t.keys.map { k -> Key(k.frame, k.ease, value(def, k, "$at@${k.frame}", targets)) }, at)
        }

        val anim = g.add("LinearAnimation", owner = artboard) {
            this["name"] = c.name; this["fps"] = c.fps; this["duration"] = c.frames; this["loopValue"] = c.loop.value
            if (c.speed != null) this["speed"] = c.speed
            if (c.workStart != null) {
                this["enableWorkArea"] = true; this["workStart"] = c.workStart; this["workEnd"] = c.workEnd!!
            }
        }
        for ((node, props) in byObject) {
            val keyed = g.add("KeyedObject", owner = anim) { this["objectId"] = node }
            for ((def, keys) in props) {
                val kp = g.add("KeyedProperty", owner = keyed) { this["propertyKey"] = def }
                for (k in keys) keyFrame(g, kp, def, k, interpolators, "$where.${node.describe()}.${def.name}@${k.frame}")
            }
        }

        // Events keyed in this clip: KeyFrameCallbacks on each event's trigger.
        val clipEvents = src.stateMachine?.events.orEmpty().filter { it.clip == c.name }
        for ((name, specs) in clipEvents.groupBy { it.name }) {
            val event = events.getValue(name)
            val frames = specs.map { s ->
                val f = s.frame ?: fail("stateMachine.events.$name: a clip event needs a frame")
                if (f !in 0..c.frames) fail("stateMachine.events.$name: frame $f is outside clip '${c.name}' (0..${c.frames})")
                f
            }.distinct().sorted()
            val keyed = g.add("KeyedObject", owner = anim) { this["objectId"] = event }
            val kp = g.add("KeyedProperty", owner = keyed) { this["propertyKey"] = schema.property("Event", "trigger") }
            for (f in frames) g.add("KeyFrameCallback", owner = kp) { this["frame"] = f }
        }
        return anim
    }

    private fun value(def: PropertyDef, k: KeySpec, at: String, targets: AnimTargets): Any = when (def.type) {
        FieldType.Double -> k.value ?: fail("$at: ${def.name} needs a numeric value")
        FieldType.Uint, FieldType.Int -> (k.value ?: fail("$at: ${def.name} needs a whole-number value")).also {
            if (it != Math.floor(it)) fail("$at: ${def.name} needs a whole number, got $it")
        }
        FieldType.Color -> Argb(parseColor(k.color ?: fail("$at: ${def.name} needs a colour"), at))
        FieldType.Bool -> k.bool ?: fail("$at: ${def.name} needs a bool")
        FieldType.Id -> {
            val ref = k.ref ?: fail("$at: ${def.name} is an object reference; give the key a ref (an art target)")
            targets.target(ref) ?: fail("$at: no art target '$ref'")
        }
        FieldType.Callback -> fail("$at: ${def.name} is a callback; fire it with a stateMachine event instead")
        FieldType.String, FieldType.Bytes -> fail("$at: ${def.name} (${def.type}) can't be keyed by rivc")
    }

    private fun keyFrame(g: RivGraph, kp: RivNode, def: PropertyDef, k: Key, interpolators: Map<Ease.Cubic, RivNode>, at: String) {
        val type = when (def.type) {
            FieldType.Double -> "KeyFrameDouble"
            FieldType.Color -> "KeyFrameColor"
            FieldType.Bool -> "KeyFrameBool"
            FieldType.Uint -> "KeyFrameUint"
            FieldType.Int -> "KeyFrameInt"
            FieldType.Id -> "KeyFrameId"
            else -> fail("$at: can't key ${def.type}")
        }
        // Booleans and references can't be eased: they hold.
        val ease = if (def.type == FieldType.Bool || def.type == FieldType.Id) Ease.Hold else k.ease
        val (interp, interpolator) = when (ease) {
            Ease.Hold -> 0 to null
            Ease.Linear -> 1 to null
            is Ease.Cubic -> 2 to interpolators.getValue(ease)
        }
        g.add(type, owner = kp) {
            this["frame"] = k.frame; this["interpolationType"] = interp
            this["value"] = k.value
            if (interpolator != null) this["interpolatorId"] = interpolator
        }
    }

    /**
     * Expands a `vertices` track into per-vertex tracks, keying every geometry property of
     * every vertex (a morph to the rest shape is a real pose: a direct blend needs one to
     * mix from, since the runtime mixes into whatever the property last held). Handle rotations are unwrapped key to key so each segment turns
     * the short way round (atan2 jumps from pi to -pi).
     */
    private fun morph(shape: String, keys: List<KeySpec>, targets: AnimTargets, at: String): List<Pair<RivNode, Map<String, List<Key>>>> {
        val shapes = keys.map { k ->
            if (k.morph != null && k.path != null) fail("$at@${k.frame}: set morph or path, not both")
            val id = k.morph ?: k.path?.let { AnimTargets.PATH_DATA + it }
                ?: fail("$at@${k.frame}: a vertices key needs morph (an SVG element id) or path (path data)")
            targets.morph(shape, id) ?: fail("$at: '$shape' has no morphable path")
        }
        val count = shapes.first().size
        shapes.forEachIndexed { i, s ->
            if (s.size != count) fail("$at@${keys[i].frame}: ${s.size} vertices, key 0 has $count")
        }
        return (0 until count).map { v ->
            val node = shapes[0][v].first
            val names = shapes.flatMap { it[v].second.keys }.distinct()
            val props = LinkedHashMap<String, List<Key>>()
            for (name in names) {
                var values = shapes.map { it[v].second[name] ?: 0.0 }
                val rest = (node[name] as? rivc.GValue.Num)?.v ?: 0.0
                // Unwrapped from the rest angle, so a blend with the rest pose also turns the short way.
                if (name.endsWith("Rotation")) values = unwrap(listOf(rest) + values).drop(1)
                props[name] = keys.mapIndexed { i, k -> Key(k.frame, k.ease, values[i]) }
            }
            node to props
        }
    }

    private fun unwrap(angles: List<Double>): List<Double> {
        val out = mutableListOf(angles.first())
        for (a in angles.drop(1)) {
            var x = a
            while (x - out.last() > PI) x -= 2 * PI
            while (x - out.last() < -PI) x += 2 * PI
            out += x
        }
        return out
    }
}

/** `#RRGGBB` (opaque) or `#AARRGGBB`, as ARGB. */
fun parseColor(s: String, where: String = "colour"): Long {
    val hex = s.removePrefix("#")
    if (!s.startsWith("#") || hex.any { it !in "0123456789abcdefABCDEF" }) throw CompileException("$where: bad colour '$s' (use #RRGGBB or #AARRGGBB)")
    return when (hex.length) {
        6 -> 0xFF000000 or hex.toLong(16)
        8 -> hex.toLong(16)
        else -> throw CompileException("$where: bad colour '$s' (use #RRGGBB or #AARRGGBB)")
    }
}
