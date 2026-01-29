plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.openmsucivibes.vibeturn"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.openmsucivibes.vibeturn"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation("androidx.core:core-splashscreen:1.0.1") // Agrega 'androidx.core:core-splashscreen:1.0.1' // Verifica la última versión

    // AppCompat para actividades y compatibilidad con Material Design
    implementation("androidx.appcompat:appcompat:1.4.0")  // Asegúrate de usar la última versión estable
    // Use the modern Media3 ExoPlayer stack instead of the legacy media library.
    // See: https://developer.android.com/jetpack/androidx/releases/media3 for the latest version.
    implementation("androidx.media3:media3-exoplayer:1.3.1")
    implementation("androidx.media3:media3-ui:1.3.1")
    implementation("androidx.media3:media3-session:1.3.1")
    implementation ("com.google.android.material:material:1.6.0")
    implementation ("androidx.compose.material:material-icons-extended:1.0.0")  // Añade esta línea

    // LocalBroadcastManager para enviar y recibir broadcasts locales
    implementation("androidx.localbroadcastmanager:localbroadcastmanager:1.0.0")
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.activity)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}