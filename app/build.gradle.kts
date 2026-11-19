plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.personaltech.gravador"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.personaltech.gravador"
        minSdk = 29          // Android 10+
        targetSdk = 34       // Android 14
        versionCode = 1
        versionName = "1.0"
    }

    // Chave fixa: assim cada APK novo do GitHub instala POR CIMA do anterior
    // (sem "app não instalado" por assinatura diferente).
    signingConfigs {
        create("pt") {
            storeFile = file("../keystore/ptgravador.jks")
            storePassword = "ptgravador123"
            keyAlias = "ptgravador"
            keyPassword = "ptgravador123"
        }
    }

    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("pt")
        }
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("pt")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}
