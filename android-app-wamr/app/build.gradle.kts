plugins {
    id("com.android.application")
    kotlin("android")
    kotlin("plugin.compose")
    id("org.jetbrains.kotlinx.kover")
}

android {
    namespace = "com.example.c2wdemo"
    compileSdk = 36
    buildToolsVersion = "36.0.0"
    ndkVersion = "27.2.12479018"

    defaultConfig {
        applicationId = "com.example.c2wdemo.wamr"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "3.0-friscy"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            // libriscv is pure C++ — builds for all Android ABIs
            abiFilters += listOf("arm64-v8a", "x86_64")
        }

        externalNativeBuild {
            cmake {
                cppFlags += listOf("-O3")
                arguments += listOf("-DCMAKE_BUILD_TYPE=Release")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
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

    buildFeatures {
        compose = true
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
        }
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
            excludes += listOf("lib/*/libtermux.so")
        }
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }

    lint {
        abortOnError = false
    }
}

dependencies {
    // Termux terminal emulator — vendored from termux-app v0.118.1
    // (was: implementation("com.github.termux.termux-app:terminal-emulator:v0.118.1"))
    // Source files at app/src/main/java/com/termux/terminal/
    // Minimal dependencies - most work done in native code
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("com.google.android.material:material:1.13.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.10.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    // SpringAnimation for smooth IME inset animations
    implementation("androidx.dynamicanimation:dynamicanimation-ktx:1.0.0-alpha03")
    // DataStore for preferences
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    // Compose (for Zim status gauge)
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.runtime:runtime")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.activity:activity-compose:1.10.0")

    // Testing
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.test:core:1.6.1")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")

    // Instrumented tests
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:core:1.6.1")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}

kover {
    currentProject {
        instrumentation {
            excludedClasses.addAll(
                "*.BuildConfig",
                "*.R",
                "*.R$*",
                "*_Factory",
                "*_MembersInjector"
            )
        }
    }
    reports {
        filters {
            excludes {
                androidGeneratedClasses()
                classes("*.BuildConfig")
            }
        }
        variant("debug") {
            xml {
                onCheck = false
                xmlFile = layout.buildDirectory.file("reports/kover/debug/report.xml")
            }
            html {
                onCheck = false
                htmlDir = layout.buildDirectory.dir("reports/kover/debug/html")
            }
        }
    }
}
