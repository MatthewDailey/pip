package rivc

import rivc.project.ProjectPass
import rivc.spike.CompileException
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Multi-artboard projects: `examples/project/project.json` and small inline projects. */
class ProjectPassTest {
    private val projectDir = File(System.getProperty("rivc.projectDir") ?: ".")
    private val example = File(projectDir, "examples/project/project.json")
    private val exampleDir = example.parentFile

    private fun dump(bytes: ByteArray) = RivDump.lines(RivReader().read(bytes)).drop(1)

    private fun compile(json: String, dir: File = exampleDir) = ProjectPass().compile(ProjectSource.parse(json), dir).encode()

    /** The dump line for the object whose line starts with `<type> ... name="<name>"`. */
    private fun List<String>.obj(type: String, name: String) =
        single { Regex("""^#\d+ $type( \[\d+])? name="$name"""").containsMatchIn(it) }

    /** A two-artboard project: Main holding [instance] of Pip (inputs control), with [extra] on Main. */
    private fun twoBoards(instance: String, extra: String = "", pipControl: String = "inputs") = """
        {
          "assets": [{ "name": "Inter-Bold", "font": "fonts/Inter-Bold.ttf" }],
          "artboards": [
            { "artboard": { "name": "Main", "width": 1080, "height": 2424 },
              "clips": [{ "name": "timeline", "frames": 120, "loop": "loop" }],
              "instances": [$instance] $extra },
            { "source": "../spike/pip.json", "control": "$pipControl" }
          ]
        }
    """

    @Test
    fun `the example project compiles with every nested reference resolved by name`() {
        val d = dump(compileFile(example))
        // File-level objects precede the first artboard: the font, then Main's view model.
        val firstArtboard = d.indexOfFirst { " Artboard " in it }
        assertTrue(d.indexOfFirst { "FontAsset" in it } in 0 until firstArtboard)
        assertTrue(d.indexOfFirst { "ViewModel name=\"Stage\"" in it } in 0 until firstArtboard)
        assertEquals(listOf("Main", "Pip", "Heart", "Note"), d.filter { " Artboard [0]" in it }.map { Regex("name=\"(\\w+)\"").find(it)!!.groupValues[1] })

        assertTrue("artboardId=1" in d.obj("NestedArtboard", "pipA"))
        assertTrue("artboardId=1" in d.obj("NestedArtboard", "pipB"))
        assertTrue("artboardId=2" in d.obj("NestedArtboard", "heart"))
        assertTrue("artboardId=3" in d.obj("NestedArtboard", "note"))
        // Pip's machine is its only one; mood and blink are its inputs 0 and 1.
        assertEquals(2, d.count { Regex("NestedNumber .*name=\"mood\".*inputId=0").containsMatchIn(it) })
        assertEquals(2, d.count { Regex("NestedTrigger .*name=\"blink\".*inputId=1").containsMatchIn(it) })
        assertTrue("nestedValue=2.0" in d.single { "NestedNumber" in it && "nestedValue=2.0" in it })
        // The note's remap names its only clip, and it plays no nested machine.
        assertTrue("animationId=0" in d.obj("NestedRemapAnimation", "wobble"))
        assertEquals(3, d.count { "NestedStateMachine" in it })

        // Texts use font asset 0; the bubble's change is two held string keys.
        assertEquals(2, d.count { "TextStylePaint" in it && "fontAssetId=0" in it })
        assertEquals(2, d.count { "KeyFrameString" in it && "interpolationType=0" in it })
        // Three events (callbacks on 395) and two blink fires (callbacks on 401), all in the timeline.
        assertEquals(3, d.count { Regex("^#\\d+ Event ").containsMatchIn(it) })
        assertEquals(3, d.count { "KeyedProperty propertyKey=395" in it })
        assertEquals(1, d.count { "KeyedProperty propertyKey=401" in it })
        assertEquals(5, d.count { "KeyFrameCallback" in it })
        // The bound mask: two binds straight after its node, and a clip on Pip B's group.
        val hole = d.indexOf(d.obj("Node", "hole"))
        assertTrue("propertyKey=13" in d[hole + 1] && "sourcePathIds=[0,0]" in d[hole + 1], d[hole + 1])
        assertTrue("propertyKey=14" in d[hole + 2] && "sourcePathIds=[0,1]" in d[hole + 2], d[hole + 2])
        assertTrue("fillRule=0" in d.obj("ClippingShape", "pipBClip"))
        // Main gets the default machine that plays its timeline.
        assertTrue(d.any { "StateMachine name=\"Main\"" in it })
    }

    @Test
    fun `a single-artboard source still compiles through compileFile, unchanged`() {
        val pip = File(projectDir, "examples/spike/pip.json")
        assertContentEquals(compileGraph(PipSource.load(pip), pip.parentFile).encode(), compileFile(pip))
    }

    @Test
    fun `cue keys land in their clip's animation, after the clip pass's own keys`() {
        val d = dump(
            compile(
                twoBoards(
                    """{ "id": "pip", "artboard": "Pip", "fire": [{ "input": "blink", "clip": "timeline", "frames": [10, 70] }] }""",
                    """, "events": [{ "name": "cue", "clip": "timeline", "frames": [5] }]""",
                ),
            ),
        )
        val anim = d.indexOfFirst { "LinearAnimation name=\"timeline\"" in it }
        val machine = d.indexOfFirst { "StateMachine name=\"Main\"" in it }
        val callbacks = d.withIndex().filter { "KeyFrameCallback" in it.value }.map { it.index }
        assertEquals(3, callbacks.size)
        assertTrue(callbacks.all { it in anim until machine }, "callbacks $callbacks outside $anim..$machine")
    }

    @Test
    fun `an inverted mask cuts an even-odd hole`() {
        val d = dump(
            compile(
                twoBoards(
                    """{ "id": "pip", "artboard": "Pip", "mask": "icon" }""",
                    """, "masks": [{ "id": "icon", "x": 500, "y": 600, "width": 200, "height": 200, "shape": "rectangle", "cornerRadius": 40, "invert": true }]""",
                ),
            ),
        )
        assertTrue("fillRule=1" in d.obj("ClippingShape", "pipClip"))
        val clip = d.indexOf(d.obj("Shape", "iconClip"))
        assertTrue("Rectangle" in d[clip + 1] && "width=8000.0" in d[clip + 1], d[clip + 1])
        assertTrue("Rectangle" in d[clip + 2] && "width=200.0" in d[clip + 2] && "cornerRadiusTL=40.0" in d[clip + 2], d[clip + 2])
    }

    @Test
    fun `mistakes name the artboard and entry at fault`() {
        fun err(json: String) = assertFailsWith<CompileException> { compile(json) }.message!!
        assertTrue("no artboard 'Ghost'" in err(twoBoards("""{ "id": "x", "artboard": "Ghost" }""")))
        val vm = err(twoBoards("""{ "id": "x", "artboard": "Pip", "inputs": { "mood": 1 } }""", pipControl = "viewModel"))
        assertTrue("artboards.Main.instances.x" in vm && "control: inputs" in vm, vm)
        val font = err(
            twoBoards("""{ "id": "x", "artboard": "Pip" }""", """, "texts": [{ "id": "t", "text": "hi", "font": "Comic", "size": 20 }]"""),
        )
        assertTrue("artboards.Main.texts.t.font: no font asset 'Comic'" in font, font)
        val frame = err(twoBoards("""{ "id": "x", "artboard": "Pip" }""", """, "events": [{ "name": "e", "clip": "timeline", "frames": [500] }]"""))
        assertTrue("frame 500 is outside clip 'timeline'" in frame, frame)
        val loop = err(
            """{ "artboards": [
                 { "artboard": { "name": "A", "width": 10, "height": 10 }, "instances": [{ "id": "b", "artboard": "B" }] },
                 { "artboard": { "name": "B", "width": 10, "height": 10 }, "instances": [{ "id": "a", "artboard": "A" }] } ] }""",
        )
        assertTrue("nesting loops: A > B > A" in loop, loop)
        val bind = err(twoBoards("""{ "id": "x", "artboard": "Pip", "bind": { "x": "nope" } }"""))
        assertTrue("artboards.Main.instances.x.bind.x" in bind && "no view model" in bind, bind)
    }
}
