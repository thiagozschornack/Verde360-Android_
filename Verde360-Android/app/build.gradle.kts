import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Credenciais da chave de upload ficam fora do código, em keystore.properties (ver keystore.properties.example).
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val hasReleaseKey = keystoreProps.getProperty("storeFile") != null

android {
    namespace = "br.com.empreenderthz.verde360"
    compileSdk = 36

    defaultConfig {
        applicationId = "br.com.empreenderthz.verde360"
        minSdk = 26
        targetSdk = 36
        // Aumente versionCode a cada envio à Play Store.
        versionCode = 3
        versionName = "1.2.0"

        buildConfigField("String", "HOME_URL", "\"https://www.empreenderthz.com.br/Ambiental/\"")
        buildConfigField("String", "PRIVACY_URL", "\"https://www.empreenderthz.com.br/Ambiental/privacidade.html\"")
    }

    signingConfigs {
        if (hasReleaseKey) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (hasReleaseKey) signingConfig = signingConfigs.getByName("release")
        }
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.activity:activity-ktx:1.10.1")
    implementation("androidx.core:core-splashscreen:1.0.1")
    implementation("androidx.webkit:webkit:1.12.1")
    implementation("androidx.browser:browser:1.8.0")
}
