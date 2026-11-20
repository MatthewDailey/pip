package showreel

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

/**
 * A set piece's motion draft (`packages/pip-rive/sets/<piece>.json`), read from any of the three
 * formats the scenery agents used:
 *
 * - **fx** (`fx-*`): `svg`, `origin`, clips with `timeline: time | progress`, a clip-level `ease`,
 *   and sometimes a prose `blend` weight.
 * - **panel** (`set-d60s-*` .. `set-d90s-*`): no `svg` (it is `<piece>.svg`), clips without a
 *   driver (the scene decides when they play), eases per track, `pivots`, `drawOrder` tracks.
 * - **scene** (`set-d00s-*` .. `set-dtoday-*`, they carry `format`): `driver: time | input:<name>`,
 *   `mixBy` (an input or `1 - <input>`), `input_curves`.
 *
 * Every format keys `[seconds, value]` pairs; values are numbers, colours or (unsupported) path
 * strings and draw orders.
 */
class Draft(val file: File, val json: JsonObject) {
    enum class Group { FX, PANEL, SCENE }

    val name: String = file.nameWithoutExtension
    val group: Group = when {
        name.startsWith("fx-") -> Group.FX
        json.containsKey("format") -> Group.SCENE
        else -> Group.PANEL
    }
    val svg: File = File(file.parentFile, (json["svg"] as? JsonPrimitive)?.content ?: "$name.svg")
    val clips: List<DraftClip> = json["clips"]?.jsonArray.orEmpty().map { DraftClip(it.jsonObject, this) }
    val inputs: List<String> = json["inputs"]?.jsonArray.orEmpty().map { it.jsonObject.getValue("name").jsonPrimitive.content }

    /** `input_curves` (set-dtoday-door): named curves sampled at `rate_hz` from the scene's start. */
    val curves: Map<String, Pair<Double, DoubleArray>> by lazy {
        val c = json["input_curves"] as? JsonObject ?: return@lazy emptyMap()
        val rate = (c["rate_hz"] as? JsonPrimitive)?.doubleOrNull ?: 60.0
        c.filter { (_, v) -> v is JsonArray && v.all { it is JsonPrimitive } }.mapValues { (_, v) -> rate to v.jsonArray.map { it.jsonPrimitive.doubleOrNull ?: 0.0 }.toDoubleArray() }
    }

    companion object {
        fun load(file: File) = Draft(file, Json.parseToJsonElement(file.readText()).jsonObject)
    }
}

class DraftClip(o: JsonObject, draft: Draft) {
    val name: String = o.getValue("name").jsonPrimitive.content
    val duration: Double = o.getValue("duration").jsonPrimitive.doubleOrNull ?: 1.0
    val loop: Boolean = o["loop"]?.jsonPrimitive?.content == "true"
    val note: String = (o["note"] as? JsonPrimitive)?.content.orEmpty()

    /** `time`, or `input:<name>` for a clip whose time is an input's value. Panel drafts have none (null). */
    val driver: String? = (o["driver"] as? JsonPrimitive)?.content
        ?: (o["timeline"] as? JsonPrimitive)?.content?.let { if (it == "time") "time" else "input:$it" }

    /** The input (or `1 - <input>`) whose 0..1 value weights the clip; fx drafts write it as prose `blend`. */
    val mixBy: String? = (o["mixBy"] as? JsonPrimitive)?.content

    val blend: String? = (o["blend"] as? JsonPrimitive)?.content
    private val clipEase: String? = (o["ease"] as? JsonPrimitive)?.content
    val tracks: List<DraftTrack> = o["tracks"]?.jsonArray.orEmpty().map { t ->
        t as JsonObject
        DraftTrack(
            target = t.getValue("target").jsonPrimitive.content,
            property = t.getValue("property").jsonPrimitive.content,
            ease = (t["ease"] as? JsonPrimitive)?.content ?: clipEase ?: "linear",
            keys = t.getValue("keys").jsonArray.map { k -> k.jsonArray.let { it[0].jsonPrimitive.doubleOrNull!! to it[1] } },
            pivot = (t["pivot"] as? JsonArray)?.map { it.jsonPrimitive.doubleOrNull!! },
        )
    }
    val group = draft.group
}

class DraftTrack(val target: String, val property: String, val ease: String, val keys: List<Pair<Double, JsonElement>>, val pivot: List<Double>?)
