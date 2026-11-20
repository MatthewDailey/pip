package dev.playground.pip.ui.show

import android.graphics.RectF
import dev.playground.pip.ui.PipState
import java.io.File

/** The emulator's home screen (1080 x 2424) with its launcher icons, as the show sees them on a device. */
internal fun emulatorStage() = StageInfo(
  1080f, 2424f,
  linkedMapOf(
    "Play Store" to RectF(50f, 1581f, 266f, 1834f),
    "Gmail" to RectF(305f, 1581f, 521f, 1834f),
    "Photos" to RectF(560f, 1581f, 776f, 1834f),
    "YouTube" to RectF(815f, 1581f, 1030f, 1834f),
    "Phone" to RectF(71f, 1929f, 244f, 2124f),
    "Messages" to RectF(326f, 1929f, 499f, 2124f),
    "Chrome" to RectF(581f, 1929f, 754f, 2124f),
  ),
)

/** Gradle runs unit tests with the module directory as the working directory. */
internal fun showreelOutDir(): File = File("build/showreel").apply { mkdirs() }

/** Every value [Show.frame] produces for Pip, in a fixed order. */
internal fun pipValues(p: PipState): List<Pair<String, Any?>> = listOf(
  "x" to p.x, "y" to p.y, "size" to p.size, "facing" to p.facing, "squashX" to p.squashX, "squashY" to p.squashY,
  "rotation" to p.rotation, "eyesOpen" to p.eyesOpen, "blink" to p.blink, "lookX" to p.lookX, "lookY" to p.lookY,
  "lidTop" to p.lidTop, "squint" to p.squint, "eyeScale" to p.eyeScale, "browRaise" to p.browRaise,
  "browTilt" to p.browTilt, "brows" to p.brows, "shadow" to p.shadow, "groundY" to p.groundY, "mood" to p.mood,
  "mouth" to p.mouth, "hatSway" to p.hatSway, "hatDroop" to p.hatDroop, "blush" to p.blush, "headwear" to p.headwear,
  "sunglasses" to p.sunglasses, "leftHand" to p.left.kind, "leftX" to p.left.x, "leftY" to p.left.y,
  "leftAngle" to p.left.angle, "leftScale" to p.left.scale, "rightHand" to p.right.kind, "rightX" to p.right.x,
  "rightY" to p.right.y, "rightAngle" to p.right.angle, "rightScale" to p.right.scale, "alpha" to p.alpha,
  "visible" to p.visible, "hideBehind" to p.hideBehind, "life" to p.life, "lifeLookX" to p.lifeLookX,
  "lifeLookY" to p.lifeLookY, "lifeBlink" to p.lifeBlink, "lifeSway" to p.lifeSway, "lifeBreath" to p.lifeBreath,
  "lifeBrow" to p.lifeBrow,
)

internal fun actorValues(a: ActorState): List<Pair<String, Any?>> = listOf(
  "x" to a.x, "y" to a.y, "scale" to a.scale, "rotation" to a.rotation, "alpha" to a.alpha, "progress" to a.progress,
  "text" to a.text, "visible" to a.visible, "color" to a.color, "layer" to a.layer, "anchorToPip" to a.anchorToPip,
)
