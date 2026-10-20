plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.flux.tv"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.flux.tv"
        minSdk = 21          // X96 mini = Android 7.1 (API 25) ; marge pour les vieux firmwares
        targetSdk = 34
        versionCode = 4
        versionName = "2.2"
    }

    // Clé de signature fixe : chaque nouvelle version se met à jour par-dessus l'ancienne
    // (sans elle, chaque compilation GitHub aurait une clé différente et la mise à jour serait refusée).
    signingConfigs {
        create("flux") {
            storeFile = file("flux.jks")
            storePassword = "fluxtv-box"
            keyAlias = "flux"
            keyPassword = "fluxtv-box"
        }
    }

    buildTypes {
        release {
            // R8 : code plus petit et plus rapide sur le processeur S905W
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("flux")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.palette:palette:1.0.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.github.bumptech.glide:glide:4.16.0")

    val media3 = "1.4.1"
    implementation("androidx.media3:media3-exoplayer:$media3")
    implementation("androidx.media3:media3-exoplayer-hls:$media3")
    implementation("androidx.media3:media3-datasource-okhttp:$media3")
    implementation("androidx.media3:media3-ui:$media3")
}
