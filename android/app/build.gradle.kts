plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
}

android {
    namespace = "app.emberio"
    compileSdk = 37

    defaultConfig {
        applicationId = "app.emberio"
        minSdk = 26
        targetSdk = 36
        versionCode = 2
        versionName = "1.1.0"
    }

    // Release builds are signed with a key from the environment when CI has one
    // (see .github/workflows/build.yml); otherwise with the debug key, so a local
    // `assembleRelease` still gives an installable APK.
    val releaseKeystore = System.getenv("EMBERIO_KEYSTORE_FILE")
    signingConfigs {
        if (releaseKeystore != null) {
            // Trimmed: a secret pasted with a trailing space or line break would otherwise fail.
            create("release") {
                storeFile = file(releaseKeystore)
                storePassword = System.getenv("EMBERIO_KEYSTORE_PASSWORD")?.trim()?.removePrefix("VALUE:")
                keyAlias = System.getenv("EMBERIO_KEY_ALIAS")?.trim()?.removePrefix("VALUE:")
                keyPassword = System.getenv("EMBERIO_KEY_PASSWORD")?.trim()?.removePrefix("VALUE:")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.service)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    implementation(libs.coroutines.android)
    implementation(libs.pebblekit2.client)

    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
}
