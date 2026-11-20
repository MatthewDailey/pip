package rivc

/** A `.riv` that can't be decoded; the message says where. */
class RivFormatException(message: String) : RuntimeException(message)

/**
 * Decodes any `.riv` into a [RivFile], using [schema] for property field types and the
 * file's own table of contents for properties the schema doesn't know (as the runtime
 * does). Objects of unknown types are kept, so a dump shows them rather than hiding them.
 */
class RivReader(private val schema: Schema = Schema.bundled) {
    fun read(bytes: ByteArray): RivFile {
        val r = Cursor(bytes)
        val magic = String(ByteArray(4) { r.byte().toByte() }, Charsets.US_ASCII)
        if (magic != "RIVE") throw RivFormatException("not a .riv: starts with '$magic', expected 'RIVE'")
        val major = r.varuint().toInt()
        val minor = r.varuint().toInt()
        val fileId = r.varuint()
        val keys = generateSequence { r.varuint().toInt() }.takeWhile { it != 0 }.toList()
        val toc = mutableListOf<TocEntry>()
        var packed = 0
        keys.forEachIndexed { i, key ->
            if (i % 4 == 0) packed = r.uint32()
            toc += TocEntry(key, (packed ushr ((i % 4) * 2)) and 3)
        }
        val tocTypes = toc.associate { it.key to it.fieldIndex }

        val objects = mutableListOf<RivRecord>()
        while (!r.atEnd()) {
            val at = r.pos
            val typeKey = r.varuint().toInt()
            val props = mutableListOf<RivProp>()
            while (true) {
                val key = r.varuint().toInt()
                if (key == 0) break
                val type = schema.propertyByKey(key)?.type ?: when (tocTypes[key]) {
                    0 -> FieldType.Uint
                    1 -> FieldType.Bytes
                    2 -> FieldType.Double
                    3 -> FieldType.Color
                    else -> throw RivFormatException(
                        "object #${objects.size} (type $typeKey at byte $at): property key $key is in neither the schema nor the ToC",
                    )
                }
                val value = when (type) {
                    FieldType.Uint, FieldType.Id -> RawValue.Varuint(r.varuint())
                    FieldType.Int -> RawValue.Varint(r.varint())
                    FieldType.Double -> RawValue.F32(java.lang.Float.intBitsToFloat(r.uint32()))
                    FieldType.String -> RawValue.Str(String(r.lengthPrefixed(), Charsets.UTF_8))
                    FieldType.Bytes -> RawValue.Bytes(r.lengthPrefixed())
                    FieldType.Color -> RawValue.Color(r.uint32())
                    FieldType.Bool -> RawValue.Bool(r.byte() != 0)
                    FieldType.Callback -> throw RivFormatException("object #${objects.size}: callback property $key can't be stored")
                }
                props += RivProp(key, value)
            }
            objects += RivRecord(typeKey, props)
        }
        return RivFile(major, minor, fileId, toc, objects)
    }

    private class Cursor(val b: ByteArray) {
        var pos = 0

        fun atEnd() = pos >= b.size

        fun byte(): Int {
            if (pos >= b.size) throw RivFormatException("unexpected end of file at byte $pos")
            return b[pos++].toInt() and 0xff
        }

        fun varuint(): Long {
            var result = 0L
            var shift = 0
            while (true) {
                val x = byte()
                result = result or ((x and 0x7f).toLong() shl shift)
                if (x and 0x80 == 0) return result
                shift += 7
                if (shift > 63) throw RivFormatException("varuint overflow at byte $pos")
            }
        }

        fun varint(): Int {
            val z = varuint().toInt()
            return (z ushr 1) xor -(z and 1)
        }

        fun uint32(): Int = byte() or (byte() shl 8) or (byte() shl 16) or (byte() shl 24)

        fun lengthPrefixed(): ByteArray {
            val n = varuint()
            if (n > b.size - pos) throw RivFormatException("length $n at byte $pos runs past the end of the file")
            return b.copyOfRange(pos, pos + n.toInt()).also { pos += n.toInt() }
        }
    }
}

/**
 * Human-readable listing of a [RivFile], one line per object, stable for diffs:
 *
 * ```
 * RIVE 7.5 fileId=0 toc=[]
 * #7 Artboard [0] name="Pip" width=540.0 ...
 * #8 Node [1] name="root" parentId=0
 * ```
 *
 * `#n` is the stream position. `[n]` is the artboard slot the object takes (the index that
 * parentId, objectId and interpolatorId use); objects that take no slot have none.
 * Values: floats in shortest form, colours as `#AARRGGBB`, strings JSON-quoted, bytes as
 * hex, plus the decoded varuints for path-id fields (`sourcePathIds=[0,1]`).
 */
object RivDump {
    fun lines(file: RivFile, schema: Schema = Schema.bundled): List<String> {
        val out = mutableListOf<String>()
        val toc = file.toc.joinToString(",", "[", "]") { "${it.key}:${it.fieldIndex}" }
        out += "RIVE ${file.major}.${file.minor} fileId=${file.fileId} toc=$toc"
        var slot = -1 // -1: no artboard open yet
        file.objects.forEachIndexed { i, o ->
            val cls = schema.clsByTypeKey(o.typeKey)
            val sb = StringBuilder("#$i ")
            sb.append(cls?.name ?: "<unknown type ${o.typeKey}>")
            if (cls != null) {
                if (schema.isA(cls, "Artboard")) {
                    slot = 0
                    sb.append(" [0]")
                } else if (slot >= 0 && !cls.name.startsWith("ScriptInput") && schema.claimsArtboardSlot(cls.name)) {
                    sb.append(" [${++slot}]")
                }
            }
            for (p in o.props) {
                val def = schema.propertyByKey(p.key)
                sb.append(' ').append(def?.name ?: "key${p.key}").append('=').append(format(p.value, def))
            }
            out += sb.toString()
        }
        return out
    }

    fun text(file: RivFile, schema: Schema = Schema.bundled) = lines(file, schema).joinToString("\n", postfix = "\n")

    private fun format(v: RawValue, def: PropertyDef?): String = when (v) {
        is RawValue.Varuint -> v.v.toString()
        is RawValue.Varint -> v.v.toString()
        is RawValue.F32 -> v.v.toString()
        is RawValue.Str -> quote(v.v)
        is RawValue.Bool -> v.v.toString()
        is RawValue.Color -> "#%08X".format(v.argb)
        is RawValue.Bytes -> {
            val name = def?.name ?: ""
            val decoded = if (name.endsWith("PathIds") || name == "path") unpackVaruints(v.v) else null
            decoded?.joinToString(",", "[", "]") ?: ("0x" + v.v.joinToString("") { "%02x".format(it) })
        }
    }

    private fun unpackVaruints(b: ByteArray): List<Long>? {
        val out = mutableListOf<Long>()
        var i = 0
        while (i < b.size) {
            var result = 0L
            var shift = 0
            while (true) {
                if (i >= b.size || shift > 63) return null
                val x = b[i++].toInt() and 0xff
                result = result or ((x and 0x7f).toLong() shl shift)
                if (x and 0x80 == 0) break
                shift += 7
            }
            out += result
        }
        return out
    }

    private fun quote(s: String) = buildString {
        append('"')
        for (c in s) when {
            c == '"' -> append("\\\"")
            c == '\\' -> append("\\\\")
            c == '\n' -> append("\\n")
            c < ' ' -> append("\\u%04x".format(c.code))
            else -> append(c)
        }
        append('"')
    }
}

/**
 * Line diff of two dumps. Lines are matched on their content without the `#n ` stream
 * position, so one inserted object shows as one `+` line rather than shifting every
 * line after it. Output uses `-` for [a] only, `+` for [b] only, and keeps the original
 * `#n` so each side can be found in its own dump.
 */
object RivDiff {
    fun diff(a: List<String>, b: List<String>): List<String> {
        val ka = a.map(::key)
        val kb = b.map(::key)
        var start = 0
        while (start < ka.size && start < kb.size && ka[start] == kb[start]) start++
        var endA = ka.size
        var endB = kb.size
        while (endA > start && endB > start && ka[endA - 1] == kb[endB - 1]) { endA--; endB-- }
        val out = mutableListOf<String>()
        val n = endA - start
        val m = endB - start
        if (n.toLong() * m > 40_000_000L) {
            // Too large for a table-based LCS: report the changed region as a whole.
            for (i in start until endA) out += "- ${a[i]}"
            for (j in start until endB) out += "+ ${b[j]}"
            return out
        }
        val lcs = Array(n + 1) { IntArray(m + 1) }
        for (i in n - 1 downTo 0) for (j in m - 1 downTo 0) {
            lcs[i][j] = if (ka[start + i] == kb[start + j]) lcs[i + 1][j + 1] + 1 else maxOf(lcs[i + 1][j], lcs[i][j + 1])
        }
        var i = 0
        var j = 0
        while (i < n || j < m) {
            when {
                i < n && j < m && ka[start + i] == kb[start + j] -> { i++; j++ }
                i < n && (j == m || lcs[i + 1][j] >= lcs[i][j + 1]) -> out += "- ${a[start + i++]}"
                else -> out += "+ ${b[start + j++]}"
            }
        }
        return out
    }

    private fun key(line: String) = if (line.startsWith("#")) line.substringAfter(' ') else line
}
