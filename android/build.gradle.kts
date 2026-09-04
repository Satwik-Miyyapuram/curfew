plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}

/**
 * Build outputs live outside the source tree.
 *
 * This project's own working copy sits inside a OneDrive folder, where the sync client takes and
 * holds handles on files while Gradle is still writing them — which surfaces as an
 * `AccessDeniedException` in whichever task happened to be running. Building into a local scratch
 * directory sidesteps it entirely, matches what the Rust build already does, and has no effect
 * anywhere else. `CURFEW_BUILD_DIR` overrides it; CI leaves it alone and builds in place.
 */
val buildRoot: File =
    System.getenv("CURFEW_BUILD_DIR")?.let(::File)
        ?: System.getenv("LOCALAPPDATA")?.let { File(it, "curfew-build") }
        ?: File(rootDir, "build")

layout.buildDirectory.set(File(buildRoot, "root"))

subprojects {
    layout.buildDirectory.set(File(buildRoot, name))
}
