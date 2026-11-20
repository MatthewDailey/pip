package rivc

import java.io.ByteArrayOutputStream

/**
 * A property value exactly as it sits in the file, after ids are resolved. [RivWriter]
 * encodes these and [RivReader] decodes them, so `read(write(f)) == f` for any file.
 */
sealed interface RawValue {
    /** Uint, Id or (in a ToC-typed unknown property) any uint-class field. */
    data class Varuint(val v: Long) : RawValue

    /** Zigzag-encoded signed int. */
    data class Varint(val v: Int) : RawValue

    data class F32(val v: Float) : RawValue

    data class Str(val v: String) : RawValue

    class Bytes(val v: ByteArray) : RawValue {
        override fun equals(other: Any?) = other is Bytes && v.contentEquals(other.v)
        override fun hashCode() = v.contentHashCode()
        override fun toString() = "Bytes(${v.joinToString("") { "%02x".format(it) }})"
    }

    data class Bool(val v: Boolean) : RawValue

    /** ARGB. */
    data class Color(val argb: Int) : RawValue
}

data class RivProp(val key: Int, val value: RawValue)

/** One object in the stream: its type key, then its properties in written order. */
data class RivRecord(val typeKey: Int, val props: List<RivProp>)

/** A property the header declares for readers that don't know it. [fieldIndex] is 0..3 (uint, string, double, colour). */
data class TocEntry(val key: Int, val fieldIndex: Int)

/** A whole `.riv`: header plus the flat object stream. */
data class RivFile(
    val major: Int,
    val minor: Int,
    val fileId: Long,
    val toc: List<TocEntry>,
    val objects: List<RivRecord>,
)

/** Little-endian primitives and LEB128 varuints, as `rive/core/reader.h` decodes them. */
class ByteSink {
    private val out = ByteArrayOutputStream()

    fun bytes(): ByteArray = out.toByteArray()

    fun byte(b: Int) = out.write(b and 0xff)

    fun raw(b: ByteArray) = out.write(b)

    fun varuint(value: Long) {
        var v = value
        do {
            var b = (v and 0x7f).toInt()
            v = v ushr 7
            if (v != 0L) b = b or 0x80
            out.write(b)
        } while (v != 0L)
    }

    fun varint(value: Int) = varuint(((value shl 1) xor (value shr 31)).toLong() and 0xffffffffL)

    fun uint32(i: Int) {
        out.write(i and 0xff); out.write((i ushr 8) and 0xff)
        out.write((i ushr 16) and 0xff); out.write((i ushr 24) and 0xff)
    }

    fun float32(f: Float) = uint32(java.lang.Float.floatToRawIntBits(f))

    fun string(s: String) = lengthPrefixed(s.toByteArray(Charsets.UTF_8))

    fun lengthPrefixed(b: ByteArray) {
        varuint(b.size.toLong()); out.write(b)
    }
}

/** Encodes a list of indices the way DataBindContext.sourcePathIds stores them: varuints packed into a bytes field. */
fun packVaruints(values: List<Long>): ByteArray = ByteSink().apply { values.forEach { varuint(it) } }.bytes()

/**
 * Writes a [RivFile]: `"RIVE"`, varuint major, minor and file id, the property table of
 * contents (varuint keys ending in 0, then 2-bit field indices packed into uint32s), then
 * each object as its type key, (property key, value) pairs and a 0 terminator.
 */
object RivWriter {
    fun write(file: RivFile): ByteArray {
        val w = ByteSink()
        "RIVE".forEach { w.byte(it.code) }
        w.varuint(file.major.toLong()); w.varuint(file.minor.toLong()); w.varuint(file.fileId)
        file.toc.forEach { w.varuint(it.key.toLong()) }
        w.varuint(0)
        // Four 2-bit entries per uint32: the runtime reads a new uint32 every 8 bits (RuntimeHeader::read).
        file.toc.chunked(4).forEach { chunk ->
            var packed = 0
            chunk.forEachIndexed { i, e -> packed = packed or ((e.fieldIndex and 3) shl (i * 2)) }
            w.uint32(packed)
        }
        for (o in file.objects) {
            w.varuint(o.typeKey.toLong())
            for (p in o.props) {
                require(p.key != 0) { "property key 0 is the object terminator" }
                w.varuint(p.key.toLong())
                when (val v = p.value) {
                    is RawValue.Varuint -> w.varuint(v.v)
                    is RawValue.Varint -> w.varint(v.v)
                    is RawValue.F32 -> w.float32(v.v)
                    is RawValue.Str -> w.string(v.v)
                    is RawValue.Bytes -> w.lengthPrefixed(v.v)
                    is RawValue.Bool -> w.byte(if (v.v) 1 else 0)
                    is RawValue.Color -> w.uint32(v.argb)
                }
            }
            w.varuint(0)
        }
        return w.bytes()
    }
}
