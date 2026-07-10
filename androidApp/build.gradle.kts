import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

val localProperties = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val supabaseUrl     = (localProperties["SUPABASE_URL"] as? String).orEmpty()
val supabaseAnonKey = (localProperties["SUPABASE_ANON_KEY"] as? String).orEmpty()

// Shared Android/iOS version source — see Config/Version.xcconfig.
val versionConfig: Map<String, String> = rootProject.file("Config/Version.xcconfig")
    .readLines()
    .filterNot { it.trimStart().startsWith("//") }
    .mapNotNull { line ->
        val parts = line.split("=", limit = 2)
        if (parts.size == 2) parts[0].trim() to parts[1].trim() else null
    }
    .toMap()
val sharedVersionName: String = versionConfig["MARKETING_VERSION"]
    ?: error("MARKETING_VERSION missing from Config/Version.xcconfig")
val sharedVersionCode: Int = versionConfig["CURRENT_PROJECT_VERSION"]?.toIntOrNull()
    ?: error("CURRENT_PROJECT_VERSION missing or not an Int in Config/Version.xcconfig")

android {
    namespace = "com.badmintontracker.android"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.badmintontracker.android"
        minSdk = 26
        targetSdk = 36
        versionCode = sharedVersionCode
        versionName = sharedVersionName

        buildConfigField("String", "SUPABASE_URL",      "\"$supabaseUrl\"")
        buildConfigField("String", "SUPABASE_ANON_KEY", "\"$supabaseAnonKey\"")
    }

    buildFeatures {
        compose     = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":shared"))

    implementation(platform(libs.supabase.bom))
    implementation(libs.supabase.auth)

    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.navigation.compose)

    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.ui)
    implementation(libs.androidx.media3.common)

    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
    implementation(libs.coil.video)
    implementation(libs.kotlinx.serialization.json)

    implementation(libs.settings)

    testImplementation(kotlin("test"))
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
    testImplementation(libs.kotest.assertions)
    testImplementation(libs.settings.test)
}
