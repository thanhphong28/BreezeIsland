plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.arnav.island"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.breeze.island"
        minSdk = 29
        targetSdk = 35
        versionCode = 11
        versionName = "1.7.3"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    val releaseKeystore = file(System.getenv("KEYSTORE_FILE") ?: "breeze-release.jks")

    signingConfigs {
        create("breeze") {
            storeFile = releaseKeystore
            storePassword = System.getenv("KEYSTORE_PASSWORD") ?: "breezeisland28"
            keyAlias = System.getenv("KEY_ALIAS") ?: "breezeisland"
            keyPassword = System.getenv("KEY_PASSWORD") ?: "breezeisland28"
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ""
            signingConfig = signingConfigs.getByName("breeze")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("breeze")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
        aidl = true
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.animation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.shizuku.api)
    implementation(libs.shizuku.provider)
    implementation("org.lsposed.hiddenapibypass:hiddenapibypass:4.3")

    debugImplementation(libs.androidx.compose.ui.tooling)
    testImplementation(libs.junit)
}

tasks.matching { it.name.contains("AarMetadata") }.configureEach {
    enabled = false
}
