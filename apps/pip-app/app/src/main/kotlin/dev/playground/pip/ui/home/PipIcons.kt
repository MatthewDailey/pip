package dev.playground.pip.ui.home

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/** The re-skin mocks' 24 px line icons (2 px stroke, round ends), drawn in the content colour by `Icon`. */
object PipIcons {
  val mic = line("M12 3a3 3 0 0 0-3 3v6a3 3 0 0 0 6 0V6a3 3 0 0 0-3-3zM5 11a7 7 0 0 0 14 0M12 18v3")
  val keyboard = line("M5 6h14a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2zM7 10h.01M11 10h.01M15 10h.01M8 14h8")
  val gear = line("M12 9a3 3 0 1 0 0 6a3 3 0 1 0 0-6zM12 2v3M12 19v3M2 12h3M19 12h3M5 5l2 2M17 17l2 2M5 19l2-2M17 7l2-2")
  val close = line("M6 6l12 12M18 6L6 18")
  val back = line("M15 6l-6 6 6 6")
  val check = line("M5 12l5 5L20 7")
  val speaker = line("M4 9h4l5-4v14l-5-4H4zM16 9a4 4 0 0 1 0 6")
  val send = line("M4 12l16-8-6 16-3-7z")
  val stop = filled("M9 7h6a2 2 0 0 1 2 2v6a2 2 0 0 1-2 2H9a2 2 0 0 1-2-2V9a2 2 0 0 1 2-2z")

  private fun line(d: String) = ImageVector.Builder(defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
    .addPath(
      pathData = PathParser().parsePathString(d).toNodes(),
      stroke = SolidColor(Color.Black),
      strokeLineWidth = 2f,
      strokeLineCap = StrokeCap.Round,
      strokeLineJoin = StrokeJoin.Round,
    ).build()

  private fun filled(d: String) = ImageVector.Builder(defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
    .addPath(pathData = PathParser().parsePathString(d).toNodes(), fill = SolidColor(Color.Black)).build()
}
