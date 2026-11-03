plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.ekeditor.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.ekeditor.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 16
        versionName = "0.7.0"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}
