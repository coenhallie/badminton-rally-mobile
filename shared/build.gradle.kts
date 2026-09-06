import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.android.library)
    alias(libs.plugins.skie)
}

kotlin {
    androidTarget {
        @OptIn(ExperimentalKotlinGradlePluginApi::class)
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }

        // No unit-test compilation for the Android target. This module has no
        // androidUnitTest source set, so the task androidTarget() would create
        // runs commonTest a third time, after jvmTest and iosSimulatorArm64Test
        // have already run it - and against the same code, since the only
        // androidMain actual is SyncLock, which is byte-identical to the jvm
        // one. It is duplicate coverage, not extra coverage.
        //
        // It also cannot pass. These tests build a Supabase client, and
        // supabase-kt's Auth calls setupPlatform on install, which on Android
        // registers lifecycle callbacks on Dispatchers.Main. A plain JVM unit
        // test has no Looper, so every test touching the client fails on a
        // missing main dispatcher. Making it pass would mean Robolectric, a new
        // dependency for tests that already run twice.
        //
        // Adding an androidUnitTest source set is the signal to delete this: at
        // that point the target would be testing something jvmTest cannot.
    }
    jvm()
    iosX64()
    iosArm64()
    iosSimulatorArm64()

    listOf(iosX64(), iosArm64(), iosSimulatorArm64()).forEach {
        it.binaries.framework {
            baseName = "Shared"
            isStatic = true
        }
    }

    sourceSets {
        commonMain.dependencies {
            // api, not implementation: :shared's public surface hands
            // :analysis types (Rally, ClipWindow) to both apps, and
            // implementation would make them unusable from Swift and from
            // androidApp.
            api(project(":analysis"))
            implementation(project.dependencies.platform(libs.supabase.bom))
            implementation(libs.supabase.auth)
            implementation(libs.supabase.postgrest)
            implementation(libs.supabase.storage)
            implementation(libs.supabase.functions)
            implementation(libs.ktor.client.core)
            implementation(libs.kotlinx.coroutines)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.datetime)
            implementation(libs.settings)
            implementation(libs.settings.no.arg)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines)
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.ktor.client.mock)
            implementation(libs.kotest.assertions)
            implementation(libs.turbine)
            implementation(libs.settings.test)
        }
        androidMain.dependencies {
            implementation(libs.ktor.client.cio)
        }
        jvmMain.dependencies {
            implementation(libs.ktor.client.cio)
        }
        iosMain.dependencies {
            implementation(libs.ktor.client.darwin)
        }
    }
}

android {
    namespace = "com.badmintontracker.shared"
    compileSdk = 35
    defaultConfig { minSdk = 26 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

// See the note in androidTarget above: the Android unit-test variants run
// commonTest a third time, against a byte-identical actual, on a JVM with no
// Looper for supabase-kt's Auth to register lifecycle callbacks on. Turned off
// here rather than left failing, so ./gradlew test in this module reports only
// suites that mean something.
androidComponents {
    beforeVariants(selector().all()) { it.enableUnitTest = false }
}
