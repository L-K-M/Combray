plugins {
    id("com.android.application")
}

android {
    namespace = "ch.lkmc.combray.spike"
    compileSdk = 37
    buildToolsVersion = "36.0.0"

    defaultConfig {
        // Harness-only ID; the product application ID is an M1 decision.
        applicationId = "ch.lkmc.combray.enginespike"
        // The native library links against API 30 (Servo's linker wrappers), so 30 is the floor.
        minSdk = 30
        targetSdk = 34
        versionCode = 1
        versionName = "m0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    sourceSets {
        named("main") {
            // Produced by scripts/m0/build-harness.sh (cargo + NDK), not by Gradle.
            jniLibs.directories.add("build/combray-jni")
        }
    }

    packaging {
        jniLibs {
            // Already stripped by the build script; keep symbols needed for JNI lookups.
            keepDebugSymbols += "**/*.so"
        }
    }
}
