package dev.playground.pip

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import dev.playground.pip.ui.show.FirstRunGreeting

/**
 * The "Pip Intro" launcher icon: replays Pip's welcome animation for a demo. It shows nothing itself; it goes to
 * the home screen, where Pip climbs out of his icon and performs ([FirstRunGreeting.replay]). Works every time,
 * unlike the first-run greeting, which plays once.
 *
 *   adb shell am start -n dev.playground.pip/.PipIntroActivity
 */
class PipIntroActivity : Activity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    if (FirstRunGreeting.replay()) {
      startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } else {
      // Pip draws over the home screen through his accessibility service; setup turns it on.
      Toast.makeText(this, "Turn on Pip's accessibility service first", Toast.LENGTH_LONG).show()
      startActivity(Intent(this, MainActivity::class.java))
    }
    finish()
  }
}
