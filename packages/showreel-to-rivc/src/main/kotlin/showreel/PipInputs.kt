package showreel

/**
 * Pip's face, mapped from the export to the rig's state machine inputs (`control: inputs`).
 *
 * The life layer maps through `life` only: the rig runs its own idle layer (blinks, darting gaze,
 * sway, breathing), so the export's `life*` channels are not keyed. The rig has no blink number:
 * a scripted blink closes the eyes through `eyesOpen`, as `PipRenderer`'s lid cover does
 * (`cover = 1 - (1 - lid) * eyesOpen * (1 - blink)`).
 */
object PipInputs {
    class Input(val desc: String, val hold: Boolean, val tol: Double, val fn: (Frame) -> Double)

    val MOOD = listOf("NEUTRAL", "HAPPY", "SAD", "ANGRY", "SURPRISED")
    val MOUTH = listOf("NONE", "SMILE", "FROWN", "O", "SHOUT", "FLAT")
    val HAND = listOf("NONE", "FIST", "POINT", "PEACE", "PALM", "SWORD", "PHONE", "CUBE")
    val HEADWEAR = listOf("NONE", "NINJA", "FLOWERS", "SWEATBAND")

    /** The spike rig's moods: 0 neutral, 1 happy, 2 angry, 3 worried. */
    private val SPIKE_MOOD = mapOf("NEUTRAL" to 0.0, "HAPPY" to 1.0, "SAD" to 3.0, "ANGRY" to 2.0, "SURPRISED" to 0.0)

    private fun ordinal(values: List<String>, v: Any?) = values.indexOf(v as? String).coerceAtLeast(0).toDouble()

    fun mapping(name: String, spike: Boolean): Input? = when (name) {
        "mood" -> if (spike) Input("Mood enum, spike order (SAD as worried, SURPRISED as neutral), hold keys", true, 0.0) { f -> SPIKE_MOOD[f.pip["mood"]] ?: 0.0 }
        else Input("Mood ordinal ${MOOD.joinToString("/")} (hold keys)", true, 0.0) { f -> ordinal(MOOD, f.pip["mood"]) }
        "mouth" -> Input("Mouth ordinal ${MOUTH.joinToString("/")} (hold keys; NONE = no mouth, as in Kotlin)", true, 0.0) { f -> ordinal(MOUTH, f.pip["mouth"]) }
        "eyesOpen" -> Input("eyesOpen x (1 - blink): the rig has no blink number (life blinks come from the rig's idle)", false, 0.004) { f ->
            f.pipNum("eyesOpen").coerceIn(0.0, 1.0) * (1 - f.pipNum("blink").coerceIn(0.0, 1.0))
        }
        "life" -> Input("life (the rig runs its own idle layer; the export's life* channels are not keyed)", false, 0.004) { f -> f.pipNum("life") }
        "brows" -> Input("brows boolean as 0/1 (hold)", true, 0.0) { f -> if (f.pip["brows"] == true) 1.0 else 0.0 }
        "handL" -> Input("leftHand ordinal ${HAND.joinToString("/")} (hold; CUBE not drawn)", true, 0.0) { f -> ordinal(HAND, f.pip["leftHand"]) }
        "handR" -> Input("rightHand ordinal (hold)", true, 0.0) { f -> ordinal(HAND, f.pip["rightHand"]) }
        "handLX" -> Input("leftX (box units)", false, 0.3) { f -> if (f.pip["leftHand"] == "NONE") Double.NaN else f.pipNum("leftX") }
        "handLY" -> Input("leftY (box units)", false, 0.3) { f -> if (f.pip["leftHand"] == "NONE") Double.NaN else f.pipNum("leftY") }
        "handRX" -> Input("rightX (box units)", false, 0.3) { f -> if (f.pip["rightHand"] == "NONE") Double.NaN else f.pipNum("rightX") }
        "handRY" -> Input("rightY (box units)", false, 0.3) { f -> if (f.pip["rightHand"] == "NONE") Double.NaN else f.pipNum("rightY") }
        "handLAngle" -> Input("leftAngle (degrees)", false, 0.2) { f -> if (f.pip["leftHand"] == "NONE") Double.NaN else f.pipNum("leftAngle") }
        "handRAngle" -> Input("rightAngle (degrees)", false, 0.2) { f -> if (f.pip["rightHand"] == "NONE") Double.NaN else f.pipNum("rightAngle") }
        "lookX", "lookY", "lidTop", "squint", "eyeScale", "browRaise", "browTilt", "blush", "hatSway", "hatDroop" ->
            Input("$name (direct)", false, 0.004) { f -> f.pipNum(name) }
        "headwear" -> Input("Headwear ordinal ${HEADWEAR.joinToString("/")} (hold)", true, 0.0) { f -> ordinal(HEADWEAR, f.pip["headwear"]) }
        else -> null
    }

    /** Exported Pip properties the rig has no input for (beyond the transform, which the instance carries). */
    fun unmapped(rig: Set<String>): List<String> {
        val placed = setOf("x", "y", "size", "facing", "squashX", "squashY", "rotation", "alpha", "visible", "groundY", "shadow", "hideBehind", "sunglasses")
        val folded = mapOf(
            "blink" to "eyesOpen", "lifeBlink" to "life", "lifeLookX" to "life", "lifeLookY" to "life", "lifeSway" to "life", "lifeBrow" to "life",
            "lifeBreath" to "life", "life" to "life", "leftHand" to "handL", "rightHand" to "handR", "leftX" to "handLX", "leftY" to "handLY",
            "rightX" to "handRX", "rightY" to "handRY", "leftAngle" to "handLAngle", "rightAngle" to "handRAngle",
        )
        val all = listOf(
            "mood", "mouth", "lookX", "lookY", "eyesOpen", "lidTop", "squint", "eyeScale", "browRaise", "browTilt", "brows", "blush", "hatSway", "hatDroop",
            "headwear", "leftHand", "leftX", "leftY", "leftAngle", "leftScale", "rightHand", "rightX", "rightY", "rightAngle", "rightScale",
        ) + folded.keys
        return all.filter { it !in placed && (folded[it] ?: it) !in rig }.map { "Pip.$it: the rig has no '${folded[it] ?: it}' input; not converted" }
    }
}
