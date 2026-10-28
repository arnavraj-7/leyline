plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

android {
    namespace = "dev.leyline.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "dev.leyline"
        minSdk = 26
        targetSdk = 37
        // Bump with -PleylineVersion=2 to make a build that spreads to older phones over the mesh.
        val build = (findProperty("leylineVersion") as String?)?.toInt() ?: 1
        versionCode = build
        versionName = "0.1.$build"
        // Phones only: MapLibre's x86 builds would double the APK for emulators we don't ship to.
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(project(":mesh-ble"))
    implementation(libs.kotlinx.coroutines.android)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.core)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    implementation(libs.maplibre.android)
    ksp(libs.androidx.room.compiler)
}
