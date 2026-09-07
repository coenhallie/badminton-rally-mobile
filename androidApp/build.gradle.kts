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

// Phase 1 model identity: the first 8 hex of each pinned weight SHA, in a
// fixed order. Changes if and only if the weights change.
val phase1ModelVersion: String = run {
    val manifest = rootProject.file("tools/models/manifest.json")
    if (!manifest.exists()) {
        "unpinned"
    } else {
        @Suppress("UNCHECKED_CAST")
        val weights = (groovy.json.JsonSlurper().parse(manifest) as Map<String, Any>)["weights"]
            as Map<String, Map<String, Any>>
        listOf("tracknet", "inpaintnet", "badminton")
            .joinToString("-") { (weights[it]?.get("sha256") as? String)?.take(8) ?: "missing" }
    }
}

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

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Derived from the pinned weight SHAs, never hand-typed. Section 5.4's
        // re-anchoring rule keys on this: when a model change moves a clip
        // boundary, every annotation on that clip has to move with it. A
        // version string someone forgets to bump makes that change invisible
        // and silently strands the annotations.
        buildConfigField("String", "MODEL_VERSION", "\"$phase1ModelVersion\"")
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

composeCompiler {
    // See the file itself: :analysis has no Compose compiler, so its types are
    // inferred unstable and a composable taking one cannot skip.
    stabilityConfigurationFiles.add(layout.projectDirectory.file("compose_stability.conf"))
}

dependencies {
    implementation(project(":shared"))
    implementation(libs.onnxruntime.android)

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

    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    implementation(libs.kotlinx.serialization.json)

    implementation(libs.settings)

    testImplementation(kotlin("test"))
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
    testImplementation(libs.kotest.assertions)
    testImplementation(libs.settings.test)
}

// The ONNX graphs ship as assets, but they are NOT in git: they are
// 36MB of binary reproducible from the SHA-pinned weights in
// tools/models/manifest.json via tools/models/export_yolo.py and
// export_tracknet.py. Copying them in at build time keeps the repository free
// of large derived artifacts without making the app fetch anything at runtime.
//
// Pose ships as the NANO model at 6.3MB. The medium model the cloud uses is
// 43.5MB, which is why pose was deliberately unbundled until now; nano is
// smaller than the detector and measured at 230ms a frame against medium's
// 1567, so it is both shippable and the only one that runs on a phone.
val bundledModels = listOf(
    // inpaintnet is fp32 on purpose; see ModelCatalog.INPAINTNET for why the
    // fp16 graph of this one model cannot be shipped.
    "tracknet.fp16.onnx", "inpaintnet.onnx", "badminton.fp16.onnx", "posen.fp16.onnx",
)
val onnxSourceDir = rootProject.layout.projectDirectory.dir("tools/models/onnx")
val onnxAssetsDir = layout.buildDirectory.dir("generated/onnxAssets")

// Fail with the command that fixes it. Without this the app builds fine and
// dies at runtime on a missing asset, which is a far worse place to learn the
// export was never run.
//
// This lives in its own task rather than in copyOnnxModels.doFirst, where it
// used to live and where it did nothing. A Copy task whose source matches no
// file at all is skipped as NO-SOURCE, and a skipped task runs none of its
// actions - so on any checkout without tools/models/onnx, which is every CI
// runner because the directory is gitignored, the guard was skipped and the APK
// was packaged with no models in it. That is the exact failure the guard exists
// to prevent. A task with no inputs is never NO-SOURCE and always runs.
//
// -PonnxModelsOptional=true downgrades the failure to a warning, for builds that
// only need to prove the code compiles and packages. CI's assemble job passes it
// because it cannot export the graphs: that needs the Modal weights and torch.
// An APK built that way will not analyse anything, so do not pass it for a build
// anyone intends to install.
val verifyOnnxModels by tasks.registering {
    description = "Fail when the ONNX graphs the app bundles have not been exported."
    val source = onnxSourceDir
    val optional = providers.gradleProperty("onnxModelsOptional")
        .map { it.toBoolean() }.getOrElse(false)
    doLast {
        val missing = bundledModels.filterNot { source.file(it).asFile.exists() }
        if (missing.isEmpty()) return@doLast
        val problem = "missing ONNX graphs in ${source.asFile}: ${missing.joinToString()}\n" +
            "Run: python tools/models/pull_weights.py --tracker-repo ../badminton-tracker\n" +
            "then: python tools/models/export_tracknet.py --tracker-repo ../badminton-tracker\n" +
            "then: python tools/models/export_yolo.py"
        if (optional) {
            logger.warn("WARNING: $problem\nBuilding anyway: -PonnxModelsOptional=true was set.")
        } else {
            error(problem)
        }
    }
}

// Sync, not Copy: a Copy leaves whatever is already in the destination alone,
// so renaming a bundled graph left the old file staged and the APK carried
// both. That was not hypothetical - switching InpaintNet from the fp16 file to
// the fp32 one kept shipping the broken fp16 graph beside the good one. Sync
// makes the assets directory match this list exactly.
val copyOnnxModels by tasks.registering(Sync::class) {
    description = "Stage the ONNX graphs as app assets."
    dependsOn(verifyOnnxModels)
    from(onnxSourceDir) { include(bundledModels) }
    into(onnxAssetsDir.map { it.dir("models") })
}

android.sourceSets.getByName("main").assets.srcDir(onnxAssetsDir)
tasks.matching { it.name.startsWith("merge") && it.name.endsWith("Assets") }
    .configureEach { dependsOn(copyOnnxModels) }
