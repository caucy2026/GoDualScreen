plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.go.dualscreen"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.go.dualscreen"
        minSdk = 31
        targetSdk = 34
        versionCode = 2
        versionName = "10.2"
    }

    signingConfigs {
        create("release") {
            storeFile = file(System.getProperty("user.home") + "/debug.keystore")
            storePassword = "880203"
            keyAlias = "androiddebugkey"
            keyPassword = "880203"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
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
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
}
