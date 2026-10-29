plugins {
    alias(libs.plugins.android.application)
    // 必須加入 Kotlin Serialization Plugin (如果你用 Version Catalog 請轉用 alias)
    id("org.jetbrains.kotlin.plugin.serialization") version "1.9.0" // 請確保版本號與你嘅 Kotlin 版本一致
}

android {
    namespace = "com.example.camera"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.example.camera"
        minSdk = 24
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            optimization {
                enable = true
                packageScope = setOf("androidx.**", "kotlin.**", "kotlinx.**")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.core.ktx)
    implementation(libs.material)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)

    val camerax_version = "1.3.1"
    implementation("androidx.camera:camera-core:${camerax_version}")
    implementation("androidx.camera:camera-camera2:${camerax_version}")
    implementation("androidx.camera:camera-lifecycle:${camerax_version}")
    implementation("androidx.camera:camera-view:${camerax_version}")
    
    // Coroutines (用嚟 background upload)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // Supabase
    val supabase_version = "3.0.1"
    implementation("io.github.jan-tennert.supabase:storage-kt:$supabase_version")
    implementation("io.github.jan-tennert.supabase:postgrest-kt:$supabase_version")
    
    // Ktor Client (Supabase 必需)
    val ktor_version = "3.0.0"
    implementation("io.ktor:ktor-client-android:$ktor_version")

    // Kotlin JSON Serialization
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")

    // Google Play Services Location
    implementation("com.google.android.gms:play-services-location:21.0.1")

    // USB Serial for Android (for ESP32 connection via OTG/Type-C)
    implementation("com.github.mik3y:usb-serial-for-android:3.7.3")
}