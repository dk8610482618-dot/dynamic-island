// Plugin versions live in settings.gradle.kts so both this library and :app can apply
// them without repeating a version (Gradle rejects a version already on the classpath).
plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.dynamicisland.engine"
    compileSdk = 36

    defaultConfig {
        // 28, not 26. The overlay's entire input model is "the window is fullscreen
        // and carves a touchable hole out of itself", and the only way to do that
        // before API 33 is ViewTreeObserver.OnComputeInternalInsetsListener - which
        // is API 28. On 26/27 the region can never be applied, so the window would
        // swallow every touch on the device. Declaring 26 advertised support for a
        // configuration that is actively harmful, so the floor is 28.
        minSdk = 28

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
    }

    lint {
        // Errors must fail the build: this project has no other gate that runs on
        // every commit, and the API-level defects it caught were real crashes.
        abortOnError = true
        checkDependencies = true
        // The internal inset resources are a deliberate last-resort fallback for
        // pre-R devices; the public WindowMetrics path is used everywhere else.
        disable += setOf("InternalInsetResource")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs += listOf(
            "-opt-in=kotlinx.coroutines.ExperimentalCoroutinesApi",
            "-opt-in=androidx.compose.animation.core.ExperimentalTransitionApi"
        )
    }

    buildFeatures {
        compose = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = libs.versions.composeCompiler.get()
    }
}

// Load instrumentation at JVM startup; inline mocks then work on hosts that
// prohibit a running JVM from attaching an agent to itself.
val mockitoAgent by configurations.creating

tasks.withType<Test>().configureEach {
    jvmArgumentProviders.add(org.gradle.process.CommandLineArgumentProvider {
        listOf("-javaagent:${mockitoAgent.singleFile.absolutePath}")
    })
}

dependencies {
    mockitoAgent(libs.bytebuddy.agent)
    // Android-free numeric core: spring law, geometry, placement, colour, state.
    // `api` rather than `implementation` because :core types (IslandPlacement,
    // DeviceGeometryProfile, SpringSpec) appear in this library's public
    // signatures, so consumers of the engine need them on their classpath too.
    api(project(":core"))

    // Kotlin Coroutines
    implementation(libs.coroutines.core)
    implementation(libs.coroutines.android)

    // AndroidX Core & Lifecycle
    implementation(libs.android.core)
    implementation(libs.lifecycle.runtime)
    implementation(libs.lifecycle.viewmodel)
    implementation(libs.savedstate)

    // Jetpack Compose BOM & UI Toolkit
    val composeBom = platform(libs.compose.bom)
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.animation:animation")
    implementation("androidx.compose.animation:animation-core")

    // Testing
    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.mockito.core)
    testImplementation(libs.mockito.kotlin)
}
