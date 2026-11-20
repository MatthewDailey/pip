package rivc.project

import rivc.ClipSpec
import rivc.CueSpec
import rivc.PropertyDef
import rivc.RivGraph
import rivc.RivNode
import rivc.Schema
import rivc.spike.CompileException

/**
 * Keys the clip pass can't express: callbacks (an Event's `trigger`, a nested trigger's
 * `fire`) and strings (a text run's `text`). They are collected per clip while an artboard's
 * objects are emitted, then appended to each clip's LinearAnimation right after the clip pass
 * writes it, so they sit in the same animation as the clip's other keys and play in step
 * with it under any state machine.
 */
class CueKeys(private val clips: Map<String, ClipSpec>) {
    sealed interface Key {
        val frame: Int
    }

    /** A KeyFrameCallback: the property fires when the clip passes [frame]. */
    data class Callback(override val frame: Int) : Key

    /** A held KeyFrameString. */
    data class Text(override val frame: Int, val value: String) : Key

    private class Track(val node: RivNode, val def: PropertyDef, val keys: MutableList<Key> = mutableListOf())

    private val byClip = LinkedHashMap<String, LinkedHashMap<Pair<RivNode, PropertyDef>, Track>>()

    private fun fail(msg: String): Nothing = throw CompileException(msg)

    /** Keys [def] of [node] at [key]'s frame of [clip]. [where] names the source entry for errors. */
    fun add(clip: String, node: RivNode, def: PropertyDef, key: Key, where: String) {
        val spec = clips[clip] ?: fail("$where: no clip '$clip' on this artboard (clips: ${clips.keys.joinToString()})")
        if (key.frame !in 0..spec.frames) fail("$where: frame ${key.frame} is outside clip '$clip' (0..${spec.frames})")
        val track = byClip.getOrPut(clip) { LinkedHashMap() }.getOrPut(node to def) { Track(node, def) }
        if (track.keys.any { it.frame == key.frame }) fail("$where: ${node.describe()}.${def.name} is keyed twice at frame ${key.frame} of '$clip'")
        track.keys += key
    }

    /** Whether [clip] has keys here. */
    fun has(clip: String): Boolean = byClip[clip]?.isNotEmpty() == true

    /** Appends [clip]'s keys to [animation], which must be the latest animation in the stream. */
    fun emit(g: RivGraph, animation: RivNode, clip: String) {
        for (track in byClip[clip]?.values.orEmpty()) {
            val keyed = g.add("KeyedObject", owner = animation) { this["objectId"] = track.node }
            val kp = g.add("KeyedProperty", owner = keyed) { this["propertyKey"] = track.def }
            for (k in track.keys.sortedBy { it.frame }) when (k) {
                is Callback -> g.add("KeyFrameCallback", owner = kp) { this["frame"] = k.frame }
                is Text -> g.add("KeyFrameString", owner = kp) {
                    this["frame"] = k.frame; this["interpolationType"] = 0; this["value"] = k.value
                }
            }
        }
    }
}

/**
 * Named events fired from timelines: one Event component per distinct name (parented to the
 * artboard), keyed on its `trigger` callback at each cue frame.
 *
 * Runtime facts (showreel spike, both runtimes): events are reported through the playing
 * StateMachineInstance, so the clip must be played by a state machine; on Android only the
 * legacy `RiveAnimationView` exposes an event listener.
 */
class EventPass(private val schema: Schema = Schema.bundled) {
    /** Emits Event components into [artboard] and registers their cue keys. Returns them by name. */
    fun run(g: RivGraph, artboard: RivNode, cues: List<CueSpec>, keys: CueKeys, where: String): Map<String, RivNode> {
        val trigger = schema.property("Event", "trigger")
        val events = LinkedHashMap<String, RivNode>()
        for (c in cues) {
            if (c.frames.isEmpty()) throw CompileException("$where.events.${c.name}: no frames")
            val event = events.getOrPut(c.name) {
                g.add("Event", owner = artboard) { this["name"] = c.name; this["parentId"] = artboard }
            }
            for (f in c.frames) keys.add(c.clip, event, trigger, CueKeys.Callback(f), "$where.events.${c.name}")
        }
        return events
    }
}
