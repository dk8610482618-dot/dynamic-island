// Pure-JVM numeric core for the Dynamic Island.
//
// This module is deliberately NOT an Android library. It applies the Kotlin JVM
// plugin and depends on no Android or AndroidX artifact, so any attempt to
// `import android.*` or `import androidx.*` here is a compile error rather than
// a convention someone has to remember.
//
// That constraint is the point. The previous geometry and physics code lived in
// the Android library, where `SquircleShapeTest` could only run at all because
// the build set `unitTests.isReturnDefaultValues = true` — meaning every
// android.graphics call silently returned a default and the geometry assertions
// proved nothing. Everything numeric belongs here, where it is really executed.
//
// Contents:
//   physics/   spring law, the spec's (T, zeta) catalogue, rubber-banding
//   motion/    one clock, one MorphFrame, content choreography
//   geometry/  density normalization, placement/clamping, squircle path math
//
// The presentation state machine is not here: it is driven by real system
// events and lives in the engine's `IslandPriorityArbiter`, which is Android
// code by definition (notifications, media sessions, calls).

plugins {
    id("org.jetbrains.kotlin.jvm")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    // NOTE: deliberately NOT `jvmToolchain(17)`.
    //
    // `jvmToolchain` demands an actual JDK 17 installation and fails the build
    // when none is present ("Cannot find a Java installation matching
    // languageVersion=17"). This machine has only JDK 21, and the Android
    // modules in this repository already take the other approach: compile with
    // whatever JDK is running, but target Java 17 bytecode.
    //
    // Setting `jvmTarget` keeps the output identical to the Android modules
    // (Java 17 bytecode) without requiring a second JDK to be installed.
    // If a JDK 17 toolchain is ever added, `jvmToolchain(17)` can replace this
    // and will additionally pin the exact compiler.
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

tasks.withType<JavaCompile>().configureEach {
    sourceCompatibility = "17"
    targetCompatibility = "17"
}

dependencies {
    // Kotlin stdlib only. No Android, no Compose, no AndroidX.
    testImplementation(libs.junit)
}

tasks.withType<Test>().configureEach {
    testLogging {
        events("passed", "skipped", "failed")
        showStandardStreams = false
    }
}
