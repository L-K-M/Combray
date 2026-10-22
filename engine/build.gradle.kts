plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "ch.lkmc.combray.engine"
    compileSdk = 37

    defaultConfig {
        // libcombray_engine.so links with Servo's API 30 linker wrappers (docs/BUILDING.md).
        minSdk = 30
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    sourceSets {
        named("main") {
            // Staged per ABI by scripts/build-native.sh (cargo + NDK), not built by Gradle.
            // Without it the APK still builds, and the app reports the engine as missing.
            jniLibs.directories.add("jniLibs")
        }
    }
}

dependencies {
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit4)
}

tasks.withType<Test>().configureEach {
    // EngineKeysTest reads servo_key's key codes from the Rust source it mirrors.
    inputs.file(rootProject.file("native/src/engine.rs")).withPathSensitivity(PathSensitivity.RELATIVE)
}
