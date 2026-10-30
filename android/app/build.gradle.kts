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
        versionCode = 12
        versionName = "0.3.0"
        // Meta Wearables app id. Default "0" = DAT Developer Mode (Meta AI app >
        // glasses > Developer Mode must list Spotter). Override for production
        // with -PmwdatAppId=<id from Wearables Developer Center>. A placeholder
        // here silently kills glasses streaming, so the working default ships.
        // Stored as a string resource (not a manifest placeholder): aapt types
        // a bare "0" as an integer and the DAT SDK then cannot read it.
        val mwdatId = (findProperty("mwdatAppId") as String?) ?: "0"
        // Developer-mode builds ship client token "0"; a real token is only
        // needed once the app id comes from the Wearables Developer Center.
        val mwdatToken = (findProperty("mwdatClientToken") as String?) ?: "0"
        // MUST be manifest placeholders, not @string resources: the DAT SDK
        // reads these meta-data values with Bundle.getString(), and a resource
        // reference lands as a typed reference, which the SDK cannot read
        // ("Failed to read metadata from manifest: ...APPLICATION_ID").
        manifestPlaceholders["mwdatApplicationId"] = mwdatId
        manifestPlaceholders["mwdatClientToken"] = mwdatToken
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
