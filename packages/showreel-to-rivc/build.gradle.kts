plugins {
    kotlin("jvm") version "2.3.20"
    kotlin("plugin.serialization") version "2.3.20"
    application
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation("rivc:rivc")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    testImplementation(kotlin("test"))
}

application {
    mainClass.set("showreel.MainKt")
    applicationName = "showreel-to-rivc"
    applicationDefaultJvmArgs = listOf("-Xss8m", "-Djava.awt.headless=true")
}

tasks.test {
    useJUnitPlatform()
    systemProperty("java.awt.headless", "true")
    // The export, the drafts and the written project live in the app tree two levels up.
    systemProperty("showreel.repo", rootDir.resolve("../..").canonicalPath)
    testLogging {
        events("passed", "failed", "skipped")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
