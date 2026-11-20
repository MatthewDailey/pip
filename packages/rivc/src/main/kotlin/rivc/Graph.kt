package rivc

/**
 * An object graph that becomes a `.riv` object stream.
 *
 * The format has two implicit rules that make hand-written streams fragile:
 *
 * 1. **Ids are positions.** `parentId`, `objectId`, `animationId`, `stateToId` and the
 *    rest are indices into some list (the open artboard's object list, its animations,
 *    a layer's states, a machine's inputs, the file's view models). One misplaced
 *    object silently shifts every later id.
 * 2. **Membership is positional.** Nothing names an object's container: a keyed object
 *    belongs to the latest animation, a transition to the latest state, a condition to
 *    the latest transition, a data bind to the latest non-bind object.
 *
 * So the graph never takes a raw id. Objects reference each other by [RivNode] handle,
 * declare their container as [RivNode.owner], and are written in the order they were
 * [added][RivGraph.add]. [RivGraph.resolve] then computes every id from stream order and
 * checks that each reference points at an added object of the right kind, in the right
 * container and, where the runtime needs it, earlier in the stream, and that each
 * object's declared owner is the one its stream position gives it. Any failure throws a
 * [RivGraphException] whose lines name the object and property at fault.
 *
 * ```
 * val g = RivGraph()
 * val ab = g.add("Artboard") { this["name"] = "Pip"; this["width"] = 540; this["height"] = 660 }
 * val root = g.add("Node", owner = ab) { this["name"] = "root"; this["parentId"] = ab }
 * val bytes = g.encode()
 * ```
 */
class RivGraph(val schema: Schema = Schema.bundled) {
    private val stream = mutableListOf<RivNode>()

    /** File id written in the header. */
    var fileId: Long = 0

    /** Placed objects, in stream order. */
    val objects: List<RivNode> get() = stream

    /**
     * Creates an object that is not yet in the stream, so it can be referenced before it
     * is placed (an artboard's default state machine, a transition to a later state).
     * Place it with [add]; a reference to a node that is never added fails [resolve].
     */
    fun create(type: String, owner: RivNode? = null, label: String? = null): RivNode =
        RivNode(this, schema.cls(type), owner, label)

    /** Appends [node] to the stream. */
    fun add(node: RivNode): RivNode {
        require(node.graph === this) { "${node.describe()} belongs to another graph" }
        require(node.position < 0) { "${node.describe()} was already added" }
        node.position = stream.size
        stream += node
        return node
    }

    /** Creates an object of [type], runs [init] on it, and appends it. */
    fun add(type: String, owner: RivNode? = null, label: String? = null, init: RivNode.() -> Unit = {}): RivNode =
        add(create(type, owner, label).apply(init))

    /** Resolves ids and validates the graph. */
    fun resolve(): RivFile = Resolver(this).run()

    /** [resolve] then write. */
    fun encode(): ByteArray = RivWriter.write(resolve())
}

/** A graph that can't be written. Each entry in [problems] names the object and property. */
class RivGraphException(val problems: List<String>) :
    RuntimeException("the object graph can't be written:\n" + problems.joinToString("\n") { "  - $it" })

/** A value held by the graph before ids exist. */
sealed interface GValue {
    data class Num(val v: Double) : GValue
    data class Str(val v: String) : GValue
    data class Bool(val v: Boolean) : GValue
    data class Color(val argb: Int) : GValue
    class Bytes(val v: ByteArray) : GValue

    /** An Id property's target. */
    data class Ref(val target: RivNode) : GValue

    /** A bytes field holding a path of ids, for example DataBindContext.sourcePathIds = [view model, property]. */
    data class Path(val targets: List<RivNode>) : GValue
}

/** ARGB colour for a Color property, for example `Argb(0xFFFDE3D2)`. */
data class Argb(val argb: Long)

/**
 * One object in a [RivGraph]. Set properties by schema name; the value is checked
 * against the property's field type:
 *
 * - Double, Uint, Int: a [Number] (Uint also takes a [PropertyDef], meaning its key,
 *   for `KeyedProperty.propertyKey` and `DataBindContext.propertyKey`)
 * - Bool: a [Boolean]; String: a [String]; Color: [Argb] or a [Number]
 * - Id: a [RivNode] handle, never a number
 * - Bytes: a [ByteArray], or a `List<RivNode>` for path-id fields
 *
 * Properties are written in the order they are first set. Setting `null` removes one.
 *
 * @property owner the container the runtime will attach this object to; see [RivGraph].
 * @property label a name for error messages when the object has no `name`.
 */
class RivNode internal constructor(
    val graph: RivGraph,
    val cls: ClassDef,
    val owner: RivNode?,
    val label: String?,
) {
    internal val values = LinkedHashMap<PropertyDef, GValue>()

    /** Stream position, or -1 while not added. */
    var position: Int = -1
        internal set

    val type: String get() = cls.name

    operator fun set(prop: String, value: Any?) {
        val def = graph.schema.property(cls.name, prop)
        if (value == null) { values.remove(def); return }
        values[def] = convert(def, value)
    }

    operator fun get(prop: String): GValue? = values[graph.schema.property(cls.name, prop)]

    /** The object's `name`, if it has one. */
    val name: String?
        get() = values.entries.firstOrNull { it.key.name == "name" }?.let { (it.value as? GValue.Str)?.v }

    /** For messages: `Shape "eyeL" (#12)`. */
    fun describe(): String {
        val n = name ?: label
        val where = if (position >= 0) "#$position" else "not added"
        return if (n != null) "${cls.name} \"$n\" ($where)" else "${cls.name} ($where)"
    }

    override fun toString() = describe()

    private fun convert(def: PropertyDef, v: Any): GValue {
        fun bad(): Nothing = throw IllegalArgumentException(
            "${describe()}: ${def.name} is ${def.type}; can't take ${v::class.simpleName} $v",
        )
        return when (def.type) {
            FieldType.Double -> (v as? Number)?.let { GValue.Num(it.toDouble()) } ?: bad()
            FieldType.Uint, FieldType.Int -> when (v) {
                is PropertyDef -> GValue.Num(v.key.toDouble())
                is Number -> {
                    val d = v.toDouble()
                    if (d != Math.floor(d) || (def.type == FieldType.Uint && d < 0)) bad()
                    GValue.Num(d)
                }
                else -> bad()
            }
            FieldType.Bool -> (v as? Boolean)?.let { GValue.Bool(it) } ?: bad()
            FieldType.String -> (v as? String)?.let { GValue.Str(it) } ?: bad()
            FieldType.Color -> when (v) {
                is Argb -> GValue.Color(v.argb.toInt())
                is Number -> GValue.Color(v.toLong().toInt())
                else -> bad()
            }
            FieldType.Id -> when (v) {
                is RivNode -> GValue.Ref(v)
                is Number -> throw IllegalArgumentException(
                    "${describe()}: ${def.name} takes an object handle, not the raw id $v; the graph assigns ids",
                )
                else -> bad()
            }
            FieldType.Bytes -> when (v) {
                is ByteArray -> GValue.Bytes(v)
                is List<*> -> GValue.Path(v.map { it as? RivNode ?: bad() })
                else -> bad()
            }
            FieldType.Callback -> throw IllegalArgumentException("${describe()}: ${def.name} is a runtime callback and can't be stored")
        }
    }
}

/**
 * Where an object must sit: the class of the latest earlier object the runtime attaches
 * it to (its importer), mirroring `importStack.latest<…>` in rive-runtime. First match by
 * inheritance wins. [FILE] means file level (no owner); [FREE] means the runtime picks it
 * up some other way, so only "owner, if given, comes first" is checked; [PREVIOUS] means
 * the latest object that isn't itself one of these (a DataBind's target).
 */
internal object OwnerRules {
    const val FILE = "<file>"
    const val FREE = "<free>"
    const val PREVIOUS = "<previous non-DataBind>"

    val rules: List<Pair<String, String>> = listOf(
        "DataBind" to PREVIOUS,
        "ViewModelInstanceValue" to "ViewModelInstance",
        "ViewModelInstance" to FILE,
        "ViewModelProperty" to "ViewModel",
        "ViewModel" to FILE,
        "DataEnumValue" to "DataEnumCustom",
        "DataEnum" to FILE,
        "Backboard" to FILE,
        "Artboard" to FILE,
        "KeyFrameInterpolator" to "Artboard",
        "Component" to "Artboard",
        "LinearAnimation" to "Artboard",
        "StateMachine" to "Artboard",
        "KeyedObject" to "LinearAnimation",
        "KeyedProperty" to "KeyedObject",
        "KeyFrame" to "KeyedProperty",
        "StateMachineInput" to "StateMachine",
        "StateMachineLayer" to "StateMachine",
        "LayerState" to "StateMachineLayer",
        "StateTransition" to "LayerState",
        "TransitionCondition" to "StateTransition",
        "BindableProperty" to FREE,
        "TransitionComparator" to "TransitionViewModelCondition",
        "BlendAnimation" to "LayerState",
        "StateMachineFireAction" to "StateMachineLayerComponent",
        "FileAssetContents" to "FileAsset",
        "FileAsset" to FILE,
    )

    fun ownerKind(schema: Schema, cls: String): String? = rules.firstOrNull { schema.isA(cls, it.first) }?.second
}

/**
 * What an Id property indexes. Every Id property a graph sets needs a rule here; an
 * unknown one fails with a message saying to add it, rather than guessing.
 */
internal sealed interface IndexRule {
    /**
     * An artboard slot: the target is the artboard (0) or an object that claims a slot in
     * the same artboard. [before]: the runtime resolves it while reading, so the target
     * must already be in the stream.
     */
    data class Slot(val before: Boolean) : IndexRule

    /**
     * The target's index among the objects of [kind] that share its owner, which must be
     * the container [container] picks for the referencing object (null: file level).
     */
    data class Sibling(val kind: String, val describe: String, val container: (RivNode) -> RivNode?) : IndexRule

    /**
     * The target's index among the objects of [kind] that share the target's own owner,
     * whatever that is (an enum value's index in its enum: the referencing object can't
     * name the enum itself).
     */
    data class Member(val kind: String) : IndexRule

    /** One property shared by several classes that index different lists: the first class the object is wins. */
    data class ByClass(val choices: List<Pair<String, IndexRule>>) : IndexRule
}

internal object IndexRules {
    private fun RivNode.enclosing(kind: String): RivNode? {
        var n: RivNode? = this
        while (n != null && !graph.schema.isA(n.cls, kind)) n = n.owner
        return n
    }

    private fun refTarget(n: RivNode?, prop: String): RivNode? = (n?.get(prop) as? GValue.Ref)?.target

    private val byProperty: Map<Pair<String, String>, IndexRule> = mapOf(
        ("Component" to "parentId") to IndexRule.Slot(before = true),
        ("KeyedObject" to "objectId") to IndexRule.Slot(before = false),
        ("InterpolatingKeyFrame" to "interpolatorId") to IndexRule.Slot(before = true),
        ("StateTransition" to "interpolatorId") to IndexRule.Slot(before = true),
        ("ClippingShape" to "sourceId") to IndexRule.Slot(before = false),
        ("DrawRules" to "drawTargetId") to IndexRule.Slot(before = false),
        ("DrawTarget" to "drawableId") to IndexRule.Slot(before = false),
        ("KeyFrameId" to "value") to IndexRule.Slot(before = false),
        ("TargetedConstraint" to "targetId") to IndexRule.Slot(before = false),
        ("Tendon" to "boneId") to IndexRule.Slot(before = false),
        ("AnimationState" to "animationId") to
            IndexRule.Sibling("LinearAnimation", "its artboard's animations") { it.enclosing("Artboard") },
        ("BlendAnimation" to "animationId") to
            IndexRule.Sibling("LinearAnimation", "its artboard's animations") { it.enclosing("Artboard") },
        ("Artboard" to "defaultStateMachineId") to
            IndexRule.Sibling("StateMachine", "this artboard's state machines") { it },
        ("Artboard" to "viewModelId") to IndexRule.Sibling("ViewModel", "the file's view models") { null },
        ("ViewModelInstance" to "viewModelId") to IndexRule.Sibling("ViewModel", "the file's view models") { null },
        ("ViewModelInstanceValue" to "viewModelPropertyId") to
            IndexRule.Sibling("ViewModelProperty", "the instance's view model properties") {
                refTarget(it.owner, "viewModelId")
            },
        ("StateTransition" to "stateToId") to
            IndexRule.Sibling("LayerState", "its layer's states") { it.enclosing("StateMachineLayer") },
        ("TransitionInputCondition" to "inputId") to
            IndexRule.Sibling("StateMachineInput", "its state machine's inputs") { it.enclosing("StateMachine") },
        ("BlendState1DInput" to "inputId") to
            IndexRule.Sibling("StateMachineInput", "its state machine's inputs") { it.enclosing("StateMachine") },
        ("BlendAnimationDirect" to "inputId") to
            IndexRule.Sibling("StateMachineInput", "its state machine's inputs") { it.enclosing("StateMachine") },
        ("StateMachineFireEvent" to "eventId") to IndexRule.Slot(before = false),
        ("ViewModelPropertyEnumCustom" to "enumId") to IndexRule.Sibling("DataEnum", "the file's enums") { null },
        ("ViewModelInstanceEnum" to "propertyValue") to IndexRule.Member("DataEnumValue"),
        ("BindablePropertyEnum" to "propertyValue") to IndexRule.Member("DataEnumValue"),
        ("TransitionValueIdComparator" to "value") to IndexRule.Member("DataEnumValue"),
        // Multi-artboard projects: a nested artboard names a file-level artboard, its nested
        // animations index that artboard's machines or clips, and a nested input indexes the
        // nested machine's inputs. The runtime resolves all of these after the whole file is read.
        ("NestedArtboard" to "artboardId") to IndexRule.Sibling("Artboard", "the file's artboards") { null },
        ("NestedAnimation" to "animationId") to IndexRule.ByClass(
            listOf(
                "NestedStateMachine" to IndexRule.Sibling("StateMachine", "the nested artboard's state machines") {
                    refTarget(refTarget(it, "parentId"), "artboardId")
                },
                "NestedLinearAnimation" to IndexRule.Sibling("LinearAnimation", "the nested artboard's animations") {
                    refTarget(refTarget(it, "parentId"), "artboardId")
                },
            ),
        ),
        ("NestedInput" to "inputId") to IndexRule.Sibling("StateMachineInput", "the nested state machine's inputs") {
            refTarget(refTarget(it, "parentId"), "animationId")
        },
        ("TextStyle" to "fontAssetId") to IndexRule.Sibling("FileAsset", "the file's assets") { null },
        ("TextValueRun" to "styleId") to IndexRule.Slot(before = false),
    )

    fun rule(def: PropertyDef): IndexRule? = byProperty[def.owner to def.name]
}

private class Resolver(val g: RivGraph) {
    val schema = g.schema
    val problems = mutableListOf<String>()
    val slots = HashMap<RivNode, Int>() // artboard slot per object (artboard = 0)
    val artboardOf = HashMap<RivNode, RivNode>() // positional artboard per object

    fun run(): RivFile {
        assignSlots()
        checkOwners()
        val records = g.objects.map { record(it) }
        if (problems.isNotEmpty()) throw RivGraphException(problems)
        return RivFile(schema.formatMajor, schema.formatMinor, g.fileId, emptyList(), records)
    }

    fun assignSlots() {
        var artboard: RivNode? = null
        var next = 0
        for (n in g.objects) {
            if (schema.isA(n.cls, "Artboard")) {
                artboard = n; next = 1; slots[n] = 0; artboardOf[n] = n
                continue
            }
            if (artboard == null) continue
            artboardOf[n] = artboard
            if (schema.claimsArtboardSlot(n.type)) slots[n] = next++
        }
    }

    fun checkOwners() {
        val latest = HashMap<String, RivNode>()
        var previousBindable: RivNode? = null
        for (n in g.objects) {
            val kind = OwnerRules.ownerKind(schema, n.type)
            val owner = n.owner
            if (owner != null && (owner.graph !== g || owner.position < 0)) {
                problems += "${n.describe()}: owner ${owner.describe()} was never added to the graph"
            } else if (owner != null && owner.position > n.position) {
                problems += "${n.describe()}: owner ${owner.describe()} comes later in the stream; an owner must be written first"
            }
            when (kind) {
                null -> {}
                OwnerRules.FREE -> {}
                OwnerRules.FILE -> {
                    if (owner != null) problems += "${n.describe()}: is file-level but declares owner ${owner.describe()}"
                }
                OwnerRules.PREVIOUS -> if (owner !== previousBindable) {
                    problems += "${n.describe()}: binds whatever comes just before it (${previousBindable?.describe() ?: "nothing"}), " +
                        "but declares owner ${owner?.describe() ?: "none"}"
                }
                else -> {
                    val positional = latest.entries.filter { schema.isA(it.key, kind) }.maxByOrNull { it.value.position }?.value
                    if (owner == null) {
                        problems += "${n.describe()}: needs an owner of kind $kind (stream position would attach it to ${positional?.describe() ?: "nothing"})"
                    } else if (!schema.isA(owner.cls, kind)) {
                        problems += "${n.describe()}: owner ${owner.describe()} is not a $kind"
                    } else if (positional !== owner && owner.position in 0 until n.position) {
                        problems += "${n.describe()}: declares owner ${owner.describe()}, but its stream position attaches it to " +
                            "${positional?.describe() ?: "nothing"}; write it right after its owner's other children"
                    }
                }
            }
            // A Component read while an artboard is open claims a slot in it, which shifts every later id.
            val fileLevel = kind == OwnerRules.FILE || (owner != null && OwnerRules.ownerKind(schema, owner.type) == OwnerRules.FILE &&
                !schema.isA(owner.cls, "Artboard"))
            if (fileLevel && slots.containsKey(n) && !schema.isA(n.cls, "Artboard")) {
                problems += "${n.describe()}: file-level object written after ${artboardOf[n]?.describe()}; it would take an " +
                    "artboard slot and shift every later id, so write view models and their instances before the first artboard"
            }
            latest[n.type] = n
            if (!schema.isA(n.cls, "DataBind")) previousBindable = n
        }
    }

    fun record(n: RivNode): RivRecord {
        val typeKey = n.cls.typeKey
        if (typeKey == null) problems += "${n.describe()}: ${n.type} has no type key and can't be written"
        val props = n.values.map { (def, v) -> RivProp(def.key, raw(n, def, v)) }
        return RivRecord(typeKey ?: 0, props)
    }

    fun raw(n: RivNode, def: PropertyDef, v: GValue): RawValue = when (v) {
        is GValue.Num -> when (def.type) {
            FieldType.Double -> RawValue.F32(v.v.toFloat())
            FieldType.Int -> RawValue.Varint(v.v.toInt())
            else -> RawValue.Varuint(v.v.toLong())
        }
        is GValue.Str -> RawValue.Str(v.v)
        is GValue.Bool -> RawValue.Bool(v.v)
        is GValue.Color -> RawValue.Color(v.argb)
        is GValue.Bytes -> RawValue.Bytes(v.v)
        is GValue.Ref -> RawValue.Varuint(index(n, def, v.target)?.toLong() ?: 0)
        is GValue.Path -> RawValue.Bytes(packVaruints(path(n, def, v.targets).map { it.toLong() }))
    }

    /** The index [target] has when [n].[def] refers to it, or null after recording a problem. */
    fun index(n: RivNode, def: PropertyDef, target: RivNode): Int? {
        val at = "${n.describe()}: ${def.name} refers to ${target.describe()}"
        if (target.graph !== g || target.position < 0) {
            problems += "$at, which was never added to the graph (dangling reference)"
            return null
        }
        val found = IndexRules.rule(def)
        val chosen = if (found is IndexRule.ByClass) found.choices.firstOrNull { schema.isA(n.cls, it.first) }?.second else found
        if (found is IndexRule.ByClass && chosen == null) {
            problems += "${n.describe()}: ${def.name} has no index rule for ${n.type}; add one to IndexRules in Graph.kt"
            return null
        }
        return when (val rule = chosen) {
            null -> {
                problems += "${n.describe()}: ${def.name} (${def.owner}.${def.name}) has no index rule; add one to IndexRules in Graph.kt"
                null
            }
            is IndexRule.Slot -> {
                val slot = slots[target]
                when {
                    slot == null -> { problems += "$at, which takes no artboard slot"; null }
                    artboardOf[target] !== artboardOf[n] ->
                        { problems += "$at in ${artboardOf[target]?.describe()}, not this object's artboard ${artboardOf[n]?.describe()}"; null }
                    rule.before && target.position > n.position ->
                        { problems += "$at, which comes later in the stream; it must be written first (mis-ordered reference)"; null }
                    def.name == "parentId" && !schema.isA(target.cls, "ContainerComponent") ->
                        { problems += "$at, which can't have children"; null }
                    else -> slot
                }
            }
            is IndexRule.Sibling -> {
                val container = rule.container(n)
                when {
                    !schema.isA(target.cls, rule.kind) -> { problems += "$at, which is not a ${rule.kind}"; null }
                    target.owner !== container ->
                        { problems += "$at, which is not among ${rule.describe} (${container?.describe() ?: "file level"})"; null }
                    else -> g.objects.count { it.position < target.position && it.owner === container && schema.isA(it.cls, rule.kind) }
                }
            }
            is IndexRule.Member -> when {
                !schema.isA(target.cls, rule.kind) -> { problems += "$at, which is not a ${rule.kind}"; null }
                target.owner == null -> { problems += "$at, which has no owner to be indexed in"; null }
                else -> g.objects.count { it.position < target.position && it.owner === target.owner && schema.isA(it.cls, rule.kind) }
            }
            is IndexRule.ByClass -> error("nested ByClass index rules aren't supported")
        }
    }

    /** sourcePathIds: [view model index in the file, property index in that view model]. */
    fun path(n: RivNode, def: PropertyDef, targets: List<RivNode>): List<Int> {
        val at = "${n.describe()}: ${def.name}"
        val out = mutableListOf<Int>()
        val vm = targets.firstOrNull()
        if (vm == null || !schema.isA(vm.cls, "ViewModel")) {
            problems += "$at must start with a ViewModel, got ${vm?.describe() ?: "an empty path"}"
            return out
        }
        for (t in targets) if (t.graph !== g || t.position < 0) {
            problems += "$at refers to ${t.describe()}, which was never added to the graph (dangling reference)"
            return out
        }
        out += g.objects.count { it.position < vm.position && schema.isA(it.cls, "ViewModel") }
        for (p in targets.drop(1)) {
            if (!schema.isA(p.cls, "ViewModelProperty") || p.owner !== vm) {
                problems += "$at: ${p.describe()} is not a property of ${vm.describe()}"
                return out
            }
            out += g.objects.count { it.position < p.position && it.owner === vm && schema.isA(it.cls, "ViewModelProperty") }
        }
        if (targets.size > 2) problems += "$at: nested view-model paths aren't supported yet"
        return out
    }
}
