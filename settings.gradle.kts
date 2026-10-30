pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
    plugins {
        id("com.android.application") version "8.10.1"
        id("com.android.test") version "8.10.1"
        id("com.android.library") version "8.10.1"
        id("org.jetbrains.kotlin.android") version "1.9.24"
        // :core is a plain JVM module, not an Android one, so that the compiler
        // itself forbids android.* from leaking into the geometry and physics
        // layer. A lint rule would only warn; this fails the build.
        id("org.jetbrains.kotlin.jvm") version "1.9.24"
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "dynamic-island-engine"

// Installable host app for the engine (root project is the library).
include(":app")

// Android-free numeric core: spring law, geometry, colour, state resolution.
// Deliberately NOT an Android library so `import android.*` cannot compile.
include(":core")

include(":benchmark")
