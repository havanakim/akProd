plugins { id("com.android.application"); kotlin("plugin.serialization"); kotlin("plugin.compose") }
android {
    namespace = "kr.co.akpda.android"
    compileSdk = 35
    defaultConfig {
        applicationId = "kr.co.akpda.android"; minSdk = 26; targetSdk = 35; versionCode =
        1; versionName = "1.0.0"
    }
    buildFeatures { buildConfig = true; compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17
    }
}
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
dependencies {
    implementation("com.google.mlkit:text-recognition:16.0.1")
    implementation("androidx.exifinterface:exifinterface:1.3.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7");
    implementation(platform("androidx.compose:compose-bom:2024.12.01"));
    implementation("androidx.compose.material3:material3");
    implementation("androidx.activity:activity-compose:1.9.3");
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7");
    implementation(project(":shared"));
    implementation("androidx.activity:activity-ktx:1.9.3");
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7");
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7");
    testImplementation(kotlin("test"))
}
