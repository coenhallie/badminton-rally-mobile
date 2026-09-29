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
    compileSdk = 36
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

// What a phone makes of a local cloud run, for the two-player references and
// their audit (docs/plans/2026-09-29-two-player-tracking-design.md section 3.2).
//
//   ./gradlew :analysis:exportCorpusRun --args="$HOME/shuttl-corpus/<id> [poses file] [out]"
tasks.register<JavaExec>("exportCorpusRun") {
    group = "verification"
    description = "Write a local cloud run's per-frame cameras and the phone's picks as JSON."
    val jvmMain = kotlin.jvm().compilations.getByName("main")
    classpath = jvmMain.output.allOutputs + jvmMain.runtimeDependencyFiles
    mainClass.set("com.badmintontracker.analysis.compare.CorpusRunExport")
}

// The corpus tests read ~/shuttl-corpus straight off the filesystem and take
// their options from the environment. Declared as inputs, so a changed pose
// file, reference or option re-runs them instead of Gradle reporting the
// previous run's results as up to date.
tasks.named<Test>("jvmTest") {
    val corpus = File(System.getProperty("user.home"), "shuttl-corpus")
    inputs.files(
        fileTree(corpus) {
            include("*/cloud-local/*.raw", "*/device-style/*.raw", "*/players_reference.json")
            include("*/reference_tracks.json", "*/marks_ideal.json", "*/video.json")
        },
    ).withPropertyName("shuttlCorpus").optional()
    listOf("TWO_PLAYER_POSES", "TWO_PLAYER_MAX_FRAME", "TWO_PLAYER_GATES", "ANALYSIS_REQUIRE_CORPUS").forEach {
        inputs.property(it, System.getenv(it) ?: "")
    }
}
