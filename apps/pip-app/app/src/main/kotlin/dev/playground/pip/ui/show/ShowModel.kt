package dev.playground.pip.ui.show

import dev.playground.pip.ui.Hand
import dev.playground.pip.ui.Headwear
import dev.playground.pip.ui.Mood
import dev.playground.pip.ui.Mouth
import dev.playground.pip.ui.PipState
import dev.playground.pip.ui.anim.DiscreteProp
import dev.playground.pip.ui.anim.FloatProp
import dev.playground.pip.ui.anim.StopScope

/** Animatable Pip properties. Keyframe stops use the extension setters below, e.g. `at(50) { y(900); squash(1.1, 0.9) }`. */
object PipProps {
  val x = FloatProp<PipState>("x", { it.x }, { s, v -> s.x = v })
  val y = FloatProp<PipState>("y", { it.y }, { s, v -> s.y = v })
  val size = FloatProp<PipState>("size", { it.size }, { s, v -> s.size = v })
  val facing = FloatProp<PipState>("facing", { it.facing }, { s, v -> s.facing = v })
  val squashX = FloatProp<PipState>("squashX", { it.squashX }, { s, v -> s.squashX = v })
  val squashY = FloatProp<PipState>("squashY", { it.squashY }, { s, v -> s.squashY = v })
  val rotation = FloatProp<PipState>("rotation", { it.rotation }, { s, v -> s.rotation = v })
  val eyesOpen = FloatProp<PipState>("eyesOpen", { it.eyesOpen }, { s, v -> s.eyesOpen = v })
  val blink = FloatProp<PipState>("blink", { it.blink }, { s, v -> s.blink = v })
  val lookX = FloatProp<PipState>("lookX", { it.lookX }, { s, v -> s.lookX = v })
  val lookY = FloatProp<PipState>("lookY", { it.lookY }, { s, v -> s.lookY = v })
  val hatSway = FloatProp<PipState>("hatSway", { it.hatSway }, { s, v -> s.hatSway = v })
  val hatDroop = FloatProp<PipState>("hatDroop", { it.hatDroop }, { s, v -> s.hatDroop = v })
  val blush = FloatProp<PipState>("blush", { it.blush }, { s, v -> s.blush = v })
  val sunglasses = FloatProp<PipState>("sunglasses", { it.sunglasses }, { s, v -> s.sunglasses = v })
  val alpha = FloatProp<PipState>("alpha", { it.alpha }, { s, v -> s.alpha = v })
  val lidTop = FloatProp<PipState>("lidTop", { it.lidTop }, { s, v -> s.lidTop = v })
  val squint = FloatProp<PipState>("squint", { it.squint }, { s, v -> s.squint = v })
  val eyeScale = FloatProp<PipState>("eyeScale", { it.eyeScale }, { s, v -> s.eyeScale = v })
  val browRaise = FloatProp<PipState>("browRaise", { it.browRaise }, { s, v -> s.browRaise = v })
  val browTilt = FloatProp<PipState>("browTilt", { it.browTilt }, { s, v -> s.browTilt = v })
  val shadow = FloatProp<PipState>("shadow", { it.shadow }, { s, v -> s.shadow = v })
  val groundY = FloatProp<PipState>("groundY", { it.groundY }, { s, v -> s.groundY = v })
  val brows = DiscreteProp<PipState>("brows") { s, v -> s.brows = v as Boolean }
  val leftX = FloatProp<PipState>("leftX", { it.left.x }, { s, v -> s.left.x = v })
  val leftY = FloatProp<PipState>("leftY", { it.left.y }, { s, v -> s.left.y = v })
  val leftAngle = FloatProp<PipState>("leftAngle", { it.left.angle }, { s, v -> s.left.angle = v })
  val leftScale = FloatProp<PipState>("leftScale", { it.left.scale }, { s, v -> s.left.scale = v })
  val rightX = FloatProp<PipState>("rightX", { it.right.x }, { s, v -> s.right.x = v })
  val rightY = FloatProp<PipState>("rightY", { it.right.y }, { s, v -> s.right.y = v })
  val rightAngle = FloatProp<PipState>("rightAngle", { it.right.angle }, { s, v -> s.right.angle = v })
  val rightScale = FloatProp<PipState>("rightScale", { it.right.scale }, { s, v -> s.right.scale = v })
  val mood = DiscreteProp<PipState>("mood") { s, v -> s.mood = v as Mood }
  val mouth = DiscreteProp<PipState>("mouth") { s, v -> s.mouth = v as Mouth }
  val headwear = DiscreteProp<PipState>("headwear") { s, v -> s.headwear = v as Headwear }
  val leftHand = DiscreteProp<PipState>("leftHand") { s, v -> s.left.kind = v as Hand }
  val rightHand = DiscreteProp<PipState>("rightHand") { s, v -> s.right.kind = v as Hand }
  val visible = DiscreteProp<PipState>("visible") { s, v -> s.visible = v as Boolean }
  val hideBehind = DiscreteProp<PipState>("hideBehind") { s, v -> s.hideBehind = v as String? }
}

fun StopScope<PipState>.x(v: Number) = set(PipProps.x, v)
fun StopScope<PipState>.y(v: Number) = set(PipProps.y, v)
fun StopScope<PipState>.pos(px: Number, py: Number) { x(px); y(py) }
fun StopScope<PipState>.size(v: Number) = set(PipProps.size, v)
fun StopScope<PipState>.facing(v: Number) = set(PipProps.facing, v)
fun StopScope<PipState>.squash(sx: Number, sy: Number) { set(PipProps.squashX, sx); set(PipProps.squashY, sy) }
fun StopScope<PipState>.rotation(deg: Number) = set(PipProps.rotation, deg)
fun StopScope<PipState>.eyesOpen(v: Number) = set(PipProps.eyesOpen, v)
fun StopScope<PipState>.blink(v: Number) = set(PipProps.blink, v)
fun StopScope<PipState>.look(x: Number, y: Number) { set(PipProps.lookX, x); set(PipProps.lookY, y) }
fun StopScope<PipState>.hatSway(v: Number) = set(PipProps.hatSway, v)
fun StopScope<PipState>.hatDroop(v: Number) = set(PipProps.hatDroop, v)
fun StopScope<PipState>.blush(v: Number) = set(PipProps.blush, v)
fun StopScope<PipState>.sunglasses(v: Number) = set(PipProps.sunglasses, v)
fun StopScope<PipState>.alpha(v: Number) = set(PipProps.alpha, v)
fun StopScope<PipState>.lidTop(v: Number) = set(PipProps.lidTop, v)
fun StopScope<PipState>.squint(v: Number) = set(PipProps.squint, v)
fun StopScope<PipState>.eyeScale(v: Number) = set(PipProps.eyeScale, v)
fun StopScope<PipState>.brows(raise: Number? = null, tilt: Number? = null, show: Boolean? = true) {
  raise?.let { set(PipProps.browRaise, it) }; tilt?.let { set(PipProps.browTilt, it) }; show?.let { set(PipProps.brows, it) }
}
fun StopScope<PipState>.shadow(v: Number) = set(PipProps.shadow, v)
fun StopScope<PipState>.groundY(v: Number) = set(PipProps.groundY, v)
fun StopScope<PipState>.mood(v: Mood) = set(PipProps.mood, v)
fun StopScope<PipState>.mouth(v: Mouth) = set(PipProps.mouth, v)
fun StopScope<PipState>.headwear(v: Headwear) = set(PipProps.headwear, v)
fun StopScope<PipState>.visible(v: Boolean) = set(PipProps.visible, v)
fun StopScope<PipState>.hideBehind(icon: String?) = set(PipProps.hideBehind, icon)

/** Hands are in Pip units (his box is 540 wide, 660 tall; feet at (270, 660), eyes near y 410). */
fun StopScope<PipState>.leftHand(kind: Hand? = null, x: Number? = null, y: Number? = null, angle: Number? = null, scale: Number? = null) {
  kind?.let { set(PipProps.leftHand, it) }; x?.let { set(PipProps.leftX, it) }; y?.let { set(PipProps.leftY, it) }
  angle?.let { set(PipProps.leftAngle, it) }; scale?.let { set(PipProps.leftScale, it) }
}
fun StopScope<PipState>.rightHand(kind: Hand? = null, x: Number? = null, y: Number? = null, angle: Number? = null, scale: Number? = null) {
  kind?.let { set(PipProps.rightHand, it) }; x?.let { set(PipProps.rightX, it) }; y?.let { set(PipProps.rightY, it) }
  angle?.let { set(PipProps.rightAngle, it) }; scale?.let { set(PipProps.rightScale, it) }
}

/** Things other than Pip that appear in a show. Each is drawn by its own function in Actors.kt. */
enum class ActorKind {
  BUBBLE,        // speech bubble above Pip's head (text); anchored to Pip unless anchorToPip = false
  BANNER,        // big title text across the top of the screen (text), e.g. "1960s"
  SMOKE,         // a poof of smoke; progress 0..1 expands and fades it
  SLASH,         // a curved sword-slash trail; progress 0..1 sweeps it; rotation orients it
  SCAM_CARD,     // a red card reading `text`; progress 0..1 splits it diagonally into two falling halves
  RAIN_CLOUD,    // a small grey cloud with rain; progress drives the drops
  SWEAT,         // a single sweat drop
  MUSIC_NOTE,    // one floating music note
  SPOTLIGHT,     // a coloured stage light cone from the top; color sets its colour
  DISCO_BALL,    // mirror ball with sparkles; progress spins it
  MOON,          // a moon with a little flag (1960s)
  PEACE_BG,      // soft tie-dye swirl behind Pip (1960s); progress rotates it
  NEON_GRID,     // 80s synthwave grid and sun behind Pip; progress scrolls it
  DOOR,          // a wooden door in a frame (Hodor); progress 0..1 is how hard it is being pushed (rattles)
  SPARKLE,       // a little star twinkle
  HEART,         // a small heart
  CONFETTI,      // a burst of confetti; progress 0..1 is its fall
  CUSTOM,        // drawn by the actor's own `paint` function: a set piece designed inside a scene file
}

class ActorState(val kind: ActorKind, var text: String = "", var anchorToPip: Boolean = false, var layer: Int = 1) {
  var x = 0f; var y = 0f; var scale = 1f; var rotation = 0f; var alpha = 1f; var progress = 0f
  var color = 0xFFFFFFFF.toInt()
  var visible = true
  /**
   * For [ActorKind.CUSTOM]: draws the actor centred on (0, 0) after position, rotation, scale and alpha are applied.
   * Must be a pure function of the actor's values and `time` (seconds) so every frame can be recomputed from scratch.
   */
  var paint: ((canvas: android.graphics.Canvas, actor: ActorState, time: Float) -> Unit)? = null
}

object ActorProps {
  val x = FloatProp<ActorState>("x", { it.x }, { s, v -> s.x = v })
  val y = FloatProp<ActorState>("y", { it.y }, { s, v -> s.y = v })
  val scale = FloatProp<ActorState>("scale", { it.scale }, { s, v -> s.scale = v })
  val rotation = FloatProp<ActorState>("rotation", { it.rotation }, { s, v -> s.rotation = v })
  val alpha = FloatProp<ActorState>("alpha", { it.alpha }, { s, v -> s.alpha = v })
  val progress = FloatProp<ActorState>("progress", { it.progress }, { s, v -> s.progress = v })
  val text = DiscreteProp<ActorState>("text") { s, v -> s.text = v as String }
  val visible = DiscreteProp<ActorState>("visible") { s, v -> s.visible = v as Boolean }
}

@JvmName("actorX")
fun StopScope<ActorState>.x(v: Number) = set(ActorProps.x, v)
@JvmName("actorY")
fun StopScope<ActorState>.y(v: Number) = set(ActorProps.y, v)
@JvmName("actorPos")
fun StopScope<ActorState>.pos(px: Number, py: Number) { x(px); y(py) }
@JvmName("actorScale")
fun StopScope<ActorState>.scale(v: Number) = set(ActorProps.scale, v)
@JvmName("actorRotation")
fun StopScope<ActorState>.rotation(v: Number) = set(ActorProps.rotation, v)
@JvmName("actorAlpha")
fun StopScope<ActorState>.alpha(v: Number) = set(ActorProps.alpha, v)
@JvmName("actorProgress")
fun StopScope<ActorState>.progress(v: Number) = set(ActorProps.progress, v)
@JvmName("actorText")
fun StopScope<ActorState>.text(v: String) = set(ActorProps.text, v)
@JvmName("actorVisible")
fun StopScope<ActorState>.visible(v: Boolean) = set(ActorProps.visible, v)
