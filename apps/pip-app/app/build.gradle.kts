import java.util.Properties

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.compose.compiler)
  alias(libs.plugins.kotlin.serialization)
}

val pipRiveDist = rootProject.file("../../packages/pip-rive/dist")

fun escBuildConfig(value: String) = value.replace("\\", "\\\\").replace("\"", "\\\"")

/**
 * Gradle property, then env, then apps/pip-app/local.properties (never commit that file), then [default].
 * [aliases] are additional local.properties / env / gradle keys (e.g. pip.callUrl).
 */
fun pipProp(name: String, default: String, vararg aliases: String): String {
  val localFile = rootProject.file("local.properties")
  val local = if (localFile.isFile) {
    Properties().apply { localFile.inputStream().use { load(it) } }
  } else Properties()
  fun read(n: String) = providers.gradleProperty(n).orNull
    ?: providers.environmentVariable(n).orNull
    ?: local.getProperty(n)
  return (sequenceOf(name) + aliases.asSequence()).mapNotNull { read(it) }.firstOrNull() ?: default
}

// Plain Gradle runs (Android Studio) don't go through turbo: say how to get the .riv files instead of shipping without them.
val checkPipRive by tasks.registering {
  val marker = pipRiveDist.resolve("pip/pip.riv")
  doLast {
    check(marker.isFile) { "No $marker. Build Pip's Rive files first: turbo run build --filter=@shady-pip/pip-rive" }
  }
}
tasks.named("preBuild") { dependsOn(checkPipRive) }

android {
  namespace = "dev.playground.pip"
  compileSdk = 36
  defaultConfig {
    applicationId = "dev.playground.pip"
    minSdk = 33
    targetSdk = 36
    versionCode = 1
    versionName = "1.0"
    // Rive ships native libraries for every ABI (about 50 MB in a debug APK); minSdk 33 devices are 64-bit.
    ndk { abiFilters += listOf("arm64-v8a", "x86_64") }

    // jev-playground base URL for Talk to Pip. Override with -PPIP_SERVER_URL=... or local.properties.
    // Default assumes `adb reverse tcp:3100 tcp:3100` against a local server.
    // PIP_SERVER_BYPASS is the optional Vercel protection-bypass secret (x-vercel-protection-bypass).
    val pipServerUrl = pipProp("PIP_SERVER_URL", "http://localhost:3100")
    val pipServerBypass = pipProp("PIP_SERVER_BYPASS", "")
    buildConfigField("String", "PIP_SERVER_URL", "\"${escBuildConfig(pipServerUrl)}\"")
    buildConfigField("String", "PIP_SERVER_BYPASS", "\"${escBuildConfig(pipServerBypass)}\"")
    // pip-call (Pip3) base URL for live call scoring. Default assumes `adb reverse tcp:3200 tcp:3200`.
    // PIP_APP_SECRET (or pip.appSecret) is the shared secret for /app/calls/:id/lines and /token; never commit it.
    val pipCallUrl = pipProp("PIP_CALL_URL", "http://localhost:3200", "pip.callUrl")
    val pipAppSecret = pipProp("PIP_APP_SECRET", "", "pip.appSecret")
    val pipUser = pipProp("PIP_USER", "mom", "pip.user")
    val pipTrusted = pipProp("PIP_TRUSTED_NUMBER", "", "pip.trustedNumber")
    buildConfigField("String", "PIP_CALL_URL", "\"${escBuildConfig(pipCallUrl)}\"")
    buildConfigField("String", "PIP_APP_SECRET", "\"${escBuildConfig(pipAppSecret)}\"")
    buildConfigField("String", "PIP_USER", "\"${escBuildConfig(pipUser)}\"")
    // "Call my family" on Pip's scam warning dials this; blank opens the dialer
    buildConfigField("String", "PIP_TRUSTED_NUMBER", "\"${escBuildConfig(pipTrusted)}\"")
  }

  // Pip's .riv files are compiled from packages/pip-rive by rivc; turbo builds that package before any app task.
  sourceSets["main"].assets.srcDir(pipRiveDist)

  buildTypes {
    release {
      isMinifyEnabled = false
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
    }
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
  }
  buildFeatures {
    compose = true
    aidl = false
    buildConfig = true
    shaders = false
  }

  packaging {
    resources {
      excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
  }
}

kotlin {
  jvmToolchain(21)
}

dependencies {
  val composeBom = platform(libs.androidx.compose.bom)
  implementation(composeBom)
  androidTestImplementation(composeBom)

  // Core Android dependencies
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.activity.compose)

  // Arch Components
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.viewmodel.compose)

  // Compose
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.ui.tooling.preview)
  implementation(libs.androidx.compose.material3)
  // Tooling
  debugImplementation(libs.androidx.compose.ui.tooling)
  // Instrumented tests
  androidTestImplementation(libs.androidx.compose.ui.test.junit4)
  debugImplementation(libs.androidx.compose.ui.test.manifest)

  // Local tests: jUnit, coroutines, Android runner
  testImplementation(libs.junit)
  testImplementation(libs.kotlinx.coroutines.test)
  // The showreel exporter test builds real scenes, whose painters create android.graphics objects at load time.
  testImplementation(libs.robolectric)

  // Instrumented tests: jUnit rules and runners
  androidTestImplementation(libs.androidx.test.core)
  androidTestImplementation(libs.androidx.test.ext.junit)
  androidTestImplementation(libs.androidx.test.runner)
  androidTestImplementation(libs.androidx.test.espresso.core)

  implementation(libs.kotlinx.serialization.json)
  implementation(libs.rive.android)
  implementation(libs.stream.webrtc.android)
  implementation(libs.twilio.voice)
}
