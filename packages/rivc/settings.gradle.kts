// Standalone build: rivc is a host tool, not part of the Android app's Gradle build.
rootProject.name = "rivc"

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}
