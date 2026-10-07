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
        // Fallback when repo.maven.apache.org TLS fails (common on some mainland networks).
        maven { url = uri("https://repo.huaweicloud.com/repository/maven/") }
    }
}

rootProject.name = "qml4j-android-shell"
include(":app")
