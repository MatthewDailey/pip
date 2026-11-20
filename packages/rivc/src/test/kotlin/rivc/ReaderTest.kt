package rivc

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The reader handles files the Rive editor exported, including a property table of contents. */
class ReaderTest {
    private fun fixture(name: String) = javaClass.getResource("/editor/$name")!!.readBytes()

    @Test
    fun `editor files round-trip read then write byte for byte`() {
        for (name in listOf("what_a_state.riv", "rating.riv")) {
            val bytes = fixture(name)
            assertContentEquals(bytes, RivWriter.write(RivReader().read(bytes)), name)
        }
    }

    @Test
    fun `the table of contents packs four field indices per uint32`() {
        val file = RivReader().read(fixture("rating.riv"))
        assertEquals(6, file.toc.size)
        assertEquals(listOf(392, 227, 224, 236, 225, 229), file.toc.map { it.key })
        // Every object decodes to a known type: a mis-read ToC desynchronises the stream.
        val unknown = file.objects.count { Schema.bundled.clsByTypeKey(it.typeKey) == null }
        assertEquals(0, unknown)
        assertTrue(file.objects.size > 1000)
    }

    @Test
    fun `diff matches objects by content, not position`() {
        // An inserted object is one + line; the objects after it moved but didn't change.
        assertEquals(listOf("+ #1 X"), RivDiff.diff(listOf("#0 A", "#1 B"), listOf("#0 A", "#1 X", "#2 B")))
        assertEquals(listOf("- #1 B", "+ #1 C"), RivDiff.diff(listOf("#0 A", "#1 B"), listOf("#0 A", "#1 C")))
        assertEquals(emptyList(), RivDiff.diff(listOf("#0 A"), listOf("#0 A")))
    }
}
