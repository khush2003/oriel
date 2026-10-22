pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

plugins {
    // Provisions the JDK 21 the test JVMs need (InnerTubeX ships Java 21 bytecode).
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // JitPack builds anyone's GitHub repo on demand, so it serves InnerTubeX and nothing else,
        // and InnerTubeX comes from nowhere else. Its files are pinned in gradle/verification-metadata.xml.
        exclusiveContent {
            forRepository { maven("https://jitpack.io") }
            filter { includeGroup("com.github.MetrolistGroup.innertubex") }
        }
    }
}

rootProject.name = "Oriel"
include(":core")
include(":app")
