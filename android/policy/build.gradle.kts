import org.gradle.internal.os.OperatingSystem

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
}

/**
 * The shared policy core, as an Android library.
 *
 * Nothing in this module is written by hand: the `.so` files come from `cargo ndk` and the Kotlin
 * comes from `uniffi-bindgen`, both driven by the tasks below. That is deliberate — the moment
 * someone can hand-edit the binding, Kotlin and Rust can disagree about what a config means, which
 * is the one thing the shared core exists to prevent.
 */
android {
    namespace = "dev.curfew.policy"
    compileSdk = 35

    defaultConfig {
        minSdk = 26
        consumerProguardFiles("consumer-rules.pro")
        ndk {
            // Every ABI a phone or a Chromebook can present. x86/x86_64 are for emulators, which
            // is where most of the instrumentation tests will run.
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    sourceSets["main"].java.srcDir(layout.buildDirectory.dir("generated/uniffi"))
    sourceSets["main"].jniLibs.srcDir(layout.buildDirectory.dir("generated/jniLibs"))

    // Unit tests load the *host* library through JNA rather than the Android `.so`, so the policy
    // core can be tested on the JVM without an emulator.
    // The policy module has no resources and its tests never touch a Context, so resource
    // merging for unit tests is pure cost.
    testOptions.unitTests.isIncludeAndroidResources = false
}

dependencies {
    // UniFFI's Kotlin bindings call into the library through JNA.
    api(libs.jna) { artifact { type = "aar" } }
    api(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.jna)
    testImplementation(libs.kotlinx.coroutines.test)
}

// --- the Rust build ------------------------------------------------------------------------------

val rustRoot = rootProject.projectDir.parentFile
val isWindows = OperatingSystem.current().isWindows
val exeSuffix = if (isWindows) ".exe" else ""

/**
 * A cargo target directory outside the project tree.
 *
 * On this project's own development machine the repository lives inside a OneDrive folder, where
 * the MSVC linker fails with LNK1104/LNK1201 writing its `.pdb`. Building into a local scratch
 * directory sidesteps it, and costs nothing anywhere else.
 */
val cargoTargetDir: File =
    (System.getenv("CARGO_TARGET_DIR")?.let(::File))
        ?: File(System.getProperty("user.home"), ".cache/curfew-target")

fun cargoEnv(spec: org.gradle.process.ProcessForkOptions) {
    spec.environment("CARGO_TARGET_DIR", cargoTargetDir.absolutePath)
    androidNdkDir()?.let { spec.environment("ANDROID_NDK_HOME", it.absolutePath) }
}

/**
 * The newest usable installed NDK, or null when the build is running without one.
 *
 * "Usable" is doing real work here. A cancelled or half-finished SDK Manager download leaves the
 * version directory behind with nothing in it, and because that stub usually carries the highest
 * version number it is exactly the one a "newest wins" rule picks — the build then fails with
 * `Error detecting NDK version`, naming a path that plainly exists. `source.properties` is the
 * file the NDK's own version detection reads, so its presence is the same question the toolchain
 * is about to ask.
 *
 * Ordering is by version component, not by string: 9.x sorts above 27.x alphabetically.
 */
fun androidNdkDir(): File? {
    System.getenv("ANDROID_NDK_HOME")?.let { return File(it) }
    val sdk = android.sdkDirectory
    val ndks = File(sdk, "ndk").listFiles()?.filter { it.isDirectory }.orEmpty()
    return ndks
        .filter { File(it, "source.properties").isFile }
        .maxWithOrNull(
            compareBy { dir ->
                dir.name.split(".").map { it.toIntOrNull() ?: 0 }
                    .let { parts -> (0..2).map { parts.getOrElse(it) { 0 } } }
                    .fold(0L) { acc, part -> acc * 1_000_000 + part }
            },
        )
}

val cargoBuild by tasks.registering(Exec::class) {
    group = "rust"
    description = "Cross-compiles curfew-ffi for every Android ABI."
    workingDir = rustRoot
    val out = layout.buildDirectory.dir("generated/jniLibs").get().asFile
    // The debug profile produces a ~40MB .so per ABI and a visibly slower matcher; release is
    // what both variants ship, because there is nothing to debug in a pure function.
    commandLine(
        buildList {
            add("cargo${exeSuffix}")
            add("ndk")
            addAll(listOf("-t", "arm64-v8a", "-t", "armeabi-v7a", "-t", "x86_64", "-t", "x86"))
            addAll(listOf("-o", out.absolutePath))
            addAll(listOf("build", "-p", "curfew-ffi", "--release"))
        },
    )
    cargoEnv(this)
    inputs.dir(File(rustRoot, "crates/curfew-core/src"))
    inputs.dir(File(rustRoot, "crates/curfew-ffi/src"))
    inputs.file(File(rustRoot, "Cargo.lock"))
    outputs.dir(out)
}

/**
 * The Rust target triple matching the JVM that runs the unit tests.
 *
 * These are not always the same machine architecture: on Windows on ARM the Android toolchain is
 * commonly an x86_64 JDK running under emulation, and JNA will refuse an aarch64 `.dll` with
 * "%1 is not a valid Win32 application". The library the tests load has to match the JVM, not the
 * compiler, so it is built for an explicit triple rather than for the default host.
 */
fun jvmTargetTriple(): String {
    val arch = when (val a = System.getProperty("os.arch").lowercase()) {
        "amd64", "x86_64" -> "x86_64"
        "aarch64", "arm64" -> "aarch64"
        else -> error("no Rust triple known for os.arch=$a")
    }
    val os = OperatingSystem.current()
    return when {
        os.isWindows -> "$arch-pc-windows-msvc"
        os.isMacOsX -> "$arch-apple-darwin"
        else -> "$arch-unknown-linux-gnu"
    }
}

/** The host build, which is both what bindgen reads the metadata out of and what unit tests load. */
val cargoBuildHost by tasks.registering(Exec::class) {
    group = "rust"
    description = "Builds curfew-ffi for the host, for bindgen and JVM unit tests."
    workingDir = rustRoot
    commandLine(
        "cargo${exeSuffix}", "build", "-p", "curfew-ffi", "--release",
        "--target", jvmTargetTriple(),
    )
    cargoEnv(this)
    inputs.dir(File(rustRoot, "crates/curfew-core/src"))
    inputs.dir(File(rustRoot, "crates/curfew-ffi/src"))
    outputs.file(hostLibrary())
}

fun hostLibraryName(): String =
    when {
        isWindows -> "curfew_ffi.dll"
        OperatingSystem.current().isMacOsX -> "libcurfew_ffi.dylib"
        else -> "libcurfew_ffi.so"
    }

fun hostLibrary(): File = File(cargoTargetDir, "${jvmTargetTriple()}/release/${hostLibraryName()}")

val uniffiBindgen by tasks.registering(Exec::class) {
    group = "rust"
    description = "Generates the Kotlin bindings from the built library."
    dependsOn(cargoBuildHost)
    workingDir = rustRoot
    val out = layout.buildDirectory.dir("generated/uniffi").get().asFile
    commandLine(
        "cargo${exeSuffix}", "run", "--release", "-p", "curfew-ffi", "--bin", "uniffi-bindgen",
        "--", "generate", "--library", hostLibrary().absolutePath,
        "--language", "kotlin", "--out-dir", out.absolutePath,
    )
    cargoEnv(this)
    inputs.file(hostLibrary())
    outputs.dir(out)
}

// Kotlin compilation needs the generated bindings; packaging needs the .so files.
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    dependsOn(uniffiBindgen)
}
tasks.matching { it.name.startsWith("merge") && it.name.contains("JniLibFolders") }.configureEach {
    dependsOn(cargoBuild)
}

// JVM unit tests load the host library instead of an Android .so.
tasks.withType<Test>().configureEach {
    dependsOn(cargoBuildHost)
    systemProperty("jna.library.path", hostLibrary().parentFile.absolutePath)
}
