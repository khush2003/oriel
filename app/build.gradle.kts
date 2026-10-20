import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("io.github.takahirom.roborazzi")
}

val hostQuickJsNative: Configuration by configurations.creating { isTransitive = false }
val hostQuickJsDir = layout.buildDirectory.dir("hostJni")
val hostQuickJs = tasks.register<Sync>("hostQuickJs") {
    from({ hostQuickJsNative.map { zipTree(it) } }) { include("jni/linux_x64/*"); eachFile { path = name } }
    into(hostQuickJsDir)
    includeEmptyDirs = false
}

android {
    namespace = "app.oriel"
    // InnerTubeX's Android artifact is compiled against 37; runtime behaviour targets 36.
    compileSdk = 37

    defaultConfig {
        applicationId = "app.oriel"
        minSdk = 26
        targetSdk = 36
        versionCode = 15
        versionName = "0.9.01-alpha"
        // Opt-in usage counts (see Telemetry.kt). Empty, the default, builds an app that reports
        // nowhere and never asks: set -PaptabaseKey=A-EU-… or APTABASE_KEY for official releases.
        val aptabaseKey = (project.findProperty("aptabaseKey") as String?) ?: System.getenv("APTABASE_KEY") ?: ""
        buildConfigField("String", "APTABASE_KEY", "\"$aptabaseKey\"")
    }

    signingConfigs {
        // A release keystore is optional: without one, release builds are signed with the
        // debug key so they still install. See keystore.properties.example.
        val props = rootProject.file("keystore.properties")
        if (props.exists()) {
            val p = Properties().apply { props.inputStream().use { load(it) } }
            create("release") {
                storeFile = rootProject.file(p.getProperty("storeFile"))
                storePassword = p.getProperty("storePassword")
                keyAlias = p.getProperty("keyAlias")
                keyPassword = p.getProperty("keyPassword")
            }
        }
    }

    splits {
        // One APK per architecture, smaller to download, plus a universal one that runs anywhere.
        // They share a versionCode: each is a complete app, and installing any over another updates it.
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64", "x86")
            isUniversalApk = true
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
        }
        release {
            // R8 is on for speed, not size: Compose runs measurably slower un-optimized.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += setOf("META-INF/INDEX.LIST", "META-INF/DEPENDENCIES", "META-INF/*.kotlin_module", "META-INF/versions/9/previous-compilation-data.bin")
            pickFirsts += setOf("META-INF/LICENSE.md", "META-INF/LICENSE.txt", "META-INF/NOTICE.md")
        }
    }

    lint {
        // Errors that predate CI are frozen here; anything new fails the build. Don't add to it.
        baseline = file("lint-baseline.xml")
        abortOnError = true
        checkReleaseBuilds = false
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            all {
                it.systemProperty("robolectric.graphicsMode", "NATIVE")
                it.systemProperty("oriel.live", project.findProperty("live")?.toString() ?: "false")
                project.findProperty("link")?.let { link -> it.systemProperty("oriel.link", link.toString()) }
                // Which videos the live playback tests play; also an input, so a new list reruns them.
                project.findProperty("ids")?.let { ids -> it.systemProperty("oriel.ids", ids.toString()) }
                it.systemProperty("oriel.frames", project.findProperty("frames")?.toString() ?: "false")
                it.maxHeapSize = "2g"
                // QuickJS (cipher solving) is Android-native in the app; its desktop build has the
                // same JNI exports, so the host tests load that one and play the fixed-quality streams too.
                it.dependsOn(hostQuickJs)
                it.systemProperty("java.library.path", hostQuickJsDir.get().asFile.absolutePath)
                // InnerTubeX is Java 21 bytecode; D8 handles it for the APK, but Robolectric loads it on the host JVM.
                it.javaLauncher.set(project.extensions.getByType<JavaToolchainService>().launcherFor { languageVersion.set(JavaLanguageVersion.of(21)) })
            }
        }
    }
}

composeCompiler {
    stabilityConfigurationFiles.add(rootProject.layout.projectDirectory.file("app/compose-stability.conf"))
}

dependencies {
    implementation(project(":core"))

    val composeBom = platform("androidx.compose:compose-bom:2026.09.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.animation:animation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.core:core-ktx:1.19.1")
    implementation("androidx.core:core-splashscreen:1.2.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.11.0")
    implementation("androidx.lifecycle:lifecycle-process:2.11.0")
    implementation("androidx.navigation:navigation-compose:2.10.2")
    implementation("androidx.datastore:datastore-preferences:1.2.1")
    implementation("androidx.palette:palette-ktx:1.0.0")
    implementation("androidx.webkit:webkit:1.17.1")

    implementation("androidx.media3:media3-exoplayer:1.11.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.11.1")
    implementation("androidx.media3:media3-session:1.11.1")
    implementation("androidx.media3:media3-datasource-okhttp:1.11.1")
    implementation("androidx.media3:media3-ui-compose:1.11.1")

    implementation("io.coil-kt.coil3:coil-compose:3.6.3")
    implementation("io.coil-kt.coil3:coil-network-okhttp:3.6.3")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-guava:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")

    hostQuickJsNative("io.github.dokar3:quickjs-kt-jvm:1.0.15")
    testImplementation("junit:junit:4.13.2")
    // Fake MediaCodec decoders, so ExoPlayer can play a live stream under Robolectric.
    testImplementation("androidx.media3:media3-test-utils-robolectric:1.11.1")
    testImplementation("org.robolectric:robolectric:4.17")
    testImplementation("io.github.takahirom.roborazzi:roborazzi:1.76.0")
    testImplementation("io.github.takahirom.roborazzi:roborazzi-compose:1.76.0")
    testImplementation("androidx.compose.ui:ui-test-junit4")
    testImplementation("androidx.test.ext:junit:1.3.0")
    testImplementation("androidx.test:core-ktx:1.7.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
