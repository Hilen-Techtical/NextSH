pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // JediTerm (Desktop terminal widget) is hosted here, not on Maven Central.
        maven("https://packages.jetbrains.team/maven/p/ij/intellij-dependencies")
        // KCEF depends on JOGL (OpenGL bindings) which lives on Jogamp, not Maven Central.
        maven("https://jogamp.org/deployment/maven")
    }
}

rootProject.name = "nextsh"

include(":app")
include(":shared")
include(":desktop")
include(":terminal:terminal-emulator")
include(":terminal:terminal-view")
