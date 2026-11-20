package rivc

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * How a property's value is encoded in a `.riv` object stream.
 *
 * [tocIndex] is the 2-bit field index the header's property table of contents uses
 * (uint 0, string 1, double 2, colour 3); a bool is written as a uint in the ToC.
 */
enum class FieldType(val tocIndex: Int) {
    /** LEB128 varuint. */
    Uint(0),

    /** An index (LEB128 varuint). What it indexes depends on the property; see [IndexRules]. */
    Id(0),

    /** Zigzag-encoded signed varint. */
    Int(0),

    /** float32, little-endian. The runtime stores every "double" as a float. */
    Double(2),

    /** varuint byte length, then UTF-8. */
    String(1),

    /** varuint byte length, then raw bytes. */
    Bytes(1),

    /** uint32 little-endian ARGB. */
    Color(3),

    /** One byte, 0 or 1. */
    Bool(0),

    /** An event the runtime fires (for example a trigger's `fire`); never stored in a file. */
    Callback(0),
}

/** One property of a class, as declared by the runtime's generated `*_base.hpp`. */
data class PropertyDef(val name: String, val key: Int, val type: FieldType, val owner: String) {
    override fun toString() = "$owner.$name($key)"
}

/**
 * One runtime class. [typeKey] is null for mixins that never appear in a file on their
 * own (ColorChannels). [parents] lists direct bases, primary first.
 */
class ClassDef(
    val name: String,
    val typeKey: Int?,
    val parents: List<String>,
    val ownProperties: Map<String, PropertyDef>,
) {
    override fun toString() = name
}

/**
 * Typed view of `schema.json`, extracted from the pinned rive-runtime by
 * `schema/extract_schema.py`. Every lookup throws with a readable message when the
 * name or key is unknown, so a schema bump that renames something fails at compile time
 * of the `.riv`, not on device.
 */
class Schema(
    val runtimeCommit: String,
    val formatMajor: Int,
    val formatMinor: Int,
    classes: Collection<ClassDef>,
) {
    private val byName: Map<String, ClassDef> = classes.associateBy { it.name }
    private val byTypeKey: Map<Int, ClassDef> = classes.filter { it.typeKey != null }.associateBy { it.typeKey!! }
    private val propsByKey: Map<Int, PropertyDef> =
        classes.flatMap { it.ownProperties.values }.associateBy { it.key }
    private val ancestorCache = HashMap<String, Set<String>>()
    private val propertyCache = HashMap<String, Map<String, PropertyDef>>()

    val classNames: Set<String> get() = byName.keys

    /** The class called [name] (runtime class name, for example `"Shape"`). */
    fun cls(name: String): ClassDef = byName[name] ?: error("schema: no class '$name' in rive-runtime ${runtimeCommit.take(8)}")

    fun clsOrNull(name: String): ClassDef? = byName[name]

    /** The class with [typeKey], or null when this runtime doesn't know it. */
    fun clsByTypeKey(typeKey: Int): ClassDef? = byTypeKey[typeKey]

    /** The property with [key], from any class (keys are globally unique). */
    fun propertyByKey(key: Int): PropertyDef? = propsByKey[key]

    /** [cls] and every class it inherits from, nearest first. */
    fun ancestors(cls: String): Set<String> = ancestorCache.getOrPut(cls) {
        val out = LinkedHashSet<String>()
        val queue = ArrayDeque(listOf(cls))
        while (queue.isNotEmpty()) {
            val c = queue.removeFirst()
            if (!out.add(c)) continue
            byName[c]?.parents?.let { queue.addAll(it) }
        }
        out
    }

    /** Whether [cls] is [ancestor] or inherits from it. */
    fun isA(cls: String, ancestor: String): Boolean = ancestor in ancestors(cls)

    fun isA(cls: ClassDef, ancestor: String): Boolean = isA(cls.name, ancestor)

    /** Every property [cls] can carry, including inherited ones, by name. */
    fun properties(cls: String): Map<String, PropertyDef> = propertyCache.getOrPut(cls) {
        val out = LinkedHashMap<String, PropertyDef>()
        for (c in ancestors(cls)) byName[c]?.ownProperties?.forEach { (n, p) -> out.putIfAbsent(n, p) }
        out
    }

    /** Property [name] on [cls] or one of its bases. */
    fun property(cls: String, name: String): PropertyDef =
        properties(cls)[name] ?: error(
            "schema: $cls has no property '$name' (known: ${properties(cls).keys.sorted().joinToString()})",
        )

    /** Whether [cls] can carry the property with [key]. */
    fun hasProperty(cls: String, key: Int): Boolean = properties(cls).values.any { it.key == key }

    /**
     * Whether an object of [cls] takes the next index in the open artboard's object
     * list, mirroring the runtime's `claimsArtboardSlot` overrides: every Component
     * except the Artboard itself and ScrollPhysics, every KeyFrameInterpolator, and
     * every UserInput. ScriptInput* decide at import time and are not supported.
     */
    fun claimsArtboardSlot(cls: String): Boolean {
        if (cls.startsWith("ScriptInput")) error("schema: $cls decides its artboard slot at import time; rivc doesn't support it")
        if (isA(cls, "Artboard") || isA(cls, "ScrollPhysics")) return false
        return isA(cls, "Component") || isA(cls, "KeyFrameInterpolator") || isA(cls, "UserInput")
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        /** The schema bundled with rivc (the pinned runtime's). */
        val bundled: Schema by lazy {
            val text = Schema::class.java.getResource("/schema.json")?.readText()
                ?: error("schema.json is missing from the classpath")
            parse(text)
        }

        fun parse(text: String): Schema {
            val file = json.decodeFromString(SchemaFile.serializer(), text)
            val classes = file.classes.map { (name, c) ->
                ClassDef(
                    name = name,
                    typeKey = c.typeKey,
                    parents = c.parents,
                    ownProperties = c.props.mapValues { (pn, p) ->
                        val type = p.type ?: error("schema: $name.$pn has no field type")
                        PropertyDef(pn, p.key, FieldType.valueOf(type), name)
                    },
                )
            }
            return Schema(
                runtimeCommit = file.runtime.commit ?: "unknown",
                formatMajor = file.runtime.formatMajor,
                formatMinor = file.runtime.formatMinor,
                classes = classes,
            )
        }
    }

    @Serializable
    private class SchemaFile(val runtime: RuntimeInfo, val classes: Map<String, SchemaClass>)

    @Serializable
    private class RuntimeInfo(
        val repo: String? = null,
        val commit: String? = null,
        val pinnedBy: String? = null,
        val formatMajor: Int,
        val formatMinor: Int,
    )

    @Serializable
    private class SchemaClass(
        val typeKey: Int? = null,
        val parents: List<String> = emptyList(),
        val file: String? = null,
        val props: Map<String, SchemaProp> = emptyMap(),
    )

    @Serializable
    private class SchemaProp(val key: Int, val type: String? = null)
}
