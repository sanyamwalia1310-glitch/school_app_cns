plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.gms.google-services")
}

import java.util.Properties

val keystoreProperties = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) {
        file.inputStream().use(::load)
    }
}

fun configString(name: String, defaultValue: String = ""): String =
    providers.gradleProperty(name)
        .orElse(providers.environmentVariable(name))
        .orElse(defaultValue)
        .get()
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")

android {
    namespace = "com.schoolms.mobile"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.sanyam.loginui"
        minSdk = 23
        targetSdk = 35
        versionCode = 25
        versionName = "1.6.8"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("String", "BOOTSTRAP_ADMIN_USERNAME", "\"${configString("SCHOOLMS_BOOTSTRAP_ADMIN_USERNAME")}\"")
        buildConfigField("String", "BOOTSTRAP_ADMIN_PASSWORD", "\"${configString("SCHOOLMS_BOOTSTRAP_ADMIN_PASSWORD")}\"")
        buildConfigField("String", "BOOTSTRAP_ADMIN_NAME", "\"${configString("SCHOOLMS_BOOTSTRAP_ADMIN_NAME")}\"")
        // Public HTTPS endpoint only. Never put the 2Factor provider key in this app.
        buildConfigField("String", "FLASK_BASE_URL", "\"${configString("SCHOOLMS_FLASK_BASE_URL")}\"")
    }

    buildFeatures {
        buildConfig = true
    }

    signingConfigs {
        if (keystoreProperties.isNotEmpty()) {
            create("release") {
                storeFile = rootProject.file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = if (keystoreProperties.isNotEmpty()) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.activity:activity-ktx:1.9.2")
    implementation("androidx.biometric:biometric:1.1.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("com.google.code.gson:gson:2.10.1")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    implementation(platform("com.google.firebase:firebase-bom:33.13.0"))
    implementation("com.google.firebase:firebase-auth-ktx")
    implementation("com.google.firebase:firebase-firestore-ktx")
    implementation("com.google.firebase:firebase-messaging-ktx")
    implementation("com.google.firebase:firebase-storage-ktx")
}
