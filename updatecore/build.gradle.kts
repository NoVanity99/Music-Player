// Plain-JVM helpers for the self-updater, used by both the Android and desktop apps.
plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    // Pure-Java Ed25519 (works on every Android version, unlike the platform's own provider).
    implementation(libs.eddsa)
    testImplementation(kotlin("test-junit"))
}
