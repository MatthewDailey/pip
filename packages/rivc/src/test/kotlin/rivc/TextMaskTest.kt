package rivc

import rivc.project.ProjectPass
import rivc.spike.CompileException
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Text strokes, gloss and fallback fonts, texts stacked among instances, and multi-shape, size-bound masks. */
class TextMaskTest {
    private val projectDir = File(System.getProperty("rivc.projectDir") ?: ".")
    private val exampleDir = File(projectDir, "examples/project")

    private fun dump(json: String) = RivDump.lines(RivReader().read(ProjectPass().compile(ProjectSource.parse(json), exampleDir).encode())).drop(1)

    private fun err(json: String) = assertFailsWith<CompileException> { ProjectPass().compile(ProjectSource.parse(json), exampleDir) }.message!!

    private fun List<String>.obj(type: String, name: String) =
        single { Regex("""^#\d+ $type( \[\d+])? name="$name"""").containsMatchIn(it) }

    /** Main (with a view model and [main]'s extra fields) holding Pip; Inter falls back to the notes subset. */
    private fun project(main: String, vm: String = "", clips: String = """[{ "name": "timeline", "frames": 60 }]""") = """
        {
          "assets": [
            { "name": "Inter-Bold", "font": "fonts/Inter-Bold.ttf", "fallback": "Notes" },
            { "name": "Notes", "font": "fonts/NotoSansSymbols-Notes.ttf" }
          ],
          "artboards": [
            { "artboard": { "name": "Main", "width": 1080, "height": 2424 },
              "viewModel": { "name": "Stage", "properties": [
                { "name": "iconX", "type": "number" }, { "name": "iconD", "type": "number" },
                { "name": "labelW", "type": "number" }, { "name": "labelH", "type": "number" } $vm ] },
              "clips": $clips,
              $main },
            { "source": "../spike/pip.json", "control": "inputs" }
          ]
        }
    """

    @Test
    fun `a stroked, glossed text draws a stroke under a hard-stop gradient fill`() {
        val d = dump(project("""
            "texts": [{ "id": "shout", "text": "HODOR!", "font": "Inter-Bold", "size": 200, "color": "#FFFF8A3D",
                        "stroke": { "color": "#FF3A2A26", "width": 30 }, "gloss": { "color": "#FFFFC8A8", "y": 110 } }]"""))
        val style = d.indexOfFirst { "TextStylePaint" in it }
        assertTrue("Stroke" in d[style + 1] && "thickness=30.0" in d[style + 1] && "join=1" in d[style + 1], d[style + 1])
        assertTrue("SolidColor" in d[style + 2], d[style + 2])
        assertTrue("Fill" in d[style + 3], d[style + 3])
        assertTrue("LinearGradient" in d[style + 4] && "endY=220.0" in d[style + 4], d[style + 4])
        val stops = d.drop(style + 5).take(4)
        assertTrue(stops.all { "GradientStop" in it }, stops.toString())
        assertEquals(listOf("0.0", "0.5", "0.5", "1.0"), stops.map { Regex("position=([0-9.]+)").find(it)?.groupValues?.get(1) ?: "0.0" })
    }

    @Test
    fun `characters the font lacks get a run in its fallback`() {
        val d = dump(project(""""texts": [{ "id": "jam", "text": "♪ Pip's Jam ♪", "font": "Inter-Bold", "size": 80 }]"""))
        val styles = d.filter { "TextStylePaint" in it }
        assertEquals(listOf("fontAssetId=0", "fontAssetId=1"), styles.map { Regex("fontAssetId=\\d").find(it)!!.value })
        val runs = d.filter { "TextValueRun" in it }.map { Regex("text=\"([^\"]*)\"").find(it)!!.groupValues[1] }
        assertEquals(listOf("♪", " Pip's Jam ", "♪"), runs)

        val changed = err(project("""
            "texts": [{ "id": "jam", "text": "Jam", "font": "Inter-Bold", "size": 80, "changes": [{ "clip": "timeline", "frame": 10, "text": "♪" }] }]"""))
        assertTrue("texts.jam.changes" in changed && "can't mix fonts" in changed, changed)
    }

    @Test
    fun `a stack puts a text under an instance`() {
        val main = """
            "instances": [{ "id": "pip", "artboard": "Pip" }],
            "texts": [{ "id": "under", "text": "under", "font": "Inter-Bold", "size": 40 }, { "id": "over", "text": "over", "font": "Inter-Bold", "size": 40 }]"""
        // Rive draws an artboard's drawables last to first: what is written earlier is on top.
        val d = dump(project("""$main, "stack": ["under", "pip", "over"]"""))
        val at = { type: String, name: String -> d.indexOf(d.obj(type, name)) }
        assertTrue(at("Text", "over") < at("NestedArtboard", "pip") && at("NestedArtboard", "pip") < at("Text", "under"))

        val plain = dump(project(main))
        assertTrue(plain.indexOf(plain.obj("Text", "under")) < plain.indexOf(plain.obj("NestedArtboard", "pip")), "default: texts over instances")

        assertTrue("missing over" in err(project("""$main, "stack": ["under", "pip"]""")))
        assertTrue("no text or instance 'ghost'" in err(project("""$main, "stack": ["under", "pip", "over", "ghost"]""")))
    }

    @Test
    fun `an inverted mask with a part cuts one hole per shape, sized from the view model`() {
        val d = dump(project("""
            "instances": [{ "id": "pip", "artboard": "Pip", "mask": "icon" }],
            "masks": [{ "id": "icon", "x": 500, "y": 600, "width": 160, "height": 160, "invert": true,
                        "bind": { "x": "iconX", "width": "iconD", "height": "iconD" },
                        "parts": [{ "shape": "rectangle", "width": 220, "height": 200, "originY": 0,
                                    "bind": { "width": "labelW", "height": "labelH" } }] }]"""))
        // Two even-odd clips on Pip's group intersect: hidden inside the circle or the label.
        assertTrue("fillRule=1" in d.obj("ClippingShape", "pipClip"))
        assertTrue("fillRule=1" in d.obj("ClippingShape", "pipClip1"))
        val node = d.indexOf(d.obj("Node", "icon"))
        assertTrue("DataBindContext" in d[node + 1] && "DataBindContext" !in d[node + 2], "only x binds on the node")
        val ellipse = d.indexOfFirst { "Ellipse" in it }
        assertTrue("width=160.0" in d[ellipse], d[ellipse])
        assertTrue(d.subList(ellipse + 1, ellipse + 3).all { "DataBindContext" in it }, "width and height bind on the ellipse")
        val clip1 = d.indexOf(d.obj("Shape", "iconClip1"))
        assertTrue("width=8000.0" in d[clip1 + 1], d[clip1 + 1])
        assertTrue("Rectangle" in d[clip1 + 2] && "width=220.0" in d[clip1 + 2] && "originY=0.0" in d[clip1 + 2], d[clip1 + 2])
        assertTrue(d.subList(clip1 + 3, clip1 + 5).all { "DataBindContext" in it })

        val bad = err(project("""
            "masks": [{ "id": "m", "width": 10, "height": 10, "parts": [{ "width": 5, "height": 5, "bind": { "rotation": "iconX" } }] }]"""))
        assertTrue("masks.m.parts[0].bind.rotation" in bad, bad)
    }

    @Test
    fun `a plain mask with a part is one union shape, and its node keys rotation and scale`() {
        val d = dump(project("""
            "instances": [{ "id": "pip", "artboard": "Pip", "mask": "panel" }],
            "masks": [{ "id": "panel", "x": 540, "y": 1150, "shape": "rectangle", "width": 940, "height": 760,
                        "parts": [{ "shape": "ellipse", "y": -380, "width": 200, "height": 100 }] }]""",
            clips = """[{ "name": "timeline", "frames": 60, "tracks": [
              { "target": "panel", "property": "rotation", "keys": [{ "frame": 0, "value": 0 }, { "frame": 60, "value": 0.5 }] },
              { "target": "panel", "property": "scaleX", "keys": [{ "frame": 0, "value": 0.86 }, { "frame": 60, "value": 1 }] } ] }]""",
        ))
        assertTrue("fillRule=0" in d.obj("ClippingShape", "pipClip"))
        assertEquals(1, d.count { "ClippingShape" in it })
        val clip = d.indexOf(d.obj("Shape", "panelClip"))
        assertTrue("Rectangle" in d[clip + 1] && "Ellipse" in d[clip + 2] && "y=-380.0" in d[clip + 2], d.subList(clip, clip + 3).toString())
        assertTrue(d.any { "KeyFrameDouble" in it && "value=0.86" in it }, "the mask's scale is keyed")
    }

    @Test
    fun `a node chain carries an instance, with binds on the root, keys on a child and the mask on the instance's group`() {
        val d = dump(project("""
            "nodes": [
              { "id": "anchor", "bind": { "x": "iconX", "y": "iconD" } },
              { "id": "arm", "parent": "anchor", "rotation": 0.25 },
              { "id": "end", "parent": "arm", "y": 300 } ],
            "instances": [{ "id": "pip", "artboard": "Pip", "parent": "end", "x": -100, "mask": "icon" }],
            "masks": [{ "id": "icon", "width": 100, "height": 100, "invert": true }]""",
            clips = """[{ "name": "timeline", "frames": 60, "tracks": [
              { "target": "arm", "property": "rotation", "keys": [{ "frame": 0, "value": 0 }, { "frame": 60, "value": 1.5 }] } ] }]""",
        ))
        val anchor = d.indexOf(d.obj("Node", "anchor"))
        assertTrue(d.subList(anchor + 1, anchor + 3).all { "DataBindContext" in it }, "x and y bind straight after the node")
        assertTrue("rotation=0.25" in d.obj("Node", "arm"))
        val endId = Regex("\\[(\\d+)]").find(d.obj("Node", "end"))!!.groupValues[1]
        assertTrue("parentId=$endId" in d.obj("Node", "pipGroup"), "the masked instance's group hangs from the chain")
        assertTrue(d.any { "KeyFrameDouble" in it && "value=1.5" in it }, "the arm's rotation is keyed")

        assertTrue("nodes.b.parent: no node 'a' listed before it" in err(project(""" "nodes": [{ "id": "b", "parent": "a" }, { "id": "a" }]""")))
        assertTrue("instances.pip.parent: no node 'nope'" in err(project(""" "instances": [{ "id": "pip", "artboard": "Pip", "parent": "nope" }]""")))
    }

    @Test
    fun `a disjoint inverted mask cuts every hole from one clip, and an instance takes several masks`() {
        val d = dump(project("""
            "instances": [{ "id": "pip", "artboard": "Pip", "masks": ["icons", "hole"] }],
            "masks": [
              { "id": "icons", "width": 1, "height": 1, "invert": true, "disjoint": true,
                "parts": [{ "shape": "ellipse", "width": 160, "height": 160, "bind": { "x": "iconX", "width": "iconD", "height": "iconD" } },
                          { "width": 90, "height": 40, "originY": 0, "bind": { "y": "labelH", "width": "labelW" } }] },
              { "id": "hole", "width": 100, "height": 100, "invert": true } ]"""))
        assertEquals(1, d.count { Regex("^#\\d+ Shape .*name=\"iconsClip").containsMatchIn(it) }, "one clip-source shape")
        val clip = d.indexOf(d.obj("Shape", "iconsClip"))
        assertTrue("width=8000.0" in d[clip + 1], d[clip + 1])
        assertEquals(3, d.subList(clip + 2, d.size).takeWhile { "Shape " !in it && "ClippingShape" !in it && "Artboard" !in it }
            .count { "Ellipse" in it || "Rectangle" in it }, "the centre and both parts share it")
        assertTrue("fillRule=1" in d.obj("ClippingShape", "pipClip"))
        assertTrue("fillRule=1" in d.obj("ClippingShape", "pipClip-hole"))
        assertTrue("disjoint" in err(project(""" "masks": [{ "id": "m", "width": 1, "height": 1, "disjoint": true }]""")))
    }
}
