plugins {
    id("com.android.application")
}

android {
    namespace = "de.gabriel.streamport"
    compileSdk = 35

    defaultConfig {
        applicationId = "de.gabriel.streamport"
        minSdk = 23
        targetSdk = 35
        versionCode = 9
        versionName = "2.2"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
