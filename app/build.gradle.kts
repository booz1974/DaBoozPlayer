plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "nl.jeroen.massqueue"
    compileSdk = 34

    defaultConfig {
        applicationId = "nl.jeroen.massqueue"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    kotlinOptions {
        jvmTarget = "1.8"
    }

    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.4")
    implementation("androidx.activity:activity-compose:1.9.1")

    val composeBom = platform("androidx.compose:compose-bom:2024.06.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    // Netwerk: OkHttp voor HTTP JSON-RPC naar Music Assistant
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    // JSON: org.json is ingebouwd in Android, geen extra plugin/dependency nodig

    // Albumart laden
    implementation("io.coil-kt:coil-compose:2.6.0")
    implementation("io.coil-kt:coil-gif:2.6.0")

    // Sleep-om-te-herordenen voor de wachtrij (inline in de lijst)
    implementation("sh.calvin.reorderable:reorderable:1.5.2")

    // MediaSession: bediening op lockscreen / notificatie / bluetooth-knoppen
    implementation("androidx.media:media:1.7.0")

    // Lokale instellingen opslaan (server-adres)
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    // Locatie services
    implementation("com.google.android.gms:play-services-location:21.3.0")
}
