import org.jetbrains.compose.desktop.application.dsl.TargetFormat

// Windows (and dev-time Linux/macOS) desktop app. Shares all UI and data code with Android via :shared.
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

/** App version, shared with the Android app (see gradle.properties). */
val appVersion: String = providers.gradleProperty("app.versionName").get()

// Native FFmpeg/JavaCPP binaries for the OS we package for (CI builds Windows on Windows).
val nativeClassifier: String = providers.gradleProperty("desktop.natives").orNull ?: run {
    val os = System.getProperty("os.name").lowercase()
    val arch = if (System.getProperty("os.arch").contains("aarch64")) "arm64" else "x86_64"
    when {
        os.contains("win") -> "windows-$arch"
        os.contains("mac") -> "macosx-$arch"
        else -> "linux-$arch"
    }
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    implementation(project(":shared"))
    implementation(compose.desktop.currentOs)
    implementation(libs.kotlinx.coroutines.swing)
    implementation(libs.androidx.sqlite.bundled)

    // Audio decoding: FFmpeg via JavaCV (LGPL build). Only FFmpeg + JavaCPP, not OpenCV etc.
    implementation(libs.javacv) { isTransitive = false }
    implementation(libs.javacpp)
    implementation(libs.ffmpeg)
    runtimeOnly(variantOf(libs.javacpp) { classifier(nativeClassifier) })
    runtimeOnly(variantOf(libs.ffmpeg) { classifier(nativeClassifier) })

    implementation(libs.jaudiotagger)
    implementation(libs.jnativehook)
    implementation(libs.eddsa)

    testImplementation(kotlin("test-junit"))
    testImplementation("org.jetbrains.compose.ui:ui-test-junit4:${libs.versions.composeMultiplatform.get()}")
}

tasks.withType<Test>().configureEach {
    systemProperty("java.awt.headless", "true")
    environment("DEMO_MUSIC_DIR", System.getenv("DEMO_MUSIC_DIR") ?: "")
    maxHeapSize = "2g"
}

compose.desktop {
    application {
        mainClass = "com.novanity.musicplayer.desktop.MainKt"
        jvmArgs += listOf("-Dapp.version=$appVersion", "-Xmx768m")

        nativeDistributions {
            targetFormats(TargetFormat.Exe, TargetFormat.Msi)
            packageName = "MusicPlayer"
            packageVersion = appVersion
            description = "Music Player"
            vendor = "NoVanity"
            copyright = "© NoVanity"
            // Modules the bundled runtime needs (sqlite driver, JNA/JNI loaders, HTTPS for updates).
            modules("java.sql", "jdk.unsupported", "java.net.http", "jdk.crypto.ec", "java.naming", "jdk.accessibility")

            windows {
                iconFile.set(rootProject.file("branding/icon.ico"))
                // Never change this: it's what lets a new installer upgrade the existing install in place.
                upgradeUuid = "6F3C2A8E-4B1D-4C7E-9A52-0D8E1B7F3C21"
                menuGroup = "Music Player"
                shortcut = true
                dirChooser = true
                perUserInstall = true
            }
        }

        buildTypes.release.proguard { isEnabled.set(false) }
    }
}
