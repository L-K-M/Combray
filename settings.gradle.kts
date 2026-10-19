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
    }
}

rootProject.name = "combray"

// app: Compose chrome and Android entry points. core: logical state, persistence, integrations.
// engine: Kotlin port to the native Servo bridge (native/). See docs/IMPLEMENTATION_PLAN.md section 3.
include(":app", ":core", ":engine")
