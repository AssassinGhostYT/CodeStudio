plugins {
    id("com.android.library")
    id("kotlin-android")
}

android {
    namespace = "com.termux"
    compileSdk = 36
    compileSdkMinor = 1
    ndkVersion = "27.1.12297006"

    defaultConfig {
        minSdk = 26
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    externalNativeBuild {
        ndkBuild {
            path = file("src/main/jni/Android.mk")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("androidx.annotation:annotation:1.7.1")
    implementation("androidx.appcompat:appcompat:1.6.1")
}