package rivc

import java.io.File
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

/** rivc reproduces the spike's files from `examples/spike/pip.json`, and reads them back. */
class SpikeParityTest {
    private val project = File(System.getProperty("rivc.projectDir") ?: ".")
    private val source = File(project, "examples/spike/pip.json")

    private fun golden(name: String) = javaClass.getResource("/spike/$name")!!.readBytes()

    private fun compile(control: Control): ByteArray {
        val src = PipSource.load(source).let { it.copy(stateMachine = it.stateMachine!!.copy(control = control)) }
        return compileGraph(src, source.parentFile).encode()
    }

    private fun dump(bytes: ByteArray) = RivDump.lines(RivReader().read(bytes))

    /**
     * `*.spike-objects.txt` is the spike compiler's own in-memory object list (type key,
     * then property key=value in written order), printed by the spike's code before it
     * encoded the file. Decoding the spike's bytes must give back exactly that list.
     */
    private fun assertReadsAsSpikeWrote(riv: String, listing: String) {
        val expected = javaClass.getResource("/spike/$listing")!!.readText().lines().filter { it.isNotEmpty() }
        val actual = RivReader().read(golden(riv)).objects.map { o ->
            (listOf(o.typeKey.toString()) + o.props.map { p ->
                "${p.key}=" + when (val v = p.value) {
                    is RawValue.Varuint -> v.v.toString()
                    is RawValue.Varint -> v.v.toString()
                    is RawValue.F32 -> v.v.toString()
                    is RawValue.Str -> "\"${v.v}\""
                    is RawValue.Bool -> v.v.toString()
                    is RawValue.Color -> "#%08X".format(v.argb)
                    is RawValue.Bytes -> "0x" + v.v.joinToString("") { "%02x".format(it) }
                }
            }).joinToString(" ")
        }
        assertEquals(expected.size, actual.size, "object count")
        expected.zip(actual).forEachIndexed { i, (e, a) -> assertEquals(e, a, "object #$i") }
    }

    @Test
    fun `reading the spike's files gives back the spike's object lists`() {
        assertReadsAsSpikeWrote("pip.riv", "pip.spike-objects.txt")
        assertReadsAsSpikeWrote("pip-inputs.riv", "pip-inputs.spike-objects.txt")
    }

    @Test
    fun `dump of the spike's pip riv matches what the spike wrote, object for object`() {
        val dump = dump(golden("pip.riv"))
        assertEquals("RIVE 7.5 fileId=0 toc=[]", dump.first())
        assertEquals(152, dump.size - 1, "object count")
        // Spot checks against the spike's compilePip, in order.
        assertEquals("#0 Backboard", dump[1])
        assertEquals("#1 ViewModel name=\"Pip\"", dump[2])
        assertEquals("#4 ViewModelInstance name=\"Default\" viewModelId=0", dump[5])
        assertEquals("#7 Artboard [0] name=\"Pip\" width=540.0 height=660.0 clip=false defaultStateMachineId=0 viewModelId=0", dump[8])
        assertEquals("#8 Node [1] name=\"root\" parentId=0", dump[9])
        assertEquals("#9 Shape [2] name=\"eyeR\" parentId=1 x=389.0 y=401.5", dump[10])
        assertEquals("#12 SolidColor [5] parentId=4 colorValue=#FF8B3F37", dump[13])
        // The full listing is checked in, so any decoder change shows up as a diff.
        assertEquals(File(project, "src/test/resources/spike/pip.dump.txt").readLines(), dump)
    }

    /**
     * The spike wrote both state machine inputs and view-model properties for `mood` and
     * `blink`, whichever control drove the conditions. rivc deliberately never emits both
     * for one name (the spike saw them fight), so each file differs from the spike's by
     * exactly the unused half; everything else is byte for byte the same.
     */
    private fun spikeWithout(riv: String, drop: (RivRecord) -> Boolean, fix: (RivRecord) -> RivRecord = { it }): ByteArray {
        val f = RivReader().read(golden(riv))
        return RivWriter.write(f.copy(objects = f.objects.filterNot(drop).map { mirrored(fix(it)) }))
    }

    /**
     * The spike wrote every curved vertex as a CubicDetachedVertex. The art pass writes a
     * vertex whose two handles are exactly opposite and equally long (a smooth point in the
     * SVG) as a CubicMirroredVertex instead: the same curve, and it stays smooth if someone
     * edits it. Three of Pip's hat vertices are like that. This turns the spike's record of
     * such a vertex into the mirrored one rivc writes (rotation and distance of the out handle).
     */
    private fun mirrored(r: RivRecord): RivRecord {
        if (r.typeKey != typeKey("CubicDetachedVertex")) return r
        fun f(name: String) = (r.props.first { it.key == Schema.bundled.property("CubicDetachedVertex", name).key }.value as RawValue.F32).v
        val turn = Math.IEEEremainder((f("inRotation") - f("outRotation")).toDouble(), 2 * Math.PI)
        if (f("inDistance") != f("outDistance") || f("outDistance") == 0f || kotlin.math.abs(kotlin.math.abs(turn) - Math.PI) > 1e-5) return r
        val keep = listOf("parentId", "x", "y").map { Schema.bundled.property("CubicDetachedVertex", it).key }
        return RivRecord(
            typeKey("CubicMirroredVertex"),
            r.props.filter { it.key in keep } + listOf(
                RivProp(Schema.bundled.property("CubicMirroredVertex", "rotation").key, RawValue.F32(f("outRotation"))),
                RivProp(Schema.bundled.property("CubicMirroredVertex", "distance").key, RawValue.F32(f("outDistance"))),
            ),
        )
    }

    private fun typeKey(name: String) = Schema.bundled.cls(name).typeKey!!

    @Test
    fun `viewModel control reproduces the spike's pip riv minus the unused inputs`() {
        val ours = compile(Control.VIEW_MODEL)
        assertEquals(
            listOf("- #97 StateMachineNumber name=\"mood\" value=0.0", "- #98 StateMachineTrigger name=\"blink\""),
            RivDiff.diff(dump(spikeWithout("pip.riv", { false })), dump(ours)),
        )
        assertEquals(3, dump(ours).count { "CubicMirroredVertex" in it }, "smooth hat vertices")
        val inputs = setOf(typeKey("StateMachineNumber"), typeKey("StateMachineTrigger"))
        assertContentEquals(spikeWithout("pip.riv", { it.typeKey in inputs }), ours)
    }

    @Test
    fun `inputs control reproduces the spike's pip-inputs riv minus the unused view model`() {
        val ours = compile(Control.INPUTS)
        val vmTypes = listOf(
            "ViewModel", "ViewModelPropertyNumber", "ViewModelPropertyTrigger", "ViewModelInstance",
            "ViewModelInstanceNumber", "ViewModelInstanceTrigger",
        ).map(::typeKey).toSet()
        val viewModelId = Schema.bundled.property("Artboard", "viewModelId").key
        val expected = spikeWithout(
            "pip-inputs.riv",
            { it.typeKey in vmTypes },
            { r -> if (r.typeKey == typeKey("Artboard")) r.copy(props = r.props.filter { it.key != viewModelId }) else r },
        )
        assertEquals(emptyList(), RivDiff.diff(dump(expected), dump(ours)))
        assertContentEquals(expected, ours)
    }

    @Test
    fun `write then read round-trips the spike file exactly`() {
        val resolved = compileGraph(PipSource.load(source), source.parentFile).resolve()
        val bytes = RivWriter.write(resolved)
        val read = RivReader().read(bytes)
        assertEquals(resolved, read)
        assertContentEquals(bytes, RivWriter.write(read))
        // And the golden file itself survives read -> write unchanged.
        assertContentEquals(golden("pip.riv"), RivWriter.write(RivReader().read(golden("pip.riv"))))
    }
}
