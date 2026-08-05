plugins {
    id("com.android.application") version "8.7.3"
    id("org.jetbrains.kotlin.android") version "2.0.21"
}

android {
    namespace = "nu.hyperworks.cellstation"
    compileSdk = 35

    defaultConfig {
        applicationId = "nu.hyperworks.cellstation"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0-pre-alpha"
        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    // libcellstation.so is built by the standalone CMake superproject (native/)
    // in CI and dropped into src/main/jniLibs/arm64-v8a/ before assembling.
    packaging {
        jniLibs {
            useLegacyPackaging = false
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.9.3")
}
