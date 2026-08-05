plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.devtools.ksp")
}

// Firebase FCM when google-services.json is present (CI writes from secret/SSM).
val googleServicesFile = file("google-services.json")
if (googleServicesFile.exists()) {
    apply(plugin = "com.google.gms.google-services")
}

// Stable signing so OTA updates install over previous builds (same pattern as R2Android).
// CI loads PKCS12 from SSM (/android/r2finance/* or shared /android/cleaningbutton/*).
val uploadStoreFile = System.getenv("R2F_KEYSTORE_PATH")?.let { file(it) }
    ?: System.getenv("CB_KEYSTORE_PATH")?.let { file(it) }
val uploadStorePassword =
    System.getenv("R2F_KEYSTORE_PASSWORD") ?: System.getenv("CB_KEYSTORE_PASSWORD")
val uploadKeyAlias = System.getenv("R2F_KEY_ALIAS") ?: System.getenv("CB_KEY_ALIAS")
val uploadKeyPassword =
    System.getenv("R2F_KEY_PASSWORD") ?: System.getenv("CB_KEY_PASSWORD")
val hasUploadSigning =
    uploadStoreFile != null &&
        uploadStoreFile.isFile &&
        !uploadStorePassword.isNullOrBlank() &&
        !uploadKeyAlias.isNullOrBlank() &&
        !uploadKeyPassword.isNullOrBlank()

android {
    namespace = "com.cleaningbutton.r2finance"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.cleaningbutton.r2finance"
        minSdk = 26
        targetSdk = 35
        versionCode = 9
        versionName = "0.4.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField(
            "String",
            "API_BASE_URL",
            "\"https://x0wiir7m27.execute-api.us-east-1.amazonaws.com\"",
        )
        buildConfigField("String", "DEFAULT_USER_EMAIL", "\"jerome.ans@gmail.com\"")
        // Self-hosted OTA (not Play Store) — same pattern as R2Android.
        buildConfigField(
            "String",
            "UPDATE_MANIFEST_URL",
            "\"https://www.cleaningbutton.com/r2finance-builds/version.json\"",
        )
        buildConfigField(
            "String",
            "UPDATE_HISTORY_URL",
            "\"https://www.cleaningbutton.com/r2finance-builds/history.json\"",
        )
    }

    signingConfigs {
        if (hasUploadSigning) {
            create("upload") {
                storeFile = uploadStoreFile
                storePassword = uploadStorePassword
                keyAlias = uploadKeyAlias
                keyPassword = uploadKeyPassword
                storeType = "PKCS12"
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            if (hasUploadSigning) {
                signingConfig = signingConfigs.getByName("upload")
            }
        }
        debug {
            versionNameSuffix = "-debug"
            if (hasUploadSigning) {
                signingConfig = signingConfigs.getByName("upload")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.navigation:navigation-compose:2.8.5")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")

    val room = "2.6.1"
    implementation("androidx.room:room-runtime:$room")
    implementation("androidx.room:room-ktx:$room")
    ksp("androidx.room:room-compiler:$room")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("androidx.security:security-crypto:1.0.0")
    implementation("androidx.biometric:biometric:1.1.0")
    implementation("androidx.fragment:fragment-ktx:1.8.5")

    // Firebase Cloud Messaging (OTA update notifications)
    implementation(platform("com.google.firebase:firebase-bom:33.7.0"))
    implementation("com.google.firebase:firebase-messaging-ktx")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    testImplementation("androidx.room:room-testing:$room")
}
