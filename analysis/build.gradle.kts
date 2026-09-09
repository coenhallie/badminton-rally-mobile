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

    // The default hierarchy plus one group: the JVM and android targets share a
    // `jvmShared` source set. It exists for the corpus loader, which reads the
    // fixtures off a JVM classpath and so cannot live in commonTest - Native
    // has no classpath and stubs the loader out in nativeTest. Both JVM-hosted
    // targets need the same implementation, and without one for android the
    // three expects had no actual there at all: `:analysis:testDebugUnitTest`
    // failed to compile, which nothing noticed because CI runs jvmTest and
    // iosSimulatorArm64Test and neither touches that variant.
    //
    // Declared through the template rather than by a hand-written `dependsOn`,
    // which switches the default hierarchy OFF and takes the native tree's own
    // edges with it - the same three expects then lose their Native actual.
    applyDefaultHierarchyTemplate {
        common {
            group("jvmShared") {
                withJvm()
                withAndroidTarget()
            }
        }
    }

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
    // The unit-test variant's java resources are AGP's, not the KMP source
    // set's: adding the corpus to androidUnitTest.resources puts it nowhere the
    // test classpath looks, and every corpus-backed test then skips itself
    // while reporting green. ANALYSIS_REQUIRE_CORPUS=1 is what says otherwise.
    sourceSets.getByName("test").resources.srcDir("src/commonTest/resources")
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
