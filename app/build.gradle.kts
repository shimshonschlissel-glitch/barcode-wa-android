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
        versionCode = 1
        versionName = "1.0"
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
            isMinifyEnabled = false
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

tasks.named("preBuild") { dependsOn(syncWeb) }
