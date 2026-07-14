plugins {
    id("com.android.application")
}

android {
    namespace = "io.github.timer_err.qml4j.android"
    compileSdk = 34

    defaultConfig {
        applicationId = "dev.t1m3.qplayer"
        minSdk = 26
        targetSdk = 34
        versionCode = 41
        versionName = "0.8.14"
        manifestPlaceholders["appLabel"] = "QPlayer"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    // Release signing is driven by env vars so the keystore never lives in the repo.
    // CI decodes a base64 keystore secret to a file and exports QPLAYER_KEYSTORE; a
    // local/secret-less build skips signing and produces an unsigned release apk.
    val keystorePath = System.getenv("QPLAYER_KEYSTORE")
    val hasSigning = keystorePath != null && file(keystorePath).exists()

    signingConfigs {
        if (hasSigning) {
            create("release") {
                storeFile = file(keystorePath!!)
                storePassword = System.getenv("QPLAYER_STORE_PASSWORD")
                keyAlias = System.getenv("QPLAYER_KEY_ALIAS")
                keyPassword = System.getenv("QPLAYER_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        named("release") {
            isMinifyEnabled = false
            isShrinkResources = false
            if (hasSigning) signingConfig = signingConfigs.getByName("release")
        }
        // Distinct applicationId so a debug build installs alongside the
        // (differently-signed) release without a signature-mismatch conflict.
        named("debug") {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            manifestPlaceholders["appLabel"] = "QPlayer (debug)"
        }
    }

    packaging {
        resources.excludes += setOf(
            "META-INF/AL2.0",
            "META-INF/LGPL2.1",
            "META-INF/*.kotlin_module"
        )
        jniLibs.useLegacyPackaging = true
    }
}

configurations.all {
    exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib-jdk7")
    exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib-jdk8")
}

val skijaNative: Configuration by configurations.creating

val skijaJniDir = layout.buildDirectory.dir("intermediates/qml4j/jniLibs").get().asFile

val extractSkijaSo by tasks.registering(Copy::class) {
    from({ skijaNative.map { zipTree(it) } }) {
        include("io/github/humbleui/skija/android/arm64/libskija.so")
        eachFile { path = "arm64-v8a/libskija.so" }
        includeEmptyDirs = false
    }
    into(skijaJniDir)
}

tasks.matching { it.name == "preBuild" }.configureEach {
    dependsOn(extractSkijaSo)
}
tasks.matching {
    it.name == "mergeDebugJniLibFolders" || it.name == "mergeReleaseJniLibFolders"
}.configureEach {
    dependsOn(extractSkijaSo)
}

android {
    sourceSets["main"].jniLibs.srcDir(skijaJniDir)
    // Vendored md3.Core component library + fonts (from the qml4j repo's shared-qml),
    // at the repo root so desktop-host can share it too. rootDir is android-shell/,
    // so the repo root is one level up. These QML/font assets aren't published in the
    // qml4j-core jar, so the app bundles them to build standalone.
    sourceSets["main"].assets.srcDir("${rootDir}/../shared-qml")
}

dependencies {
    skijaNative("io.github.humbleui:skija-android-arm64:0.143.16")

    // The RECODE refactor merged parser/engine/compiler/render into one module.
    implementation("io.github.timer-err:qml4j-core:0.2.17")

    // Our platform-neutral player core (netease + lyrics + audio abstraction +
    // QML bridge). Pulls gson + zxing-core transitively; all Android-dexable.
    // Player core: netease + lyrics + audio abstraction + QML bridge + the
    // host-drawn lyric page (fluid SkSL backdrop + per-syllable renderer + the
    // QML/Skija frame compositor), shared with the desktop host.
    implementation("dev.t1m3.qplayer:player-core:0.1.0-SNAPSHOT")

    implementation("io.github.humbleui:skija-shared:0.143.16")
    implementation("io.github.humbleui:skija-android-arm64:0.143.16")

    implementation("com.android.tools:r8:8.13.17")

    implementation("androidx.appcompat:appcompat:1.7.0")

    // MediaSessionCompat + MediaStyle notification + media-button handling for
    // system media controls (lockscreen / notification / bluetooth).
    implementation("androidx.media:media:1.7.0")
}
