plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.jetbrainsKotlinAndroid)
    // Sử dụng KSP version tương thích với Kotlin 1.9.22
    id("com.google.devtools.ksp") version "1.9.22-1.0.17"
}

android {
    namespace = "com.example.ocrmanga"
    compileSdk = 36

    sourceSets {
        getByName("main") {
            jniLibs.srcDir(file("../opencv-4.12.0-android-sdk/OpenCV-android-sdk/sdk/native/libs"))
        }
    }

    defaultConfig {
        applicationId = "com.example.ocrmanga"
        minSdk = 28
        targetSdk = 36
        versionCode = 3
        versionName = "1.5"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }

        ndk {
            abiFilters += listOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            isCrunchPngs = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.8"
    }
    aaptOptions {
        noCompress += "onnx"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += setOf("META-INF/DEPENDENCIES")
        }
        jniLibs {
            pickFirsts += setOf("**/libopencv_java4.so", "lib/**/libc++_shared.so")
        }
    }
}

dependencies {
    // Sử dụng Version Catalog cho các thư viện chuẩn
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.core.splashscreen)

    // Các thư viện Compose bổ sung
    implementation("androidx.compose.material3:material3-window-size-class")
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.navigation:navigation-compose:2.8.9")

    // Kotlin Coroutines & Serialization
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.8.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.0")

    // Image & Networking
    implementation("io.coil-kt:coil-compose:2.4.0")
    implementation ("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.google.code.gson:gson:2.10.1")
    implementation("org.jsoup:jsoup:1.17.2")

    // ML Kit & Google APIs
    implementation("com.google.mlkit:translate:17.0.3")
    implementation("com.google.mlkit:text-recognition:16.0.1")
    implementation("com.google.mlkit:text-recognition-chinese:16.0.1")
    implementation("com.google.mlkit:text-recognition-japanese:16.0.1")
    implementation("com.google.mlkit:text-recognition-korean:16.0.1")
    implementation ("com.google.mlkit:language-id:17.0.4")
    implementation ("androidx.compose.material:material-icons-extended:1.6.1")
    implementation("com.google.ai.client.generativeai:generativeai:0.9.0")

    // Room Database
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")
    implementation("androidx.sqlite:sqlite:2.3.1")

    // OpenCV & ML Libraries
    implementation(project(":opencv"))
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.18.0")

    // Google Drive & Auth
    implementation("com.google.android.gms:play-services-auth:21.1.0")
    implementation("com.google.api-client:google-api-client-android:1.35.0")
    implementation("com.google.apis:google-api-services-drive:v3-rev20230815-2.0.0")
    implementation("com.google.http-client:google-http-client-gson:1.43.3")

    // Utilities
    implementation("androidx.exifinterface:exifinterface:1.3.7")
    implementation("org.json:json:20230227")

    // Testing
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}
