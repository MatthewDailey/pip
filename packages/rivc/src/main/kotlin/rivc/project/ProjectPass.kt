package rivc.project

import rivc.ClipSpec
import rivc.Control
import rivc.CueSpec
import rivc.InputSpec
import rivc.InstanceSpec
import rivc.LayerSpec
import rivc.MaskSpec
import rivc.PipSource
import rivc.ProjectArtboardSpec
import rivc.ProjectSource
import rivc.RivGraph
import rivc.RivNode
import rivc.Schema
import rivc.StateMachineSpec
import rivc.StateSpec
import rivc.TextSpec
import rivc.TransitionSpec
import rivc.ValueType
import rivc.anim.AnimTargets
import rivc.anim.ClipPass
import rivc.anim.ClipResult
import rivc.anim.StateMachinePass
import rivc.anim.ViewModelResult
import rivc.art.ArtPass
import rivc.spike.CompileException
import java.io.File

/**
 * Compiles a [ProjectSource]: several artboards in one file, placed on each other as nested
 * artboards, with text, events and runtime-bound masks.
 *
 * Stream order: Backboard; font assets; every artboard's enums, view model and default
 * instance (all file-level objects precede the first artboard, or they would take artboard
 * slots); then each artboard in source order, the first being the runtimes' default. Within an
 * artboard: the Artboard; Events; texts and instances (top of the draw order first, so a
 * later list or `stack` entry is written earlier); masks; the art; then each clip as the clip pass writes
 * it followed by its cue keys; the state machine.
 *
 * Recipe (showreel spike, feature 1, both runtimes):
 * - `NestedArtboard.artboardId` is the file-level artboard index (stream order).
 * - `NestedStateMachine.animationId` indexes the nested artboard's state machines; under it,
 *   a `NestedNumber`/`NestedBool`/`NestedTrigger` per input, `inputId` indexing that machine's
 *   inputs. Keying `nestedValue` from the parent's timeline writes straight through to the
 *   live input, so the nested machine's own transitions run (use hold keys). A trigger is
 *   fired by a KeyFrameCallback on `fire`.
 * - `NestedRemapAnimation` (`animationId` indexing the nested artboard's clips) scrubs a
 *   nested clip by `time` (0..1 of its duration) and `mix`, without the nested machine.
 *
 * Every nested reference is to an object of a later artboard, so instances record them and
 * [compile] resolves them by name once every artboard is written.
 */
class ProjectPass(private val schema: Schema = Schema.bundled) {
    private fun fail(msg: String): Nothing = throw CompileException(msg)

    /** One artboard entry with its sections resolved (an included source merged in). */
    private class Board(
        val index: Int,
        val spec: ProjectArtboardSpec,
        val src: PipSource,
        val artDir: File,
        val node: RivNode,
        val machine: RivNode?,
    ) {
        val name get() = src.artboard.name
        val where get() = "artboards.$name"
    }

    private class PendingInput(val node: RivNode, val board: Board, val input: String, val where: String)
    private class PendingRemap(val node: RivNode, val board: Board, val clip: String, val where: String)

    /** Builds the object graph for [project]; relative paths resolve against [baseDir]. */
    fun compile(project: ProjectSource, baseDir: File): RivGraph {
        if (project.artboards.isEmpty()) fail("artboards: a project needs at least one artboard")
        val g = RivGraph(schema)
        val boards = project.artboards.mapIndexed { i, spec -> board(g, i, spec, baseDir) }
        boards.groupBy { it.name }.forEach { (n, l) -> if (l.size > 1) fail("artboards: duplicate artboard '$n'") }
        val byName = boards.associateBy { it.name }
        checkNesting(boards, byName)

        g.add("Backboard")
        val fonts = TextPass(schema).assets(g, project.assets, baseDir)
        val sm = StateMachinePass(schema)
        val vms = boards.map { sm.viewModel(g, it.src) }

        val inputs = mutableListOf<PendingInput>()
        val remaps = mutableListOf<PendingRemap>()
        for (b in boards) artboard(g, b, byName, vms[b.index], fonts, sm, inputs, remaps)

        for (p in inputs) {
            val machine = p.board.machine!!
            val node = g.objects.firstOrNull { it.owner === machine && schema.isA(it.cls, "StateMachineInput") && it.name == p.input }
                ?: fail("${p.where}: '${p.board.name}' has no state machine input '${p.input}'")
            val expected = mapOf("NestedNumber" to "StateMachineNumber", "NestedBool" to "StateMachineBool", "NestedTrigger" to "StateMachineTrigger")
            if (expected[p.node.type] != node.type) fail("${p.where}: input '${p.input}' is a ${node.type}, not a ${expected[p.node.type]}")
            p.node["inputId"] = node
        }
        for (p in remaps) {
            val anim = g.objects.firstOrNull { it.owner === p.board.node && schema.isA(it.cls, "LinearAnimation") && it.name == p.clip }
                ?: fail("${p.where}: '${p.board.name}' has no clip '${p.clip}'")
            p.node["animationId"] = anim
        }
        return g
    }

    private fun board(g: RivGraph, index: Int, spec: ProjectArtboardSpec, baseDir: File): Board {
        val included = spec.source?.let { path ->
            val f = File(baseDir, path)
            if (!f.isFile) fail("artboards[$index].source: no file at $f")
            PipSource.load(f) to f.absoluteFile.parentFile
        }
        val base = included?.first
        val artboard = spec.artboard ?: base?.artboard ?: fail("artboards[$index]: set artboard (name, width, height) or source")
        val clips = spec.clips ?: base?.clips.orEmpty()
        var machine = spec.stateMachine ?: base?.stateMachine
        if (machine == null && clips.isNotEmpty()) machine = defaultMachine(clips.first())
        if (machine != null && spec.control != null) machine = machine.copy(control = spec.control)
        val src = PipSource(
            artboard = artboard,
            art = spec.art ?: base?.art,
            clips = clips,
            viewModel = spec.viewModel ?: base?.viewModel,
            stateMachine = machine,
        )
        val artDir = if (spec.art == null && included != null) included.second else baseDir
        val node = g.create("Artboard", label = artboard.name)
        return Board(index, spec, src, artDir, node, machine?.let { g.create("StateMachine", owner = node) })
    }

    /** `Main`: one layer that plays [clip] from entry, so the clip's events and callbacks are reported. */
    private fun defaultMachine(clip: ClipSpec) = StateMachineSpec(
        name = "Main",
        control = Control.INPUTS,
        layers = listOf(
            LayerSpec(
                name = "Timeline",
                states = listOf(StateSpec(name = clip.name, clip = clip.name)),
                transitions = listOf(TransitionSpec(from = "entry", to = clip.name)),
            ),
        ),
    )

    private fun checkNesting(boards: List<Board>, byName: Map<String, Board>) {
        for (b in boards) for (i in b.spec.instances) {
            val target = byName[i.artboard] ?: fail("${b.where}.instances.${i.id}: no artboard '${i.artboard}' (have ${byName.keys.joinToString()})")
            if (target === b) fail("${b.where}.instances.${i.id}: an artboard can't contain itself")
        }
        val state = HashMap<Board, Int>() // 1 visiting, 2 done
        fun visit(b: Board, path: List<String>) {
            when (state[b]) {
                2 -> return
                1 -> fail("artboards: nesting loops: ${(path + b.name).joinToString(" > ")}")
            }
            state[b] = 1
            for (i in b.spec.instances) visit(byName.getValue(i.artboard), path + b.name)
            state[b] = 2
        }
        boards.forEach { visit(it, emptyList()) }
    }

    /**
     * [b]'s texts and instances bottom to top: its `stack` when set, else every instance under
     * every text, each list in its own order.
     */
    private fun stacked(b: Board): List<Any> {
        val spec = b.spec
        val order = spec.stack ?: return spec.instances + spec.texts
        val items = LinkedHashMap<String, Any>()
        for (i in spec.instances) items[i.id] = i
        for (t in spec.texts) if (items.put(t.id, t) != null) fail("${b.where}: '${t.id}' is both a text and an instance id")
        val seen = HashSet<String>()
        val out = order.map { id ->
            if (!seen.add(id)) fail("${b.where}.stack: '$id' is listed twice")
            items[id] ?: fail("${b.where}.stack: no text or instance '$id'")
        }
        val missing = items.keys - seen
        if (missing.isNotEmpty()) fail("${b.where}.stack: list every text and instance; missing ${missing.joinToString()}")
        return out
    }

    /** The inputs [b]'s state machine really has, as StateMachinePass emits them. */
    private fun machineInputs(b: Board): List<InputSpec> {
        val sm = b.src.stateMachine ?: return emptyList()
        val vmNames = b.src.viewModel?.properties.orEmpty().map { it.name }.toSet()
        return sm.inputs.filter { sm.control == Control.INPUTS || it.name !in vmNames }
    }

    private fun artboard(
        g: RivGraph,
        b: Board,
        byName: Map<String, Board>,
        vm: ViewModelResult,
        fonts: Map<String, TextPass.Font>,
        sm: StateMachinePass,
        inputs: MutableList<PendingInput>,
        remaps: MutableList<PendingRemap>,
    ) {
        val ab = b.node
        val src = b.src
        val spec = b.spec
        g.add(ab.apply {
            ArtPass.artboardProperties(this, src.artboard)
            if (b.machine != null) this["defaultStateMachineId"] = b.machine
            if (vm.viewModel != null) this["viewModelId"] = vm.viewModel
        })

        val clipSpecs = src.clips.associateBy { it.name }
        val keys = CueKeys(clipSpecs)
        val own = LinkedHashMap<String, RivNode>()
        fun register(name: String, node: RivNode, where: String) {
            if (own.put(name, node) != null) fail("$where: duplicate id '$name' on artboard '${b.name}'")
        }

        // Clip events in the state machine section are cues too: the clip pass only keys them in a
        // clip it writes along with its events, and here clips are written one at a time.
        val smClipEvents = src.stateMachine?.events.orEmpty().filter { it.clip != null }
        val cues = spec.events + smClipEvents.map {
            CueSpec(it.name, it.clip!!, listOf(it.frame ?: fail("${b.where}.stateMachine.events.${it.name}: a clip event needs a frame")))
        }
        val events = EventPass(schema).run(g, ab, cues, keys, b.where)

        val layout = LayoutBinding(schema)
        val maskSpecs = LinkedHashMap<String, MaskSpec>()
        for (m in spec.masks) if (maskSpecs.put(m.id, m) != null) fail("${b.where}.masks: duplicate mask '${m.id}'")
        val maskShapes = spec.masks.associate { it.id to layout.create(g, ab, it) }

        val nodes = LinkedHashMap<String, RivNode>()
        for (n in spec.nodes) {
            val where = "${b.where}.nodes.${n.id}"
            val parent = n.parent?.let { nodes[it] ?: fail("$where.parent: no node '$it' listed before it") } ?: ab
            val node = g.add("Node", owner = ab) {
                this["name"] = n.id; this["parentId"] = parent; this["x"] = n.x; this["y"] = n.y
                if (n.rotation != 0.0) this["rotation"] = n.rotation
                if (n.scaleX != 1.0) this["scaleX"] = n.scaleX
                if (n.scaleY != 1.0) this["scaleY"] = n.scaleY
            }
            layout.bind(g, node, n.bind, vm, where)
            nodes[n.id] = node
            register(n.id, node, where)
        }

        val text = TextPass(schema)
        // Top of the draw order first: Rive draws an artboard's drawables last to first.
        for (item in stacked(b).reversed()) when (item) {
            is TextSpec -> {
                val t = item
                val where = "${b.where}.texts.${t.id}"
                val placed = text.text(g, ab, t, fonts, vm, layout, keys, where)
                register(t.id, placed.box, where)
                register("${t.id}/fill", placed.color, where)
                placed.stroke?.let { register("${t.id}/stroke", it, where) }
                register("${t.id}/run", placed.run, where)
            }
            is InstanceSpec -> {
                val parent = item.parent?.let { nodes[it] ?: fail("${b.where}.instances.${item.id}.parent: no node '$it'") } ?: ab
                instance(g, b, item, parent, byName.getValue(item.artboard), vm, layout, maskSpecs, maskShapes, keys, inputs, remaps, ::register)
            }
        }
        for (m in spec.masks) {
            val where = "${b.where}.masks.${m.id}"
            register(m.id, layout.mask(g, ab, m, maskShapes.getValue(m.id), vm, where), where)
        }

        // The art pass draws below everything above.
        val art = ArtPass().emit(g, ab, src, b.artDir)
        for (name in own.keys) if (art.target(name) != null) fail("${b.where}: '$name' is both an art id and a project id")
        val targets = object : AnimTargets {
            override fun target(name: String) = own[name] ?: art.target(name)
            override fun morph(shape: String, svgId: String) = art.morph(shape, svgId)
        }

        // One clip at a time, each followed by its cue keys, so those land in the same animation.
        val clipPass = ClipPass(schema)
        val clips = LinkedHashMap<String, RivNode>()
        val interpolators = LinkedHashMap<rivc.Ease.Cubic, RivNode>()
        for (c in src.clips) {
            val r = clipPass.run(g, ab, src.copy(clips = listOf(c), stateMachine = null), targets)
            clips.putAll(r.clips)
            interpolators.putAll(r.interpolators)
            keys.emit(g, r.clips.getValue(c.name), c.name)
        }
        // State events and transition easings, which the clip pass writes from the state machine section.
        val machine = src.stateMachine
        val events2 = if (machine != null) {
            val rest = clipPass.run(g, ab, src.copy(clips = emptyList(), stateMachine = machine.copy(events = machine.events.filter { it.clip == null })), targets)
            interpolators.putAll(rest.interpolators)
            rest.events
        } else emptyMap()
        if (b.machine != null) sm.stateMachine(g, b.machine, src, vm, ClipResult(clips, events + events2, interpolators))
    }

    private fun instance(
        g: RivGraph,
        b: Board,
        inst: InstanceSpec,
        parent: RivNode,
        target: Board,
        vm: ViewModelResult,
        layout: LayoutBinding,
        maskSpecs: Map<String, MaskSpec>,
        maskShapes: Map<String, List<RivNode>>,
        keys: CueKeys,
        inputs: MutableList<PendingInput>,
        remaps: MutableList<PendingRemap>,
        register: (String, RivNode, String) -> Unit,
    ) {
        val ab = b.node
        val where = "${b.where}.instances.${inst.id}"
        val masks = (listOfNotNull(inst.mask) + inst.masks).map { maskSpecs[it] ?: fail("$where.mask: no mask '$it'") }
        // A clip applies to a group's drawables, so a masked instance sits in its own group.
        val group = if (masks.isNotEmpty()) {
            g.add("Node", owner = ab) { this["name"] = "${inst.id}Group"; this["parentId"] = parent }
        } else parent
        val nested = g.add("NestedArtboard", owner = ab) {
            this["name"] = inst.id
            this["parentId"] = group
            this["x"] = inst.x; this["y"] = inst.y
            if (inst.scale != 1.0) { this["scaleX"] = inst.scale; this["scaleY"] = inst.scale }
            if (inst.rotation != 0.0) this["rotation"] = inst.rotation
            if (inst.opacity != 1.0) this["opacity"] = inst.opacity
            this["artboardId"] = target.node
        }
        layout.bind(g, nested, inst.bind, vm, where)
        register(inst.id, nested, where)

        // Remaps scrub clips directly; the nested machine would fight them unless asked for.
        val wantsMachine = inst.stateMachine != null || inst.remaps.isEmpty()
        val targetMachine = target.src.stateMachine
        if (wantsMachine) {
            if (targetMachine == null || target.machine == null) {
                if (inst.stateMachine != null || inst.inputs.isNotEmpty() || inst.fire.isNotEmpty()) {
                    fail("$where: artboard '${target.name}' has no state machine")
                }
            } else {
                if (inst.stateMachine != null && inst.stateMachine != targetMachine.name) {
                    fail("$where.stateMachine: '${target.name}' has state machine '${targetMachine.name}', not '${inst.stateMachine}'")
                }
                val nsm = g.add("NestedStateMachine", owner = ab) {
                    this["name"] = targetMachine.name; this["parentId"] = nested; this["animationId"] = target.machine
                }
                val available = machineInputs(target).associateBy { it.name }
                for (name in inst.inputs.keys + inst.fire.map { it.input }) {
                    if (name !in available) {
                        val hint = if (targetMachine.control == Control.VIEW_MODEL) "; nested artboards are driven through inputs, so give it control: inputs" else ""
                        fail("$where: '${target.name}' has no state machine input '$name' (has ${available.keys.joinToString()})$hint")
                    }
                }
                for (input in available.values) {
                    val at = "$where.inputs.${input.name}"
                    val start = inst.inputs[input.name]
                    val node = g.add(
                        when (input.type) {
                            ValueType.NUMBER -> "NestedNumber"
                            ValueType.BOOLEAN -> "NestedBool"
                            ValueType.TRIGGER -> "NestedTrigger"
                            else -> fail("$at: inputs are number, boolean or trigger")
                        },
                        owner = ab,
                    ) {
                        this["name"] = input.name
                        this["parentId"] = nsm
                        when (input.type) {
                            ValueType.NUMBER -> this["nestedValue"] = start ?: input.default ?: 0.0
                            ValueType.BOOLEAN -> this["nestedValue"] = start?.let { it != 0.0 } ?: input.defaultBool ?: false
                            else -> if (start != null) fail("$at: a trigger has no starting value")
                        }
                    }
                    inputs += PendingInput(node, target, input.name, at)
                    register("${inst.id}/${input.name}", node, at)
                }
                val fire = schema.property("NestedTrigger", "fire")
                for (f in inst.fire) {
                    val at = "$where.fire.${f.input}"
                    if (available.getValue(f.input).type != ValueType.TRIGGER) fail("$at: '${f.input}' is not a trigger; key a number or boolean in a clip track")
                    if (f.frames.isEmpty()) fail("$at: no frames")
                    val node = g.objects.last { it.owner === ab && it.name == f.input && schema.isA(it.cls, "NestedTrigger") && (it["parentId"] as? rivc.GValue.Ref)?.target === nsm }
                    for (frame in f.frames) keys.add(f.clip, node, fire, CueKeys.Callback(frame), at)
                }
            }
        }
        for (r in inst.remaps) {
            val id = r.id ?: r.clip
            val at = "$where.remaps.$id"
            if (r.time !in 0.0..1.0 || r.mix !in 0.0..1.0) fail("$at: time and mix are 0 to 1")
            val node = g.add("NestedRemapAnimation", owner = ab) {
                this["name"] = id; this["parentId"] = nested; this["mix"] = r.mix; this["time"] = r.time
            }
            remaps += PendingRemap(node, target, r.clip, at)
            register("${inst.id}/$id", node, at)
        }
        // Every mask's clips sit on the one group, and clips on a group intersect.
        masks.forEachIndexed { i, mask ->
            layout.clip(g, ab, group, maskShapes.getValue(mask.id), mask, if (i == 0) "${inst.id}Clip" else "${inst.id}Clip-${mask.id}")
        }
    }
}
