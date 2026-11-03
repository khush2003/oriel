plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.serialization")
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    api("com.github.MetrolistGroup.innertubex:innertubex:v0.10.0")
    api("io.ktor:ktor-client-okhttp:3.6.0")
    api("io.ktor:ktor-client-content-negotiation:3.6.0")
    api("io.ktor:ktor-serialization-kotlinx-json:3.6.0")
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.12.0")
    api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.12.0")
    api("com.squareup.okhttp3:okhttp:5.5.0")

    testImplementation(kotlin("test"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.12.0")
}

tasks.test {
    useJUnitPlatform()
    // Compiles to 17 for Android, but InnerTubeX is Java 21 bytecode, so the test JVM must be 21.
    javaLauncher.set(javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(21)) })
    // Live tests hit YouTube; opt in with -Plive so offline builds stay green.
    project.findProperty("dumps")?.let { systemProperty("oriel.dumps", it.toString()) }
    systemProperty("oriel.live", project.findProperty("live")?.toString() ?: "false")
    project.findProperty("streams")?.let { systemProperty("oriel.streams", it.toString()) }
    systemProperty("oriel.probe", project.findProperty("probe")?.toString() ?: "false")
    project.findProperty("ids")?.let { systemProperty("oriel.ids", it.toString()) }
    project.findProperty("debug")?.let { systemProperty("oriel.debug", it.toString()) }
    project.findProperty("repeat")?.let { systemProperty("oriel.repeat", it.toString()) }
    project.findProperty("plans")?.let { systemProperty("oriel.plans", it.toString()) }
    testLogging { events("passed", "failed", "skipped"); showStandardStreams = true; exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
}
