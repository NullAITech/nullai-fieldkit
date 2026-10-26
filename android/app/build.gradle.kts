plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "tech.nullai.fieldkit"
    compileSdk = 35

    defaultConfig {
        applicationId = "tech.nullai.fieldkit"
        minSdk = 30
        targetSdk = 34
        versionCode = 2
        versionName = "0.2.0"
        // Meta Wearables Developer Center app id. Override with -PmwdatAppId=<id>.
        manifestPlaceholders["mwdatAppId"] =
            (findProperty("mwdatAppId") as String?) ?: "REPLACE_WITH_WEARABLES_APP_ID"
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
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.google.zxing:core:3.5.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    // Meta Wearables Device Access Toolkit (Ray-Ban Meta glasses via Meta AI app)
    implementation("com.meta.wearable:mwdat-core:1.0.1")
    implementation("com.meta.wearable:mwdat-camera:1.0.1")
    implementation("com.meta.wearable:mwdat-mockdevice:1.0.1")
}
