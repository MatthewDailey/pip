package showreel

import rivc.Ease
import rivc.KeySpec
import rivc.PipSource
import rivc.ProjectSource
import java.io.File
import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Pip's numbers, end to end: the evaluator against the Kotlin engine's own frames
 * (`apps/pip-app/app/build/showreel/frames.txt`), then the keys in the written project against the export at
 * 30 sample times. Writes the table to `.proof/showreel-rive/pip-check.txt`.
 */
class PipCheckTest {
    private val repo = File(System.getProperty("showreel.repo") ?: "../..")
    private val export = File(repo, "apps/pip-app/app/build/showreel/showreel.json")

    @Test
    fun evaluatorMatchesKotlinFrames() {
        val frames = File(repo, "apps/pip-app/app/build/showreel/frames.txt")
        if (!frames.isFile || !export.isFile) return
        val ev = Evaluator(ShowExport.load(export))
        var t = Double.NaN
        val worst = HashMap<String, Double>()
        var checked = 0
        for (line in frames.readLines()) {
            if (line.startsWith("t=")) { t = line.removePrefix("t=").toDouble(); continue }
            if (!line.startsWith("  pip ")) continue
            val f = ev.frame(t)
            for (m in Regex("(\\w+)=(-?[0-9.]+(?:E-?\\d+)?)#").findAll(line)) {
                val (name, v) = m.destructured
                val got = f.pip[name] as? Double ?: continue
                val err = abs(got - v.toDouble())
                worst[name] = maxOf(worst[name] ?: 0.0, err)
                checked++
            }
        }
        println("evaluator vs frames.txt: $checked values; worst ${worst.entries.sortedByDescending { it.value }.take(5)}")
        // verify.txt's own bounds: sine-ease stand-ins reach ~2.2 px and ~1.3 degrees on the biggest moves.
        for ((k, e) in worst) assertTrue(e < 2.5, "pip.$k off by $e")
    }

    @Test
    fun projectKeysMatchTheExportAt30Times() {
        val project = File(repo, "packages/pip-rive/showreel/showreel.json")
        val rigFile = File(repo, "packages/pip-rive/pip/pip-inputs.json")
        if (!project.isFile || !export.isFile || !rigFile.isFile) return
        val show = ShowExport.load(export)
        val ev = Evaluator(show)
        val src = ProjectSource.parse(project.readText())
        val clip = src.artboards.first().clips!!.single()
        val tracks = clip.tracks.associateBy { it.target to it.property }
        val rig = PipSource.load(rigFile).artboard
        val feetX = rig.padding.left + rig.width / 2
        val feetY = rig.padding.top + rig.height
        val unit = PipGeometry.BOX_W / rig.width

        val rnd = Random(20261120)
        val samples = (0 until 30).map { rnd.nextInt(0, clip.frames) }.sorted()
        val rows = mutableListOf<String>()
        val worst = HashMap<String, Double>()
        val tol = mapOf("x" to 0.6, "y" to 0.6, "rotation" to 0.004, "scaleX" to 0.002, "scaleY" to 0.002, "opacity" to 0.01)
        for (fr in samples) {
            val f = ev.frame(fr / 60.0)
            val shown = PipGeometry.visible(f) && f.pip["hideBehind"] == null
            val expected = linkedMapOf<String, Double>("opacity" to if (shown) f.pipNum("alpha").coerceIn(0.0, 1.0) else 0.0)
            if (shown) {
                val n = PipGeometry.body(f).scaled(unit).node(feetX, feetY)
                expected += mapOf("x" to n.x, "y" to n.y, "rotation" to n.rot, "scaleX" to n.sx, "scaleY" to n.sy)
                for (input in listOf("mood", "mouth", "lookX", "lookY", "eyesOpen", "squint", "browRaise", "hatSway", "brows", "handR", "life")) {
                    val v = PipInputs.mapping(input, false)!!.fn(f)
                    if (!v.isNaN()) expected["in:$input"] = v
                }
            }
            val parts = mutableListOf<String>()
            for ((k, want) in expected) {
                val key = if (k.startsWith("in:")) "pip/${k.removePrefix("in:")}" to "nestedValue" else "pip" to k
                val got = at(tracks.getValue(key).keys, fr)
                val err = abs(got - want)
                worst[k] = maxOf(worst[k] ?: 0.0, err)
                parts += "$k ${"%.3f".format(want)}/${"%.3f".format(got)}"
                assertTrue(err <= (tol[k] ?: 0.02), "t=${fr / 60.0} $k: export $want, keys $got")
            }
            rows += "t=${"%.3f".format(fr / 60.0)} ${f.scene.name}: ${parts.joinToString("  ")}"
        }
        val out = File(repo, ".proof/showreel-rive/pip-check.txt")
        out.parentFile.mkdirs()
        out.writeText(
            "Pip: keys in showreel.json vs the export (expected/keyed) at 30 seeded times\n" +
                rows.joinToString("\n") + "\nworst: " + worst.entries.joinToString { "${it.key} ${"%.4f".format(it.value)}" } + "\n",
        )
        println(out.readText().lines().last { it.startsWith("worst") })
    }

    /** The value rivc's keys give at [frame]: linear or held (the Pip tracks use no cubic keys). */
    private fun at(keys: List<KeySpec>, frame: Int): Double {
        val a = keys.lastOrNull { it.frame <= frame } ?: return keys.first().value!!
        val b = keys.firstOrNull { it.frame > frame } ?: return a.value!!
        if (a.ease == Ease.Hold) return a.value!!
        return a.value!! + (b.value!! - a.value!!) * (frame - a.frame) / (b.frame - a.frame)
    }
}
