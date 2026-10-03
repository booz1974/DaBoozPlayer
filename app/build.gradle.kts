import java.text.SimpleDateFormat
import java.util.Date
import java.util.Properties

fun localProperty(key: String): String? {
    val file = rootProject.file("local.properties")
    if (!file.exists()) return null
    return Properties().apply { file.inputStream().use { load(it) } }.getProperty(key)?.takeIf { it.isNotBlank() }
}

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "nl.jeroen.massqueue"
    compileSdk = 36

    defaultConfig {
        // Lokaal te overschrijven via spinflow.applicationId in local.properties (niet in git),
        // bijv. om een eerder geïnstalleerde versie met een ander ID bij te werken
        applicationId = localProperty("spinflow.applicationId") ?: "nl.jeroen.massqueue"
        minSdk = 26
        targetSdk = 36
        versionCode = 9
        versionName = "3.3.1"
        resValue("string", "app_name", "SpinFlow")
        buildConfigField(
            "String", "BUILD_TIME",
            "\"${SimpleDateFormat("yyyy-MM-dd HH:mm").format(Date())}\""
        )
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        // 17: sendspin-jvm is JDK 17-bytecode (inline-functies vereisen hetzelfde target)
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

    // Telefoon als MA-speler: Sendspin-client (legacy, zonder Noise-encryptie) + Media3-sessie
    implementation("com.github.Sendspin:sendspin-jvm:v0.3.4") {
        // org.json zit al in Android zelf
        exclude(group = "org.json", module = "json")
    }
    implementation("com.squareup.moshi:moshi-kotlin:1.15.1")
    implementation("androidx.media3:media3-session:1.4.1")

    // Locatie services
    implementation("com.google.android.gms:play-services-location:21.3.0")
}
