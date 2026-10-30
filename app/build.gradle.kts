plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.dynamicisland.host"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.dynamicisland.host"
        // Matches the engine: the overlay's input model requires API 28.
        minSdk = 28
        targetSdk = 36
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        versionCode = 7
        versionName = "2.0"
    }

    lint {
        abortOnError = true
        // The engine is the root Android project and is linted by :lintDebug.
        // Re-importing that ancestor while reporting this nested app creates a
        // configuration-parent cycle inside Android lint.
        checkDependencies = false
        disable += setOf("InternalInsetResource")
    }

    val signingPath = providers.environmentVariable("ISLAND_KEYSTORE_PATH").orNull
    val signingPassword = providers.environmentVariable("ISLAND_KEYSTORE_PASSWORD").orNull
    val signingAlias = providers.environmentVariable("ISLAND_KEY_ALIAS").orNull
    val signingKeyPassword = providers.environmentVariable("ISLAND_KEY_PASSWORD").orNull
    val publisherSigning = if(listOf(signingPath,signingPassword,signingAlias,signingKeyPassword).all { !it.isNullOrBlank() }) {
        signingConfigs.create("publisher") {
            storeFile = file(signingPath!!)
            storePassword = signingPassword
            keyAlias = signingAlias
            keyPassword = signingKeyPassword
        }
    } else null

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            if(publisherSigning != null) signingConfig = publisherSigning
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    buildTypes.create("benchmark") {
        initWith(buildTypes.getByName("release"))
        isMinifyEnabled = false
        isShrinkResources = false
        signingConfig = signingConfigs.getByName("debug")
        matchingFallbacks += listOf("release")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
    composeOptions { kotlinCompilerExtensionVersion = libs.versions.composeCompiler.get() }
}

dependencies {
    // The engine lives in the root project.
    implementation(project(":"))

    val composeBom = platform(libs.compose.bom)
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.animation:animation")
    implementation(libs.activity.compose)
    implementation(libs.android.core)
    implementation(libs.profileinstaller)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation(libs.test.runner)
    androidTestImplementation(libs.test.rules)
    androidTestImplementation(libs.test.ext.junit)
    androidTestImplementation(libs.uiautomator)
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
