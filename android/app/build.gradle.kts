plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Firma de release: si existen las variables (las pone el workflow desde los secretos del
// repositorio) se firma con la clave fija; si no, con la clave de depuración.
val ksFile = System.getenv("CRIBA_KEYSTORE_FILE")
val ksPass = System.getenv("CRIBA_KEYSTORE_PASSWORD")
val hasReleaseKey = !ksFile.isNullOrBlank() && !ksPass.isNullOrBlank() && file(ksFile).exists()

android {
    namespace = "io.github.loourx.criba"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.loourx.criba"
        minSdk = 30
        targetSdk = 34
        versionCode = (System.getenv("CRIBA_VERSION_CODE") ?: "1").toInt()
        versionName = System.getenv("CRIBA_VERSION_NAME") ?: "1.0.0"
    }

    signingConfigs {
        if (hasReleaseKey) {
            create("release") {
                storeFile = file(ksFile!!)
                storePassword = ksPass
                keyAlias = "criba"
                keyPassword = ksPass
                storeType = "pkcs12"
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = if (hasReleaseKey) signingConfigs.getByName("release")
                            else signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }

    lint {
        abortOnError = true
        checkReleaseBuilds = true
        // La app se instala a mano (no Play Store) y necesita "Acceso a todos los archivos".
        disable += setOf("ScopedStorage", "ExpiredTargetSdkVersion")
    }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.10.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.annotation:annotation:1.9.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-ui:1.4.1")
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("io.coil-kt:coil-video:2.7.0")
}
