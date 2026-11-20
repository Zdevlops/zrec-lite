plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.zrec.recorder"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.zrec.recorder"
        minSdk = 26
        targetSdk = 35
        versionCode = 8
        versionName = "1.0.0"
    }

    signingConfigs {
        // The keystore is never committed. CI decodes it from a secret and
        // points ZREC_KEYSTORE at the file. A local build without these
        // variables produces an unsigned APK on purpose.
        create("release") {
            val ksPath = System.getenv("ZREC_KEYSTORE")
            if (!ksPath.isNullOrBlank() && file(ksPath).exists()) {
                storeFile = file(ksPath)
                storePassword = System.getenv("ZREC_STORE_PASSWORD")
                keyAlias = System.getenv("ZREC_KEY_ALIAS") ?: "zrec"
                keyPassword = System.getenv("ZREC_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            if (signingConfigs.getByName("release").storeFile != null) {
                signingConfig = signingConfigs.getByName("release")
            }
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        resources.excludes += setOf("META-INF/*.kotlin_module")
    }
}

// No AndroidX on purpose: framework-only keeps the APK tiny and the app light.
dependencies {
}
