plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

android {
    namespace = "ch.lkmc.combray.core"
    compileSdk = 37

    defaultConfig {
        minSdk = 30
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        // Robolectric runs Room on the host JVM with real SQLite.
        unitTests.isIncludeAndroidResources = true
        // The desktop JDK's HttpURLConnection drops "Origin" (Hister's native-client header)
        // unless allowed; Android's sends it. scripts/check-app.sh proves the device path.
        unitTests.all { it.systemProperty("sun.net.http.allowRestrictedHeaders", "true") }
    }
}

ksp {
    // Exported schemas are committed: migrations are tested against them.
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    api(project(":engine"))

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.work.runtime)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit4)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.kotlinx.coroutines.test)
}
