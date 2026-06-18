plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    id("com.google.gms.google-services")
    alias(libs.plugins.firebase.crashlytics)
    alias(libs.plugins.firebase.perf)
}

val defaultVersionName = "1.3.4"

val appVersionName: String = System.getenv("APP_VERSION_NAME") ?: defaultVersionName

val buildNumber = System.getenv("BUILD_NUMBER")?.toIntOrNull() ?: 0

fun getVersionCodeFrom(name: String, build: Int): Int {
    val cleanVersion = name.substringBefore("-")
    val parts = cleanVersion.split(".").map { it.toIntOrNull() ?: 0 }

    val major = parts.getOrElse(0) { 0 }
    val minor = parts.getOrElse(1) { 0 }
    val patch = parts.getOrElse(2) { 0 }

    // versionCode layout: MAJOR | MINOR(2) | PATCH(2) | BUILD(3)
    //   1.3.3  + run 38  -> 10303038
    //   2.4.5  + run 120 -> 20405120
    // The GitHub Actions run number occupies the last 3 digits (0..999); the version is
    // readable in the high digits. Strictly increasing as long as version and run only
    // grow. minor/patch are 2 digits (0..99), major up to ~214 (Int.MAX_VALUE limit).
    return (major * 10_000_000) + (minor * 100_000) + (patch * 1_000) + build
}

val appVersionCode = getVersionCodeFrom(appVersionName, buildNumber)

android {
    namespace = "com.vasmarfas.UniversalAmbientLight"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.vasmarfas.UniversalAmbientLight"
        minSdk = 26
        targetSdk = 36
        versionName = appVersionName
        versionCode = appVersionCode

        externalNativeBuild {
            cmake {
                abiFilters("armeabi-v7a")
            }
        }
    }

    signingConfigs {
        create("release") {
            val keystorePath = System.getenv("KEYSTORE_PATH")
            if (keystorePath != null) {
                storeFile = file(keystorePath)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }

    flavorDimensions += "distribution"
    productFlavors {
        // GitHub / RuStore: full feature set, including the AccessibilityService capture
        // fallback and accessibility-assisted ADB auto-pairing.
        create("full") {
            dimension = "distribution"
            buildConfigField("boolean", "HAS_ACCESSIBILITY", "true")
        }
        // Google Play: no AccessibilityService (the API is disallowed here without an
        // eligible accessibility-tool use case). Screen capture stays on MediaProjection.
        create("playstore") {
            dimension = "distribution"
            buildConfigField("boolean", "HAS_ACCESSIBILITY", "false")
        }
    }

    buildTypes {
        getByName("debug") {
            // Disable Firebase in debug builds with dummy google-services.json,
            // but keep it enabled when a real one is present (e.g., for testing).
            val gsFile = file("google-services.json")
            val hasDummyFirebase = !gsFile.exists() || gsFile.readText().contains("dummy-local-dev")
            manifestPlaceholders["firebaseCrashlyticsEnabled"] = (!hasDummyFirebase).toString()
            manifestPlaceholders["firebaseAnalyticsDeactivated"] = hasDummyFirebase.toString()
            manifestPlaceholders["firebasePerfDeactivated"] = hasDummyFirebase.toString()
        }
        getByName("release") {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (System.getenv("KEYSTORE_PATH") != null) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/jni/CMakeLists.txt")
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.jar"))))
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.preference.ktx)
    implementation(libs.androidx.localbroadcastmanager)
    implementation(libs.flatbuffers.java)
    implementation(libs.dadb)
    implementation(libs.libadb.android)
    implementation(libs.conscrypt.android)
    implementation(libs.sun.security.android)
    implementation(libs.usb.serial)

    implementation(libs.androidx.leanback)
    implementation(libs.androidx.leanback.preference)
    implementation(libs.konfetti.xml)

    // Compose
    val composeBom = platform(libs.androidx.compose.bom)
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.tooling.preview)
    debugImplementation(libs.androidx.ui.tooling)

    // Material Design 3
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)

    // Compose for TV
    implementation(libs.androidx.tv.foundation)
    implementation(libs.androidx.tv.material)

    // Integration with activities
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.app.update)
    implementation(libs.app.update.ktx)
    implementation(libs.play.review)
    implementation(libs.play.review.ktx)
    implementation(libs.zxing.core)

    // CameraX
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)

    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.analytics)
    implementation(libs.firebase.crashlytics)
    implementation(libs.firebase.perf)
    implementation(libs.firebase.config)
}

// Generate a dummy google-services.json for local builds when the real one is missing.
// CI places the real file from secrets before this task runs, so it's a no-op there.
tasks.register("generateDummyGoogleServicesJson") {
    val gsFile = file("google-services.json")
    onlyIf { !gsFile.exists() }
    doLast {
        gsFile.writeText(
            """{
  "project_info": {
    "project_number": "000000000000",
    "project_id": "dummy-local-dev",
    "storage_bucket": "dummy-local-dev.appspot.com"
  },
  "client": [
    {
      "client_info": {
        "mobilesdk_app_id": "1:000000000000:android:0000000000000000",
        "android_client_info": {
          "package_name": "com.vasmarfas.UniversalAmbientLight"
        }
      },
      "api_key": [
        {
          "current_key": "AIzaSyDummyLocalDevKeyNotReal000000"
        }
      ]
    }
  ],
  "configuration_version": "1"
}
"""
        )
        logger.lifecycle("Generated dummy google-services.json for local build")
    }
}

tasks.named("preBuild") {
    dependsOn("generateDummyGoogleServicesJson")
}
