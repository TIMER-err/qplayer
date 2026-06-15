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
        versionCode = 1
        versionName = "0.1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    buildTypes {
        named("release") {
            isMinifyEnabled = false
            isShrinkResources = false
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
    // Single source of truth for QML: md3/Core + showcases + fonts come from
    // ../shared-qml (shared with the tests and the desktop host), not stale
    // per-shell copies. The shell's own assets dir still supplies legacy demo
    // pieces (apptheme/, theme/, widgets/, demo.qml, ...).
    sourceSets["main"].assets.srcDir("${rootDir}/../qml4j/shared-qml")
}

dependencies {
    skijaNative("io.github.humbleui:skija-android-arm64:0.143.16")

    // The RECODE refactor merged parser/engine/compiler/render into one module.
    implementation("io.github.timer-err:qml4j-core:0.2.2")

    // Our platform-neutral player core (netease + lyrics + audio abstraction +
    // QML bridge). Pulls gson + zxing-core transitively; all Android-dexable.
    implementation("dev.t1m3.qplayer:player-core:0.1.0-SNAPSHOT")

    implementation("io.github.humbleui:skija-shared:0.143.16")
    implementation("io.github.humbleui:skija-android-arm64:0.143.16")

    implementation("com.android.tools:r8:8.13.17")

    implementation("androidx.appcompat:appcompat:1.7.0")
}
