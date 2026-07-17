plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.lsplugin.apksign)
}

apksign {
    storeFileProperty = "androidStoreFile"
    storePasswordProperty = "androidStorePassword"
    keyAliasProperty = "androidKeyAlias"
    keyPasswordProperty = "androidKeyPassword"
}

android {
    namespace = "com.example.flipunlock"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.example.flipunlock"
        minSdk = 33
        targetSdk = 36
        versionCode = 9
        versionName = "2.6.1"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    buildFeatures {
        buildConfig = true
    }
    lint {
        disable += "SoonBlockedPrivateApi"
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    compileOnly(libs.libxposed.api)
    implementation(libs.libxposed.service)
    implementation(libs.dexkit)
}
