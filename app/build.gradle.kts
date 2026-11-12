plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.retromobilebench"
    compileSdk = 37

    // NDK r23c is the last NDK that supports API 16-18 and non-NEON ARMv7 (docs/02-platform-constraints.md).
    ndkVersion = "23.2.8568313"

    defaultConfig {
        applicationId = "com.retromobilebench"
        minSdk = 16
        // 23: lowest target Android 14+ will install; no behaviour change on 4.x (README "发布方式").
        targetSdk = 23
        versionCode = 1
        versionName = "0.0.1"

        ndk {
            abiFilters += listOf("armeabi-v7a", "x86")
        }
        externalNativeBuild {
            cmake {
                arguments += listOf(
                    "-DANDROID_STL=c++_static",
                    "-DANDROID_PLATFORM=android-16",
                )
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        // D8 desugars Java 9-17 language features and backports the java.util.Objects etc.
        // calls javac emits, so this is safe for minSdk 16.
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    externalNativeBuild {
        cmake {
            path = file("../native/CMakeLists.txt")
            version = "3.18.1"
        }
    }

    buildFeatures {
        buildConfig = true
    }
}

dependencies {
    // Intentionally empty: no AndroidX (minSdk 21+), no Kotlin stdlib (docs/02-platform-constraints.md).
}
