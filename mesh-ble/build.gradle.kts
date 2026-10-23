// Android-only Bluetooth layer. Moves bytes between phones; knows nothing
// about routing, which lives in mesh-core.
plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "dev.leyline.ble"
    compileSdk = 37

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    api(project(":mesh-core"))
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.nordic.ble)
    implementation(libs.nordic.ble.ktx)
}
