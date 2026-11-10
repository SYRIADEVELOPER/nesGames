plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.nesgames.app"
    compileSdk { version = release(37) }
    ndkVersion = "28.2.13676358"

    defaultConfig {
        applicationId = "com.nesgames.app"
        minSdk = 26          // AAudio
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0-m1"
        // arm64-v8a = shipping target; x86_64 only so the core can be tested on the PC emulator.
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
        externalNativeBuild { cmake { cppFlags += "-std=c++17" } }
    }
    externalNativeBuild {
        cmake { path = file("src/main/cpp/CMakeLists.txt"); version = "3.22.1" }
    }
    buildFeatures { compose = true; buildConfig = true }
    buildTypes {
        release {
            optimization { enable = true }
            signingConfig = signingConfigs.getByName("debug") // TODO M7: real release key
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    packaging { jniLibs { useLegacyPackaging = true } } // cores must exist as files for dlopen
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.icons)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.datastore.preferences)
    implementation(libs.documentfile)
    testImplementation(libs.junit)
}
