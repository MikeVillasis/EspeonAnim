import java.net.URI

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

/**
 * Downloads the MediaPipe Pose Landmarker "lite" model (Apache-2.0) at build time and adds it
 * to the APK assets. Cached in build/ so it only downloads once per clean.
 */
abstract class DownloadPoseModel : DefaultTask() {
    @get:Input
    abstract val url: Property<String>

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun download() {
        val target = outputDir.get().asFile.resolve("pose_landmarker_lite.task")
        target.parentFile.mkdirs()
        URI(url.get()).toURL().openStream().use { input -> target.outputStream().use { input.copyTo(it) } }
        check(target.length() > 1_000_000) { "Downloaded pose model looks too small: ${target.length()} bytes" }
    }
}

val downloadPoseModel = tasks.register<DownloadPoseModel>("downloadPoseModel") {
    url.set("https://storage.googleapis.com/mediapipe-models/pose_landmarker/pose_landmarker_lite/float16/latest/pose_landmarker_lite.task")
    outputDir.set(layout.buildDirectory.dir("generated/poseModel"))
}

android {
    namespace = "io.github.mikevillasis.espeonanim"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.mikevillasis.espeonanim"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
    lint {
        abortOnError = true
    }
}

androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(downloadPoseModel, DownloadPoseModel::outputDir)
    }
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":shared"))
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.camerax.core)
    implementation(libs.camerax.camera2)
    implementation(libs.camerax.lifecycle)
    implementation(libs.camerax.video)
    implementation(libs.camerax.view)

    implementation(libs.mediapipe.tasks.vision)
}
