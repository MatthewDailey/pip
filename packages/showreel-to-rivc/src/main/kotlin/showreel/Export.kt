package showreel

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

/**
 * The exported showreel (`pip.showreel/1`, see the app's `ui/show/EXPORT.md`), read into
 * plain classes. Values are `Double`, `String`, `Boolean` or `null` (discrete values keep their
 * enum names; a `NaN` float was written as `null`).
 */
class ShowExport(
    val duration: Double,
    val stageWidth: Double,
    val stageHeight: Double,
    /** Launcher icon cells, name to `[left, top, right, bottom]`. */
    val icons: Map<String, List<Double>>,
    val scenes: List<Scene>,
    val actors: Map<String, Actor>,
    val animations: Map<String, Animation>,
    val cues: List<Cue>,
    val texts: List<TextEntry>,
    val customActors: Map<String, CustomActor>,
) {
    class Scene(
        val index: Int,
        val name: String,
        val start: Double,
        val end: Double,
        val pipBase: Map<String, Any?>,
        val actorIds: List<String>,
        val stack: List<String>,
    )

    class Actor(
        val id: String,
        val scene: String,
        val index: Int,
        val kind: String,
        val text: String,
        val layer: Int,
        val anchorToPip: Boolean,
        val color: String,
        val base: Map<String, Any?>,
    )

    class Animation(
        val id: String,
        val scope: String,
        val target: String,
        val start: Double,
        val end: Double?,
        val fillBefore: Boolean,
        val fillAfter: Boolean,
        val additive: Boolean,
        val tracks: List<Track>,
    )

    class Track(val property: String, val discrete: Boolean, val keys: List<Key>) {
        val times = DoubleArray(keys.size) { keys[it].t }
    }

    /** A key; [ease] null on the last key, [Ease.Step] for a hold; [unset] marks a discrete "set nothing". */
    class Key(val t: Double, val v: Any?, val ease: Ease?, val src: String?) {
        val unset get() = src == "unset"
    }

    sealed interface Ease {
        data object Step : Ease
        data class Bezier(val x1: Double, val y1: Double, val x2: Double, val y2: Double) : Ease
    }

    class Cue(val t: Double, val name: String, val scene: String)

    class TextEntry(val actor: String, val scene: String, val kind: String, val text: String, val texts: List<String>, val shown: List<Pair<Double, Double>>)

    class CustomActor(
        val id: String,
        val scene: String,
        val painter: String?,
        val sourceFile: String,
        val sourceLine: Int,
        val sourceText: String,
    )

    fun scene(name: String) = scenes.first { it.name == name }

    /** The scene evaluated at [t]: `start <= t < end`, the last one past the end. */
    fun sceneAt(t: Double): Scene = scenes.firstOrNull { t >= it.start && t < it.end } ?: if (t < 0) scenes.first() else scenes.last()

    companion object {
        fun load(file: File): ShowExport = parse(Json.parseToJsonElement(file.readText()).jsonObject)

        fun value(e: JsonElement?): Any? = when (e) {
            null, JsonNull -> null
            is JsonPrimitive -> if (e.isString) e.content else e.booleanOrNull ?: e.doubleOrNull
            else -> error("unexpected value $e")
        }

        private fun JsonObject.str(k: String) = getValue(k).jsonPrimitive.content
        private fun JsonObject.num(k: String) = getValue(k).jsonPrimitive.doubleOrNull ?: error("$k is not a number")
        private fun JsonObject.bool(k: String) = (get(k) as? JsonPrimitive)?.booleanOrNull ?: false

        fun parse(o: JsonObject): ShowExport {
            require(o.str("format") == "pip.showreel/1") { "not an pip.showreel/1 export" }
            val stage = o.getValue("stage").jsonObject
            val icons = stage.getValue("icons").jsonObject.mapValues { (_, v) -> v.jsonArray.map { it.jsonPrimitive.doubleOrNull!! } }
            val scenes = o.getValue("scenes").jsonArray.map { s ->
                s as JsonObject
                Scene(
                    s.num("index").toInt(), s.str("name"), s.num("start"), s.num("end"),
                    s.getValue("pipBase").jsonObject.mapValues { value(it.value) },
                    s.getValue("actors").jsonArray.map { it.jsonPrimitive.content },
                    s.getValue("stack").jsonArray.map { it.jsonPrimitive.content },
                )
            }
            val actors = o.getValue("actors").jsonArray.map { a ->
                a as JsonObject
                Actor(
                    a.str("id"), a.str("scene"), a.num("index").toInt(), a.str("kind"), a.str("text"), a.num("layer").toInt(),
                    a.bool("anchorToPip"), a.str("color"), a.getValue("base").jsonObject.mapValues { value(it.value) },
                )
            }.associateBy { it.id }
            val anims = o.getValue("animations").jsonArray.map { an ->
                an as JsonObject
                val act = an.getValue("active").jsonObject
                Animation(
                    an.str("id"), an.str("scope"), an.str("target"),
                    act.num("start"), (act["end"] as? JsonPrimitive)?.doubleOrNull,
                    act.bool("fillBefore"), act.bool("fillAfter"), act.bool("additive"),
                    an.getValue("tracks").jsonArray.map { t ->
                        t as JsonObject
                        Track(t.str("property"), t.str("type") == "discrete", t.getValue("keys").jsonArray.map { k -> key(k.jsonObject) })
                    },
                )
            }.associateBy { it.id }
            val cues = o.getValue("cues").jsonArray.map { c -> c as JsonObject; Cue(c.num("t"), c.str("name"), c.str("scene")) }
            val texts = o.getValue("texts").jsonArray.map { t ->
                t as JsonObject
                TextEntry(
                    t.str("actor"), t.str("scene"), t.str("kind"), t.str("text"),
                    t.getValue("texts").jsonArray.map { it.jsonPrimitive.content },
                    t.getValue("shown").jsonArray.map { w -> w.jsonArray.let { it[0].jsonPrimitive.doubleOrNull!! to it[1].jsonPrimitive.doubleOrNull!! } },
                )
            }
            val customs = o.getValue("customActors").jsonArray.map { c ->
                c as JsonObject
                val src = c.getValue("source").jsonObject
                CustomActor(
                    c.str("id"), c.str("scene"), (c["painter"] as? JsonPrimitive)?.content,
                    src.str("file"), src.num("line").toInt(), src.str("text"),
                )
            }.associateBy { it.id }
            return ShowExport(
                o.num("duration"), stage.num("width"), stage.num("height"), icons, scenes, actors, anims, cues, texts, customs,
            )
        }

        private fun key(k: JsonObject): Key {
            val ease = when (val e = k["ease"]) {
                null, JsonNull -> null
                is JsonPrimitive -> if (e.content == "step") Ease.Step else error("unknown ease $e")
                is JsonArray -> e.map { it.jsonPrimitive.doubleOrNull!! }.let { Ease.Bezier(it[0], it[1], it[2], it[3]) }
                else -> error("bad ease $e")
            }
            return Key(k.getValue("t").jsonPrimitive.doubleOrNull!!, value(k["v"]), ease, (k["src"] as? JsonPrimitive)?.content)
        }
    }
}
