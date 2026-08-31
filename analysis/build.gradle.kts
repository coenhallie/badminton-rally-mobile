import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.android.library)
}

kotlin {
    androidTarget {
        @OptIn(ExperimentalKotlinGradlePluginApi::class)
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }
    jvm()
    iosX64()
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        // Deliberately no Supabase, no Ktor, no I/O. This module is pure
        // computation so it can be tested without a device or a network.
        commonMain.dependencies {
            implementation(libs.kotlinx.serialization.json)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotest.assertions)
        }
        // Corpus fixtures live beside the common tests that describe them,
        // but only the JVM test target reads them off disk. Native has no
        // classpath to read them from, so nativeTest stubs the loader out.
        jvmTest { resources.srcDir("src/commonTest/resources") }
    }
}

android {
    namespace = "com.badmintontracker.analysis"
    compileSdk = 35
    defaultConfig { minSdk = 26 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

// The desktop venue for the A/B comparator (section 5.7). Without a task the
// only way to run it is a hand-assembled classpath, which stops working at the
// next dependency bump.
//
//   ./gradlew :analysis:compareResults --args="local.json cloud.json"
tasks.register<JavaExec>("compareResults") {
    group = "verification"
    description = "Diff a local results.json against the cloud's for the same video."
    val jvmMain = kotlin.jvm().compilations.getByName("main")
    classpath = jvmMain.output.allOutputs + jvmMain.runtimeDependencyFiles
    mainClass.set("com.badmintontracker.analysis.compare.ComparatorCli")
}
