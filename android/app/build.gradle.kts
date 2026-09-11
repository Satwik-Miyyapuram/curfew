plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

android {
    namespace = "dev.curfew.app"
    compileSdk = 35

    /**
     * Sign the release build when a key is available, and leave it unsigned when one is not.
     *
     * **P1-7 was that the published APK cannot be installed by anyone.** `ARCHITECTURE.md` §12 lists
     * GitHub Releases as a reference channel and calls the sideloaded build "the reference build",
     * and there was no way to produce one: no `signingConfig`, so `assembleRelease` emitted an
     * unaligned unsigned artifact that Android refuses unless the user signs it themselves.
     *
     * **The review's suggested fix is wrong, and it is worth saying why.** It offers "generate a
     * debug-style self-signed release key in CI and sign". Android requires the *same* key for an
     * in-place update, so a key minted fresh on every run gives every release a different
     * certificate — users could never upgrade without uninstalling first, and the only lesson the
     * signature teaches them is that it changes, which is the opposite of what a signature is for.
     * An unsigned artifact is more honest than a signature that means nothing.
     *
     * So the key comes from outside: `keystore.properties` beside this file, or the four
     * `CURFEW_KEYSTORE_*` environment variables, whichever is present. Neither is in the repository
     * and both are gitignored, because a signing key in a public repo is a signing key everyone has.
     * With no key the build behaves exactly as it did — unsigned, for downstream signing — so CI
     * keeps working and nothing here is a new requirement for a contributor.
     */
    /**
     * `keystore.properties`, parsed as **plain text rather than with `Properties`**.
     *
     * **`\` is an escape character in the `.properties` format**, so `Properties.load` turns
     * `storeFile=C:\keys\curfew.jks` into `C:keyscurfew.jks` — `\k` and `\c` are silently swallowed.
     * That is not a theoretical trap: the first version of this block used `Properties`, configured
     * without error, and produced an *unsigned* release, because `file(storePath).isFile` was false
     * for a path that had been quietly mangled on the way in. Nothing warned; the artifact was just
     * unsigned for no stated reason.
     *
     * Four lines of `key=value` do not need Java's escaping rules, and the rules actively break the
     * one value a Windows user will write. So this reads the text and splits on the first `=`, which
     * is what somebody editing this file expects to happen.
     */
    val keyStoreFile: Map<String, String> =
        rootProject.file("keystore.properties").takeIf { it.isFile }?.let { props ->
            props.readLines()
                .map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("#") && it.contains('=') }
                .associate { it.substringBefore('=').trim() to it.substringAfter('=').trim() }
        } ?: emptyMap()

    fun key(name: String, env: String): String? =
        keyStoreFile[name]?.takeIf { it.isNotBlank() }
            ?: System.getenv(env)?.takeIf { it.isNotBlank() }

    val storePath = key("storeFile", "CURFEW_KEYSTORE_FILE")
    val signable = storePath != null && file(storePath).isFile &&
        key("storePassword", "CURFEW_KEYSTORE_PASSWORD") != null &&
        key("keyAlias", "CURFEW_KEY_ALIAS") != null &&
        key("keyPassword", "CURFEW_KEY_PASSWORD") != null

    if (storePath != null && !signable) {
        // Said out loud rather than skipped in silence. A `keystore.properties` that is present but
        // incomplete is a mistake somebody will otherwise spend an afternoon on: the build succeeds,
        // and the artifact is unsigned for no stated reason.
        logger.lifecycle(
            "Curfew: keystore.properties is present but the release build will NOT be signed. " +
                "It needs storeFile (an existing file), storePassword, keyAlias and keyPassword.",
        )
    }

    signingConfigs {
        if (signable) {
            create("release") {
                storeFile = file(storePath!!)
                storePassword = key("storePassword", "CURFEW_KEYSTORE_PASSWORD")
                this.keyAlias = key("keyAlias", "CURFEW_KEY_ALIAS")
                keyPassword = key("keyPassword", "CURFEW_KEY_PASSWORD")
            }
        }
    }

    defaultConfig {
        applicationId = "dev.curfew.app"
        // 26 is where foreground services, notification channels and `UsageStatsManager` all
        // behave the way the enforcement design assumes.
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // The four ABIs Android still ships. Without this, JNA drags in `mips`, `mips64` and
        // `armeabi` copies of jnidispatch for devices that have not existed for a decade.
        ndk { abiFilters += setOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64") }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            // R8 shrinks the app but must not strip the accessibility service or the JNA bindings;
            // both are named in proguard-rules.pro.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // `null` when no key is configured, and then this variant is unsigned exactly as it was
            // before. See the `signingConfigs` block above for why the key is supplied, not generated.
            signingConfig = signingConfigs.findByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}")
    }
    testOptions.unitTests {
        isIncludeAndroidResources = true
        isReturnDefaultValues = true
    }
}

dependencies {
    implementation(project(":policy"))

    implementation(libs.zxing.core)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.biometric)
    implementation(libs.kotlinx.coroutines.android)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    implementation(libs.androidx.sqlite.ktx)
    implementation(libs.sqlcipher)
    ksp(libs.androidx.room.compiler)

    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.androidx.work.testing)
    testImplementation(libs.androidx.room.testing)
    testImplementation(libs.mockk)
    testImplementation(libs.turbine)
    testImplementation(libs.androidx.test.junit)
    // The desktop JNA jar: :policy exports the Android `.aar`, which carries no jnidispatch for the
    // host JVM, so a unit test that touches the Rust core would fail to link without this.
    testImplementation(libs.jna)

    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
}

/**
 * The unit tests exercise the real policy core, so they need the host library the `:policy` module
 * builds — and JNA needs it to match the JVM's own architecture, not the compiler's default host.
 * See `policy/build.gradle.kts` for why those are not always the same machine.
 */
tasks.withType<Test>().configureEach {
    dependsOn(":policy:cargoBuildHost")
    val cargoTargetDir =
        System.getenv("CARGO_TARGET_DIR")?.let(::File)
            ?: File(System.getProperty("user.home"), ".cache/curfew-target")
    val arch = when (val a = System.getProperty("os.arch").lowercase()) {
        "amd64", "x86_64" -> "x86_64"
        "aarch64", "arm64" -> "aarch64"
        else -> error("no Rust triple known for os.arch=$a")
    }
    val os = org.gradle.internal.os.OperatingSystem.current()
    val triple = when {
        os.isWindows -> "$arch-pc-windows-msvc"
        os.isMacOsX -> "$arch-apple-darwin"
        else -> "$arch-unknown-linux-gnu"
    }
    systemProperty("jna.library.path", File(cargoTargetDir, "$triple/release").absolutePath)
}
