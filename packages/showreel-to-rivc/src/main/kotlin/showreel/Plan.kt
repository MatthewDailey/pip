package showreel

/** What a set piece sees of the show at one frame: its actor's `progress`, the scene clock and everything else. */
class Ctx(val progress: Double, val sceneTime: Double, val frame: Frame, val actorId: String?)

/**
 * How one of a piece's clips plays in the showreel.
 *
 * Loops (and mixed loops) run in the piece's own state machine, which starts with the file, so
 * their phase is show time modulo the loop, as the Kotlin painters' `time` is. Everything tied to
 * the choreography is scrubbed from the main timeline through a NestedRemapAnimation.
 */
sealed interface Play {
    val desc: String

    /** A free-running layer in the piece's state machine. */
    data object Loop : Play {
        override val desc = "loop (state machine layer)"
    }

    /** A loop layer mixed by the input `<clip>Mix` (0..100), which the main timeline keys to 100 x [weight]. */
    class Mix(override val desc: String, val weight: (Ctx) -> Double) : Play

    /** Scrubbed by a remap from the main timeline: clip time in seconds (clamped, or wrapped for a loop). */
    class Scrub(override val desc: String, val seconds: (Ctx) -> Double) : Play

    /** Not played in the showreel. */
    class Skip(override val desc: String) : Play
}

/**
 * A clip the converter adds to a piece so that an input the draft only describes ("progress = the
 * root's scale") becomes something the host can drive: [tracks] go linearly from `from` at clip
 * time 0 to `to` at its end, and the main timeline scrubs it with [u] (0..1).
 */
class Synth(val name: String, val tracks: List<SynthTrack>, val desc: String, val u: (Ctx) -> Double)

class SynthTrack(val target: String, val property: String, val from: Double, val to: Double)

/** Per-piece choices on top of what the draft says (see [Plans]). */
class PiecePlan(
    val clips: Map<String, Play> = emptyMap(),
    val synth: List<Synth> = emptyList(),
    /** The piece is drawn only while this holds (Kotlin painters that skip drawing at some progress). */
    val visible: ((Ctx) -> Boolean)? = null,
)
