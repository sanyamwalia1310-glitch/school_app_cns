plugins {
    // Keep root and android-app builds on the same, Gradle-8.7-compatible toolchain.
    id("com.android.application") version "8.6.1" apply false
    id("org.jetbrains.kotlin.android") version "2.1.20" apply false
    // The mobile module applies this plugin to process app/google-services.json.
    id("com.google.gms.google-services") version "4.4.4" apply false
}
