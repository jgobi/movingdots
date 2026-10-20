import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

/**
 * Release signing key, from environment variables (CI, from repository secrets) or from a local,
 * git-ignored keystore.properties with the same keys. Without one, release APKs are unsigned:
 * that's what F-Droid expects, since it signs its builds with its own key.
 */
val releaseKey: Map<String, String>? = run {
    val file = rootProject.file("keystore.properties")
    val props = Properties().apply { if (file.exists()) file.inputStream().use { load(it) } }
    val keys = listOf("MOVINGDOTS_KEYSTORE", "MOVINGDOTS_KEYSTORE_PASSWORD", "MOVINGDOTS_KEY_ALIAS", "MOVINGDOTS_KEY_PASSWORD")
    val values = keys.associateWith { System.getenv(it) ?: props.getProperty(it) }
    if (values.values.all { !it.isNullOrBlank() }) values.mapValues { it.value!! } else null
}

android {
    namespace = "br.com.movingdots"
    compileSdk = 35

    defaultConfig {
        applicationId = "br.com.movingdots"
        minSdk = 31
        targetSdk = 35
        versionCode = 9
        versionName = "0.6.2"
        resourceConfigurations += listOf("en", "pt")
    }

    // "full": sideloaded release with "turn off when I leave the vehicle" (Google Play services).
    // "foss": F-Droid build without any Google dependency, and so without that option. Its own
    // application id and icon color let both be installed side by side for testing.
    flavorDimensions += "distribution"
    productFlavors {
        create("full") {
            dimension = "distribution"
            isDefault = true
        }
        create("foss") {
            dimension = "distribution"
            applicationIdSuffix = ".foss"
            versionNameSuffix = "-foss"
        }
    }

    signingConfigs {
        releaseKey?.let { key ->
            create("release") {
                storeFile = rootProject.file(key.getValue("MOVINGDOTS_KEYSTORE"))
                storePassword = key.getValue("MOVINGDOTS_KEYSTORE_PASSWORD")
                keyAlias = key.getValue("MOVINGDOTS_KEY_ALIAS")
                keyPassword = key.getValue("MOVINGDOTS_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (releaseKey != null) signingConfig = signingConfigs.getByName("release")
        }
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    packaging {
        resources.excludes += listOf("META-INF/*.version", "META-INF/**/*.kotlin_builtins", "kotlin/**", "DebugProbesKt.bin")
    }
}

dependencies {
    "fullImplementation"("com.google.android.gms:play-services-location:21.3.0")
    testImplementation("junit:junit:4.13.2")
}
