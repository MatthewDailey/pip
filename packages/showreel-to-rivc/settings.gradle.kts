// Standalone host tool, like rivc: converts the exported Kotlin showreel into a rivc project.
rootProject.name = "showreel-to-rivc"

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

// rivc's source model (ProjectSource, PipSource, SvgImport) and compiler, built from its own tree.
includeBuild("../rivc") {
    dependencySubstitution {
        substitute(module("rivc:rivc")).using(project(":"))
    }
}
