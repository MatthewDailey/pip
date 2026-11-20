package showreel

import kotlin.math.floor

/** Where an actor's set piece sits on the stage. */
sealed interface Placement {
    /** At the actor's x, y (or Pip-relative for `anchorToPip`), rotation and scale. */
    data object AtActor : Placement

    /**
     * Rides on Pip like part of him: the piece's (0, 0) at box point ([bx], [by]) of his 540 x 660 box,
     * under his whole transform. [scaleByProgress] also scales it by the actor's progress about that point.
     */
    data class PipBox(val bx: Double, val by: Double, val scaleByProgress: Boolean = false) : Placement

    /** Pip's sunglasses (his `sunglasses` value, not an actor): drop 0..1, hidden below 0. */
    data object Sunglasses : Placement

    /** `flagPaint`: in Pip's right fist below progress 0.5, planted at the actor after. */
    data object Flag : Placement

    /** Set [slot] of a panel actor's [sets]: progress n.f slides set n out and n+1 in (a whip pan). */
    data class PanelSet(val slot: Int, val sets: Int, val vertical: List<Boolean>) : Placement
}

/** One set piece an actor draws. */
class Use(val piece: String, val placement: Placement = Placement.AtActor)

/** What an actor becomes: set pieces, a text, or nothing (with why). */
sealed interface ActorMapping {
    val rule: String

    class Pieces(val uses: List<Use>, override val rule: String, val panelMask: Boolean = false) : ActorMapping
    class Bubble(override val rule: String) : ActorMapping
    class Banner(override val rule: String) : ActorMapping
    class Shout(val fill: String, val size: Double, val maxWidth: Double, val jitter: Double, override val rule: String) : ActorMapping
    class Unmatched(override val rule: String) : ActorMapping
}

/**
 * The actor-to-piece table. Non-CUSTOM kinds map to their fx piece; CUSTOM actors are matched by
 * scene plus their painter (or the declaring source line), against the scenery agents' piece names.
 */
object Mapping {
    private val kinds = mapOf(
        "SMOKE" to "fx-smoke", "SLASH" to "fx-slash", "SCAM_CARD" to "fx-scam-card", "RAIN_CLOUD" to "fx-rain-cloud",
        "SWEAT" to "fx-sweat", "SPOTLIGHT" to "fx-spotlight", "SPARKLE" to "fx-sparkle", "CONFETTI" to "fx-confetti",
        "DISCO_BALL" to "fx-disco-ball", "MOON" to "fx-moon", "PEACE_BG" to "fx-peace-bg", "NEON_GRID" to "fx-neon-grid",
        "DOOR" to "fx-door", "HEART" to "fx-heart",
    )

    /** CUSTOM rules: scene (or `*`), a painter name or a substring of the declaring line, and the mapping. */
    private class Rule(val scene: String, val match: String, val make: (ShowExport.Actor) -> ActorMapping)

    private fun piece(p: String, placement: Placement = Placement.AtActor): (ShowExport.Actor) -> ActorMapping =
        { ActorMapping.Pieces(listOf(Use(p, placement)), "piece $p") }

    private fun panel(sets: List<String?>, vertical: List<Boolean>, frame: String?): (ShowExport.Actor) -> ActorMapping = {
        val uses = sets.mapIndexedNotNull { i, s -> s?.let { Use(it, Placement.PanelSet(i, sets.size, vertical)) } } +
            listOfNotNull(frame?.let { Use(it) })
        ActorMapping.Pieces(uses, "panel ${sets.joinToString("/") { it ?: "-" }}${frame?.let { " + $it" } ?: ""}", panelMask = true)
    }

    private val rules = listOf(
        // 1960s: the framed panel (moon, zebra crossing, comic), the starburst wipe, the flag, the moon boots, the helmet.
        Rule("d60s", "panelPaint") { a ->
            panel(listOf("set-d60s-moon", "set-d60s-zebra", "set-d60s-comic"), listOf(false, false), "set-d60s-frame")(a)
        },
        Rule("d60s", "starburstPaint", piece("set-d60s-starburst")),
        Rule("d60s", "flagPaint", piece("set-d60s-flag", Placement.Flag)),
        Rule("d60s", "feetPaint", piece("set-d60s-shoes", Placement.PipBox(270.0, 672.0))),
        Rule("d60s", "helmetPaint", piece("set-d60s-helmet", Placement.PipBox(285.0, 380.0, scaleByProgress = true))),
        Rule("d60s", "drawRoundRect(-PHW, -PHH", piece(Generated.FLASH_PANEL)),
        Rule("d60s", "custom(2, paint)", piece("set-d60s-banner")),
        Rule("d60s", "burstPaint") { a ->
            // POW, BAM and ZAP are told apart by where they land (the drafts' origins).
            val p = mapOf("set-d60s-burst-pow" to (860.0 to 990.0), "set-d60s-burst-bam" to (720.0 to 900.0), "set-d60s-burst-zap" to (880.0 to 1080.0))
                .minBy { (_, o) -> Math.hypot(o.first - (a.base["x"] as Double), o.second - (a.base["y"] as Double)) }.key
            piece(p)(a)
        },
        // 1970s: sea / disco / steps panel with the sea's front waves on their own panel, the fin, two splashes, the banner.
        Rule("d70s", "panelPaint") { a ->
            if (a.layer < 0) panel(listOf("set-d70s-sea", "set-d70s-disco", "set-d70s-steps"), listOf(true, false), "set-d60s-frame")(a)
            else panel(listOf("set-d70s-sea-front", null, null), listOf(true, false), null)(a)
        },
        Rule("d70s", "finPaint", piece("set-d70s-fin")),
        Rule("d70s", "splashPaint", piece("set-d70s-splash")),
        Rule("d70s", "custom(2, paint)", piece("set-d70s-banner")),
        // 1980s and 1990s: painters are named values.
        Rule("d80s", "NEON80", piece("set-d80s-neon")),
        Rule("d80s", "MAZE", piece("set-d80s-maze")),
        Rule("d80s", "GHOST", piece("set-d80s-ghost")),
        Rule("*", "STREAKS", piece("set-d80s-streaks")),
        Rule("d80s", "MOON", piece("set-d80s-moon")),
        Rule("d80s", "BIKE", piece("set-d80s-bike")),
        Rule("d80s", "BASKET", piece("set-d80s-basket")),
        Rule("d80s", "SPEEDO", piece("set-d80s-speedo")),
        Rule("d80s", "ZAP", piece("set-d80s-zap")),
        Rule("d80s", "TRAILS", piece("set-d80s-trails")),
        Rule("d80s", "FLASH", piece(Generated.FLASH_SCREEN)),
        Rule("d90s", "BANNER90", piece("set-d90s-banner")),
        Rule("d90s", "MIRROR_BACK", piece("set-d90s-mirror-back")),
        Rule("d90s", "MIRROR_FRONT", piece("set-d90s-mirror-front")),
        Rule("d90s", "SCREAM", piece("set-d90s-scream")),
        Rule("d90s", "SUNSET", piece("set-d90s-sunset")),
        Rule("d90s", "BOW", piece("set-d90s-bow")),
        Rule("d90s", "WIND", piece("set-d90s-wind")),
        Rule("d90s", "MATRIX", piece("set-d90s-matrix")),
        Rule("d90s", "ORBIT", piece("set-d90s-orbit")),
        Rule("d90s", "TINT", piece("set-d90s-tint")),
        Rule("d90s", "BULLET", piece("set-d90s-bullet")),
        // 2000s, 2010s, today: painters are lambdas, matched by the function they call.
        Rule("d00s", "paintCavern", piece("set-d00s-cavern")),
        Rule("d00s", "paintWizardHat", piece("set-d00s-wizard-hat")),
        Rule("d00s", "paintStaff", piece("set-d00s-staff")),
        Rule("d00s", "paintBurst", piece("set-d00s-burst")),
        Rule("d00s", "paintField", piece("set-d00s-field")),
        Rule("d00s", "paintDustTrail", piece("set-d00s-dust-trail")),
        Rule("d00s", "paintBrawl", piece("set-d00s-brawl")),
        Rule("d00s", "val bars = custom", piece("set-d00s-signal")),
        Rule("d10s", "paintRoom", piece("set-d10s-room")),
        Rule("d10s", "front = false", piece("set-d10s-fire-back")),
        Rule("d10s", "front = true", piece("set-d10s-fire-front")),
        Rule("d10s", "paintMug", piece("set-d10s-mug")),
        Rule("d10s", "paintTipFlame", piece("set-d10s-tip-flame")),
        Rule("d10s", "paintParty", piece("set-d10s-party")),
        Rule("d10s", "paintLasso", piece("set-d10s-lasso")),
        Rule("dToday", "paintCorridor", piece("set-dtoday-corridor")),
        Rule("dToday", "paintDoor", piece("set-dtoday-door")),
        Rule("dToday", "paintDust", piece("set-dtoday-dust")),
        Rule("dToday", "paintYard", piece("set-dtoday-yard")),
        Rule("dToday", "paintDoll", piece("set-dtoday-doll")),
        // Shouted words: set-d00s-leeroy's glyphs are data-text placeholders, so every shout is a rivc text.
        Rule("*", "SHOUT_GOLD") { ActorMapping.Shout("#FFFFC93C", 170.0, 1000.0, 4.0, "shout text (set-d00s-leeroy's glyph slots are data-text)") },
        Rule("*", "SHOUT_WARM") { ActorMapping.Shout("#FFFFE3A3", 128.0, 980.0, 2.5, "shout text") },
        Rule("*", "SHOUT_HOT") { ActorMapping.Shout("#FFFF8A3D", 240.0, 1000.0, 5.0, "shout text") },
        Rule("*", "SHOUT_GREEN") { ActorMapping.Shout("#FF5BD873", 120.0, 900.0, 0.0, "shout text") },
        Rule("*", "SHOUT_RED") { ActorMapping.Shout("#FFFF5147", 130.0, 900.0, 1.5, "shout text") },
        Rule("*", "paintWhip") { ActorMapping.Unmatched("paintWhip (full-screen whip-pan streaks): no set piece") },
    )

    fun map(show: ShowExport, a: ShowExport.Actor): ActorMapping {
        when (a.kind) {
            "BUBBLE" -> return ActorMapping.Bubble("BUBBLE: fx-bubble frame + text")
            "BANNER" -> return ActorMapping.Banner("BANNER: fx-banner as texts")
            "MUSIC_NOTE" -> {
                val double = a.text.contains('♫') || a.text.contains('2')
                val p = if (double) "fx-music-note-double" else "fx-music-note"
                return ActorMapping.Pieces(listOf(Use(p)), "kind MUSIC_NOTE -> $p")
            }
            "CUSTOM" -> {}
            else -> return kinds[a.kind]?.let { ActorMapping.Pieces(listOf(Use(it)), "kind ${a.kind} -> $it") }
                ?: ActorMapping.Unmatched("kind ${a.kind}: no fx piece")
        }
        val c = show.customActors.getValue(a.id)
        val haystack = listOfNotNull(c.painter, c.sourceText).joinToString(" | ")
        val rule = rules.firstOrNull { (it.scene == "*" || it.scene == a.scene) && haystack.contains(it.match) }
            ?: return ActorMapping.Unmatched("no rule for '${c.painter ?: c.sourceText}' (${c.sourceFile}:${c.sourceLine})")
        return rule.make(a)
    }
}

/**
 * Per-piece plans: how each clip plays in the show, where a draft's own driver says too little
 * (panel drafts have no drivers), or contradicts the actor tracks (one-shots the scene already
 * animates on the actor).
 */
object Plans {
    private const val SCENE_ONLY = "scene-level motion the actor's own tracks already carry"

    private fun panelF(sets: Int): (Ctx) -> Double = { x ->
        val p = x.progress.coerceIn(0.0, sets - 1.0)
        val i = floor(p).toInt().coerceAtMost(sets - 1)
        if (i >= sets - 1) 0.0 else p - i
    }

    private fun scrub(desc: String, f: (Ctx) -> Double) = Play.Scrub(desc, f)
    private fun skip(why: String = SCENE_ONLY) = Play.Skip(why)
    private val between01: (Ctx) -> Boolean = { it.progress > 0 && it.progress < 1 }

    val plans: Map<String, PiecePlan> = mapOf(
        "fx-scam-card" to PiecePlan(clips = mapOf(
            "shake" to Play.Mix("weight clamp(progress / 0.12) while cracking") { x -> if (x.progress < 0.12) (x.progress / 0.12).coerceIn(0.0, 1.0) else 0.0 },
        )),
        "set-d60s-frame" to PiecePlan(clips = mapOf(
            "pop-in" to skip(), "pop-out" to skip(), "flash" to skip("the white flash is its own actor"),
            "whip" to scrub("time = pan fraction f of progress n.f", panelF(3)),
        ), synth = listOf(
            // The frame is drawn twice, under the sets (for its drop shadow) and over them (border, streaks).
            Synth("input-no-shadow", listOf(SynthTrack("shadow", "opacity", 1.0, 0.0)), "hides the drop shadow in the copy drawn over the sets") { 0.0 },
        )),
        "set-d60s-helmet" to PiecePlan(clips = mapOf("pop-in" to skip(), "pop-out" to skip()), visible = { it.progress > 0 }),
        "set-d60s-shoes" to PiecePlan(clips = mapOf("stride" to scrub("time = progress / 2 (wraps)") { x -> x.progress / 2 })),
        "set-d60s-starburst" to PiecePlan(clips = mapOf("cover" to scrub("time = progress") { it.progress }), visible = between01),
        "set-d60s-flag" to PiecePlan(clips = mapOf("plant-wobble" to skip())),
        "set-d70s-fin" to PiecePlan(synth = listOf(
            Synth("input-heading", listOf(SynthTrack("heading", "scaleX", -1.0, 1.0)), "heading = progress (-1 left, 1 right)") { x -> (x.progress + 1) / 2 },
        )),
        "set-d70s-splash" to PiecePlan(clips = mapOf("splash" to scrub("time = progress x 0.7") { it.progress * 0.7 }), visible = between01),
        "set-d70s-steps" to PiecePlan(clips = mapOf("bloom" to scrub("one-shot at set time 6.4 s") { it.sceneTime - 6.4 })),
        "set-d80s-speedo" to PiecePlan(clips = mapOf(
            "count" to scrub("time = progress") { it.progress },
            "redline" to Play.Mix("strobes once progress reaches 1") { x -> if (x.progress >= 1) 1.0 else 0.0 },
            "pop-in" to skip(),
        )),
        "set-d80s-moon" to PiecePlan(clips = mapOf("iris-out" to scrub("time = progress") { it.progress }, "whip-in" to skip(), "whip-out" to skip())),
        "set-d80s-streaks" to PiecePlan(clips = mapOf("pan" to scrub("time = progress") { it.progress })),
        "set-d80s-trails" to PiecePlan(clips = mapOf(
            "lay-down" to scrub("time = progress (0..1)") { it.progress.coerceIn(0.0, 1.0) },
            "burn-out" to Play.Scrub("time = progress - 1 (1..2)") { x -> (x.progress - 1).coerceIn(0.0, 1.0) },
        )),
        "set-d80s-neon" to PiecePlan(clips = mapOf("flicker-on" to skip())),
        "set-d90s-mirror-front" to PiecePlan(clips = mapOf(
            "sheen" to scrub("time = progress") { it.progress }, "rattle" to skip("the actor's additive rotation carries the rattle"),
        )),
        "set-d90s-mirror-back" to PiecePlan(clips = mapOf("rattle" to skip("the actor's additive rotation carries the rattle"))),
        "set-d90s-sunglasses" to PiecePlan(clips = mapOf("drop-on" to skip("Pip's sunglasses value drives the drop"), "lift-off" to skip("Pip's sunglasses value drives the lift"))),
        "set-d90s-bullet" to PiecePlan(clips = mapOf("fly" to scrub("time = distance (progress px) / 887") { it.progress / 887.0 })),
        "set-d00s-dust-trail" to PiecePlan(clips = mapOf("trail" to scrub("one-shot at scene time 4.0 s (runAt)") { it.sceneTime - 4.0 })),
        "set-d00s-burst" to PiecePlan(visible = between01),
    )

    /** Scrubs that are only applied (remap mix 1) while this holds; otherwise their mix is 0. */
    val scrubActive: Map<Pair<String, String>, (Ctx) -> Boolean> = mapOf(
        ("set-d80s-trails" to "burn-out") to { x -> x.progress > 1 },
        ("set-d80s-trails" to "lay-down") to { x -> x.progress <= 1 },
    )
}
