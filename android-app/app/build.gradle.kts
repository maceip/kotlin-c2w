plugins {
    id("com.android.application")
    kotlin("android")
}

android {
    namespace = "com.example.c2wdemo"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.example.c2wdemo"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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
    // wasi-emscripten-host with Chicory binding (from Maven Central)
    implementation("at.released.weh:bindings-chicory-wasip1-jvm:0.6.0")
    implementation("com.dylibso.chicory:runtime:1.5.1")

    // Android
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
}
