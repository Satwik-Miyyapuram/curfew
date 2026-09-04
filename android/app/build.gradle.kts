plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

android {
    namespace = "dev.curfew.app"
    compileSdk = 35

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
