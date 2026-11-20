package rivc

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class GraphTest {
    private fun artboard(g: RivGraph) = g.add("Artboard") { this["name"] = "A"; this["width"] = 10; this["height"] = 10 }

    @Test
    fun `a dangling parent fails with a message naming the object and property`() {
        val g = RivGraph()
        val ab = artboard(g)
        val ghost = g.create("Node", owner = ab, label = "ghost") // never added
        g.add("Shape", owner = ab) { this["name"] = "eyeL"; this["parentId"] = ghost }
        val e = assertFailsWith<RivGraphException> { g.resolve() }
        assertEquals(1, e.problems.size, e.message)
        val msg = e.problems.single()
        assertTrue("Shape \"eyeL\" (#1)" in msg, msg)
        assertTrue("parentId refers to Node \"ghost\"" in msg, msg)
        assertTrue("dangling" in msg, msg)
    }

    @Test
    fun `a parent written after its child is refused`() {
        val g = RivGraph()
        val ab = artboard(g)
        val root = g.create("Node", owner = ab).apply { this["name"] = "root"; this["parentId"] = ab }
        g.add("Shape", owner = ab) { this["name"] = "face"; this["parentId"] = root }
        g.add(root)
        val msg = assertFailsWith<RivGraphException> { g.resolve() }.problems.single()
        assertTrue(msg.startsWith("Shape \"face\" (#1): parentId refers to Node \"root\" (#2)"), msg)
        assertTrue("mis-ordered" in msg, msg)
    }

    @Test
    fun `an object whose stream position attaches it to the wrong owner is refused`() {
        val g = RivGraph()
        val ab = artboard(g)
        val node = g.add("Node", owner = ab) { this["name"] = "n"; this["parentId"] = ab }
        val a = g.add("LinearAnimation", owner = ab) { this["name"] = "a" }
        g.add("LinearAnimation", owner = ab) { this["name"] = "b" }
        g.add("KeyedObject", owner = a) { this["objectId"] = node } // would land in "b"
        val msg = assertFailsWith<RivGraphException> { g.resolve() }.problems.single()
        assertTrue("KeyedObject (#4): declares owner LinearAnimation \"a\" (#2)" in msg, msg)
        assertTrue("attaches it to LinearAnimation \"b\" (#3)" in msg, msg)
    }

    @Test
    fun `a view model instance after the artboard is refused because it would shift ids`() {
        val g = RivGraph()
        val vm = g.add("ViewModel") { this["name"] = "Pip" }
        artboard(g)
        g.add("ViewModelInstance") { this["name"] = "Default"; this["viewModelId"] = vm }
        val msg = assertFailsWith<RivGraphException> { g.resolve() }.problems.single()
        assertTrue(msg.startsWith("ViewModelInstance \"Default\" (#2): file-level object written after Artboard"), msg)
    }

    @Test
    fun `raw ids are rejected`() {
        val g = RivGraph()
        val ab = artboard(g)
        val e = assertFailsWith<IllegalArgumentException> { g.add("Node", owner = ab) { this["parentId"] = 0 } }
        assertTrue("takes an object handle, not the raw id 0" in e.message!!, e.message)
    }

    @Test
    fun `ids are assigned from stream order`() {
        val g = RivGraph()
        val ab = artboard(g)
        val root = g.add("Node", owner = ab) { this["name"] = "root"; this["parentId"] = ab }
        val shape = g.add("Shape", owner = ab) { this["name"] = "s"; this["parentId"] = root }
        val anim = g.add("LinearAnimation", owner = ab) { this["name"] = "a" }
        val ko = g.add("KeyedObject", owner = anim) { this["objectId"] = shape }
        g.add("KeyedProperty", owner = ko) { this["propertyKey"] = g.schema.property("Shape", "y") }
        val dump = RivDump.lines(g.resolve())
        assertEquals(
            listOf(
                "RIVE 7.5 fileId=0 toc=[]",
                "#0 Artboard [0] name=\"A\" width=10.0 height=10.0",
                "#1 Node [1] name=\"root\" parentId=0",
                "#2 Shape [2] name=\"s\" parentId=1",
                "#3 LinearAnimation name=\"a\"",
                "#4 KeyedObject objectId=2",
                "#5 KeyedProperty propertyKey=14",
            ),
            dump,
        )
    }

    @Test
    fun `schema lookups follow inheritance`() {
        val s = Schema.bundled
        assertEquals("37b3bc71304d0e65ebd0a23de4b6857068be1f09", s.runtimeCommit)
        assertEquals(37, s.property("SolidColor", "colorValue").key) // from the ColorChannels mixin
        assertEquals(FieldType.Id, s.property("Shape", "parentId").type)
        assertEquals(7, s.property("Artboard", "width").key)
        assertEquals(20, s.property("Ellipse", "width").key)
        assertTrue(s.isA("CubicEaseInterpolator", "KeyFrameInterpolator"))
        assertTrue(s.claimsArtboardSlot("CubicEaseInterpolator"))
        assertTrue(!s.claimsArtboardSlot("Artboard"))
        assertEquals("TransitionPropertyViewModelComparator", s.clsByTypeKey(479)?.name)
    }
}
