plugins {
    id("com.android.application")
}

// The web app in ../web is bundled into the APK as assets/www.
val webAssets = layout.buildDirectory.dir("generated/webassets")
val syncWeb by tasks.registering(Sync::class) {
    from(rootProject.file("web"))
    into(webAssets.map { it.dir("www") })
    exclude("sw.js", "*.apk")
}

// The release key lives outside the repository. Its location and passwords come from
// ~/.gradle/gradle.properties (bcwa.keystore, bcwa.storePassword, bcwa.keyAlias, bcwa.keyPassword).
val keystorePath = providers.gradleProperty("bcwa.keystore").orNull

android {
    namespace = "com.barcodewa.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.barcodewa.app"
        minSdk = 24
        targetSdk = 35
        versionCode = 4
        versionName = "1.3"

        // Phones only: keeps the ML Kit native code to the two ARM builds.
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
    }

    sourceSets["main"].assets.srcDir(webAssets)

    signingConfigs {
        if (keystorePath != null) {
            create("release") {
                storeFile = file(keystorePath)
                storePassword = providers.gradleProperty("bcwa.storePassword").get()
                keyAlias = providers.gradleProperty("bcwa.keyAlias").get()
                keyPassword = providers.gradleProperty("bcwa.keyPassword").get()
            }
        }
    }

    buildTypes {
        getByName("release") {
            // Shrinks the camera and ML Kit libraries to what the app uses.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (keystorePath != null) signingConfig = signingConfigs.getByName("release")
        }
    }

    // Release lint pulls in a large set of extra libraries; it is not needed for this small app.
    lint {
        checkReleaseBuilds = false
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("androidx.activity:activity:1.9.3")
    implementation("androidx.camera:camera-core:1.4.1")
    implementation("androidx.camera:camera-camera2:1.4.1")
    implementation("androidx.camera:camera-lifecycle:1.4.1")
    implementation("androidx.camera:camera-view:1.4.1")
    // The barcode model is bundled in the app, so scanning works offline and without Google Play services.
    implementation("com.google.mlkit:barcode-scanning:17.3.0")
    testImplementation("junit:junit:4.13.2")
}

tasks.named("preBuild") { dependsOn(syncWeb) }
