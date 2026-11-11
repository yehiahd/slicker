plugins {
    id("com.android.application")
}

android {
    namespace = "dev.slicker.sample"
    compileSdk = 36
    defaultConfig {
        applicationId = "dev.slicker.sample"
        minSdk = 23
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    debugImplementation(project(":slicker"))
    releaseImplementation(project(":slicker-noop"))
}
