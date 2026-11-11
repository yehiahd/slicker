plugins {
    id("com.android.library")
    `maven-publish`
}

// On JitPack, GROUP (com.github.<user>) and VERSION (the git tag) are set by the build.
group = System.getenv("GROUP") ?: "dev.slicker"
version = System.getenv("VERSION") ?: "0.1.0"

android {
    namespace = "dev.slicker"
    compileSdk = 36
    defaultConfig { minSdk = 23 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    publishing {
        singleVariant("release") { withSourcesJar() }
    }
}

dependencies {
    api("com.squareup.okhttp3:okhttp:4.12.0")
}

publishing {
    publications {
        register<MavenPublication>("release") {
            artifactId = "slicker"
            afterEvaluate { from(components["release"]) }
        }
    }
}
