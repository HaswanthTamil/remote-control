import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

/*
 * Build-time defaults. They mirror `remote_control_phone/config.js` exactly, so
 * a fresh checkout talks to the same relay with the same pairing token without
 * any extra setup. Override them per machine with a gitignored
 * `keystore.properties` (see README) or with -P flags.
 */
val localProps = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

fun setting(key: String, gradleProp: String, fallback: String): String =
    localProps.getProperty(key) ?: providers.gradleProperty(gradleProp).orNull ?: fallback

android {
    namespace = "com.remotecontrol"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.remotecontrol"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        buildConfigField(
            "String",
            "DEFAULT_SERVER_URL",
            "\"${setting("serverUrl", "rc.serverUrl", "wss://remote-control-lmxu.vercel.app/")}\"",
        )
        buildConfigField(
            "String",
            "DEFAULT_PAIR_TOKEN",
            "\"${setting("pairToken", "rc.pairToken", "rc.pairToken (see gitignored env files)")}\"",
        )
        buildConfigField(
            "String",
            "DEFAULT_PASSCODE",
            "\"${setting("passcode", "rc.passcode", "laser")}\"",
        )
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
        debug {
            isMinifyEnabled = false
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.biometric)
    implementation(libs.androidx.fragment)
    implementation(libs.kotlinx.coroutines.android)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    debugImplementation(libs.androidx.ui.tooling)

    implementation(libs.okhttp)
    implementation(libs.bouncycastle)

    testImplementation(libs.junit)
    testImplementation(libs.json)
    testImplementation(libs.okhttp)
    testImplementation(libs.mockwebserver)
}