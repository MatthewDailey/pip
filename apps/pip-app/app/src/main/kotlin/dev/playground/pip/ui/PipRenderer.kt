package dev.playground.pip.ui

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

enum class Mood { NEUTRAL, HAPPY, SAD, ANGRY, SURPRISED }
enum class Mouth { NONE, SMILE, FROWN, O, SHOUT, FLAT }
enum class Hand { NONE, FIST, POINT, PEACE, PALM, SWORD, PHONE, CUBE }
enum class Headwear { NONE, NINJA, FLOWERS, SWEATBAND }

/** Where a hand sits, in Pip's own units (the 540 x 660 box he is drawn in), and which way it points (degrees). */
data class HandPose(var kind: Hand = Hand.NONE, var x: Float = 0f, var y: Float = 0f, var angle: Float = 0f, var scale: Float = 1f)

/**
 * Everything that varies about Pip on one frame. Position is the bottom-centre of his box in screen pixels;
 * [size] is the box width in pixels. Shape values are in his own 540 x 660 units, so poses read the same at any size.
 */
class PipState {
  var x = 0f; var y = 0f; var size = 300f
  var facing = 1f            // -1 mirrors him
  var squashX = 1f; var squashY = 1f
  var rotation = 0f           // degrees, around his feet
  var eyesOpen = 1f; var blink = 0f; var lookX = 0f; var lookY = 0f
  var lidTop = 0f             // 0..1 upper lid lowering (tired, smug, sly), independent of blinking
  var squint = 0f             // 0..1 lower lid rising (smile-squint, suspicion)
  var eyeScale = 1f           // pupil/eye size; >1 wide-eyed, <1 beady
  var browRaise = 0f          // -1 furrowed down .. 1 raised high
  var browTilt = 0f           // -1 worried (inner ends up) .. 1 angry (inner ends down)
  var brows = false           // draw brows at all (they're off in his resting design)
  var shadow = 1f             // ground shadow strength; fades as he leaves the ground
  var groundY = Float.NaN     // screen y of the floor for the shadow; NaN = under his feet
  var mood = Mood.NEUTRAL; var mouth = Mouth.NONE
  var hatSway = 0f            // -1..1 swings the floppy tip
  var hatDroop = 0f           // 0..1 tips the whole hat down
  var blush = 0f
  var headwear = Headwear.NONE
  var sunglasses = -1f        // <0 hidden; otherwise 0..1 = how far they've dropped onto his eyes
  val left = HandPose(); val right = HandPose()
  var alpha = 1f
  var visible = true
  /** Name of a home-screen icon Pip is behind; the stage masks him where that icon and its label are. */
  var hideBehind: String? = null

  // The always-on "aliveness" layer (see show/PipLife.kt). Scenes never animate these, so their own looks, blinks
  // and sways can't cancel it; the renderer adds them on top, scaled by [life].
  var life = 1f               // 0 freezes the idle layer (a locked stare), 1 = fully alive
  var lifeLookX = 0f; var lifeLookY = 0f
  var lifeBlink = 0f
  var lifeSway = 0f
  var lifeBreath = 0f         // tiny vertical breathing stretch (fraction)
  var lifeBrow = 0f           // tiny brow flicker, only visible when brows are drawn

  fun reset() {
    x = 0f; y = 0f; size = 300f; facing = 1f; squashX = 1f; squashY = 1f; rotation = 0f
    eyesOpen = 1f; blink = 0f; lookX = 0f; lookY = 0f; lidTop = 0f; squint = 0f; eyeScale = 1f
    browRaise = 0f; browTilt = 0f; brows = false; shadow = 1f; groundY = Float.NaN
    mood = Mood.NEUTRAL; mouth = Mouth.NONE
    hatSway = 0f; hatDroop = 0f; blush = 0f; headwear = Headwear.NONE; sunglasses = -1f
    left.kind = Hand.NONE; right.kind = Hand.NONE; left.scale = 1f; right.scale = 1f; alpha = 1f; visible = true; hideBehind = null
    left.x = 0f; left.y = 0f; left.angle = 0f; right.x = 0f; right.y = 0f; right.angle = 0f
    life = 1f; lifeLookX = 0f; lifeLookY = 0f; lifeBlink = 0f; lifeSway = 0f; lifeBreath = 0f; lifeBrow = 0f
  }
}

/**
 * Draws Pip from his SVG artwork ([PipArt]). The hat and face are the traced paths, deformed per frame (the hat tip
 * bends with [PipState.hatSway] and the hat tips with [PipState.hatDroop]); eyes, mouth, brows and props are drawn
 * on top so they can change shape with his mood.
 *
 * Style: flat vector with a soft key light from the top-left of the screen (it stays top-left when he faces the
 * other way). Shading is kept to gentle radial gradients so he still reads as the original flat illustration.
 * Nothing here allocates per frame: shaders are built once in unit space and placed with a local matrix.
 */
class PipRenderer(private val art: PipArt) {
  private val hatRed = Color.parseColor("#F05248")
  private val skin = Color.parseColor("#FDE3D2")
  private val eyeBrown = Color.parseColor("#8B3F37")
  private val ink = Color.parseColor("#3A2A26")
  private val handLine = Color.parseColor("#C4836C")
  private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
  private val shade = Paint(Paint.ANTI_ALIAS_FLAG)
  private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
  private val stitch = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeWidth = 3.5f; pathEffect = DashPathEffect(floatArrayOf(9f, 9f), 0f)
  }
  private val path = Path()
  private val path2 = Path()
  private val oval = RectF()
  private val m = Matrix()

  // Unit-space shaders (centre 0,0, radius 1), positioned per draw with setLocalMatrix.
  private val groundShader = RadialGradient(0f, 0f, 1f,
    intArrayOf(Color.argb(95, 58, 42, 38), Color.argb(55, 58, 42, 38), Color.argb(0, 58, 42, 38)), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
  private val hatShader = RadialGradient(0f, 0f, 1f,
    intArrayOf(Color.parseColor("#FB7468"), hatRed, Color.parseColor("#D8443A")), floatArrayOf(0f, 0.48f, 1f), Shader.TileMode.CLAMP)
  private val faceShader = RadialGradient(0f, 0f, 1f,
    intArrayOf(Color.parseColor("#FFF3EB"), skin, Color.parseColor("#F7D1BE")), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
  private val chinShader = LinearGradient(0f, 540f, 0f, 662f, Color.argb(0, 236, 150, 120), Color.argb(46, 236, 150, 120), Shader.TileMode.CLAMP)
  private val blushShader = RadialGradient(0f, 0f, 1f,
    intArrayOf(Color.argb(170, 242, 112, 110), Color.argb(90, 242, 112, 110), Color.argb(0, 242, 112, 110)), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
  private val eyeShader = RadialGradient(0f, 0f, 1f,
    intArrayOf(Color.parseColor("#A9564B"), eyeBrown, Color.parseColor("#68271F")), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
  private val handShader = RadialGradient(0f, 0f, 1f,
    intArrayOf(Color.parseColor("#FFF5EE"), skin, Color.parseColor("#F1BFA7")), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
  private val bladeShader = LinearGradient(-12f, 0f, 12f, 0f,
    intArrayOf(Color.parseColor("#FBFDFF"), Color.parseColor("#DCE5EC"), Color.parseColor("#AEBBC6"), Color.parseColor("#E3EAF0")),
    floatArrayOf(0f, 0.47f, 0.53f, 1f), Shader.TileMode.CLAMP)
  private val guardShader = LinearGradient(0f, -56f, 0f, -36f,
    intArrayOf(Color.parseColor("#FBE19A"), Color.parseColor("#DDA840"), Color.parseColor("#9C6B1C")), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
  private val pommelShader = RadialGradient(-4f, 56f, 16f,
    intArrayOf(Color.parseColor("#FBE19A"), Color.parseColor("#D49B33"), Color.parseColor("#94641A")), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
  private val phoneShader = LinearGradient(-26f, 0f, 26f, 0f,
    intArrayOf(Color.parseColor("#B6C1CB"), Color.parseColor("#8693A0"), Color.parseColor("#5F6B77")), floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP)
  private val screenShader = LinearGradient(0f, -158f, 0f, -118f,
    intArrayOf(Color.parseColor("#D8F1FF"), Color.parseColor("#7CC0F2"), Color.parseColor("#4E95D6")), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
  private val plateShader = LinearGradient(0f, -17f, 0f, 17f,
    intArrayOf(Color.parseColor("#F3F6F8"), Color.parseColor("#BCC5CD"), Color.parseColor("#8A96A1")), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)

  /** Screen-space key light, expressed in Pip's (possibly mirrored) local x: -1 = towards his local left. */
  private var lightX = -1f

  fun draw(c: Canvas, s: PipState) {
    if (!s.visible || s.alpha <= 0f) return
    ensureFace()
    val k = s.size / 540f
    val life = s.life.coerceIn(0f, 1f)
    val breath = s.lifeBreath * life
    lightX = if (s.facing < 0f) 1f else -1f
    val layer = if (s.alpha < 1f) c.saveLayerAlpha(null, (s.alpha * 255).toInt()) else c.save()
    drawGroundShadow(c, s)
    c.translate(s.x, s.y)
    c.rotate(s.rotation)
    c.scale(k * s.squashX * (1f - breath * 0.6f) * s.facing, k * s.squashY * (1f + breath))
    c.translate(-270f, -660f)
    sway = s.hatSway + s.lifeSway * life
    drawHat(c, s)
    drawFace(c)
    drawCheeks(c, s)
    drawEyes(c, s)
    drawMouth(c, s)
    c.save(); c.rotate(-14f * s.hatDroop, 300f, 430f); drawHeadwear(c, s); c.restore()
    drawSunglasses(c, s)
    drawHand(c, s, s.left, -1f)
    drawHand(c, s, s.right, 1f)
    c.restoreToCount(layer)
  }

  // ---------------------------------------------------------------- ground shadow

  /** A soft contact shadow on the floor, outside his rotation: it shrinks and fades as he rises. */
  private fun drawGroundShadow(c: Canvas, s: PipState) {
    if (s.shadow <= 0f) return
    val gy = if (s.groundY.isNaN()) s.y else s.groundY
    val rise = (gy - s.y).coerceAtLeast(0f)
    val near = 1f / (1f + rise / (s.size * 0.75f))
    val hw = s.size * 0.37f * s.squashX * (0.45f + 0.55f * near)
    val hh = hw * 0.15f
    val a = (s.shadow * near * near).coerceIn(0f, 1f)
    if (a <= 0.01f) return
    val cy = gy + hh * 0.15f
    m.setScale(hw, hh); m.postTranslate(s.x, cy)
    groundShader.setLocalMatrix(m)
    shade.shader = groundShader; shade.alpha = (a * 255).toInt()
    oval.set(s.x - hw, cy - hh, s.x + hw, cy + hh)
    c.drawOval(oval, shade)
    shade.shader = null; shade.alpha = 255
  }

  // ---------------------------------------------------------------- hat and face

  private val hatPath = Path()
  private val facePath = Path()
  private val faceBands = arrayOf(Path(), Path(), Path())
  private var faceReady = false
  private var sway = 0f

  /** Bends the hat towards its tip: points near the top move most, and the floppy tip (top-left) swings extra. */
  private val hatWarp = PipArt.Warp { x, y, out ->
    val wy = ((420f - y) / 415f).coerceIn(0f, 1f)
    val bend = wy * wy
    val tip = ((130f - x) / 130f).coerceIn(0f, 1f) * ((260f - y) / 260f).coerceIn(0f, 1f)
    out[0] = x + sway * (38f * bend + 24f * tip)
    out[1] = y - abs(sway) * 7f * bend + sway * 10f * tip
  }

  /** The face never deforms, so its path and the soft bands under the hat line are built once. */
  private fun ensureFace() {
    if (faceReady) return
    val face = art.shapes["face"] ?: return
    face.build(facePath) { x, y, out -> out[0] = x; out[1] = y }
    val offsets = floatArrayOf(7f, 15f, 26f)
    for (i in faceBands.indices) {
      path2.set(facePath); path2.offset(0f, offsets[i])
      faceBands[i].op(facePath, path2, Path.Op.DIFFERENCE)
    }
    faceReady = true
  }

  private fun drawHat(c: Canvas, s: PipState) {
    val hat = art.shapes["hat"] ?: return
    c.save()
    c.rotate(-14f * s.hatDroop, 300f, 430f)
    hat.build(hatPath, hatWarp)
    // Volume: lit from the top-left, the highlight drifting a little with the sway.
    m.setScale(430f, 430f); m.postTranslate(270f + lightX * 105f + sway * 26f, 95f)
    hatShader.setLocalMatrix(m)
    shade.shader = hatShader
    c.drawPath(hatPath, shade)
    shade.shader = null
    // Occlusion: the hat darkens just where it tucks behind the face, so the face sits in front of it.
    if (!facePath.isEmpty) {
      c.clipPath(hatPath)
      c.rotate(14f * s.hatDroop, 300f, 430f)
      stroke.shader = null
      stroke.color = Color.argb(34, 120, 20, 16); stroke.strokeWidth = 46f; c.drawPath(facePath, stroke)
      stroke.color = Color.argb(40, 120, 20, 16); stroke.strokeWidth = 24f; c.drawPath(facePath, stroke)
    }
    c.restore()
  }

  private fun drawFace(c: Canvas) {
    if (facePath.isEmpty) return
    m.setScale(360f, 330f); m.postTranslate(270f + lightX * 95f, 400f)
    faceShader.setLocalMatrix(m)
    shade.shader = faceShader; c.drawPath(facePath, shade)
    shade.shader = chinShader; c.drawPath(facePath, shade)
    shade.shader = null
    // A soft inner shadow along the top edge where the face meets the hat (three stacked bands = a soft falloff).
    fill.color = Color.argb(20, 205, 120, 95)
    for (b in faceBands) c.drawPath(b, fill)
  }

  private fun drawCheeks(c: Canvas, s: PipState) {
    val smile = if (s.mouth == Mouth.SMILE) 0.12f else 0f
    val b = (s.blush + smile).coerceIn(0f, 1f)
    if (b <= 0f) return
    shade.shader = blushShader; shade.alpha = (b * 255).toInt()
    blushAt(c, 182f, 500f); blushAt(c, 468f, 485f)
    shade.shader = null; shade.alpha = 255
  }

  private fun blushAt(c: Canvas, x: Float, y: Float) {
    m.setScale(46f, 26f); m.postTranslate(x, y); blushShader.setLocalMatrix(m)
    oval.set(x - 46f, y - 26f, x + 46f, y + 26f); c.drawOval(oval, shade)
  }

  // ---------------------------------------------------------------- eyes and brows

  // Per-frame eye values shared by both eyes.
  private var lookX = 0f; private var lookY = 0f; private var eyeK = 1f; private var blinkAmt = 0f
  private var cover = 0f; private var lidAmt = 0f; private var squintAmt = 0f; private var happy = false
  private var lidSlope = 0f
  private val lidPath = Path()
  private val lowPath = Path()
  private val eyePath = Path()

  private fun drawEyes(c: Canvas, s: PipState) {
    val life = s.life.coerceIn(0f, 1f)
    // Angry eyes lock on; sad eyes wander less.
    val dart = life * when (s.mood) { Mood.ANGRY -> 0.3f; Mood.SAD -> 0.6f; else -> 1f }
    lookX = s.lookX + s.lifeLookX * dart
    lookY = s.lookY + s.lifeLookY * dart
    happy = s.mood == Mood.HAPPY
    eyeK = s.eyeScale * when (s.mood) { Mood.SURPRISED -> 1.3f; Mood.HAPPY -> 1.12f; else -> 1f }
    blinkAmt = max(s.blink, s.lifeBlink * life).coerceIn(0f, 1f)
    val moodLid = when (s.mood) { Mood.SAD -> 0.34f; Mood.ANGRY -> 0.2f; else -> 0f }
    // Upper lids follow the gaze down a little, as real ones do.
    lidAmt = (max(s.lidTop, moodLid) + max(0f, lookY) * 0.12f).coerceIn(0f, 1f)
    cover = (1f - (1f - lidAmt) * s.eyesOpen.coerceIn(0f, 1f) * (1f - blinkAmt)).coerceIn(0f, 1f)
    squintAmt = (if (happy) max(s.squint, 0.72f) else s.squint).coerceIn(0f, 1f)

    // Brows: explicit when a scene shows them, otherwise implied by the mood.
    var raise = s.browRaise; var tilt = s.browTilt
    var browsOn = s.brows
    if (!s.brows) when (s.mood) {
      Mood.ANGRY -> { browsOn = true; raise = -0.45f; tilt = 0.9f }
      Mood.SAD -> { browsOn = true; raise = 0.2f; tilt = -0.85f }
      Mood.SURPRISED -> { browsOn = true; raise = 1f; tilt = -0.15f }
      else -> {}
    }
    raise += s.lifeBrow * life
    lidSlope = if (browsOn) tilt.coerceIn(-1f, 1f) * 0.32f else 0f

    val l = art.ellipses["eyeL"]; val r = art.ellipses["eyeR"]
    val lx = l?.cx ?: 252f; val ly = l?.cy ?: 420.5f
    val rx = r?.cx ?: 389f; val ry = r?.cy ?: 401.5f
    eye(c, lx, ly, 1f)
    eye(c, rx, ry, -1f)
    if (browsOn) {
      brow(c, lx, ly, 1f, raise, tilt)
      brow(c, rx, ry, -1f, raise, tilt)
    }
  }

  /**
   * One eye, in its own tilted frame. The upper lid comes down from the top with a gently arched edge, the lower lid
   * rises for squints (and carves the eye into a crescent when he's happy), and a blink squashes the eye slightly
   * as the lids meet a little below centre. [inner] is +1 when the inner corner is towards +x.
   */
  private fun eye(c: Canvas, cx0: Float, cy0: Float, inner: Float) {
    val cx = cx0 + lookX * 16f; val cy = cy0 + lookY * 14f
    val rx = 21f * eyeK * (1f + 0.08f * blinkAmt)
    val ry = 34.5f * eyeK * (1f - 0.12f * blinkAmt)
    c.save()
    c.translate(cx, cy)
    c.rotate(-8f)

    var lowTop = ry * (1f - 1.15f * squintAmt) + (1f - squintAmt) * 3f
    val upTop = -ry - 6f
    val up: Float
    if (happy) {
      // Happy crescents close by thinning into an arch: the lower cut rises, the top edge stays put.
      lowTop = max(lowTop - ry * 0.6f, -ry * 0.45f)
      lowTop += (-ry - 2f - lowTop) * cover
      up = upTop
    } else {
      val meet = min(lowTop, ry * 0.4f)
      lowTop -= (lowTop - meet) * cover * cover
      up = upTop + (meet - upTop) * cover
    }
    val opening = lowTop - up

    if (opening > 0.6f) {
      val saved = c.save()
      lidEdge(lidPath, up, inner, close = true)
      c.clipOutPath(lidPath)
      val wide = if (happy) 1.25f else 2.4f
      lowPath.reset(); lowPath.addOval(-rx * wide, lowTop, rx * wide, lowTop + ry * 2f + 10f, Path.Direction.CW)
      c.clipOutPath(lowPath)
      // Eye: soft radial shading, lit from the top-left.
      m.setScale(ry * 1.25f, ry * 1.25f); m.postTranslate(lightX * rx * 0.35f, -ry * 0.4f)
      eyeShader.setLocalMatrix(m)
      shade.shader = eyeShader
      oval.set(-rx, -ry, rx, ry); c.drawOval(oval, shade)
      shade.shader = null
      // Highlights stay with the light: they shift only slightly against the gaze.
      fill.color = Color.argb(230, 255, 255, 255)
      if (happy) c.drawCircle(lightX * 6f * eyeK, -ry * 0.68f, 4f * eyeK, fill)
      else c.drawCircle(lightX * 7f * eyeK - lookX * 2.5f, -ry * 0.42f - lookY * 2.5f, 6.2f * eyeK, fill)
      fill.color = Color.argb(110, 255, 255, 255)
      if (!happy) c.drawCircle(-lightX * 6.5f * eyeK - lookX * 1.5f, ry * 0.4f - lookY * 1.5f, 2.6f * eyeK, fill)
      c.restoreToCount(saved)

      // Lash line along a lowered lid, so tired or sly lids read as lids rather than a cropped eye.
      if (cover > 0.08f && !happy) {
        val a = ((cover - 0.08f) / 0.22f).coerceIn(0f, 1f)
        val saved2 = c.save()
        eyePath.reset(); eyePath.addOval(-rx, -ry, rx, ry, Path.Direction.CW)
        c.clipPath(eyePath)
        lidEdge(lidPath, up, inner, close = false)
        stroke.color = Color.argb((a * 200).toInt(), 74, 30, 25); stroke.strokeWidth = 4f
        c.drawPath(lidPath, stroke)
        c.restoreToCount(saved2)
      }
    }

    // Closed (or nearly closed) eyes become a soft line: a content curve, or happy arches.
    if (opening < 7f) {
      val a = (1f - opening / 7f).coerceIn(0f, 1f)
      val y = if (happy) -ry * 0.4f else if (opening > 0f) (up + lowTop) / 2f else lowTop
      val w = rx * 1.15f
      val bend = if (happy) -14f else 7f
      path.reset(); path.moveTo(-w, y - bend * 0.4f); path.quadTo(0f, y + bend, w, y - bend * 0.4f)
      stroke.color = eyeBrown; stroke.alpha = (a * 255).toInt(); stroke.strokeWidth = 8f * eyeK.coerceIn(0.8f, 1.3f)
      c.drawPath(path, stroke)
      stroke.alpha = 255
    }
    c.restore()
  }

  /** The upper-lid edge at height [up]: slightly arched, tilted by mood (angry: inner end down; worried: outer down). */
  private fun lidEdge(p: Path, up: Float, inner: Float, close: Boolean) {
    val span = 90f; val bow = 46f
    val yNeg = up + bow - inner * span * lidSlope   // at x = -span
    val yPos = up + bow + inner * span * lidSlope   // at x = +span
    val ctrl = 2f * up - (yNeg + yPos) / 2f
    p.reset()
    if (close) { p.moveTo(-span, -400f); p.lineTo(span, -400f); p.lineTo(span, yPos) } else p.moveTo(span, yPos)
    p.quadTo(0f, ctrl, -span, yNeg)
    if (close) p.close()
  }

  private fun brow(c: Canvas, ex: Float, ey: Float, inner: Float, raise: Float, tilt: Float) {
    val t = tilt.coerceIn(-1f, 1f)
    val bx = ex + lookX * 5f + inner * 2f
    val by = ey - 34.5f * eyeK - 21f - raise.coerceIn(-1.2f, 1.2f) * 16f + squintAmt * 5f + lidAmt * 5f + lookY * 3f
    c.save()
    c.translate(bx, by)
    c.rotate(-8f + inner * t * 20f)
    val arch = -9f * (1f - 0.6f * max(0f, t))
    path.reset(); path.moveTo(-27f, 3f); path.quadTo(0f, arch, 27f, 3f)
    stroke.color = eyeBrown; stroke.strokeWidth = 12.5f
    c.drawPath(path, stroke)
    c.restore()
  }

  // ---------------------------------------------------------------- mouth

  private fun drawMouth(c: Canvas, s: PipState) {
    val cx = 322f + lookX * 6f; val cy = 505f + lookY * 4f
    stroke.color = eyeBrown; stroke.strokeWidth = 10f
    when (s.mouth) {
      Mouth.NONE -> {}
      Mouth.SMILE -> {
        stroke.strokeWidth = 11f
        path.reset(); path.moveTo(cx - 29f, cy - 3f); path.cubicTo(cx - 16f, cy + 20f, cx + 16f, cy + 20f, cx + 29f, cy - 3f)
        c.drawPath(path, stroke)
        // Cheek push: tiny upturned ticks at the corners.
        stroke.strokeWidth = 6.5f; stroke.alpha = 190
        path.reset(); path.moveTo(cx - 29f, cy - 3f); path.quadTo(cx - 37f, cy - 4f, cx - 39f, cy - 12f)
        path.moveTo(cx + 29f, cy - 3f); path.quadTo(cx + 37f, cy - 4f, cx + 39f, cy - 12f)
        c.drawPath(path, stroke)
        stroke.alpha = 255
      }
      Mouth.FROWN -> {
        path.reset(); path.moveTo(cx - 25f, cy + 24f); path.cubicTo(cx - 14f, cy + 6f, cx + 14f, cy + 6f, cx + 25f, cy + 24f)
        c.drawPath(path, stroke)
      }
      Mouth.FLAT -> {
        path.reset(); path.moveTo(cx - 22f, cy + 8f); path.quadTo(cx, cy + 4f, cx + 22f, cy + 5f)
        c.drawPath(path, stroke)
      }
      Mouth.O -> {
        fill.color = eyeBrown; oval.set(cx - 17f, cy - 7f, cx + 17f, cy + 33f); c.drawOval(oval, fill)
        val saved = c.save()
        path.reset(); path.addOval(cx - 12f, cy - 1f, cx + 12f, cy + 28f, Path.Direction.CW)
        c.clipPath(path)
        fill.color = Color.parseColor("#4C1C18"); c.drawPath(path, fill)
        fill.color = Color.parseColor("#D9746E"); oval.set(cx - 12f, cy + 18f, cx + 12f, cy + 36f); c.drawOval(oval, fill)
        c.restoreToCount(saved)
        fill.color = Color.argb(70, 255, 255, 255); oval.set(cx + 4f, cy - 5f, cx + 12f, cy + 1f); c.drawOval(oval, fill)
      }
      Mouth.SHOUT -> {
        path.reset(); path.moveTo(cx - 40f, cy - 6f); path.quadTo(cx, cy - 12f, cx + 40f, cy - 10f)
        path.cubicTo(cx + 38f, cy + 46f, cx - 38f, cy + 50f, cx - 40f, cy - 6f); path.close()
        fill.color = Color.parseColor("#5A211D"); c.drawPath(path, fill)
        val saved = c.save()
        c.clipPath(path)
        // Teeth hint along the top, tongue at the bottom with a centre groove.
        fill.color = Color.parseColor("#FFF7EF"); c.drawRect(cx - 44f, cy - 16f, cx + 44f, cy + 1f, fill)
        fill.color = Color.parseColor("#E0807A"); oval.set(cx - 26f, cy + 18f, cx + 26f, cy + 56f); c.drawOval(oval, fill)
        stroke.color = Color.parseColor("#B95B56"); stroke.strokeWidth = 4f; c.drawLine(cx, cy + 26f, cx, cy + 38f, stroke)
        c.restoreToCount(saved)
        stroke.color = Color.parseColor("#6E2B26"); stroke.strokeWidth = 6f; c.drawPath(path, stroke)
      }
    }
  }

  // ---------------------------------------------------------------- headwear

  private val bandPath = Path().apply { moveTo(40f, 390f); quadTo(280f, 240f, 528f, 352f) }
  private val ribbonPath = Path()

  private fun drawHeadwear(c: Canvas, s: PipState) {
    when (s.headwear) {
      Headwear.NONE -> {}
      Headwear.NINJA -> ninjaBand(c)
      Headwear.FLOWERS -> flowerCrown(c)
      Headwear.SWEATBAND -> sweatband(c)
    }
  }

  private fun ninjaBand(c: Canvas) {
    // Knot tails first, behind the band. They trail the sway and flutter at different rates.
    val f = sway
    fill.color = Color.parseColor("#1B191B")
    ribbon(ribbonPath, 44f, 382f, -4f, 352f - f * 18f, -62f + f * 26f, 330f + f * 34f, 24f, 11f)
    c.drawPath(ribbonPath, fill)
    fill.color = Color.parseColor("#232023")
    ribbon(ribbonPath, 44f, 396f, 4f, 426f + f * 12f, -48f + f * 34f, 452f - f * 20f, 22f, 10f)
    c.drawPath(ribbonPath, fill)
    // Band with a soft top highlight and a darker lower edge.
    stroke.color = Color.parseColor("#262326"); stroke.strokeWidth = 44f; c.drawPath(bandPath, stroke)
    c.save(); c.translate(0f, -13f); stroke.color = Color.parseColor("#3D393D"); stroke.strokeWidth = 9f; c.drawPath(bandPath, stroke); c.restore()
    c.save(); c.translate(0f, 15f); stroke.color = Color.parseColor("#141214"); stroke.strokeWidth = 8f; c.drawPath(bandPath, stroke); c.restore()
    // Knot.
    fill.color = Color.parseColor("#2E2A2E"); oval.set(24f, 370f, 62f, 408f); c.drawOval(oval, fill)
    fill.color = Color.argb(60, 255, 255, 255); oval.set(32f, 376f, 48f, 386f); c.drawOval(oval, fill)
    // Engraved metal plate, aligned with the band's slope at its middle.
    c.save(); c.translate(284f, 299f); c.rotate(-4.5f)
    oval.set(-36f, -17f, 36f, 17f)
    shade.shader = plateShader; c.drawRoundRect(oval, 8f, 8f, shade); shade.shader = null
    stroke.color = Color.parseColor("#5E6873"); stroke.strokeWidth = 3f; c.drawRoundRect(oval, 8f, 8f, stroke)
    stroke.strokeWidth = 4f
    path.reset(); path.moveTo(-14f, 6f); path.cubicTo(-8f, -10f, 8f, -10f, 6f, 0f); path.cubicTo(4f, 7f, -4f, 6f, -3f, 0f)
    c.drawPath(path, stroke)
    c.drawLine(10f, 6f, 18f, -6f, stroke)
    fill.color = Color.parseColor("#6E7883")
    for (sx in floatArrayOf(-29f, 29f)) { c.drawCircle(sx, -10f, 2.6f, fill); c.drawCircle(sx, 10f, 2.6f, fill) }
    fill.color = Color.argb(150, 255, 255, 255); c.drawRect(-30f, -14f, 30f, -12f, fill)
    c.restore()
  }

  private val petalColors = intArrayOf(
    Color.parseColor("#FFD23F"), Color.parseColor("#EE4266"), Color.parseColor("#3BCEAC"),
    Color.parseColor("#A06CD5"), Color.parseColor("#FF8C42"),
  )

  private fun flowerCrown(c: Canvas) {
    // Leaves along the band first, then the flowers on top, alternating sizes.
    for (i in 0..5) {
      val t = (i + 0.5f) / 6f
      val px = bandX(t); val py = bandY(t)
      c.save(); c.translate(px, py); c.rotate(if (i % 2 == 0) -35f else 35f)
      fill.color = Color.parseColor("#4FA65D"); oval.set(-22f, -9f, 22f, 9f); c.drawOval(oval, fill)
      stroke.color = Color.parseColor("#3C8449"); stroke.strokeWidth = 2.5f; c.drawLine(-16f, 0f, 16f, 0f, stroke)
      c.restore()
    }
    for (i in 0..6) {
      val t = i / 6f
      flower(c, bandX(t), bandY(t), petalColors[i % petalColors.size], if (i % 2 == 0) 1f else 0.8f, i * 23f)
    }
  }

  private fun bandX(t: Float) = (1 - t) * (1 - t) * 40f + 2 * (1 - t) * t * 280f + t * t * 528f
  private fun bandY(t: Float) = (1 - t) * (1 - t) * 390f + 2 * (1 - t) * t * 240f + t * t * 352f

  private fun flower(c: Canvas, x: Float, y: Float, color: Int, k: Float, spin: Float) {
    c.save(); c.translate(x, y); c.scale(k, k); c.rotate(spin)
    // Back ring: darker, larger petals.
    fill.color = mix(color, Color.BLACK, 0.18f)
    for (p in 0 until 5) { c.save(); c.rotate(p * 72f + 36f); oval.set(-10f, -30f, 10f, -4f); c.drawOval(oval, fill); c.restore() }
    // Front ring with a light inner edge.
    for (p in 0 until 5) {
      c.save(); c.rotate(p * 72f)
      fill.color = color; oval.set(-11f, -27f, 11f, -2f); c.drawOval(oval, fill)
      fill.color = mix(color, Color.WHITE, 0.35f); oval.set(-5f, -24f, 3f, -12f); c.drawOval(oval, fill)
      c.restore()
    }
    fill.color = Color.parseColor("#FFE58C"); c.drawCircle(0f, 0f, 10f, fill)
    fill.color = Color.parseColor("#E0A33B")
    for (d in 0 until 6) { val a = Math.toRadians(d * 60.0); c.drawCircle(5.5f * cos(a).toFloat(), 5.5f * sin(a).toFloat(), 1.8f, fill) }
    fill.color = Color.argb(150, 255, 255, 255); c.drawCircle(-3f, -3.5f, 2.6f, fill)
    c.restore()
  }

  private fun sweatband(c: Canvas) {
    stroke.color = Color.parseColor("#FF2E9A"); stroke.strokeWidth = 46f; c.drawPath(bandPath, stroke)
    // Terry volume: a lighter top roll and a darker underside.
    c.save(); c.translate(0f, -14f); stroke.color = Color.parseColor("#FF6DBB"); stroke.strokeWidth = 10f; c.drawPath(bandPath, stroke); c.restore()
    c.save(); c.translate(0f, 15f); stroke.color = Color.parseColor("#D3157A"); stroke.strokeWidth = 10f; c.drawPath(bandPath, stroke); c.restore()
    stroke.color = Color.parseColor("#2EF3FF"); stroke.strokeWidth = 10f; c.drawPath(bandPath, stroke)
    // Stitching just inside both edges.
    stitch.color = Color.argb(185, 255, 235, 246)
    c.save(); c.translate(0f, -18f); c.drawPath(bandPath, stitch); c.restore()
    c.save(); c.translate(0f, 18f); c.drawPath(bandPath, stitch); c.restore()
  }

  /** A tapered ribbon along a quadratic curve (no allocation). */
  private fun ribbon(p: Path, x0: Float, y0: Float, qx: Float, qy: Float, x1: Float, y1: Float, w0: Float, w1: Float) {
    p.reset()
    val n = 10
    for (pass in 0..1) for (j in 0..n) {
      val i = if (pass == 0) j else n - j
      val t = i / n.toFloat()
      val x = (1 - t) * (1 - t) * x0 + 2 * (1 - t) * t * qx + t * t * x1
      val y = (1 - t) * (1 - t) * y0 + 2 * (1 - t) * t * qy + t * t * y1
      var tx = 2 * (1 - t) * (qx - x0) + 2 * t * (x1 - qx)
      var ty = 2 * (1 - t) * (qy - y0) + 2 * t * (y1 - qy)
      val len = sqrt(tx * tx + ty * ty).coerceAtLeast(1e-3f); tx /= len; ty /= len
      val w = (w0 + (w1 - w0) * t) / 2f * (if (pass == 0) 1f else -1f)
      val px = x - ty * w; val py = y + tx * w
      if (pass == 0 && j == 0) p.moveTo(px, py) else p.lineTo(px, py)
    }
    p.close()
  }

  private fun mix(a: Int, b: Int, t: Float): Int = Color.rgb(
    (Color.red(a) + (Color.red(b) - Color.red(a)) * t).toInt(),
    (Color.green(a) + (Color.green(b) - Color.green(a)) * t).toInt(),
    (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t).toInt(),
  )

  // ---------------------------------------------------------------- sunglasses

  private val glassDark = Path(); private val glassWhite = Path()
  init {
    // Pixel-art "deal with it" glasses, as one path per colour so neighbouring pixels never show seams.
    val px = 18f
    val rows = arrayOf(
      "XXXXXXXXXXXXXXXXXXXXXXXX",
      "X.WWXXXXXXXXXX.WWXXXXXX.",
      ".XWXXXXX..XXX.XWXXXXX...",
      "..XXXXX....X...XXXXX....",
    )
    val ox = 330f - rows[0].length * px / 2f
    for ((r, row) in rows.withIndex()) for ((i, ch) in row.withIndex()) {
      val target = when (ch) { 'X' -> glassDark; 'W' -> glassWhite; else -> continue }
      target.addRect(ox + i * px, r * px, ox + (i + 1) * px, (r + 1) * px, Path.Direction.CW)
    }
  }

  private fun drawSunglasses(c: Canvas, s: PipState) {
    if (s.sunglasses < 0f) return
    val drop = s.sunglasses.coerceIn(0f, 1f)
    c.save(); c.translate(0f, 360f + (1f - drop) * -900f)
    // A faint contact shadow on the face once they've landed.
    val sh = drop * drop * drop * drop
    if (sh > 0.02f) { c.save(); c.translate(5f, 9f); fill.color = Color.argb((sh * 45).toInt(), 58, 42, 38); c.drawPath(glassDark, fill); c.restore() }
    fill.color = Color.parseColor("#111111"); c.drawPath(glassDark, fill)
    fill.color = Color.WHITE; c.drawPath(glassWhite, fill)
    c.restore()
  }

  // ---------------------------------------------------------------- hands and props

  private val fistPath = Path().apply { addCircle(0f, 0f, 36f, Path.Direction.CW) }
  private val pointPath = Path()
  private val peacePath = Path()
  private val palmPath = Path()
  init {
    val tmp = Path()
    pointPath.addCircle(0f, 0f, 35f, Path.Direction.CW)
    tmp.addRoundRect(-12f, -100f, 12f, -14f, 12f, 12f, Path.Direction.CW)
    pointPath.op(tmp, Path.Op.UNION)
    peacePath.addCircle(0f, 0f, 35f, Path.Direction.CW)
    val mx = Matrix()
    for (a in floatArrayOf(-11f, 15f)) {
      tmp.reset(); tmp.addRoundRect(-11f, -98f, 11f, -14f, 11f, 11f, Path.Direction.CW)
      mx.setRotate(a); tmp.transform(mx); peacePath.op(tmp, Path.Op.UNION)
    }
    palmPath.addRoundRect(-44f, -36f, 44f, 40f, 30f, 30f, Path.Direction.CW)
    val tops = floatArrayOf(-80f, -92f, -88f, -74f)
    for (f in 0 until 4) {
      val x = -33f + f * 22f
      tmp.reset(); tmp.addRoundRect(x - 10f, tops[f], x + 10f, -14f, 10f, 10f, Path.Direction.CW); palmPath.op(tmp, Path.Op.UNION)
    }
    tmp.reset(); tmp.addRoundRect(-11f, -52f, 11f, 4f, 11f, 11f, Path.Direction.CW)
    mx.setRotate(58f); mx.postTranslate(40f, 2f); tmp.transform(mx); palmPath.op(tmp, Path.Op.UNION)
  }

  /** Hands float beside him, Rayman style; [side] is -1 for his left (it mirrors the thumb). */
  private fun drawHand(c: Canvas, s: PipState, h: HandPose, side: Float) {
    if (h.kind == Hand.NONE) return
    c.save()
    c.translate(h.x, h.y)
    c.rotate(h.angle)
    c.scale(h.scale, h.scale)
    when (h.kind) {
      Hand.SWORD -> sword(c)
      Hand.PHONE -> phone(c)
      Hand.CUBE -> cube(c)
      else -> {}
    }
    c.scale(side, 1f)
    val shape = when (h.kind) {
      Hand.PALM -> palmPath
      Hand.POINT -> pointPath
      Hand.PEACE -> peacePath
      else -> fistPath
    }
    // Light from the screen's top-left, carried into the hand's rotated (and mirrored) frame.
    val a = Math.toRadians(h.angle.toDouble())
    val vx0 = lightX * 0.7071f; val vy0 = -0.7071f
    val ca = cos(a).toFloat(); val sa = sin(a).toFloat()
    val vx = (vx0 * ca + vy0 * sa) * side; val vy = -vx0 * sa + vy0 * ca
    m.setScale(66f, 66f); m.postTranslate(vx * 24f, vy * 24f)
    handShader.setLocalMatrix(m)
    shade.shader = handShader; c.drawPath(shape, shade); shade.shader = null
    stroke.color = handLine; stroke.strokeWidth = 5f; c.drawPath(shape, stroke)
    if (h.kind == Hand.FIST || h.kind == Hand.SWORD || h.kind == Hand.PHONE || h.kind == Hand.CUBE) {
      // Curled fingers and a thumb across the front.
      stroke.strokeWidth = 4f; stroke.alpha = 200
      c.drawLine(-12f, -34f, -10f, -20f, stroke); c.drawLine(6f, -35f, 6f, -21f, stroke); c.drawLine(22f, -27f, 19f, -15f, stroke)
      path.reset(); path.moveTo(-30f, -2f); path.quadTo(-8f, 14f, 16f, 4f)
      c.drawPath(path, stroke)
      stroke.alpha = 255
    }
    c.restore()
  }

  private fun sword(c: Canvas) {
    // Blade points along -y from the fist.
    path.reset(); path.moveTo(-12f, -44f); path.lineTo(12f, -44f); path.lineTo(12f, -326f); path.lineTo(0f, -372f); path.lineTo(-12f, -326f); path.close()
    shade.shader = bladeShader; c.drawPath(path, shade); shade.shader = null
    stroke.color = Color.parseColor("#7F8C97"); stroke.strokeWidth = 3f; c.drawPath(path, stroke)
    stroke.color = Color.parseColor("#9EABB6"); stroke.strokeWidth = 3f; c.drawLine(0f, -60f, 0f, -300f, stroke)
    stroke.color = Color.argb(220, 255, 255, 255); stroke.strokeWidth = 3f; c.drawLine(-7f, -64f, -7f, -318f, stroke)
    fill.color = Color.argb(200, 255, 255, 255); c.drawCircle(-6f, -330f, 3f, fill)
    // Grip with wrap, gold guard with a gem, round pommel.
    fill.color = Color.parseColor("#3B2A22"); oval.set(-10f, -40f, 10f, 54f); c.drawRoundRect(oval, 6f, 6f, fill)
    stroke.color = Color.parseColor("#5C4235"); stroke.strokeWidth = 3f
    var y = -34f
    while (y < 50f) { c.drawLine(-9f, y + 6f, 9f, y, stroke); y += 11f }
    oval.set(-42f, -56f, 42f, -36f)
    shade.shader = guardShader; c.drawRoundRect(oval, 10f, 10f, shade)
    c.drawCircle(-44f, -46f, 9f, shade); c.drawCircle(44f, -46f, 9f, shade)
    shade.shader = pommelShader; c.drawCircle(0f, 58f, 13f, shade); shade.shader = null
    stroke.color = Color.parseColor("#8A5F18"); stroke.strokeWidth = 2.5f; c.drawRoundRect(oval, 10f, 10f, stroke)
    fill.color = Color.parseColor("#D2453C"); c.drawCircle(0f, -46f, 6.5f, fill)
    fill.color = Color.argb(200, 255, 255, 255); c.drawCircle(-2f, -48.5f, 2.2f, fill)
  }

  private fun phone(c: Canvas) {
    // An open flip phone: keypad half in the hand, hinge, lid with a glowing screen, stubby antenna.
    stroke.color = Color.parseColor("#3E4852"); stroke.strokeWidth = 5f
    c.drawLine(15f, -160f, 15f, -178f, stroke)
    fill.color = Color.parseColor("#3E4852"); c.drawCircle(15f, -180f, 5f, fill)
    shade.shader = phoneShader
    oval.set(-25f, -88f, 25f, -6f); c.drawRoundRect(oval, 12f, 12f, shade)
    oval.set(-25f, -166f, 25f, -96f); c.drawRoundRect(oval, 12f, 12f, shade)
    shade.shader = null
    stroke.strokeWidth = 3f
    oval.set(-25f, -88f, 25f, -6f); c.drawRoundRect(oval, 12f, 12f, stroke)
    oval.set(-25f, -166f, 25f, -96f); c.drawRoundRect(oval, 12f, 12f, stroke)
    // Hinge barrel.
    fill.color = Color.parseColor("#56616C"); oval.set(-21f, -100f, 21f, -85f); c.drawRoundRect(oval, 7f, 7f, fill)
    fill.color = Color.argb(70, 255, 255, 255); oval.set(-19f, -98f, 19f, -94f); c.drawRoundRect(oval, 2f, 2f, fill)
    // Screen and speaker slit.
    fill.color = Color.parseColor("#2A3138"); oval.set(-19f, -158f, 19f, -114f); c.drawRoundRect(oval, 5f, 5f, fill)
    shade.shader = screenShader; oval.set(-16f, -155f, 16f, -117f); c.drawRoundRect(oval, 3f, 3f, shade); shade.shader = null
    fill.color = Color.argb(110, 255, 255, 255); path.reset(); path.moveTo(-16f, -155f); path.lineTo(-2f, -155f); path.lineTo(-16f, -133f); path.close(); c.drawPath(path, fill)
    stroke.color = Color.parseColor("#3E4852"); stroke.strokeWidth = 3f; c.drawLine(-7f, -162f, 7f, -162f, stroke)
    // Keypad.
    fill.color = Color.parseColor("#C9D2DA"); c.drawCircle(0f, -74f, 7f, fill)
    fill.color = Color.parseColor("#E6EBF0")
    for (r in 0 until 3) for (q in 0 until 3) {
      val kx = -14f + q * 14f; val ky = -58f + r * 12f
      oval.set(kx - 5.5f, ky - 4f, kx + 5.5f, ky + 4f); c.drawRoundRect(oval, 3f, 3f, fill)
    }
  }

  private val cubeColors = intArrayOf(
    Color.parseColor("#E53935"), Color.parseColor("#FDD835"), Color.parseColor("#43A047"),
    Color.parseColor("#1E88E5"), Color.parseColor("#FAFAFA"), Color.parseColor("#FB8C00"),
  )

  private fun cube(c: Canvas) {
    // A 3/4 view: front face plus a lighter top and darker right side, each sticker bevelled.
    val d = 24f
    fill.color = Color.parseColor("#1D1D21")
    path.reset(); path.moveTo(-58f, -12f); path.lineTo(-58f, -128f); path.lineTo(-58f + d, -128f - d); path.lineTo(58f + d, -128f - d)
    path.lineTo(58f + d, -12f - d); path.lineTo(58f, -12f); path.close()
    c.drawPath(path, fill)
    stroke.color = fill.color; stroke.strokeWidth = 8f; c.drawPath(path, stroke)
    // Top face: unit square (u across, v into depth).
    c.save(); m.setValues(floatArrayOf(116f, d, -58f, 0f, -d, -128f, 0f, 0f, 1f)); c.concat(m)
    stickers(c, 0.06f, 0, Color.argb(60, 255, 255, 255))
    c.restore()
    // Right side: unit square (u up the side, v into depth).
    c.save(); m.setValues(floatArrayOf(0f, d, 58f, -116f, -d, -12f, 0f, 0f, 1f)); c.concat(m)
    stickers(c, 0.06f, 4, Color.argb(80, 0, 0, 0))
    c.restore()
    // Front face.
    c.save(); c.translate(-58f, -128f); c.scale(116f, 116f)
    stickers(c, 0.035f, 2, 0)
    c.restore()
  }

  /** A 3x3 grid of rounded stickers in a unit square, with a bevel highlight and an optional tint over the face. */
  private fun stickers(c: Canvas, inset: Float, seed: Int, tint: Int) {
    val cell = 1f / 3f
    for (r in 0..2) for (q in 0..2) {
      val l = q * cell + inset; val t = r * cell + inset; val rr = (q + 1) * cell - inset; val b = (r + 1) * cell - inset
      oval.set(l, t, rr, b)
      fill.color = cubeColors[(r * 3 + q * 2 + seed) % cubeColors.size]; c.drawRoundRect(oval, 0.05f, 0.05f, fill)
      fill.color = Color.argb(80, 255, 255, 255); oval.set(l + 0.02f, t + 0.02f, rr - 0.02f, t + (b - t) * 0.38f); c.drawRoundRect(oval, 0.04f, 0.04f, fill)
      fill.color = Color.argb(45, 0, 0, 0); oval.set(l, b - (b - t) * 0.18f, rr, b); c.drawRoundRect(oval, 0.04f, 0.04f, fill)
      if (tint != 0) { fill.color = tint; oval.set(l, t, rr, b); c.drawRoundRect(oval, 0.05f, 0.05f, fill) }
    }
  }
}
