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
        // CI stamps these from the git tag / run number; local builds fall back.
        versionCode = (System.getenv("CELLSTATION_VERSION_CODE") ?: "1").toInt()
        versionName = System.getenv("CELLSTATION_VERSION") ?: "0.1.0-dev"
        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    signingConfigs {
        create("release") {
            // ci-keystore.jks is committed and its passwords are public: it
            // provides a *stable sideload identity* (updates install over each
            // other), not authenticity. To sign with a private key, override
            // all four environment variables.
            storeFile = file(System.getenv("CELLSTATION_KEYSTORE") ?: "ci-keystore.jks")
            storePassword = System.getenv("CELLSTATION_KEYSTORE_PASS") ?: "cellstation"
            keyAlias = System.getenv("CELLSTATION_KEY_ALIAS") ?: "cellstation"
            keyPassword = System.getenv("CELLSTATION_KEY_PASS") ?: "cellstation"
        }
    }

    buildTypes {
        release {
            // The emulator core is all native code, already built -O2 by the
            // separate CMake project; minifying the thin Kotlin shell buys
            // nothing and complicates JNI.
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
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
