package dev.playground.pip.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

/**
 * Pip's colour tokens, from the re-skin ref's theme table (`--pip-*`). Every colour comes from his art: the hat red
 * becomes danger, the skin the halo and dark primary, the eye ink the primary action. Red never marks a normal
 * button, so it always means danger.
 */
@Immutable
data class PipColors(
  val bg: Color,
  val sf: Color,
  val sf2: Color,
  val tx: Color,
  val tx2: Color,
  val ln: Color,
  val pr: Color,
  val onPr: Color,
  val info: Color,
  val infoC: Color,
  val cau: Color,
  val cauC: Color,
  val dgr: Color,
  val dgrC: Color,
  val act: Color,
  val actC: Color,
)

val PipLight = PipColors(
  bg = Color(0xFFFBF6F2), sf = Color(0xFFFFFFFF), sf2 = Color(0xFFF4E9E2), tx = Color(0xFF2A1714), tx2 = Color(0xFF6E5550),
  ln = Color(0xFFE9DCD4), pr = Color(0xFF8B3F37), onPr = Color(0xFFFFFFFF), info = Color(0xFF2F7A55), infoC = Color(0xFFE2F1E7),
  cau = Color(0xFF9A6408), cauC = Color(0xFFFBEFD7), dgr = Color(0xFFCF3328), dgrC = Color(0xFFFDE3DF), act = Color(0xFFA3170F),
  actC = Color(0xFFFBE0DC),
)

val PipDark = PipColors(
  bg = Color(0xFF17110F), sf = Color(0xFF241A17), sf2 = Color(0xFF2F2421), tx = Color(0xFFF7E9E2), tx2 = Color(0xFFC2A69D),
  ln = Color(0xFF3D2F2A), pr = Color(0xFFFDE3D2), onPr = Color(0xFF3A1A16), info = Color(0xFF7CC79C), infoC = Color(0xFF1B2E23),
  cau = Color(0xFFF0B44C), cauC = Color(0xFF33270E), dgr = Color(0xFFFF6E62), dgrC = Color(0xFF3D1915), act = Color(0xFFD9382C),
  actC = Color(0xFF4A1410),
)
