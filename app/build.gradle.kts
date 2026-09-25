import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
}

// Machine-local configuration. `local.properties` is gitignored, so anything stored there
// never leaves this computer. The online lossless source's base URL lives there as
// `net24.baseUrl` (no trailing slash needed) and is injected as BuildConfig.NET24_BASE_URL.
val localProps = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }
        ?.inputStream()?.use { load(it) }
}
// Release signing. `keystore.properties` (gitignored) holds the release keystore
// location + password. When it is absent (e.g. a clean clone) the release build
// simply stays unsigned rather than failing — so debug builds / unit tests still work.
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) keystorePropsFile.inputStream().use { load(it) }
}
val hasReleaseSigning = keystorePropsFile.exists()

val net24BaseUrl = localProps.getProperty("net24.baseUrl").orEmpty()
if (net24BaseUrl.isBlank()) {
    logger.warn(
        "local.properties: net24.baseUrl is not set — the 无损音乐 online source will be unavailable at runtime. " +
            "Add e.g. `net24.baseUrl=https://<site>/` to local.properties."
    )
}

android {
    namespace = "com.amusic"
    compileSdk = 34
    ndkVersion = "30.0.15729638"

    defaultConfig {
        applicationId = "com.amusic"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables { useSupportLibrary = true }

        // Site URL comes from local.properties (gitignored) — see the note at the top.
        buildConfigField("String", "NET24_BASE_URL", "\"$net24BaseUrl\"")

        // Read where the prebuilt libmpv + headers live (see gradle.properties).
        // Resolve relative to the ROOT project (mpv.dir is root-relative) and pass
        // an absolute path so ndk-build finds it regardless of its working dir.
        val mpvDir = providers.gradleProperty("mpv.dir").get()
        externalNativeBuild {
            ndkBuild {
                arguments += "MPV_DIR=${rootProject.file(mpvDir).absolutePath}"
            }
        }

        // Limit which ABIs the NDK build produces. ndk{} is only valid on
        // defaultConfig/flavors, not on the android{} extension in AGP 8.5.
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    signingConfigs {
        create("release") {
            if (hasReleaseSigning) {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Sign with the release key when keystore.properties is present.
            // Deliberately do NOT fall back to the debug key: a debug-signed
            // "release" APK installs fine but breaks in-place updates on every
            // machine swap, which is the worst kind of silent failure.
            if (hasReleaseSigning) signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    composeOptions {
        // Compose compiler matching Kotlin 1.9.22
        kotlinCompilerExtensionVersion = "1.5.8"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    // Native JNI wrapper (libplayer) + prebuilt libmpv.
    externalNativeBuild {
        ndkBuild {
            path = file("src/main/jni/Android.mk")
        }
    }

    // libmusicspace.so (相似推荐 query engine) is built by the buildMusicspace cargo
    // task below and handed to AGP through this jniLibs dir, ABI-foldered like any
    // prebuilt .so. When Rust is missing the dir simply stays empty.
    sourceSets {
        getByName("main") {
            // Points at the PARENT of the ABI folders (srcDir must contain arm64-v8a/, etc.).
            jniLibs.srcDir(layout.buildDirectory.dir("musicspace-jniLibs"))
        }
    }
}

// ---------------------------------------------------------------------------
// musicspace: build the recommendation query engine (Rust, native/musicspace) as a
// cdylib for the device ABI. Feature extraction never runs on the phone; this .so
// only scores. See native/musicspace/README.md for the data + parity discipline.
// Without a Rust toolchain the tasks disable themselves with a warning and the app
// degrades to "相似推荐不可用" instead of failing the build.
// ---------------------------------------------------------------------------
val musicspaceCargo: String? = run {
    val exe = if (System.getProperty("os.name").startsWith("Windows")) "cargo.exe" else "cargo"
    val dirs = buildList {
        (System.getenv("PATH") ?: "").split(File.pathSeparator).filter { it.isNotBlank() }.forEach { add(File(it)) }
        // rustup installs into ~/.cargo/bin even when PATH handling went wrong.
        (System.getenv("USERPROFILE") ?: System.getProperty("user.home"))?.let { add(File(it, ".cargo/bin")) }
    }
    dirs.firstOrNull { File(it, exe).isFile }?.let { File(it, exe).absolutePath }
}
if (musicspaceCargo == null) {
    logger.warn(
        "cargo was not found — libmusicspace.so will not be built and 相似推荐 will be unavailable. " +
            "Install Rust + `rustup target add aarch64-linux-android` (see native/musicspace/README.md)."
    )
}

val musicspaceTarget = "aarch64-linux-android"
val musicspaceCargoSo = rootProject.file("native/musicspace/target/$musicspaceTarget/release/libmusicspace.so")
val musicspaceJniLibs = layout.buildDirectory.dir("musicspace-jniLibs/arm64-v8a")

val buildMusicspace by tasks.registering(Exec::class) {
    enabled = musicspaceCargo != null
    workingDir(rootProject.file("native/musicspace"))
    commandLine(musicspaceCargo, "build", "--release", "--target", musicspaceTarget)
    // Link with the NDK's per-API clang wrapper (matches minSdk 26 and ndkVersion above).
    val hostTag = when {
        System.getProperty("os.name").startsWith("Windows") -> "windows-x86_64"
        System.getProperty("os.name").startsWith("Mac") -> "darwin-x86_64"
        else -> "linux-x86_64"
    }
    val ndkBin = file(File(File(File(localProps.getProperty("sdk.dir"), "ndk"), android.ndkVersion), "toolchains/llvm/prebuilt/$hostTag/bin"))
    val linker = listOf("aarch64-linux-android26-clang.cmd", "aarch64-linux-android26-clang")
        .map { File(ndkBin, it) }
        .firstOrNull { it.exists() }
        ?: throw GradleException("NDK linker not found under ${ndkBin.absolutePath}")
    environment("CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER", linker.absolutePath)
    inputs.dir(rootProject.file("native/musicspace/src"))
    inputs.file(rootProject.file("native/musicspace/Cargo.toml"))
    outputs.file(musicspaceCargoSo)
}

// ABI-folder the cargo output so AGP's jniLibs merge picks it up like any prebuilt .so.
val copyMusicspace by tasks.registering(Copy::class) {
    enabled = musicspaceCargo != null
    from(musicspaceCargoSo)
    into(musicspaceJniLibs)
    dependsOn(buildMusicspace)
}

tasks.matching { it.name == "mergeDebugJniLibFolders" || it.name == "mergeReleaseJniLibFolders" }.configureEach {
    if (musicspaceCargo != null) dependsOn(copyMusicspace)
}

dependencies {
    // Core
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.7.0")
    implementation("androidx.activity:activity-compose:1.9.0")

    // Compose
    val composeBom = "2024.06.00"
    implementation(platform("androidx.compose:compose-bom:$composeBom"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.animation:animation")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // Navigation
    implementation("androidx.navigation:navigation-compose:2.7.7")

    // Media session / notification (legacy compat, works on API 26+)
    implementation("androidx.media:media:1.7.0")

    // Room
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    // SAF directory listing for the recommendation payload import
    implementation("androidx.documentfile:documentfile:1.0.1")

    // Async images (album art)
    implementation("io.coil-kt:coil-compose:2.6.0")

    // Coroutines (already pulled in transitively, declared explicitly for clarity)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // Test
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
}
