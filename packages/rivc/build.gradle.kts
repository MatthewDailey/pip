plugins {
    kotlin("jvm") version "2.3.20"
    kotlin("plugin.serialization") version "2.3.20"
    application
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    testImplementation(kotlin("test"))
}

// schema.json ships on the classpath; the extractor that writes it does not.
sourceSets {
    main {
        resources {
            srcDir("schema")
            exclude("**/*.py")
        }
    }
}

application {
    mainClass.set("rivc.MainKt")
    applicationName = "rivc"
}

tasks.test {
    useJUnitPlatform()
    // Tests read the spike's golden files and examples relative to the project.
    systemProperty("rivc.projectDir", projectDir.absolutePath)
    testLogging {
        events("passed", "failed", "skipped")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
