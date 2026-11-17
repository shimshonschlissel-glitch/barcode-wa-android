plugins {
    id("com.android.application")
}

val run = System.getenv("GITHUB_RUN_NUMBER") ?: "1"

android {
    namespace = "com.barcodewa.test"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.barcodewa.test"
        minSdk = 24
        targetSdk = 35
        versionCode = run.toInt()
        versionName = "test-$run"
    }

    // A fixed key for this throwaway test app only, so a newer test build installs over an older one.
    // The real app will be signed with a private key kept in GitHub secrets.
    signingConfigs {
        create("test") {
            storeFile = file("test.keystore")
            storePassword = "android"
            keyAlias = "test"
            keyPassword = "android"
        }
    }

    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("test")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
