package rivc

import rivc.anim.ClipPass
import rivc.anim.MapTargets
import rivc.spike.CompileException
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The animation pass, checked as golden `rivc dump` listings. A listing that changes on
 * purpose is refreshed with `-Drivc.updateGoldens=true` (see [assertGolden]); review the
 * diff before committing it. The same example plays in the web runtime in
 * `packages/rivc-preview/anim-proof.mjs`.
 */
class AnimPassTest {
    private val project = File(System.getProperty("rivc.projectDir") ?: ".")
    private val animDir = File(project, "examples/anim")

    private fun dump(g: RivGraph) = RivDump.lines(RivReader().read(g.encode()))

    private fun assertGolden(name: String, actual: List<String>) {
        val file = File(project, "src/test/resources/anim/$name")
        if (System.getProperty("rivc.updateGoldens") == "true" || !file.exists()) {
            file.parentFile.mkdirs()
            file.writeText(actual.joinToString("\n", postfix = "\n"))
        }
        assertEquals(file.readLines(), actual, "golden $name (rerun with -Drivc.updateGoldens=true if intended)")
    }

    private fun compile(json: String, dir: File = animDir) = compileGraph(PipSource.parse(json), dir)

    /** examples/anim/anim.json with [edit] applied to its text. */
    private fun anim(edit: (String) -> String = { it }) = compile(edit(File(animDir, "anim.json").readText()))

    @Test
    fun `the anim example compiles to its golden listing`() {
        val lines = dump(anim())
        assertGolden("anim.dump.txt", lines)
        // Spot checks of the runtime rules the listing encodes.
        assertContains(lines, "#1 DataEnumCustom name=\"mood\"")
        assertContains(lines, "#13 ViewModelInstanceEnum viewModelPropertyId=0 propertyValue=0")
        assertContains(lines, "#57 KeyFrameDouble frame=0 interpolationType=2 value=0.0 interpolatorId=31")
        assertTrue(lines.any { it.endsWith("KeyedProperty propertyKey=395") }, "event trigger keyed")
        assertTrue(lines.any { it.endsWith("KeyFrameCallback frame=10") })
        assertTrue(lines.any { it.endsWith("StateMachineFireEvent occursValue=0 eventId=30") })
        assertTrue(lines.any { it.endsWith("TransitionValueEnumComparator value=1") })
        // A view-model 1D blend reads the bindable written just before it.
        val blend = lines.indexOfFirst { it.endsWith("BlendState1DViewModel flags=2") }
        assertTrue(lines[blend - 2].endsWith("BindablePropertyNumber"))
        assertTrue(lines[blend - 1].endsWith("DataBindContext propertyKey=636 flags=0 sourcePathIds=[0,2]"))
        assertEquals(lines.size, lines.distinct().size)
    }

    @Test
    fun `cubic interpolators are shared by every key and transition with the same curve`() {
        val lines = dump(anim())
        val interpolators = lines.filter { "CubicEaseInterpolator" in it }
        assertEquals(3, interpolators.size, "easeInOut, easeOut and the doze cubic, once each")
        // The Alert layer's easeInOut transition reuses the bob clip's interpolator (slot 31).
        assertTrue(lines.any { it.endsWith("StateTransition stateToId=3 flags=12 duration=100 exitTime=100 interpolationType=2 interpolatorId=31") })
    }

    private val inputsJson = """
        {
          "artboard": { "name": "Anim", "width": 200, "height": 200 },
          "art": { "svg": "anim.svg", "nodes": [{ "id": "eyes" }], "shapes": [{ "id": "eyeL", "parent": "eyes" }, { "id": "flash" }] },
          "clips": [
            { "name": "quiet", "frames": 1, "tracks": [{ "target": "flash", "property": "opacity", "keys": [{ "frame": 0, "value": 0 }] }] },
            { "name": "alert", "frames": 30, "tracks": [{ "target": "flash", "property": "opacity", "keys": [{ "frame": 0, "value": 1 }] }] },
            { "name": "left", "frames": 1, "tracks": [{ "target": "eyes", "property": "x", "keys": [{ "frame": 0, "value": -30 }] }] },
            { "name": "right", "frames": 1, "tracks": [{ "target": "eyes", "property": "x", "keys": [{ "frame": 0, "value": 30 }] }] }
          ],
          "viewModel": { "name": "Anim", "properties": [
            { "name": "alert", "type": "trigger" }, { "name": "lookX", "type": "number" }, { "name": "label", "type": "string" }
          ] },
          "stateMachine": {
            "name": "Anim", "control": "inputs",
            "inputs": [
              { "name": "alert", "type": "trigger" }, { "name": "lookX", "type": "number" },
              { "name": "awake", "type": "boolean", "defaultBool": true }, { "name": "level", "type": "number", "default": 2 }
            ],
            "layers": [
              { "name": "Alert", "states": [{ "name": "quiet", "clip": "quiet" }, { "name": "alert", "clip": "alert" }],
                "transitions": [
                  { "from": "entry", "to": "quiet" },
                  { "from": "quiet", "to": "alert", "conditions": [{ "on": "alert" }, { "on": "awake", "bool": false, "op": "!=" }, { "on": "level", "op": ">=", "value": 1 }] },
                  { "from": "alert", "to": "quiet", "exitTimeMs": 400, "conditions": [{ "on": "awake", "bool": false }] }
                ] },
              { "name": "Look", "states": [
                  { "name": "look", "blend": { "by": "lookX", "clips": [{ "clip": "left", "value": -1 }, { "clip": "right", "value": 1 }] } },
                  { "name": "mix", "direct": [{ "clip": "left", "by": "level" }, { "clip": "right", "mix": 25 }] }
                ],
                "transitions": [{ "from": "entry", "to": "look" }] }
            ]
          }
        }
    """

    @Test
    fun `the inputs control compiles legacy inputs and never a view-model twin`() {
        val lines = dump(compile(inputsJson))
        assertGolden("inputs.dump.txt", lines)
        // alert and lookX are inputs here, so the view model keeps only label.
        assertEquals(listOf("#2 ViewModelPropertyString name=\"label\""), lines.filter { "ViewModelProperty" in it })
        assertTrue(lines.none { "TransitionViewModelCondition" in it || "Bindable" in it })
        assertTrue(lines.any { it.endsWith("TransitionTriggerCondition inputId=0") })
        // bool false with != is "input is true".
        assertTrue(lines.any { it.endsWith("TransitionBoolCondition inputId=2 opValue=0") })
        assertTrue(lines.any { it.endsWith("TransitionBoolCondition inputId=2 opValue=1") })
        assertTrue(lines.any { it.endsWith("TransitionNumberCondition inputId=3 opValue=3 value=1.0") })
        assertTrue(lines.any { it.endsWith("BlendState1DInput flags=2 inputId=1") })
        assertTrue(lines.any { it.endsWith("BlendAnimationDirect animationId=2 blendSource=0 inputId=3") })
        assertTrue(lines.any { it.endsWith("BlendAnimationDirect animationId=3 blendSource=1 mixValue=25.0") })
    }

    @Test
    fun `the viewModel control drops inputs that a view-model property already drives`() {
        val lines = dump(compile(inputsJson.replace("\"control\": \"inputs\"", "\"control\": \"viewModel\"")))
        assertEquals(
            listOf("StateMachineBool name=\"awake\" value=true", "StateMachineNumber name=\"level\" value=2.0"),
            lines.filter { "StateMachineBool" in it || "StateMachineNumber" in it || "StateMachineTrigger" in it }
                .map { it.substringAfter(' ') },
        )
        assertTrue(lines.any { it.endsWith("BlendState1DViewModel flags=2") })
        assertTrue(lines.any { it.endsWith("TransitionValueTriggerComparator") })
    }

    @Test
    fun `clips key gradient stops, ellipse size and paint visibility by target name`() {
        val g = RivGraph()
        g.add("Backboard")
        val ab = g.add("Artboard") { this["name"] = "G"; this["width"] = 100; this["height"] = 100 }
        val shape = g.add("Shape", owner = ab) { this["name"] = "orb"; this["parentId"] = ab }
        val ellipse = g.add("Ellipse", owner = ab) { this["parentId"] = shape; this["width"] = 40; this["height"] = 40 }
        val fill = g.add("Fill", owner = ab) { this["parentId"] = shape }
        val grad = g.add("RadialGradient", owner = ab) { this["parentId"] = fill; this["endX"] = 20 }
        val stop0 = g.add("GradientStop", owner = ab) { this["parentId"] = grad; this["colorValue"] = Argb(0xFFFFFFFF); this["position"] = 0 }
        val stop1 = g.add("GradientStop", owner = ab) { this["parentId"] = grad; this["colorValue"] = Argb(0xFF0000FF); this["position"] = 1 }
        val targets = MapTargets(
            mapOf(
                "orb" to shape, "orb/path" to ellipse, "orb/fill/paint" to fill, "orb/fill" to grad,
                "orb/fill/stop0" to stop0, "orb/fill/stop1" to stop1,
            ),
        )
        val src = PipSource.parse(
            """
            { "artboard": { "name": "G", "width": 100, "height": 100 },
              "clips": [{ "name": "glow", "frames": 30, "loop": "pingPong", "tracks": [
                { "target": "orb/fill/stop1", "property": "colorValue", "keys": [{ "frame": 0, "color": "#FF0000FF" }, { "frame": 30, "color": "#80FF8000", "ease": "easeOut" }] },
                { "target": "orb/fill/stop0", "property": "position", "keys": [{ "frame": 0, "value": 0 }, { "frame": 30, "value": 0.4, "ease": "hold" }] },
                { "target": "orb/fill", "property": "opacity", "keys": [{ "frame": 0, "value": 1 }, { "frame": 15, "value": 0.5 }] },
                { "target": "orb/path", "property": "width", "keys": [{ "frame": 0, "value": 40 }, { "frame": 30, "value": 48, "ease": { "cubic": [0.3, 0, 0.7, 1] } }] },
                { "target": "orb/fill/paint", "property": "isVisible", "keys": [{ "frame": 0, "bool": true }, { "frame": 20, "bool": false }] },
                { "target": "orb", "property": "rotation", "keys": [{ "frame": 0, "value": 0 }, { "frame": 30, "value": 3.14159 }] }
              ] }] }
            """,
        )
        ClipPass().run(g, ab, src, targets)
        assertGolden("gradient-clip.dump.txt", dump(g))
    }

    @Test
    fun `a morph keys every vertex of the path toward the named SVG shape`() {
        val lines = dump(anim())
        val smile = lines.indexOfFirst { it.endsWith("LinearAnimation name=\"smile\" fps=60 duration=1 loopValue=0") }
        // Two cubic vertices, six properties each (x, y, in/out rotation and distance).
        val clip = lines.drop(smile + 1).takeWhile { "LinearAnimation" !in it }
        assertEquals(2, clip.count { "KeyedObject" in it })
        assertEquals(12, clip.count { "KeyFrameDouble" in it })
        assertTrue(clip.any { it.endsWith("KeyFrameDouble frame=0 interpolationType=1 value=78.0") })
    }

    @Test
    fun `bad sources fail with a message naming the item`() {
        fun failure(edit: (String) -> String) = assertFailsWith<CompileException> { anim(edit) }.message!!
        assertContains(failure { it.replace("\"target\": \"bobber\"", "\"target\": \"nobody\"") }, "clips.bob.nobody.y: no art target 'nobody'")
        assertContains(
            failure { it.replace("{ \"frame\": 60, \"value\": 100,", "{ \"frame\": 0, \"value\": 100,") },
            "clips.bob.bobber.y: key frames must increase (0 then 0)",
        )
        assertContains(failure { it.replace("\"is\": \"upset\"", "\"is\": \"furious\"") }, "'furious' is not one of [calm, upset]")
        assertContains(failure { it.replace("\"morph\": \"mouth_smile\"", "\"morph\": \"face\"") }, "morph: 'face' is not an SVG path")
        assertContains(failure { it.replace("\"frame\": 10 }", "\"frame\": 99 }") }, "frame 99 is outside clip 'alert' (0..30)")
        assertContains(failure { it.replace("\"workStart\": 10", "\"workStart\": 40") }, "clips.doze: work area 40..40")
        assertContains(
            failure { it.replace("{ \"name\": \"alert\", \"type\": \"trigger\" }", "{ \"name\": \"alert\", \"type\": \"number\" }") },
            "a number condition needs a value",
        )
        assertContains(failure { it.replace("[{ \"on\": \"sleepy\", \"bool\": true }]", "[{ \"on\": \"sleepy\", \"bool\": true, \"value\": 1 }]") }, "this condition doesn't take value")
    }
}
