package dev.playground.pip

import android.app.Application
import dev.playground.pip.core.Guard
import dev.playground.pip.core.PipFlags
import dev.playground.pip.live.PipCallVerdicts

class GuardApp : Application() {
  override fun onCreate() {
    super.onCreate()
    PipFlags.init(this)
    Guard.init(this)
    PipCallVerdicts.init(this)
  }
}
