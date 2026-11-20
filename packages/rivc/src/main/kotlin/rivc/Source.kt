package rivc

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

/**
 * The `pip.json` source format: everything `rivc compile` needs to build one artboard.
 *
 * Each section is filled by a different pass, so passes can be written independently:
 *
 * | Section          | Filled by                   | Emits                                        |
 * |------------------|-----------------------------|----------------------------------------------|
 * | [artboard]       | hand-written                | the Artboard                                 |
 * | [art]            | the art pass (`rivc/art/`)  | nodes, shapes, paths, paints                 |
 * | [clips]          | the animation pass (`rivc/anim/`) | LinearAnimations and their keyframes   |
 * | [viewModel]      | the animation pass          | the file's ViewModel and default instance    |
 * | [stateMachine]   | the animation pass          | the artboard's (default) StateMachine        |
 *
 * Names are the glue between sections: clips target art ids, states name clips,
 * conditions name view-model properties or state machine inputs. Unknown JSON keys are
 * an error, so a typo doesn't silently drop part of the rig.
 *
 * Paths in the file (for example [ArtSpec.svg]) are relative to the JSON file.
 */
@Serializable
data class PipSource(
    /** Source format version; bump it when a change isn't backward compatible. */
    val format: Int = 1,
    val artboard: ArtboardSpec,
    val art: ArtSpec? = null,
    val clips: List<ClipSpec> = emptyList(),
    val viewModel: ViewModelSpec? = null,
    val stateMachine: StateMachineSpec? = null,
) {
    companion object {
        @OptIn(ExperimentalSerializationApi::class)
        val json = Json {
            ignoreUnknownKeys = false
            allowTrailingComma = true
            prettyPrint = true
            encodeDefaults = false
        }

        fun parse(text: String): PipSource = json.decodeFromString(serializer(), text)

        fun load(file: File): PipSource = parse(file.readText())
    }
}

/**
 * The artboard. [width] and [height] are the art's size in SVG units; [padding] grows
 * the artboard around the art (for example room for the idle bob above the hat) without
 * moving the art relative to its own coordinates.
 */
@Serializable
data class ArtboardSpec(
    val name: String,
    val width: Double,
    val height: Double,
    val padding: Padding = Padding(),
    /** Whether the artboard clips its contents. Pip draws past it while moving, so off by default. */
    val clip: Boolean = false,
)

/** Extra space around the art, in SVG units. */
@Serializable
data class Padding(
    val top: Double = 0.0,
    val right: Double = 0.0,
    val bottom: Double = 0.0,
    val left: Double = 0.0,
)

/**
 * The art: an SVG file plus the rig built over it.
 *
 * The SVG supplies geometry and fills; this section says which SVG elements become
 * shapes and how they hang together. Ids must be unique across [nodes], [groups] and
 * [shapes], and they are the names clips and the state machine use.
 *
 * Draw order follows the SVG's paint order (later elements on top) regardless of the
 * order of [shapes]; the compiler reverses it for Rive, which draws last-to-first.
 */
@Serializable
data class ArtSpec(
    /** Path to the SVG, relative to the JSON file. */
    val svg: String,
    /** Rig nodes that don't exist in the SVG (for example `root`, a whole-body pivot). */
    val nodes: List<NodeSpec> = emptyList(),
    /**
     * SVG `<g id>` elements to keep as nodes, so clips can move a group at once. With [shapes]
     * empty every `<g id>` is kept anyway; list one here to give it a [GroupSpec.pivot] or parent.
     */
    val groups: List<GroupSpec> = emptyList(),
    /**
     * SVG elements (by id) to emit as shapes; other shapes are skipped. Leave it empty to emit
     * the whole SVG, every shape under its nearest `<g id>` (how scenery sets compile).
     */
    val shapes: List<ShapeSpec> = emptyList(),
    /** Draw-order overrides a clip can switch between (Rive DrawRules); see [DrawRuleSpec]. */
    val drawRules: List<DrawRuleSpec> = emptyList(),
)

/**
 * Lets clips move the drawables under node, group or shape [id] above or below other art.
 * Each of [targets] becomes a DrawTarget, named `"<id>/drawRules/<name>"`; a clip switches
 * between them by keying `"<id>/drawRules"`'s `drawTargetId` with [KeySpec.ref]. [active] is
 * the one in force at rest; null keeps the SVG's paint order until a clip picks one.
 */
@Serializable
data class DrawRuleSpec(
    val id: String,
    val targets: List<DrawTargetSpec>,
    val active: String? = null,
)

/**
 * One placement for a [DrawRuleSpec]: directly [above] or [below] the art with that id (for a
 * group, above its topmost or below its bottommost shape). Set exactly one.
 */
@Serializable
data class DrawTargetSpec(val name: String, val above: String? = null, val below: String? = null)

/**
 * A transform node. [parent] is another node or group id; null means the artboard. [x] and
 * [y] are its origin (where it rotates and scales about) in SVG units; whatever hangs from it
 * stays where the SVG draws it, and a clip keying `x`/`y` moves it in SVG units from there.
 */
@Serializable
data class NodeSpec(
    val id: String,
    val parent: String? = null,
    val x: Double = 0.0,
    val y: Double = 0.0,
)

/** An SVG `<g>` kept as a node. [pivot] is where it rotates and scales about, in SVG units. */
@Serializable
data class GroupSpec(
    val id: String,
    val parent: String? = null,
    val pivot: Point? = null,
)

/**
 * An SVG element emitted as a shape.
 *
 * @property id the SVG element's `id`.
 * @property parent the node or group the shape hangs from; null means the artboard.
 * @property pivot where the shape's transform origin sits: [Pivot.ORIGIN] keeps SVG
 *   coordinates (the shape at 0,0), [Pivot.CENTER] puts the shape at its geometric
 *   centre so `scaleX/scaleY/rotation` act about it (an eye's blink squash).
 */
@Serializable
data class ShapeSpec(
    val id: String,
    val parent: String? = null,
    val pivot: Pivot = Pivot.ORIGIN,
)

@Serializable
enum class Pivot {
    @SerialName("origin") ORIGIN,
    @SerialName("center") CENTER,
}

@Serializable
data class Point(val x: Double, val y: Double)

/**
 * A keyframe clip (one LinearAnimation).
 *
 * @property frames the duration in frames at [fps].
 * @property tracks one per animated property.
 * @property workStart with [workEnd], play only frames workStart..workEnd (the work area).
 * @property speed playback speed multiplier (negative plays backwards).
 */
@Serializable
data class ClipSpec(
    val name: String,
    val frames: Int,
    val fps: Int = 60,
    val loop: Loop = Loop.ONE_SHOT,
    val tracks: List<TrackSpec> = emptyList(),
    val workStart: Int? = null,
    val workEnd: Int? = null,
    val speed: Double? = null,
)

@Serializable
enum class Loop(val value: Int) {
    @SerialName("oneShot") ONE_SHOT(0),
    @SerialName("loop") LOOP(1),
    @SerialName("pingPong") PING_PONG(2),
}

/**
 * One animated property.
 *
 * @property target an art id (node, group or shape), or a sub-target the art pass names:
 *   `"<shape>/fill"` (the fill's solid colour), `"<shape>/fill/paint"` (the Fill itself,
 *   for `isVisible`), `"<shape>/path"` (the path, for an ellipse's `width`/`height`),
 *   `"<shape>/v<i>"` (path vertex i), and gradient stops as the art pass names them.
 * @property property the runtime property name from the schema, for example `y`,
 *   `scaleY`, `rotation` (radians), `opacity`, or `colorValue` on a fill. The special
 *   property `vertices` morphs a path shape: each key names an SVG element in [KeySpec.morph]
 *   or gives path data in [KeySpec.path], with the same subpaths (count and which are closed);
 *   vertex counts may differ, as the compiler subdivides every pose to the largest count, and
 *   every vertex is keyed to its geometry. A stroke's TrimPath is `"<shape>/stroke/trim"`
 *   (`start`, `end`, `offset`, fractions of the length), made when a clip keys it or the SVG
 *   element has `data-trim-start`/`-end`/`-offset`.
 */
@Serializable
data class TrackSpec(
    val target: String,
    val property: String,
    val keys: List<KeySpec>,
)

/**
 * A keyframe. Set [value] for number properties, [color] (`#RRGGBB` or `#AARRGGBB`)
 * for colours, [bool] for booleans (`isVisible`), or [morph] (an SVG element id) on a
 * `vertices` track. [ease] shapes the curve from this key to the next.
 */
@Serializable
data class KeySpec(
    val frame: Int,
    val value: Double? = null,
    val color: String? = null,
    val bool: Boolean? = null,
    val morph: String? = null,
    /** On a `vertices` track instead of [morph]: SVG path data in the morphed shape's own SVG user space. */
    val path: String? = null,
    /** For an object-reference property (a DrawRules' `drawTargetId`): the art target it points at. */
    val ref: String? = null,
    val ease: Ease = Ease.Linear,
)

/**
 * Keyframe easing. In JSON: `"linear"`, `"hold"`, `"easeIn"`, `"easeOut"`, `"easeInOut"`,
 * or `{"cubic": [x1, y1, x2, y2]}`.
 */
@Serializable(with = EaseSerializer::class)
sealed interface Ease {
    data object Linear : Ease
    data object Hold : Ease
    data class Cubic(val x1: Double, val y1: Double, val x2: Double, val y2: Double) : Ease

    companion object {
        val easeIn = Cubic(0.42, 0.0, 1.0, 1.0)
        val easeOut = Cubic(0.0, 0.0, 0.58, 1.0)
        val easeInOut = Cubic(0.42, 0.0, 0.58, 1.0)
    }
}

/**
 * The view model the app drives Pip through (one per file, with one default instance).
 * Property order is significant: it's the order the runtime lists them.
 */
@Serializable
data class ViewModelSpec(
    val name: String,
    val instance: String = "Default",
    val properties: List<VmPropertySpec> = emptyList(),
)

/**
 * A view-model property. [default] is a number's starting value and [defaultBool] a
 * boolean's. An enum takes [values] and its default is one of them, named in
 * [defaultValue]; [defaultValue] is also a string's text and a colour's `#RRGGBB`.
 */
@Serializable
data class VmPropertySpec(
    val name: String,
    val type: ValueType,
    val default: Double? = null,
    val defaultBool: Boolean? = null,
    val values: List<String> = emptyList(),
    val defaultValue: String? = null,
)

@Serializable
enum class ValueType {
    @SerialName("number") NUMBER,
    @SerialName("boolean") BOOLEAN,
    @SerialName("trigger") TRIGGER,
    @SerialName("enum") ENUM,
    @SerialName("string") STRING,
    @SerialName("color") COLOR,
}

/**
 * The artboard's state machine (it becomes the artboard default).
 *
 * @property control what conditions read: [Control.VIEW_MODEL] binds them to
 *   [PipSource.viewModel] properties; [Control.INPUTS] uses state machine [inputs].
 *   Don't drive one layer both ways: an idle input condition keeps matching and fights
 *   view-model changes.
 * @property inputs state machine inputs, written whichever [control] is used.
 */
@Serializable
data class StateMachineSpec(
    val name: String,
    val control: Control = Control.VIEW_MODEL,
    val inputs: List<InputSpec> = emptyList(),
    val layers: List<LayerSpec> = emptyList(),
    val events: List<EventSpec> = emptyList(),
)

@Serializable
enum class Control {
    @SerialName("viewModel") VIEW_MODEL,
    @SerialName("inputs") INPUTS,
}

/** A state machine input. Only number, boolean and trigger exist as inputs. */
@Serializable
data class InputSpec(
    val name: String,
    val type: ValueType,
    val default: Double? = null,
    val defaultBool: Boolean? = null,
)

/**
 * A layer. Every layer implicitly has `any`, `entry` and `exit` states; [states] adds the
 * rest, and [transitions] connects them by name.
 */
@Serializable
data class LayerSpec(
    val name: String,
    val states: List<StateSpec> = emptyList(),
    val transitions: List<TransitionSpec> = emptyList(),
)

/**
 * A state. [clip] plays a clip, [blend] mixes clips along one number, [direct] mixes
 * clips each by its own amount; with none of them the state is empty (holds whatever the
 * layer last applied). Names are unique within the layer and can't be `any`, `entry` or
 * `exit`. [speed] scales a clip state's playback.
 */
@Serializable
data class StateSpec(
    val name: String,
    val clip: String? = null,
    val blend: BlendSpec? = null,
    val direct: List<DirectClipSpec> = emptyList(),
    val speed: Double? = null,
)

/**
 * One clip in a direct blend state, mixed by number property or input [by] (0 to 100,
 * as percent) or by the constant [mix] (0 to 100). The runtime mixes each clip into the
 * property's current value, which a lone clip at 50 drifts to full over a few frames;
 * list a rest-pose clip first at `mix: 100` so every frame starts from it.
 */
@Serializable
data class DirectClipSpec(val clip: String, val by: String? = null, val mix: Double? = null)

/**
 * A 1D blend state: mixes [clips] by a number property or input [by], each clip placed
 * at its [BlendClipSpec.value].
 */
@Serializable
data class BlendSpec(
    val by: String,
    val clips: List<BlendClipSpec>,
)

@Serializable
data class BlendClipSpec(val clip: String, val value: Double)

/**
 * A transition. [from] and [to] are state names, or `any`/`entry` (from) and `exit` (to).
 * Transitions out of one state are tried in the order listed.
 *
 * @property durationMs the mix time.
 * @property exitTimePercent wait until this much of the source state's clip has played.
 * @property exitTimeMs the same in milliseconds. Set at most one exit time.
 * @property conditions all must hold (AND).
 */
@Serializable
data class TransitionSpec(
    val from: String,
    val to: String,
    val durationMs: Int = 0,
    val exitTimePercent: Int? = null,
    val exitTimeMs: Int? = null,
    val conditions: List<ConditionSpec> = emptyList(),
    /** Easing of the mix; linear by default. */
    val ease: Ease = Ease.Linear,
    /** Hold the source state's clip still while mixing out. */
    val pauseOnExit: Boolean = false,
    /** Allow leaving the transition before its mix finishes. */
    val earlyExit: Boolean = false,
)

/**
 * A condition on a view-model property or input named [on]. Triggers take no [op].
 * Numbers compare with [op] against [value]; booleans against [bool]; enums, strings and
 * colours (`#RRGGBB`) against [match] with `==` or `!=`.
 */
@Serializable
data class ConditionSpec(
    val on: String,
    val op: Op = Op.EQ,
    val value: Double? = null,
    val bool: Boolean? = null,
    @SerialName("is") val match: String? = null,
)

/** Comparison, in the runtime's TransitionConditionOp order. */
@Serializable
enum class Op(val value: Int) {
    @SerialName("==") EQ(0),
    @SerialName("!=") NE(1),
    @SerialName("<=") LE(2),
    @SerialName(">=") GE(3),
    @SerialName("<") LT(4),
    @SerialName(">") GT(5),
}

/** Reads and writes [Ease] as a keyword string or `{"cubic": [x1, y1, x2, y2]}`. */
object EaseSerializer : KSerializer<Ease> {
    override val descriptor: SerialDescriptor = JsonElement.serializer().descriptor

    override fun deserialize(decoder: Decoder): Ease {
        val el = (decoder as? JsonDecoder)?.decodeJsonElement() ?: throw SerializationException("ease needs JSON")
        if (el is JsonPrimitive && el.isString) return when (el.content) {
            "linear" -> Ease.Linear
            "hold" -> Ease.Hold
            "easeIn" -> Ease.easeIn
            "easeOut" -> Ease.easeOut
            "easeInOut" -> Ease.easeInOut
            else -> throw SerializationException(
                "unknown ease '${el.content}' (linear, hold, easeIn, easeOut, easeInOut or {\"cubic\": [x1, y1, x2, y2]})",
            )
        }
        val cubic = (el as? JsonObject)?.get("cubic")?.jsonArray?.map { it.jsonPrimitive.double }
        if (cubic == null || cubic.size != 4 || el.size != 1) throw SerializationException("bad ease $el")
        return Ease.Cubic(cubic[0], cubic[1], cubic[2], cubic[3])
    }

    override fun serialize(encoder: Encoder, value: Ease) {
        val el: JsonElement = when (value) {
            Ease.Linear -> JsonPrimitive("linear")
            Ease.Hold -> JsonPrimitive("hold")
            Ease.easeIn -> JsonPrimitive("easeIn")
            Ease.easeOut -> JsonPrimitive("easeOut")
            Ease.easeInOut -> JsonPrimitive("easeInOut")
            is Ease.Cubic -> JsonObject(
                mapOf("cubic" to JsonArray(listOf(value.x1, value.y1, value.x2, value.y2).map { JsonPrimitive(it) })),
            )
        }
        (encoder as JsonEncoder).encodeJsonElement(el)
    }
}

/**
 * A Rive event the app can listen for, fired when a state starts or ends, or when a clip
 * reaches a frame. Several entries may share a [name]; they fire the same event.
 */
@Serializable
data class EventSpec(
    val name: String,
    /** Fire when this state (in [layer]) starts. */
    val onEnterState: String? = null,
    /** Or when this state (in [layer]) ends. */
    val onExitState: String? = null,
    val layer: String? = null,
    /** Or fire when [clip] reaches [frame]. */
    val clip: String? = null,
    val frame: Int? = null,
)

// ---- Multi-artboard projects (compiled by rivc/project/) ---------------------------------------

/**
 * A project: several artboards in one `.riv`. A source file is a project when its top level
 * has `artboards`; otherwise it is a single-artboard [PipSource].
 *
 * The first artboard is the one runtimes open by default. Artboards refer to each other by
 * [ArtboardSpec.name]. Paths are relative to the project JSON.
 */
@Serializable
data class ProjectSource(
    val format: Int = 1,
    /** Fonts embedded in the file (before every artboard). Texts name them by [AssetSpec.name]. */
    val assets: List<AssetSpec> = emptyList(),
    val artboards: List<ProjectArtboardSpec>,
) {
    companion object {
        fun parse(text: String): ProjectSource = PipSource.json.decodeFromString(serializer(), text)

        fun load(file: File): ProjectSource = parse(file.readText())

        /** Whether [text] is a project (has a top-level `artboards`) rather than a [PipSource]. */
        fun isProject(text: String): Boolean =
            (PipSource.json.parseToJsonElement(text) as? JsonObject)?.containsKey("artboards") == true
    }
}

/**
 * A font embedded in band: [font] is a `.ttf`/`.otf` path. Subset it first to save size.
 * [fallback] names another asset: texts in this font draw the characters it has no glyph for
 * in that one, as runs of their own.
 */
@Serializable
data class AssetSpec(val name: String, val font: String, val fallback: String? = null)

/**
 * One artboard of a project. Either inline (`artboard`, `art`, `clips`, `viewModel`,
 * `stateMachine`, as in [PipSource]) or [source], a single-artboard source file whose
 * sections are used unless this entry sets them.
 *
 * Draw order, top to bottom: [texts], then [instances], then [masks]' markers, then the
 * art; within a list a later entry draws on top of an earlier one. [stack] instead orders
 * texts and instances together, bottom to top, naming every text and instance id once, so a
 * text can sit under an instance.
 *
 * Without a [stateMachine], an artboard with clips gets one called `Main` whose single
 * layer plays the first clip from entry. Events and nested triggers are only reported
 * while a state machine plays the clip that keys them.
 *
 * Clip tracks can target what this entry adds: an instance id (`x`, `y`, `rotation`,
 * `scaleX`, `opacity`...), `"<instance>/<input>"` (`nestedValue` of a number or boolean
 * input), `"<instance>/<remap>"` (`time`, `mix`), a text id (`opacity`, `x`, ...),
 * `"<text>/fill"` and `"<text>/stroke"` (`colorValue`), a [nodes] id and a mask id (`x`, `y`, `rotation`,
 * `scaleX`, `scaleY`).
 */
@Serializable
data class ProjectArtboardSpec(
    val source: String? = null,
    val artboard: ArtboardSpec? = null,
    val art: ArtSpec? = null,
    val clips: List<ClipSpec>? = null,
    val viewModel: ViewModelSpec? = null,
    val stateMachine: StateMachineSpec? = null,
    /** Overrides the state machine's control; nested artboards are driven through `inputs`. */
    val control: Control? = null,
    val instances: List<InstanceSpec> = emptyList(),
    val texts: List<TextSpec> = emptyList(),
    val events: List<CueSpec> = emptyList(),
    val masks: List<MaskSpec> = emptyList(),
    val stack: List<String>? = null,
    /** Transform nodes instances can hang from; see [ProjectNodeSpec]. */
    val nodes: List<ProjectNodeSpec> = emptyList(),
)

/**
 * A transform node on a project artboard: no drawing of its own, but instances ([InstanceSpec.parent])
 * and other nodes ([parent]) hang from it, so translations add, rotations add and scales multiply down
 * a chain. List a parent before its children. [x], [y] is its origin in the parent's space, which is
 * where it rotates and scales about. [bind] ties a property (`x`, `y`, `rotation` in radians,
 * `scaleX`...) to a view-model number; clips key the id like any other target. Give a property one
 * owner: either bind it or key it.
 */
@Serializable
data class ProjectNodeSpec(
    val id: String,
    val parent: String? = null,
    val x: Double = 0.0,
    val y: Double = 0.0,
    val rotation: Double = 0.0,
    val scaleX: Double = 1.0,
    val scaleY: Double = 1.0,
    val bind: Map<String, String> = emptyMap(),
)

/**
 * Another artboard placed on this one (a NestedArtboard). Its [stateMachine] (default:
 * the nested artboard's state machine) plays inside it, and every input of that machine
 * can be keyed from this artboard's clips as `"<id>/<input>"`; [inputs] sets starting
 * values. [fire] keys trigger inputs. [remaps] instead scrub a nested clip directly
 * (keyed as `"<id>/<remap id>"`, properties `time` 0..1 and `mix` 0..1).
 *
 * @property mask a [MaskSpec] id: the instance is clipped by that mask.
 * @property masks more [MaskSpec] ids; the instance shows only where every mask shows it.
 * @property bind node property name to a number in this artboard's view model, for
 *   example `{"x": "pipX"}`.
 * @property parent a [ProjectNodeSpec] id: the instance hangs from that node and moves with it
 *   (default: the artboard). Draw order is unchanged by parenting.
 */
@Serializable
data class InstanceSpec(
    val id: String,
    val artboard: String,
    val x: Double = 0.0,
    val y: Double = 0.0,
    val scale: Double = 1.0,
    val rotation: Double = 0.0,
    val opacity: Double = 1.0,
    val stateMachine: String? = null,
    val inputs: Map<String, Double> = emptyMap(),
    val fire: List<FireSpec> = emptyList(),
    val remaps: List<RemapSpec> = emptyList(),
    val mask: String? = null,
    val masks: List<String> = emptyList(),
    val bind: Map<String, String> = emptyMap(),
    val parent: String? = null,
)

/** Fire nested trigger [input] when [clip] reaches each of [frames]. */
@Serializable
data class FireSpec(val input: String, val clip: String, val frames: List<Int>)

/** A nested clip scrubbed by the parent: [clip] in the nested artboard, keyed by [id] (default: the clip name). */
@Serializable
data class RemapSpec(val clip: String, val id: String? = null, val time: Double = 0.0, val mix: Double = 1.0)

/**
 * A text box. [x], [y] is its top-left corner. With [width] and [height] the box is fixed;
 * with [width] only it grows downwards; with neither it is one line wide as the text.
 * [lineHeight] is in pixels (unset: the font's). [changes] swap the text when a clip
 * reaches a frame (a keyed string). [stroke] outlines the glyphs under the fill (the
 * runtime's text stroke); [gloss] gives the glyphs above a line another colour.
 */
@Serializable
data class TextSpec(
    val id: String,
    val text: String,
    val font: String,
    val size: Double,
    val color: String = "#FF000000",
    val x: Double = 0.0,
    val y: Double = 0.0,
    val width: Double? = null,
    val height: Double? = null,
    val align: TextAlignSpec = TextAlignSpec.LEFT,
    val verticalAlign: VerticalAlignSpec = VerticalAlignSpec.TOP,
    val wrap: Boolean = true,
    val lineHeight: Double? = null,
    val letterSpacing: Double? = null,
    val opacity: Double = 1.0,
    val changes: List<TextChangeSpec> = emptyList(),
    val bind: Map<String, String> = emptyMap(),
    val stroke: TextStrokeSpec? = null,
    val gloss: TextGlossSpec? = null,
)

/** A stroke of [width] (centred on the outline, drawn under the fill) in [color]. */
@Serializable
data class TextStrokeSpec(val color: String, val width: Double)

/** Glyph parts above [y] (in the box, from its top) fill with [color]: a hard-stop vertical gradient. */
@Serializable
data class TextGlossSpec(val color: String, val y: Double)

@Serializable
enum class TextAlignSpec(val value: Int) {
    @SerialName("left") LEFT(0),
    @SerialName("right") RIGHT(1),
    @SerialName("center") CENTER(2),
}

@Serializable
enum class VerticalAlignSpec(val value: Int) {
    @SerialName("top") TOP(0),
    @SerialName("bottom") BOTTOM(1),
    @SerialName("middle") MIDDLE(2),
}

/** From [frame] of [clip] on, the text reads [text] (until the next change). */
@Serializable
data class TextChangeSpec(val clip: String, val frame: Int, val text: String)

/** A named event, fired when [clip] reaches each of [frames]. Apps listen for it by [name]. */
@Serializable
data class CueSpec(val name: String, val clip: String, val frames: List<Int>)

/**
 * A clip mask: a shape at [x], [y] (its centre) that clips the instances naming it. With
 * [invert] the instances are hidden inside the shape instead of shown only inside it (a
 * hole cut with even-odd), for example Pip going behind an app icon. [parts] add more shapes,
 * placed relative to the mask's centre; the mask is their union. [bind] ties the node's
 * `x`/`y` (or `rotation`, `scaleX`...) to view-model numbers so the app can move it at
 * runtime, and `width`/`height` to the shape's own size. [marker] (`#AARRGGBB`) draws the
 * shapes too, under the instances, for previews. [disjoint] promises an inverted mask's shapes
 * never overlap, so one even-odd clip cuts every hole (otherwise each shape is its own clip,
 * since even-odd would show the instance again where two overlap).
 */
@Serializable
data class MaskSpec(
    val id: String,
    val x: Double = 0.0,
    val y: Double = 0.0,
    val shape: MaskShape = MaskShape.ELLIPSE,
    val width: Double,
    val height: Double,
    val cornerRadius: Double = 0.0,
    val invert: Boolean = false,
    val bind: Map<String, String> = emptyMap(),
    val marker: String? = null,
    val parts: List<MaskPartSpec> = emptyList(),
    val disjoint: Boolean = false,
)

/**
 * One more shape of a mask, at [x], [y] from the mask's centre. [originX], [originY] (0..1)
 * pick the point of the shape that sits there (0.5, 0.5: its centre; 0.5, 0: its top edge).
 * [bind] ties its `x`/`y` (from the mask's centre) and `width`/`height` to view-model numbers.
 */
@Serializable
data class MaskPartSpec(
    val shape: MaskShape = MaskShape.RECTANGLE,
    val x: Double = 0.0,
    val y: Double = 0.0,
    val width: Double,
    val height: Double,
    val cornerRadius: Double = 0.0,
    val originX: Double = 0.5,
    val originY: Double = 0.5,
    val bind: Map<String, String> = emptyMap(),
)

@Serializable
enum class MaskShape {
    @SerialName("ellipse") ELLIPSE,
    @SerialName("rectangle") RECTANGLE,
}
