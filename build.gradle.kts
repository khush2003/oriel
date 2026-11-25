plugins {
    id("com.android.application") version "9.4.1" apply false
    id("org.jetbrains.kotlin.jvm") version "2.4.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.21" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.4.21" apply false
    id("io.github.takahirom.roborazzi") version "1.76.0" apply false
}

val detekt = configurations.create("detekt")

dependencies {
    detekt("io.gitlab.arturbosch.detekt:detekt-cli:1.23.8")
}

tasks.register<JavaExec>("detekt") {
    group = "verification"
    description = "Static analysis of the main Kotlin sources; existing findings are in detekt-baseline.xml."
    classpath = detekt
    mainClass = "io.gitlab.arturbosch.detekt.cli.Main"
    args(
        "--input", "app/src/main/java,core/src/main/kotlin",
        // Vendored.
        "--excludes", "**/ui/glass/backdrop/**",
        "--config", "detekt.yml",
        "--build-upon-default-config",
        "--baseline", "detekt-baseline.xml",
    )
}
