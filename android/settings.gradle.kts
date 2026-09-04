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

rootProject.name = "curfew"

// :policy is the Rust core and its generated Kotlin bindings; :app is everything Android-shaped.
// Keeping them apart is what stops UI code from quietly reimplementing a policy decision.
include(":app")
include(":policy")
