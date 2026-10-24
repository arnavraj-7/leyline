import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Runs many virtual nodes on top of mesh-core to measure delivery rate,
// duplicates and latency without phones.
plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

application {
    mainClass.set("dev.leyline.sim.MainKt")
}

dependencies {
    implementation(project(":mesh-core"))
    implementation(libs.kotlinx.coroutines.core)
    // Main code, not just tests: the simulator itself runs on virtual time, so an
    // hour of mesh traffic takes seconds and a seed always gives the same run.
    implementation(libs.kotlinx.coroutines.test)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
}

// The report path in Main is relative to this module.
tasks.named<JavaExec>("run") {
    workingDir = projectDir
}
