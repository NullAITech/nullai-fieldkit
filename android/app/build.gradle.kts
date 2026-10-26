plugins {
    id("com.android.application")
}

android {
    namespace = "tech.nullai.fieldkit"
    compileSdk = 34

    defaultConfig {
        applicationId = "tech.nullai.fieldkit"
        minSdk = 30
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}
