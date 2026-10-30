plugins {
    id("com.android.application")
}

// One version source for Maven, Android, and the source-build CI recipe.
val qplayerPom = javax.xml.parsers.DocumentBuilderFactory.newInstance()
    .newDocumentBuilder().parse(file("${rootDir}/../pom.xml"))
val qml4jVersion = qplayerPom.getElementsByTagName("qml4j.version").item(0).textContent.trim()
val okhttpVersion = qplayerPom.getElementsByTagName("okhttp.version").item(0).textContent.trim()

android {
    namespace = "io.github.timer_err.qml4j.android"
    compileSdk = 34

    defaultConfig {
        applicationId = "dev.t1m3.qplayer"
        minSdk = 26
        targetSdk = 34
        versionCode = 99
        versionName = "1.9.4-rc.1"
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
        // Release-speed binary with a side-by-side id, signed by the debug
        // keystore so a local machine without QPLAYER_KEYSTORE can still
        // install it. Use this to measure frame time; debug is too slow.
        create("perf") {
            initWith(getByName("release"))
            applicationIdSuffix = ".perf"
            versionNameSuffix = "-perf"
            isDebuggable = false
            matchingFallbacks += "release"
            signingConfig = signingConfigs.getByName("debug")
            manifestPlaceholders["appLabel"] = "QPlayer (perf)"
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
    // Lyricon 0.1.70 pulls androidx.core 1.17, which needs AGP 8.9+ / compileSdk 36.
    // The SDK does not call APIs newer than what 1.13 already provides.
    resolutionStrategy {
        force("androidx.core:core:1.13.0")
        force("androidx.core:core-ktx:1.13.0")
        force("androidx.appcompat:appcompat-resources:1.7.0")
    }
}

// Lyricon's AAR was built with compileSdk 36, so AGP refuses it while this
// app still targets 34. The library does not require API 36 at runtime.
tasks.withType(com.android.build.gradle.internal.tasks.CheckAarMetadataTask::class.java)
    .configureEach { enabled = false }

val skijaNative: Configuration by configurations.creating

// Compile the shared build tool on the host JVM. It is never packaged in the APK.
// Generated classes enter the ordinary AGP Java/D8 pipeline for every variant.
val qmlCompilerClasspath: Configuration by configurations.creating
val qmlToolClasses = layout.buildDirectory.dir("qml-aot/tool-classes")
val qmlBundleOutput = layout.buildDirectory.dir("qml-aot/bundle")
val compileQmlTool by tasks.registering(JavaCompile::class) {
    source(fileTree("${rootDir}/../qml-aot-tool/src/main/java") { include("**/*.java") })
    classpath = qmlCompilerClasspath
    destinationDirectory.set(qmlToolClasses)
    options.release.set(8)
}
val generateBundledQml by tasks.registering(JavaExec::class) {
    dependsOn(compileQmlTool)
    classpath = files(qmlToolClasses) + qmlCompilerClasspath
    mainClass.set("dev.t1m3.qplayer.aot.build.QmlBundleCompiler")
    args("--input", "${rootDir}/../shared-qml",
         "--output", qmlBundleOutput.get().asFile.absolutePath)
    inputs.files(fileTree("${rootDir}/../shared-qml") {
        include("**/*.qml", "**/*.js", "**/qmldir")
    }).withPathSensitivity(PathSensitivity.RELATIVE)
    outputs.dir(qmlBundleOutput)
}

val qmlRuntimeAssets = layout.buildDirectory.dir("qml-aot/assets")
val stageQmlRuntimeAssets by tasks.registering(Sync::class) {
    from("${rootDir}/../shared-qml") {
        exclude("**/*.qml", "**/qmldir")
    }
    into(qmlRuntimeAssets)
}

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
    dependsOn(generateBundledQml)
    dependsOn(stageQmlRuntimeAssets)
}
tasks.matching {
    it.name == "mergeDebugJniLibFolders" || it.name == "mergeReleaseJniLibFolders"
            || it.name == "mergePerfJniLibFolders"
}.configureEach {
    dependsOn(extractSkijaSo)
}

android {
    sourceSets["main"].jniLibs.srcDir(skijaJniDir)
    // Only images/fonts/JS modules are runtime resources. Component bytecode and
    // scene manifests come from the generated jar; no QML source fallback ships.
    sourceSets["main"].assets.srcDir(qmlRuntimeAssets)
}

dependencies {
    skijaNative("io.github.humbleui:skija-android-arm64:0.143.17")
    qmlCompilerClasspath("dev.t1m3.qplayer:player-core:0.1.0-SNAPSHOT")
    implementation(files(qmlBundleOutput.map { it.file("qml-bundle.jar") })
        .builtBy(generateBundledQml))

    // The RECODE refactor merged parser/engine/compiler/render into one module.
    implementation("io.github.timer-err:qml4j-core:$qml4jVersion")

    // Our platform-neutral player core (netease + lyrics + audio abstraction +
    // QML bridge). Pulls gson + zxing-core transitively; all Android-dexable.
    // Player core: netease + lyrics + audio abstraction + QML bridge + the
    // host-drawn lyric page (fluid SkSL backdrop + per-syllable renderer + the
    // QML/Skija frame compositor), shared with the desktop host.
    implementation("dev.t1m3.qplayer:player-core:0.1.0-SNAPSHOT") {
        // The host-side qmlCompilerClasspath still uses the Maven JVM artifact.
        // Runtime must select OkHttp's Android variant and Startup initializer.
        exclude(group = "com.squareup.okhttp3", module = "okhttp-jvm")
    }
    implementation("com.squareup.okhttp3:okhttp:$okhttpVersion")

    implementation("io.github.humbleui:skija-shared:0.143.17")
    implementation("io.github.humbleui:skija-android-arm64:0.143.17")

    implementation("androidx.appcompat:appcompat:1.7.0")

    // MediaSessionCompat + MediaStyle notification + media-button handling for
    // system media controls (lockscreen / notification / bluetooth).
    implementation("androidx.media:media:1.7.0")

    // Native Lyricon (词幕) provider: structured/syllable lyrics + playback
    // state for the status-bar lyric overlay.
    implementation("io.github.proify.lyricon:provider:0.1.70")

    testImplementation("junit:junit:4.13.2")
}
