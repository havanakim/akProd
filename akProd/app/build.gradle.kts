import java.util.Properties

plugins { alias(libs.plugins.android.application) }
val localProps = Properties().apply {
    val config = rootProject.file("local.properties")
    if (config.exists()) config.inputStream().use { load(it) }
}
fun configValue(key: String, fallback: String = ""): String =
    (localProps.getProperty(key) ?: fallback).replace("\\", "\\\\").replace("\"", "\\\"")
android {
    namespace = "com.bsd.akProd"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.bsd.akProd"
        minSdk = 24
        targetSdk = 34
        testInstrumentationRunner = "com.bsd.akProd.MonoFullPhotoInstrumentation"
        versionCode = 14
        versionName = "1.3.3"
        mapOf("DB_IP" to "", "DB_PORT" to "1433", "DB_NAME" to "", "DB_USER" to "",
            "DB_PASSWORD" to "", "DB_CLASSES" to "net.sourceforge.jtds.jdbc.Driver",
            "DB_AUTH_NAME" to "JSAK_ERP").forEach { (key, fallback) ->
            buildConfigField("String", key, "\"${configValue(key, fallback)}\"")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { viewBinding = true; buildConfig = true }
    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    packaging { jniLibs { useLegacyPackaging = false } }
}
dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation("androidx.constraintlayout:constraintlayout:2.2.1")
    implementation(libs.androidx.activity.ktx)
    implementation(libs.jtds)
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.10.0")
    implementation("com.google.mlkit:text-recognition:16.0.1")
    implementation("com.google.mlkit:text-recognition-korean:16.0.1")
    implementation("org.opencv:opencv:4.13.0")
    implementation("androidx.exifinterface:exifinterface:1.3.7")
    testImplementation(libs.junit)
}
