plugins {
  alias(libs.plugins.android.application)
}

android {
  namespace = "dev.playground.pipfixtures"
  compileSdk = 36
  defaultConfig {
    applicationId = "dev.playground.pipfixtures"
    minSdk = 33
    targetSdk = 36
    versionCode = 1
    versionName = "1.0"
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
  }
  buildFeatures {
    aidl = false
    buildConfig = false
    shaders = false
  }
}

kotlin {
  jvmToolchain(21)
}
