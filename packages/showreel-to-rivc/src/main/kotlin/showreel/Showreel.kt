package showreel

import kotlinx.serialization.json.Json
import rivc.ArtboardSpec
import rivc.AssetSpec
import rivc.ClipSpec
import rivc.Control
import rivc.CueSpec
import rivc.InputSpec
import rivc.InstanceSpec
import rivc.KeySpec
import rivc.Loop
import rivc.MaskPartSpec
import rivc.MaskShape
import rivc.MaskSpec
import rivc.PipSource
import rivc.ProjectArtboardSpec
import rivc.ProjectSource
import rivc.RemapSpec
import rivc.TextAlignSpec
import rivc.TextChangeSpec
import rivc.TextGlossSpec
import rivc.TextStrokeSpec
import rivc.TextSpec
import rivc.TrackSpec
import rivc.ValueType
import rivc.ViewModelSpec
import rivc.VmPropertySpec
import java.io.File
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sin

/** Paths the converter reads and writes. */
class Paths(val repo: File, val export: File, val pipRig: File, val out: File) {
    val sets = File(repo, "packages/pip-rive/sets")
    val font = File(repo, "packages/rivc/examples/project/fonts/Inter-Bold.ttf")
    val fontLicence = File(repo, "packages/rivc/examples/project/fonts/Inter-OFL.txt")
    val notesFont = File(repo, "packages/rivc/examples/project/fonts/NotoSansSymbols-Notes.ttf")
    val notesLicence = File(repo, "packages/rivc/examples/project/fonts/NotoSansSymbols-OFL.txt")
    val projectDir: File get() = out.parentFile
    val gen get() = File(projectDir, "gen")
}

/**
 * Builds the showreel project: one main artboard (1080 x 2424, transparent) whose single 60 fps
 * clip `show` runs the whole reel, with Pip, every actor's set piece, the texts, masks and cue
 * events keyed on it.
 */
class Converter(
    private val paths: Paths,
    /** Converts only this scene (a cut-down project whose clip starts at the scene's start), or the whole show. */
    private val only: String? = null,
    private val log: (String) -> Unit = ::println,
) {
    val show = ShowExport.load(paths.export)
    private val ev = Evaluator(show)
    val fps = 60
    /** Show time of the main clip's frame 0. */
    val t0: Double = only?.let { show.scene(it).start } ?: 0.0
    val frames = ((only?.let { show.scene(it).end } ?: show.duration) * fps - t0 * fps).roundToInt()
    val report = Report()
    private val measure = TextMeasure(paths.font)

    private val drafts: Map<String, Draft> = paths.sets.listFiles { f -> f.name.endsWith(".json") && !f.name.endsWith(".rivc.json") }!!
        .sortedBy { it.name }.map { Draft.load(it) }.associateBy { it.name }

    /** A draft input curve by name (set-dtoday-door's `light` and `impact`), sampled on the scene clock. */
    private fun curve(name: String): ((Double) -> Double)? {
        val (rate, xs) = drafts.values.firstNotNullOfOrNull { it.curves[name] } ?: return null
        return { t ->
            val p = (t * rate).coerceIn(0.0, xs.size - 1.0)
            val i = floor(p).toInt().coerceAtMost(xs.size - 2).coerceAtLeast(0)
            if (xs.size == 1) xs[0] else xs[i] + (xs[i + 1] - xs[i]) * (p - i)
        }
    }

    val pieces: Map<String, NormalisedPiece> = drafts.mapValues { (name, d) ->
        Normaliser.normalise(d, Plans.plans[name] ?: PiecePlan(), ::curve)
    }

    private val generated = LinkedHashMap<String, Pair<Generated.Piece, NormalisedPiece?>>()

    // ---- output being built --------------------------------------------------------------------------------------
    private class Entry(val order: Double, val spec: InstanceSpec)
    private val instances = mutableListOf<Entry>()
    private val texts = mutableListOf<Pair<Double, TextSpec>>()
    private val masks = mutableListOf<MaskSpec>()
    private val producers = mutableListOf<Producer>()
    private val vmProps = mutableListOf<VmPropertySpec>()
    private val usedPieces = LinkedHashSet<String>()

    private fun sceneFrames(scene: ShowExport.Scene): Pair<Int, Int> =
        (((scene.start - t0) * fps).roundToInt() - 1).coerceAtLeast(0) to (((scene.end - t0) * fps).roundToInt() + 1).coerceAtMost(frames)

    /** Writes every normalised piece next to its draft. */
    fun writePieces() {
        for ((name, p) in pieces) {
            val f = File(paths.sets, "$name.rivc.json")
            f.writeText(PipSource.json.encodeToString(PipSource.serializer(), p.source) + "\n")
            report.pieceGaps[name] = p.gaps
        }
    }

    fun build(): ProjectSource {
        val rig = PipSource.load(paths.pipRig)
        pip(rig)
        val ordered = show.scenes.filter { only == null || it.name == only }.flatMap { s -> s.actorIds.map { show.actors.getValue(it) } }
        for (a in ordered) actor(a)
        cues()

        val sortedInstances = instances.sortedBy { it.order }.map { it.spec }
        val sortedTexts = texts.sortedBy { it.first }.map { it.second }
        // Texts and instances in one draw order (bottom to top), by actor layer as Kotlin paints them.
        val stack = (instances.map { it.order to it.spec.id } + texts.map { it.first to it.second.id }).sortedBy { it.first }.map { it.second }
        log("sampling ${producers.size} channels over $frames frames")
        val keys = Sampler(ev, fps, frames, t0).run(producers) { f -> if (f % 1800 == 0) log("  frame $f / $frames") }
        val tracks = producers.map { p -> TrackSpec(p.target, p.property, keys.getValue(p)) }.filter { it.keys.isNotEmpty() }
        report.keys = tracks.sumOf { it.keys.size }
        report.tracks = tracks.size

        val main = ProjectArtboardSpec(
            artboard = ArtboardSpec(name = "Showreel", width = show.stageWidth, height = show.stageHeight),
            clips = listOf(ClipSpec(name = CLIP, frames = frames, fps = fps, loop = Loop.ONE_SHOT, tracks = tracks)),
            viewModel = if (vmProps.isEmpty()) null else ViewModelSpec(name = "Stage", properties = vmProps),
            instances = sortedInstances,
            texts = sortedTexts,
            events = cueSpecs,
            masks = masks,
            stack = stack,
        )
        val rel = { f: File -> paths.projectDir.toPath().relativize(f.absoluteFile.toPath()).toString() }
        val boards = mutableListOf(main, ProjectArtboardSpec(source = rel(paths.pipRig), control = Control.INPUTS))
        for (name in usedPieces) {
            val g = generated[name]
            boards += if (g != null) ProjectArtboardSpec(source = "gen/$name.json") else ProjectArtboardSpec(source = rel(File(paths.sets, "$name.rivc.json")))
        }
        val assets = listOf(AssetSpec(FONT, "fonts/Inter-Bold.ttf", fallback = NOTES), AssetSpec(NOTES, "fonts/$NOTES.ttf"))
        return ProjectSource(assets = assets, artboards = boards)
    }

    // ---- Pip -----------------------------------------------------------------------------------------------------

    private fun pip(rig: PipSource) {
        val ab = rig.artboard
        val unit = PipGeometry.BOX_W / ab.width
        val feetX = ab.padding.left + ab.width / 2
        val feetY = ab.padding.top + ab.height
        val rigInputs = rig.stateMachine?.inputs.orEmpty()
        val spike = paths.pipRig.path.contains("examples/spike")
        report.pipRig = paths.pipRig.path
        val hideIcons = show.animations.values.filter { it.target == "pip" && (only == null || it.scope == only) }.flatMap { a -> a.tracks.filter { it.property == "hideBehind" } }
            .flatMap { t -> t.keys.mapNotNull { it.v as? String } }.distinct()

        // The ground shadow, under Pip.
        gen(Generated.shadow())
        val shadowId = "pip-shadow"
        instances += Entry(-0.6, InstanceSpec(id = shadowId, artboard = Generated.SHADOW, opacity = 0.0))
        fun shadow(at: At) = at.memo("shadow") { if (PipGeometry.visible(at.frame) && at.frame.pip["hideBehind"] == null) PipGeometry.shadow(at.frame) else null }
        fun shadowPlace(at: At): Place? = shadow(at)?.let { s -> Place(s[0], s[1], 0.0, s[2] / 100, s[3] / 100).node(100.0, 100.0) }
        xform(shadowId, 0, frames, ::shadowPlace) { at -> shadow(at)?.let { it[4] * at.frame.pipNum("alpha").coerceIn(0.0, 1.0) } ?: 0.0 }

        // Pip himself, plus one masked copy per icon he hides behind (an instance takes one mask).
        val copies = listOf<String?>(null) + hideIcons
        for ((i, icon) in copies.withIndex()) {
            val id = if (icon == null) "pip" else "pip-${slug(icon)}"
            var mask: String? = null
            if (icon != null) {
                mask = "hide-${slug(icon)}"
                val cell = show.icons[icon] ?: listOf(815.0, 1581.0, 1030.0, 1834.0)
                val cx = (cell[0] + cell[2]) / 2
                val cy = cell[1] + (cell[3] - cell[1]) * 0.375
                val d = (cell[2] - cell[0]) * 0.366 * 2
                // The label under the icon: the cell's width, from the circle's centre to 40 px below the cell.
                val labelW = cell[2] - cell[0]
                val labelH = cell[3] + 40 - cy
                val p = slug(icon).replaceFirstChar { it.lowercase() }
                vmProps += VmPropertySpec("${p}X", ValueType.NUMBER, default = cx)
                vmProps += VmPropertySpec("${p}Y", ValueType.NUMBER, default = cy)
                vmProps += VmPropertySpec("${p}D", ValueType.NUMBER, default = d)
                vmProps += VmPropertySpec("${p}LabelW", ValueType.NUMBER, default = labelW)
                vmProps += VmPropertySpec("${p}LabelH", ValueType.NUMBER, default = labelH)
                masks += MaskSpec(
                    id = mask, x = cx, y = cy, shape = MaskShape.ELLIPSE, width = d, height = d, invert = true,
                    bind = mapOf("x" to "${p}X", "y" to "${p}Y", "width" to "${p}D", "height" to "${p}D"),
                    parts = listOf(MaskPartSpec(
                        shape = MaskShape.RECTANGLE, width = labelW, height = labelH, originY = 0.0,
                        bind = mapOf("width" to "${p}LabelW", "height" to "${p}LabelH"),
                    )),
                )
                report.masks += "$mask: Pip hidden inside $icon's icon circle and label (view model ${p}X/${p}Y/${p}D/${p}LabelW/${p}LabelH, " +
                    "defaults ${cx.r1()}, ${cy.r1()}, ${d.r1()}, ${labelW.r1()}, ${labelH.r1()})"
            }
            instances += Entry(-0.5 + i * 0.01, InstanceSpec(id = id, artboard = rig.artboard.name, mask = mask, opacity = 0.0))
            val shown = { at: At -> PipGeometry.visible(at.frame) && at.frame.pip["hideBehind"] == icon }
            xform(id, 0, frames, { at -> if (shown(at)) PipGeometry.body(at.frame).scaled(unit).node(feetX, feetY) else null }) { at ->
                if (shown(at)) at.frame.pipNum("alpha").coerceIn(0.0, 1.0) else 0.0
            }
            for (input in rigInputs) {
                val m = PipInputs.mapping(input.name, spike) ?: continue
                if (input.type == ValueType.TRIGGER) continue
                producers += Producer("$id/${input.name}", "nestedValue", 0, frames, m.tol, hold = m.hold) { at ->
                    if (shown(at)) m.fn(at.frame) else Double.NaN
                }
            }
        }
        // Pip's sunglasses (his `sunglasses` value: below 0 none, 0..1 dropping onto his face): set-d90s-sunglasses on his box.
        pieces["set-d90s-sunglasses"]?.let { np ->
            usedPieces += np.name
            val id = "pip-sunglasses"
            instances += Entry(-0.5 + copies.size * 0.01, InstanceSpec(id = id, artboard = np.name, opacity = 0.0,
                stateMachine = np.source.stateMachine?.name))
            val drop = { at: At -> at.frame.pipNum("sunglasses") }
            xform(id, 0, frames, { at ->
                if (drop(at) < 0 || !PipGeometry.visible(at.frame)) null
                else PipGeometry.box(at.frame).at(330.0, 396.0 + (1 - drop(at)) * -900).node(np.anchor.x, np.anchor.y)
            }) { at -> if (drop(at) >= 0 && PipGeometry.visible(at.frame) && at.frame.pip["hideBehind"] == null) at.frame.pipNum("alpha").coerceIn(0.0, 1.0) else 0.0 }
            report.actorRules += "Pip.sunglasses -> set-d90s-sunglasses on his box at (330, 396), y offset (1 - drop) x -900"
        }
        report.pipInputs = rigInputs.map { it.name to (PipInputs.mapping(it.name, spike)?.desc ?: "not in the export; left at its default") }
        report.pipGaps += PipInputs.unmapped(rigInputs.map { it.name }.toSet())
        if (hideIcons.isNotEmpty()) report.compilerGaps += "hideBehind masks: an instance takes one mask, so Pip is instanced once per icon and the copies cross-fade by opacity."
    }

    // ---- actors --------------------------------------------------------------------------------------------------

    private fun actor(a: ShowExport.Actor) {
        val scene = show.scene(a.scene)
        val m = Mapping.map(show, a)
        report.actorRules += "${a.id} (${a.kind}) -> ${m.rule}"
        when (m) {
            is ActorMapping.Unmatched -> report.unmatched += "${a.id}: ${m.rule}"
            is ActorMapping.Bubble -> { bubble(a, scene); report.textsConverted++ }
            is ActorMapping.Banner -> { banner(a, scene); report.textsConverted++ }
            is ActorMapping.Shout -> { shout(a, scene, m); report.textsConverted++ }
            is ActorMapping.Pieces -> { pieces(a, scene, m); report.actorsConverted++ }
        }
    }

    private fun ctx(at: At, a: ShowExport.Actor): Ctx =
        at.memo("ctx:${a.id}") { Ctx(at.frame.actorNum(a.id, "progress").let { if (it.isNaN()) 0.0 else it }, at.frame.t - at.frame.scene.start, at.frame, a.id) }

    private fun actorShown(at: At, a: ShowExport.Actor): Double {
        val s = at.frame.actors[a.id] ?: return 0.0
        if (s["visible"] == false) return 0.0
        return ((s["alpha"] as? Double) ?: 1.0).coerceIn(0.0, 1.0)
    }

    /** The actor's own frame: its position (Pip-relative when anchored), rotation and scale. */
    private fun actorPlace(at: At, a: ShowExport.Actor): Place? = at.memo("place:${a.id}") {
        val s = at.frame.actors[a.id] ?: return@memo null
        var x = s["x"] as Double
        var y = s["y"] as Double
        if (a.anchorToPip) {
            val (hx, hy) = PipGeometry.headTop(at.frame)
            x += hx; y += hy
        }
        val k = s["scale"] as Double
        Place(x, y, (s["rotation"] as Double) * Place.DEG, k, k)
    }

    private fun pieces(a: ShowExport.Actor, scene: ShowExport.Scene, m: ActorMapping.Pieces) {
        val (first, last) = sceneFrames(scene)
        var maskId: String? = null
        if (m.panelMask) {
            maskId = "panel-${a.id}"
            masks += MaskSpec(id = maskId, x = 540.0, y = 1150.0, shape = MaskShape.RECTANGLE, width = 940.0, height = 760.0, cornerRadius = 64.0)
            producers += Producer(maskId, "x", first, last, 0.3) { at -> actorPlace(at, a)?.x ?: Double.NaN }
            producers += Producer(maskId, "y", first, last, 0.3) { at -> actorPlace(at, a)?.y ?: Double.NaN }
            producers += Producer(maskId, "rotation", first, last, 0.002) { at -> actorPlace(at, a)?.rot ?: Double.NaN }
            producers += Producer(maskId, "scaleX", first, last, 0.0006) { at -> actorPlace(at, a)?.sx ?: Double.NaN }
            producers += Producer(maskId, "scaleY", first, last, 0.0006) { at -> actorPlace(at, a)?.sy ?: Double.NaN }
            report.masks += "$maskId: the set panel's rounded clip (940 x 760, r 64), keyed to the panel actor's position, rotation and scale"
        }
        // Painters that clip to the d60s panel (the starburst wipe, the flag) share its mask.
        val sharedPanel = if (a.scene == "d60s" && m.uses.any { it.piece == "set-d60s-starburst" || it.piece == "set-d60s-flag" }) "panel-d60s-actor-0" else null
        val frameUnder = m.uses.any { it.piece == "set-d60s-frame" }
        for ((ui, use) in m.uses.withIndex()) {
            val np = pieceFor(use.piece) ?: run { report.unmatched += "${a.id}: piece ${use.piece} has no draft"; continue }
            val id = if (m.uses.size == 1) a.id else "${a.id}--${use.piece}"
            val order = a.layer + (scene.index * 100 + a.index) * 1e-4 + ui * 1e-6
            val inPanel = use.placement is Placement.PanelSet
            if (use.piece == "set-d60s-frame" && frameUnder) {
                // The frame's drop shadow belongs under the sets, its border and streaks over them: two instances.
                addPieceInstance(a, scene, np, "$id-under", a.layer + (scene.index * 100 + a.index) * 1e-4 - 1e-6, use, null, mapOf("input-no-shadow" to 0.0), first, last)
                addPieceInstance(a, scene, np, id, order, use, null, mapOf("input-no-shadow" to 1.0), first, last)
                continue
            }
            addPieceInstance(a, scene, np, id, order, use, if (inPanel) maskId else sharedPanel, emptyMap(), first, last)
        }
    }

    private fun pieceFor(name: String): NormalisedPiece? {
        if (name == Generated.FLASH_PANEL) return gen(Generated.flashPanel())
        if (name == Generated.FLASH_SCREEN) return gen(Generated.flashScreen())
        return pieces[name]?.also { usedPieces += name }
    }

    private fun gen(p: Generated.Piece): NormalisedPiece {
        generated[p.name]?.second?.let { return it }
        Generated.write(paths.gen, p)
        val src = PipSource.load(File(paths.gen, "${p.name}.json"))
        val np = NormalisedPiece(Draft(File(paths.gen, "${p.name}.json"), kotlinx.serialization.json.JsonObject(emptyMap())), src,
            rivc.art.Pt(p.anchorX, p.anchorY), emptyMap(), emptyList(), null, emptyList())
        generated[p.name] = p to np
        usedPieces += p.name
        return np
    }

    /** One NestedArtboard of [np] for actor [a], keyed for its whole scene. */
    private fun addPieceInstance(
        a: ShowExport.Actor, scene: ShowExport.Scene, np: NormalisedPiece, id: String, order: Double, use: Use, mask: String?,
        synthU: Map<String, Double>, first: Int, last: Int,
    ) {
        val remaps = np.remapped.map { RemapSpec(clip = it) }
        val hasMachine = np.source.stateMachine != null
        instances += Entry(order, InstanceSpec(
            id = id, artboard = np.name, opacity = 0.0, mask = mask, remaps = remaps,
            stateMachine = if (hasMachine && remaps.isNotEmpty()) np.source.stateMachine!!.name else null,
        ))
        val place: (At) -> Place? = { at -> placeUse(at, a, use, np) }
        xform(id, first, last, { at -> place(at)?.node(np.anchor.x, np.anchor.y) }) { at ->
            val shown = actorShown(at, a)
            if (shown <= 0) return@xform 0.0
            val x = ctx(at, a)
            if (np.visible?.invoke(x) == false || place(at) == null) 0.0 else shown
        }
        val visibleNow = { at: At -> actorShown(at, a) > 0 && place(at) != null }
        for ((clip, play) in np.plays) {
            if (play !is Play.Scrub) continue
            val dur = np.clipSeconds(clip)
            val loops = np.clipLoops(clip)
            producers += Producer("$id/$clip", "time", first, last, minOf(0.002, 1.0 / 240 / dur)) { at ->
                if (!visibleNow(at)) Double.NaN else {
                    val s = play.seconds(ctx(at, a))
                    if (loops) ((s % dur) + dur) % dur / dur else (s / dur).coerceIn(0.0, 1.0)
                }
            }
            Plans.scrubActive[np.name to clip]?.let { active ->
                producers += Producer("$id/$clip", "mix", first, last, 0.01, hold = true) { at -> if (!visibleNow(at)) Double.NaN else if (active(ctx(at, a))) 1.0 else 0.0 }
            }
        }
        for (s in np.synth) {
            val fixed = synthU[s.name]
            producers += Producer("$id/${s.name}", "time", first, last, 0.002) { at ->
                if (!visibleNow(at)) Double.NaN else (fixed ?: s.u(ctx(at, a))).coerceIn(0.0, 1.0)
            }
        }
        for ((clip, mix) in np.mixes) {
            producers += Producer("$id/${clip}Mix", "nestedValue", first, last, 0.5) { at ->
                if (!visibleNow(at)) Double.NaN else 100 * mix.weight(ctx(at, a)).coerceIn(0.0, 1.0)
            }
        }
        report.instances++
    }

    /** Where use [use] of actor [a] puts its piece's (0, 0) this frame, or null when it isn't drawn. */
    private fun placeUse(at: At, a: ShowExport.Actor, use: Use, np: NormalisedPiece): Place? {
        val f = at.frame
        return when (val p = use.placement) {
            Placement.AtActor -> actorPlace(at, a)
            is Placement.PipBox -> {
                if (!PipGeometry.visible(f)) return null
                var pl = PipGeometry.box(f).at(p.bx, p.by)
                if (p.scaleByProgress) {
                    val k = ctx(at, a).progress
                    if (k <= 0) return null
                    pl = pl.scaled(k)
                }
                pl
            }
            Placement.Sunglasses -> null
            Placement.Flag -> {
                val s = f.actors[a.id] ?: return null
                if (((s["progress"] as? Double) ?: 0.0) < 0.5) {
                    if (f.pip["rightHand"] == "NONE" || !PipGeometry.visible(f)) return null
                    val (qx, qy) = PipGeometry.box(f).point(f.pipNum("rightX"), f.pipNum("rightY"))
                    Place(qx, qy, f.pipNum("rotation") * Place.DEG, 1.0, 1.0).at(0.0, 95.0)
                } else Place(s["x"] as Double, s["y"] as Double, (s["rotation"] as Double) * Place.DEG, 1.0, 1.0)
            }
            is Placement.PanelSet -> {
                val base = actorPlace(at, a) ?: return null
                val n = p.sets
                val pr = ctx(at, a).progress.coerceIn(0.0, n - 1.0)
                val i = floor(pr).toInt().coerceAtMost(n - 1)
                val fr = if (i >= n - 1) 0.0 else pr - i
                val vertical = p.vertical.getOrElse(i) { false }
                val ox = if (vertical) 0.0 else -fr * 940
                val oy = if (vertical) fr * 760 else 0.0
                when {
                    p.slot == i -> base.at(ox, oy)
                    p.slot == i + 1 && fr >= 0.001 -> base.at(if (vertical) 0.0 else ox + 940, if (vertical) oy - 760 else 0.0)
                    else -> null
                }
            }
        }
    }

    /** Keys x, y, rotation, scaleX, scaleY of [id] from [place] (null: hidden, don't care) and its opacity. */
    private fun xform(id: String, first: Int, last: Int, place: (At) -> Place?, opacity: (At) -> Double) {
        val outside = if (first > 0 || last < frames) 0.0 else null
        producers += Producer(id, "opacity", first, last, 0.004, outside = outside) { at -> opacity(at) }
        val shown = { at: At -> at.memo("op:$id") { opacity(at) } > 0 }
        fun ch(prop: String, tol: Double, f: (Place) -> Double) {
            producers += Producer(id, prop, first, last, tol) { at -> if (!shown(at)) Double.NaN else place(at)?.let(f) ?: Double.NaN }
        }
        ch("x", 0.35) { it.x }
        ch("y", 0.35) { it.y }
        ch("rotation", 0.002) { it.rot }
        ch("scaleX", 0.0006) { it.sx }
        ch("scaleY", 0.0006) { it.sy }
    }

    // ---- texts ---------------------------------------------------------------------------------------------------

    /** Every text an actor shows in its scene, with the frames it reads each. */
    private fun textOf(at: At, a: ShowExport.Actor): String = (at.frame.actors[a.id]?.get("text") as? String) ?: a.text

    private fun bubble(a: ShowExport.Actor, scene: ShowExport.Scene) {
        val (first, last) = sceneFrames(scene)
        val strings = (show.texts.firstOrNull { it.actor == a.id }?.texts ?: listOf(a.text)).distinct()
        val d = Generated
        val tail = gen(Generated.bubbleTail())
        val order = a.layer + (scene.index * 100 + a.index) * 1e-4
        instances += Entry(order + 2e-6, InstanceSpec(id = "${a.id}-tail", artboard = tail.name, opacity = 0.0))
        xform("${a.id}-tail", first, last, { at -> actorPlace(at, a)?.node(tail.anchor.x, tail.anchor.y) }) { at -> actorShown(at, a) }
        for ((i, s) in strings.withIndex()) {
            val tw = measure.bubbleTextWidth(s)
            val lines = measure.lines(s, d.BUBBLE_TEXT, tw)
            val th = lines * d.BUBBLE_LINE
            val bw = tw + 2 * d.BUBBLE_PAD
            val bh = th + 2 * d.BUBBLE_PAD
            val box = gen(Generated.bubbleBox(bw, bh))
            val boxId = "${a.id}-box-$i"
            val textId = "${a.id}-text-$i"
            instances += Entry(order + 1e-6 * (i + 1) / (strings.size + 1), InstanceSpec(id = boxId, artboard = box.name, opacity = 0.0))
            // The box's left edge in the bubble's frame, clamped 12 dp inside the screen (the tail stays put).
            fun left(pl: Place): Double {
                var l = -bw / 2
                val margin = 12 * d.DENSITY
                if (pl.x + l < margin) l = margin - pl.x
                if (pl.x + l + bw > show.stageWidth - margin) l = show.stageWidth - margin - pl.x - bw
                return l
            }
            val opacity = { at: At -> if (textOf(at, a) == s) actorShown(at, a) else 0.0 }
            xform(boxId, first, last, { at -> actorPlace(at, a)?.let { pl -> pl.at(left(pl), -d.BUBBLE_GAP - bh).node(box.anchor.x, box.anchor.y) } }, opacity)
            texts += order + 3e-6 to TextSpec(
                id = textId, text = s, font = FONT, size = d.BUBBLE_TEXT, color = "#FF3A2A26", width = tw + 2, height = th,
                align = TextAlignSpec.CENTER, lineHeight = d.BUBBLE_LINE, opacity = 0.0,
            )
            xform(textId, first, last, { at -> actorPlace(at, a)?.let { pl -> pl.at(left(pl) + d.BUBBLE_PAD - 1, -d.BUBBLE_GAP - bh + d.BUBBLE_PAD) } }, opacity)
        }
        report.texts += "${a.id}: bubble ${strings.joinToString(" / ") { "\"$it\"" }}"
    }

    /** A title: an ink drop shadow, then the fill with the runtime's text stroke as its ink outline (drawn under the fill). */
    private fun outlined(
        a: ShowExport.Actor, scene: ShowExport.Scene, base: String, size: Double, fill: String, stroke: Double,
        shadow: Pair<Double, Double>, shadowColor: String, baselineY: Double, shown: (At) -> Double,
    ) {
        val (first, last) = sceneFrames(scene)
        val strings = (show.texts.firstOrNull { it.actor == a.id }?.texts ?: listOf(a.text)).distinct()
        val widest = strings.maxOf { measure.width(it, size) }
        val w = widest + 2 * stroke + 60
        val top = baselineY - measure.ascent(size)
        val order = a.layer + (scene.index * 100 + a.index) * 1e-4
        val changes = textChanges(a, first, last)
        val layers = listOf(
            Triple("shadow", TextSpec(id = "", text = "", font = FONT, size = size, color = shadowColor), shadow),
            Triple("fill", TextSpec(id = "", text = "", font = FONT, size = size, color = fill, stroke = TextStrokeSpec(INK, stroke)), 0.0 to 0.0),
        )
        for ((li, layer) in layers.withIndex()) {
            val (name, style, off) = layer
            val tid = "${a.id}-$base-$name"
            texts += order + li * 1e-7 to style.copy(
                id = tid, text = strings.first(), width = w, align = TextAlignSpec.CENTER, wrap = false, opacity = 0.0, changes = changes,
            )
            xform(tid, first, last, { at -> actorPlace(at, a)?.at(-w / 2 + off.first, top + off.second) }, shown)
        }
    }

    private fun textChanges(a: ShowExport.Actor, first: Int, last: Int): List<TextChangeSpec> {
        if (show.texts.firstOrNull { it.actor == a.id }?.texts.orEmpty().distinct().size <= 1) return emptyList()
        val out = mutableListOf<TextChangeSpec>()
        var prev: String? = null
        for (f in first..last) {
            val s = textOf(At(ev.frame(t0 + f.toDouble() / fps)), a)
            if (s != prev) { out += TextChangeSpec(CLIP, f, s); prev = s }
        }
        return out
    }

    private fun banner(a: ShowExport.Actor, scene: ShowExport.Scene) {
        val d = Generated.DENSITY
        val maxSize = 44 * d
        val strokeMax = 7 * d
        val avail = show.stageWidth - 48 * d
        val measured = measure.width(a.text, maxSize) + strokeMax
        val size = if (measured > avail) maxSize * avail / measured else maxSize
        val sw = strokeMax * size / maxSize
        outlined(a, scene, "banner", size, "#FFFFFFFF", sw, 0.0 to sw * 0.6, "#403A2A26", 0.0) { at -> actorShown(at, a) }
        report.texts += "${a.id}: banner \"${a.text}\" at ${size.r1()} px"
        report.textGaps += "${a.id}: the banner's fit-to-width shrink is computed at scale 1 (Kotlin re-fits as the actor scales)"
    }

    /**
     * `paintShout`, letter by letter: each letter is a drop-shadow text and a text with an ink stroke and a
     * glossy top half, placed at its advance, tilted by sin(i 1.7) x 4 degrees, jittered, and popped in
     * (scale 0.25 + 0.75 back-ease) as progress reveals it. Every string the actor shows gets its own letters.
     */
    private fun shout(a: ShowExport.Actor, scene: ShowExport.Scene, m: ActorMapping.Shout) {
        val (first, last) = sceneFrames(scene)
        val c = show.customActors.getValue(a.id)
        val declared = Regex("text = \"([^\"]*)\"").find(c.sourceText)?.groupValues?.get(1) ?: a.text
        val strings = (show.texts.firstOrNull { it.actor == a.id }?.texts ?: listOf(declared)).distinct()
        val order = a.layer + (scene.index * 100 + a.index) * 1e-4
        val hi = mixArgb(m.fill, "#FFFFFFFF", 0.55)
        val progress = { at: At -> at.frame.actorNum(a.id, "progress").let { if (it.isNaN()) 0.0 else it } }
        for ((si, s) in strings.withIndex()) {
            var size = m.size
            var total = measure.width(s, size)
            if (total > m.maxWidth) { size *= m.maxWidth / total; total = m.maxWidth }
            val base = size * 0.36
            val top = base - measure.ascent(size)
            var x = -total / 2
            for ((i, ch) in s.withIndex()) {
                val adv = measure.width(ch.toString(), size)
                if (ch != ' ') {
                    val cx = x + adv / 2
                    val boxW = adv + size * 0.6
                    val tilt = sin(i * 1.7) * 4 * Place.DEG
                    val letter = { at: At ->
                        at.memo("shout:${a.id}:$si:$i") {
                            val pr = progress(at)
                            val vis = if (pr >= 1) 9.0 else pr.coerceIn(0.0, 1.0) * s.length - i
                            if (vis <= 0 || pr <= 0 || textOf(at, a) != s) null
                            else actorPlace(at, a)?.let { pl ->
                                val t = ctx(at, a).sceneTime
                                val jx = m.jitter * sin(t * 43 + i * 2.1)
                                val jy = m.jitter * cos(t * 37 + i * 1.3)
                                pl.at(cx + jx, jy).rotated(tilt).scaled(0.25 + 0.75 * backOut(vis / 1.4))
                            }
                        }
                    }
                    val opacity = { at: At -> if (letter(at) != null) actorShown(at, a) else 0.0 }
                    val id = "${a.id}-shout$si-$i"
                    val o = order + (si * 64 + i) * 1e-8
                    texts += o to TextSpec(
                        id = "$id-shadow", text = ch.toString(), font = FONT, size = size, color = "#66000000",
                        width = boxW, align = TextAlignSpec.CENTER, wrap = false, opacity = 0.0,
                    )
                    xform("$id-shadow", first, last, { at -> letter(at)?.at(-boxW / 2 + size * 0.04, top + size * 0.08) }, opacity)
                    texts += o + 5e-9 to TextSpec(
                        id = id, text = ch.toString(), font = FONT, size = size, color = m.fill, width = boxW,
                        align = TextAlignSpec.CENTER, wrap = false, opacity = 0.0,
                        stroke = TextStrokeSpec(INK, size * 0.15), gloss = TextGlossSpec(hi, -size * 0.05 - top),
                    )
                    xform(id, first, last, { at -> letter(at)?.at(-boxW / 2, top) }, opacity)
                }
                x += adv
            }
        }
        report.texts += "${a.id}: shout ${strings.joinToString(" / ") { "\"$it\"" }} at ${m.size.r1()} px, letter by letter"
        report.textGaps += "${a.id}: the shout face is Inter Bold upright; Kotlin's is sans-serif-black bold italic"
    }

    /** `mBack`: back-ease out of [t] clamped to 0..1 (overshoots to about 1.1 before settling at 1). */
    private fun backOut(t: Double): Double { val x = t.coerceIn(0.0, 1.0) - 1; return 1 + x * x * (2.70158 * x + 1.70158) }

    /** `mMix` of two `#AARRGGBB` colours. */
    private fun mixArgb(a: String, b: String, u: Double): String {
        val ca = a.removePrefix("#").toLong(16); val cb = b.removePrefix("#").toLong(16)
        val ch = { s: Int -> val x = (ca shr s) and 0xFF; val y = (cb shr s) and 0xFF; ((x + (y - x) * u).toLong() and 0xFF) shl s }
        return "#%08X".format(ch(24) or ch(16) or ch(8) or ch(0))
    }

    // ---- cues ----------------------------------------------------------------------------------------------------

    private var cueSpecs: List<CueSpec> = emptyList()

    private fun cues() {
        val cues = show.cues.filter { only == null || it.scene == only }
        cueSpecs = cues.groupBy { it.name }.map { (name, list) ->
            CueSpec(name, CLIP, list.map { ((it.t - t0) * fps).roundToInt().coerceIn(0, frames) }.distinct().sorted())
        }
        report.cues = cues.size
        report.events = cueSpecs.size
    }

    private fun slug(s: String) = s.replace(Regex("[^A-Za-z0-9]"), "")

    companion object {
        const val CLIP = "show"
        const val FONT = "Inter-Bold"
        /** Music notes (U+2669..266C), which Inter lacks: Inter-Bold's fallback. */
        const val NOTES = "NotoSansSymbols-Notes"
        const val INK = "#FF3A2A26"
        val compact = Json(PipSource.json) { prettyPrint = false }
    }
}

private fun Double.r1() = (this * 10).roundToInt() / 10.0
