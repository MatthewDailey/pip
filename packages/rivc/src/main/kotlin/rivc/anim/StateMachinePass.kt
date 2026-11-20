package rivc.anim

import rivc.Argb
import rivc.ConditionSpec
import rivc.Control
import rivc.Ease
import rivc.EventSpec
import rivc.LayerSpec
import rivc.Op
import rivc.PipSource
import rivc.RivGraph
import rivc.RivNode
import rivc.Schema
import rivc.StateSpec
import rivc.TransitionSpec
import rivc.ValueType
import rivc.VmPropertySpec
import rivc.spike.CompileException

/** A view-model property as placed: its node, type and (for an enum) its value nodes by name. */
class VmProp(val node: RivNode, val type: ValueType, val enumValues: Map<String, RivNode> = emptyMap())

/** The file-level half of [StateMachinePass]: what [StateMachinePass.viewModel] placed. */
class ViewModelResult(val viewModel: RivNode?, val props: Map<String, VmProp>)

/**
 * Emits the behaviour half of a [PipSource]: the view model (file level, before the
 * artboard) and the artboard's state machine.
 *
 * What the app drives is chosen by [rivc.StateMachineSpec.control], per name: with
 * `viewModel` a condition or blend names a view-model property, with `inputs` a state
 * machine input; a name only the other kind has falls back to it. A name is never
 * emitted as both: in `viewModel` control an input that shares a view-model property's
 * name is dropped, and in `inputs` control such a view-model property is (the spike found
 * an idle input condition kept matching and fought every view-model change).
 *
 * Stream layout, from rive-runtime's importers:
 * - Enums (DataEnumCustom then its DataEnumValues) come first: a ViewModelPropertyEnumCustom
 *   resolves `enumId` against the enums already read. Then the ViewModel, its properties,
 *   the default ViewModelInstance and its values, all before the artboard (an instance
 *   value is a Component and would otherwise take an artboard slot).
 * - Each layer writes its states Any, Entry, Exit, then its own; after each state come its
 *   fire events (they attach to the latest state-or-transition), its blend animations, and
 *   its transitions, each followed by its conditions.
 * - A view-model condition is the editor's 5-object pattern: TransitionViewModelCondition,
 *   a BindableProperty, a DataBindContext pointing it at the view-model property, a
 *   TransitionPropertyViewModelComparator (left, reads the bindable) and a value comparator
 *   (right, the constant).
 * - A view-model 1D blend (BlendState1DViewModel) and a view-model direct blend clip
 *   (BlendAnimationDirect with blendSource 2) take the latest BindableProperty when they
 *   are read, so a fresh bindable and its DataBindContext are written just before each.
 */
class StateMachinePass(private val schema: Schema = Schema.bundled) {
    private fun fail(msg: String): Nothing = throw CompileException(msg)

    /** Names of view-model properties to leave out because the inputs control owns them. */
    private fun droppedVmNames(src: PipSource): Set<String> {
        val sm = src.stateMachine ?: return emptySet()
        return if (sm.control == Control.INPUTS) sm.inputs.map { it.name }.toSet() else emptySet()
    }

    /** Emits enums, the view model and its default instance. Call before adding the artboard. */
    fun viewModel(g: RivGraph, src: PipSource): ViewModelResult {
        val spec = src.viewModel ?: return ViewModelResult(null, emptyMap())
        val dropped = droppedVmNames(src)
        val specs = spec.properties.filter { it.name !in dropped }
        if (specs.isEmpty()) return ViewModelResult(null, emptyMap())
        specs.groupBy { it.name }.forEach { (n, l) -> if (l.size > 1) fail("viewModel: duplicate property '$n'") }

        // Enums first.
        val enums = LinkedHashMap<String, Pair<RivNode, Map<String, RivNode>>>()
        for (p in specs.filter { it.type == ValueType.ENUM }) {
            val where = "viewModel.${p.name}"
            if (p.values.isEmpty()) fail("$where: an enum needs values")
            if (p.values.toSet().size != p.values.size) fail("$where: enum values must be unique")
            val e = g.add("DataEnumCustom") { this["name"] = p.name }
            enums[p.name] = e to p.values.associateWith { v -> g.add("DataEnumValue", owner = e) { this["key"] = v } }
        }

        val vm = g.add("ViewModel") { this["name"] = spec.name }
        val props = LinkedHashMap<String, VmProp>()
        for (p in specs) {
            val type = when (p.type) {
                ValueType.NUMBER -> "ViewModelPropertyNumber"
                ValueType.BOOLEAN -> "ViewModelPropertyBoolean"
                ValueType.TRIGGER -> "ViewModelPropertyTrigger"
                ValueType.ENUM -> "ViewModelPropertyEnumCustom"
                ValueType.STRING -> "ViewModelPropertyString"
                ValueType.COLOR -> "ViewModelPropertyColor"
            }
            val node = g.add(type, owner = vm) {
                this["name"] = p.name
                if (p.type == ValueType.ENUM) this["enumId"] = enums.getValue(p.name).first
            }
            props[p.name] = VmProp(node, p.type, enums[p.name]?.second.orEmpty())
        }
        val instance = g.add("ViewModelInstance") { this["name"] = spec.instance; this["viewModelId"] = vm }
        for (p in specs) instanceValue(g, instance, p, props.getValue(p.name))
        return ViewModelResult(vm, props)
    }

    private fun instanceValue(g: RivGraph, instance: RivNode, p: VmPropertySpec, prop: VmProp) {
        val where = "viewModel.${p.name}"
        fun only(vararg allowed: String) {
            val set = mapOf("default" to p.default, "defaultBool" to p.defaultBool, "defaultValue" to p.defaultValue)
                .filterValues { it != null }.keys
            val bad = set - allowed.toSet()
            if (bad.isNotEmpty()) fail("$where: a ${p.type.name.lowercase()} property doesn't take ${bad.joinToString()}")
            if (p.values.isNotEmpty() && p.type != ValueType.ENUM) fail("$where: only an enum takes values")
        }
        when (p.type) {
            ValueType.NUMBER -> {
                only("default")
                g.add("ViewModelInstanceNumber", owner = instance) {
                    this["viewModelPropertyId"] = prop.node; this["propertyValue"] = p.default ?: 0.0
                }
            }
            ValueType.BOOLEAN -> {
                only("defaultBool")
                g.add("ViewModelInstanceBoolean", owner = instance) {
                    this["viewModelPropertyId"] = prop.node; this["propertyValue"] = p.defaultBool ?: false
                }
            }
            ValueType.TRIGGER -> {
                only()
                g.add("ViewModelInstanceTrigger", owner = instance) { this["viewModelPropertyId"] = prop.node }
            }
            ValueType.ENUM -> {
                only("defaultValue")
                val default = p.defaultValue ?: p.values.first()
                val value = prop.enumValues[default] ?: fail("$where: default '$default' is not one of ${p.values}")
                g.add("ViewModelInstanceEnum", owner = instance) {
                    this["viewModelPropertyId"] = prop.node; this["propertyValue"] = value
                }
            }
            ValueType.STRING -> {
                only("defaultValue")
                g.add("ViewModelInstanceString", owner = instance) {
                    this["viewModelPropertyId"] = prop.node; this["propertyValue"] = p.defaultValue ?: ""
                }
            }
            ValueType.COLOR -> {
                only("defaultValue")
                g.add("ViewModelInstanceColor", owner = instance) {
                    this["viewModelPropertyId"] = prop.node
                    this["propertyValue"] = Argb(parseColor(p.defaultValue ?: "#FF000000", where))
                }
            }
        }
    }

    /** An input as placed. */
    private class Input(val node: RivNode, val type: ValueType)

    /** Everything a layer needs to resolve names. */
    private inner class Ctx(
        val g: RivGraph,
        val machine: RivNode,
        val control: Control,
        val vm: ViewModelResult,
        val inputs: Map<String, Input>,
        val clips: ClipResult,
        val events: List<EventSpec>,
    ) {
        /** What [name] drives: a view-model property or an input, preferring [control]'s kind. */
        fun source(name: String, where: String): Any {
            val p = vm.props[name]
            val i = inputs[name]
            return when (control) {
                Control.VIEW_MODEL -> p ?: i
                Control.INPUTS -> i ?: p
            } ?: fail("$where: no view-model property or state machine input '$name'")
        }
    }

    /**
     * Emits the state machine. [machine] was created (not added) before the artboard so the
     * artboard could name it as its default.
     */
    fun stateMachine(g: RivGraph, machine: RivNode, src: PipSource, vm: ViewModelResult, clips: ClipResult) {
        val sm = src.stateMachine ?: return
        g.add(machine.apply { this["name"] = sm.name })

        val inputs = LinkedHashMap<String, Input>()
        for (i in sm.inputs) {
            if (sm.control == Control.VIEW_MODEL && i.name in vm.props) continue
            if (i.name in inputs) fail("stateMachine.inputs: duplicate input '${i.name}'")
            inputs[i.name] = Input(
                when (i.type) {
                    ValueType.NUMBER -> g.add("StateMachineNumber", owner = machine) { this["name"] = i.name; this["value"] = i.default ?: 0.0 }
                    ValueType.BOOLEAN -> g.add("StateMachineBool", owner = machine) { this["name"] = i.name; this["value"] = i.defaultBool ?: false }
                    ValueType.TRIGGER -> g.add("StateMachineTrigger", owner = machine) { this["name"] = i.name }
                    else -> fail("stateMachine.inputs.${i.name}: inputs are number, boolean or trigger, not ${i.type.name.lowercase()}")
                },
                i.type,
            )
        }

        for (e in sm.events) {
            val kinds = listOfNotNull(e.onEnterState, e.onExitState, e.clip)
            if (kinds.size != 1) fail("stateMachine.events.${e.name}: set exactly one of onEnterState, onExitState or clip")
            if (e.clip == null && e.frame != null) fail("stateMachine.events.${e.name}: frame only applies to a clip event")
            val state = e.onEnterState ?: e.onExitState
            if (state != null) {
                val layers = sm.layers.filter { l -> (e.layer == null || l.name == e.layer) && l.states.any { it.name == state } }
                if (layers.size != 1) {
                    fail("stateMachine.events.${e.name}: state '$state' " + if (layers.isEmpty()) "not found" else "is in several layers; set layer")
                }
            }
        }

        val ctx = Ctx(g, machine, sm.control, vm, inputs, clips, sm.events)
        sm.layers.groupBy { it.name }.forEach { (n, l) -> if (l.size > 1) fail("stateMachine.layers: duplicate layer '$n'") }
        for (layer in sm.layers) layer(ctx, layer)
    }

    private fun layer(ctx: Ctx, spec: LayerSpec) {
        val g = ctx.g
        val where = "stateMachine.layers.${spec.name}"
        val layer = g.add("StateMachineLayer", owner = ctx.machine) { this["name"] = spec.name }
        // Each layer's states are numbered Any 0, Entry 1, Exit 2, then the layer's own.
        val states = LinkedHashMap<String, RivNode>()
        val stateSpecs = HashMap<String, StateSpec>()
        states["any"] = g.create("AnyState", owner = layer, label = "any")
        states["entry"] = g.create("EntryState", owner = layer, label = "entry")
        states["exit"] = g.create("ExitState", owner = layer, label = "exit")
        for (s in spec.states) {
            val at = "$where.states.${s.name}"
            if (s.name in states) fail("$where.states: '${s.name}' is reserved or duplicated")
            val kinds = listOfNotNull(s.clip, s.blend, s.direct.ifEmpty { null })
            if (kinds.size > 1) fail("$at: set at most one of clip, blend or direct")
            val type = when {
                s.blend != null -> when (ctx.source(s.blend.by, "$at.blend")) {
                    is VmProp -> "BlendState1DViewModel"
                    else -> "BlendState1DInput"
                }
                s.direct.isNotEmpty() -> "BlendStateDirect"
                else -> "AnimationState"
            }
            if (s.speed != null && type != "AnimationState") fail("$at: speed applies to clip states only")
            states[s.name] = g.create(type, owner = layer, label = s.name)
            stateSpecs[s.name] = s
        }
        for (t in spec.transitions) {
            if (t.from !in states || t.from == "exit") fail("$where.transitions: bad source state '${t.from}'")
            if (t.to !in states || t.to == "any" || t.to == "entry") fail("$where.transitions: bad target state '${t.to}'")
        }
        if (spec.transitions.none { it.from == "entry" }) fail("$where: no transition from entry, so the layer never starts")

        // A transition belongs to the state written just before it.
        for ((name, state) in states) {
            val s = stateSpecs[name]
            state(ctx, layer, state, s, "$where.states.$name")
            for (e in ctx.events) {
                if (e.layer != null && e.layer != spec.name) continue
                val occurs = when (name) {
                    e.onEnterState -> 0
                    e.onExitState -> 1
                    else -> continue
                }
                g.add("StateMachineFireEvent", owner = state) {
                    this["occursValue"] = occurs; this["eventId"] = ctx.clips.events.getValue(e.name)
                }
            }
            if (s != null) blendAnimations(ctx, state, s, "$where.states.$name")
            for (t in spec.transitions.filter { it.from == name }) {
                transition(ctx, state, states.getValue(t.to), t, "$where.${t.from}->${t.to}")
            }
        }
    }

    private fun clip(ctx: Ctx, name: String, where: String): RivNode = ctx.clips.clips[name] ?: fail("$where: no clip '$name'")

    /** Adds [state], preceded by the bindable a view-model 1D blend reads. */
    private fun state(ctx: Ctx, layer: RivNode, state: RivNode, s: StateSpec?, where: String) {
        val g = ctx.g
        if (s?.blend != null) {
            val src = ctx.source(s.blend.by, "$where.blend")
            if (src is VmProp) {
                if (src.type != ValueType.NUMBER) fail("$where.blend: '${s.blend.by}' must be a number")
                bindable(ctx, layer, src, "BindablePropertyNumber")
            }
            g.add(state.apply {
                this["flags"] = LAYER_STATE_RESET
                if (src is Input) {
                    if (src.type != ValueType.NUMBER) fail("$where.blend: input '${s.blend.by}' must be a number")
                    this["inputId"] = src.node
                }
            })
            return
        }
        g.add(state.apply {
            if (s?.clip != null) this["animationId"] = clip(ctx, s.clip, where)
            if (s?.speed != null) this["speed"] = s.speed
        })
    }

    private fun blendAnimations(ctx: Ctx, state: RivNode, s: StateSpec, where: String) {
        val g = ctx.g
        s.blend?.let { b ->
            if (b.clips.isEmpty()) fail("$where.blend: no clips")
            if (b.clips.map { it.value }.toSet().size != b.clips.size) fail("$where.blend: each clip needs its own value")
            // The runtime binary-searches the blend's clips by value, so they must be ascending.
            for (c in b.clips.sortedBy { it.value }) {
                g.add("BlendAnimation1D", owner = state) { this["animationId"] = clip(ctx, c.clip, "$where.blend"); this["value"] = c.value }
            }
        }
        for (c in s.direct) {
            val at = "$where.direct.${c.clip}"
            if ((c.by == null) == (c.mix == null)) fail("$at: set exactly one of by or mix")
            val anim = clip(ctx, c.clip, at)
            if (c.mix != null) {
                g.add("BlendAnimationDirect", owner = state) { this["animationId"] = anim; this["blendSource"] = 1; this["mixValue"] = c.mix }
                continue
            }
            when (val src = ctx.source(c.by!!, at)) {
                is VmProp -> {
                    if (src.type != ValueType.NUMBER) fail("$at: '${c.by}' must be a number")
                    bindable(ctx, state, src, "BindablePropertyNumber")
                    g.add("BlendAnimationDirect", owner = state) { this["animationId"] = anim; this["blendSource"] = 2 }
                }
                is Input -> {
                    if (src.type != ValueType.NUMBER) fail("$at: input '${c.by}' must be a number")
                    g.add("BlendAnimationDirect", owner = state) { this["animationId"] = anim; this["blendSource"] = 0; this["inputId"] = src.node }
                }
            }
        }
    }

    /** A bindable of [type] bound to view-model property [prop], followed by its DataBindContext. */
    private fun bindable(ctx: Ctx, owner: RivNode, prop: VmProp, type: String): RivNode {
        val b = ctx.g.add(type, owner = owner)
        ctx.g.add("DataBindContext", owner = b) {
            this["propertyKey"] = schema.property(type, "propertyValue")
            this["flags"] = 0
            this["sourcePathIds"] = listOf(ctx.vm.viewModel!!, prop.node)
        }
        return b
    }

    private fun transition(ctx: Ctx, from: RivNode, to: RivNode, t: TransitionSpec, where: String) {
        if (t.exitTimePercent != null && t.exitTimeMs != null) fail("$where: set exitTimePercent or exitTimeMs, not both")
        if (t.durationMs < 0) fail("$where: durationMs can't be negative")
        var flags = 0
        if (t.exitTimePercent != null || t.exitTimeMs != null) flags = flags or ENABLE_EXIT_TIME
        if (t.exitTimePercent != null) flags = flags or EXIT_TIME_IS_PERCENTAGE
        if (t.pauseOnExit) flags = flags or PAUSE_ON_EXIT
        if (t.earlyExit) flags = flags or ENABLE_EARLY_EXIT
        val transition = ctx.g.add("StateTransition", owner = from) {
            this["stateToId"] = to
            this["flags"] = flags
            this["duration"] = t.durationMs
            this["exitTime"] = t.exitTimePercent ?: t.exitTimeMs ?: 0
            when (val e = t.ease) {
                Ease.Linear -> {}
                Ease.Hold -> this["interpolationType"] = 0
                is Ease.Cubic -> { this["interpolationType"] = 2; this["interpolatorId"] = ctx.clips.interpolators.getValue(e) }
            }
        }
        for (c in t.conditions) {
            val at = "$where[${c.on}]"
            when (val src = ctx.source(c.on, at)) {
                is Input -> inputCondition(ctx, transition, c, src, at)
                is VmProp -> vmCondition(ctx, transition, c, src, at)
            }
        }
    }

    private fun checkFields(c: ConditionSpec, at: String, vararg allowed: String) {
        val set = mapOf("value" to c.value, "bool" to c.bool, "is" to c.match).filterValues { it != null }.keys
        val bad = set - allowed.toSet()
        if (bad.isNotEmpty()) fail("$at: this condition doesn't take ${bad.joinToString()}")
    }

    private fun inputCondition(ctx: Ctx, transition: RivNode, c: ConditionSpec, input: Input, at: String) {
        val g = ctx.g
        when (input.type) {
            ValueType.TRIGGER -> {
                checkFields(c, at)
                g.add("TransitionTriggerCondition", owner = transition) { this["inputId"] = input.node }
            }
            ValueType.NUMBER -> {
                checkFields(c, at, "value")
                g.add("TransitionNumberCondition", owner = transition) {
                    this["inputId"] = input.node; this["opValue"] = c.op.value
                    this["value"] = c.value ?: fail("$at: a number condition needs a value")
                }
            }
            ValueType.BOOLEAN -> {
                checkFields(c, at, "bool")
                if (c.op != Op.EQ && c.op != Op.NE) fail("$at: a boolean compares with == or !=")
                val want = c.bool ?: fail("$at: a boolean condition needs bool")
                // The runtime's bool condition is "input is true" (==) or "input is false" (!=).
                val isTrue = want == (c.op == Op.EQ)
                g.add("TransitionBoolCondition", owner = transition) {
                    this["inputId"] = input.node; this["opValue"] = if (isTrue) Op.EQ.value else Op.NE.value
                }
            }
            else -> fail("$at: unexpected input type ${input.type}")
        }
    }

    private fun vmCondition(ctx: Ctx, transition: RivNode, c: ConditionSpec, prop: VmProp, at: String) {
        val g = ctx.g
        val (bindableType, rightType) = when (prop.type) {
            ValueType.TRIGGER -> "BindablePropertyTrigger" to "TransitionValueTriggerComparator"
            ValueType.NUMBER -> "BindablePropertyNumber" to "TransitionValueNumberComparator"
            ValueType.BOOLEAN -> "BindablePropertyBoolean" to "TransitionValueBooleanComparator"
            ValueType.ENUM -> "BindablePropertyEnum" to "TransitionValueEnumComparator"
            ValueType.STRING -> "BindablePropertyString" to "TransitionValueStringComparator"
            ValueType.COLOR -> "BindablePropertyColor" to "TransitionValueColorComparator"
        }
        when (prop.type) {
            ValueType.TRIGGER -> checkFields(c, at)
            ValueType.NUMBER -> checkFields(c, at, "value")
            ValueType.BOOLEAN -> checkFields(c, at, "bool")
            else -> checkFields(c, at, "is")
        }
        if (prop.type !in setOf(ValueType.NUMBER, ValueType.TRIGGER) && c.op != Op.EQ && c.op != Op.NE) {
            fail("$at: a ${prop.type.name.lowercase()} compares with == or !=")
        }
        val op = if (prop.type == ValueType.TRIGGER) Op.EQ else c.op
        val cond = g.add("TransitionViewModelCondition", owner = transition) { this["opValue"] = op.value }
        bindable(ctx, cond, prop, bindableType)
        g.add("TransitionPropertyViewModelComparator", owner = cond)
        g.add(rightType, owner = cond) {
            when (prop.type) {
                ValueType.TRIGGER -> {}
                ValueType.NUMBER -> this["value"] = c.value ?: fail("$at: a number condition needs a value")
                ValueType.BOOLEAN -> this["value"] = c.bool ?: fail("$at: a boolean condition needs bool")
                ValueType.ENUM -> {
                    val name = c.match ?: fail("$at: an enum condition needs is (one of ${prop.enumValues.keys})")
                    this["value"] = prop.enumValues[name] ?: fail("$at: '$name' is not one of ${prop.enumValues.keys}")
                }
                ValueType.STRING -> this["value"] = c.match ?: fail("$at: a string condition needs is")
                ValueType.COLOR -> this["value"] = Argb(parseColor(c.match ?: fail("$at: a colour condition needs is"), at))
            }
        }
    }

    companion object {
        // StateTransitionFlags
        const val ENABLE_EXIT_TIME = 1 shl 2
        const val EXIT_TIME_IS_PERCENTAGE = 1 shl 3
        const val PAUSE_ON_EXIT = 1 shl 4
        const val ENABLE_EARLY_EXIT = 1 shl 5

        /**
         * LayerStateFlags.Reset. A 1D blend mixes its two neighbouring clips one after the
         * other into the property's current value, so without a baseline the result drifts
         * off the proportional mix (lookX 0 between -30 and 30 settled at +10 in the web
         * runtime). Reset seeds each frame from the first (lowest) clip, which makes a
         * two-clip blend exactly proportional.
         */
        const val LAYER_STATE_RESET = 1 shl 1
    }
}
