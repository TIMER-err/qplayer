pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        mavenLocal()
        google()
        mavenCentral()
        exclusiveContent {
            forRepository {
                maven {
                    name = "JitPack"
                    url = uri("https://jitpack.io")
                    mavenContent { releasesOnly() }
                }
            }
            filter { includeGroup("com.github.TIMER-err.qml4j") }
        }
        // Fallback when repo.maven.apache.org TLS fails (common on some mainland networks).
        maven { url = uri("https://repo.huaweicloud.com/repository/maven/") }
    }
}

rootProject.name = "qml4j-android-shell"
include(":app")
