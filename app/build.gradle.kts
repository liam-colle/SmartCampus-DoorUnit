plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.smartcampus.door"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.smartcampus.door"
        // Android 6. Older terminals are not supported: the Keystore only does ECDSA from API 23.
        minSdk = 23
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        // java.time (night sleep, quiet hours, the clock) only exists from API 26: it is backported at build time.
        isCoreLibraryDesugaringEnabled = true
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

dependencies {
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")
    implementation(project(":core"))

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")

    // QR scanner that works without Google Play services, which Famoco terminals may not have.
    implementation("com.journeyapps:zxing-android-embedded:4.3.0") { isTransitive = false }
    // Pinned: newer zxing core releases need API 24 or later. Enough for QR codes.
    implementation("com.google.zxing:core:3.3.0") { isTransitive = false }
    // Pinned: newer zxing core releases need API 24 or later. Enough for QR codes.
    implementation("com.google.zxing:core:3.3.0")

    implementation(platform("androidx.compose:compose-bom:2024.10.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
