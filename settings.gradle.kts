pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.10.0"
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // Alleen voor sendspin-jvm (geen Maven Central-release)
        maven {
            url = uri("https://jitpack.io")
            content { includeGroup("com.github.Sendspin") }
        }
    }
}

rootProject.name = "MassQueueApp"
include(":app")
