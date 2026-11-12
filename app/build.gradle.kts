import com.android.build.api.artifact.SingleArtifact
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

// "owner/repo" of the GitHub project whose releases this build checks for updates. Not part of the source:
// pass -Passistant.updateRepo=owner/repo (or set ASSISTANT_UPDATE_REPO) when building. Empty = no update checks.
val updateRepo: String = (findProperty("assistant.updateRepo") as String?) ?: System.getenv("ASSISTANT_UPDATE_REPO") ?: ""

val sourceUrl: String = if (updateRepo.isBlank()) "" else "https://github.com/$updateRepo"

// The version of the next release: raise it (major.minor.patch, each 0-99) before publishing a new stable build.
// Betas of that version are numbered automatically (1.1.0-beta.1, 1.1.0-beta.2, ...) and sort below it.
val appVersionName = "1.2.0"

// versionCode is computed from the version, so a newer version always has a higher code and Android never
// refuses an update as a downgrade. AppVersion.code in the app uses the same formula (a unit test guards it).
val versionParts = appVersionName.split(".").map { it.toInt() }
require(versionParts.size == 3 && versionParts.all { it in 0..99 }) { "appVersionName must be major.minor.patch, each 0-99" }
fun versionCodeFor(beta: Int?): Int {
    require(beta == null || beta in 1..998) { "beta number out of range: $beta" }
    return versionParts[0] * 10_000_000 + versionParts[1] * 100_000 + versionParts[2] * 1_000 + (beta ?: 999)
}

val betaTags = providers.provider {
    providers.exec {
        commandLine("git", "tag", "-l", "v${appVersionName}-beta.*")
    }.standardOutput.asText.get().trim()
}
val betaNumber = betaTags.map { tags ->
    tags.lineSequence()
        .mapNotNull { it.substringAfter("-beta.").toIntOrNull() }
        .maxOrNull()
        ?.plus(1)
        ?: 1
}

// The debug tools (app/src/debug: receivers that drive the car's tools, play sound outside the car and start the live
// broadcast) belong to the debug build alone. They live in the debug source set, so a beta or release build cannot have them;
// this check says so out loud if that ever stops being true (a receiver added to the main manifest, a source set shared by
// mistake), before an APK is built.
abstract class CheckNoDebugComponents : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val manifest: RegularFileProperty

    @get:OutputFile
    abstract val result: RegularFileProperty

    @TaskAction
    fun check() {
        val text = manifest.get().asFile.readText()
        val found = Regex("""android:name="(com\.bydassistantng\.debug\.[^"]*)"""").findAll(text).map { it.groupValues[1] }.toList()
        if (found.isNotEmpty()) {
            throw GradleException("The manifest of this build has debug-only components (${found.joinToString()}). Beta and release builds must not.")
        }
        result.get().asFile.writeText("no debug components\n")
    }
}

android {
    namespace = "com.bydassistantng"
    compileSdk {
        version = release(37)
    }

    // The echo canceller of the live microphone broadcast is C code (app/src/main/cpp). The NDK and CMake versions are pinned, so that a
    // build fetches exactly these where they are missing.
    ndkVersion = "27.2.12479018"
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    defaultConfig {
        applicationId = "com.bydassistantng"
        minSdk = 24
        targetSdk = 37
        versionCode = versionCodeFor(null)
        versionName = appVersionName
        ndk {
            abiFilters += listOf("arm64-v8a")
        }

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "UPDATE_REPO", "\"$updateRepo\"")
        buildConfigField("String", "SOURCE_URL", "\"$sourceUrl\"")
    }

    signingConfigs {
        if (file("signing.properties").exists()) {
            create("release") {
                val properties = Properties().apply {
                    file("signing.properties").inputStream().use { load(it) }
                }

                keyAlias = properties["KEY_ALIAS"] as String
                keyPassword = properties["KEY_PASSWORD"] as String
                storeFile = file(properties["STORE_FILE"] as String)
                storePassword = properties["KEY_PASSWORD"] as String
                storeType = "PKCS12"
            }
        }
    }

    buildTypes {
        // Local test builds only. The distinct applicationId lets this install *next to* the
        // CI-signed release app: a locally built APK is debug-signed, and Android refuses to upgrade
        // an app over one signed with a different key — while uninstalling the release app to work
        // around that would wipe its saved API key and settings. It's also debuggable, so `run-as`
        // and logcat work on it, unlike the minified release/beta builds.
        debug {
            applicationIdSuffix = ".dev"
            versionNameSuffix = "-dev"
        }

        release {
            optimization {
                enable = true
                isMinifyEnabled = true
                isShrinkResources = true
            }
            signingConfig = if (file("signing.properties").exists()) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }

        register("beta") {
            initWith(getByName("release"))
            matchingFallbacks += listOf("release")
            versionNameSuffix = "-beta.${betaNumber.get()}"
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    installation {
        // A plain install over ADB also puts the app into the car's second Android user (999), where its copy asks the car
        // to allow a new ADB key at every boot (see AppUser). Install for the main user only.
        installOptions += listOf("--user", "0")
    }
}

androidComponents {
    onVariants { variant ->
        if (variant.buildType != "debug") {
            val capitalised = variant.name.replaceFirstChar { it.uppercase() }
            val check = tasks.register<CheckNoDebugComponents>("check${capitalised}NoDebugComponents") {
                manifest.set(variant.artifacts.get(SingleArtifact.MERGED_MANIFEST))
                result.set(layout.buildDirectory.file("intermediates/no-debug-components/${variant.name}.txt"))
            }
            tasks.matching { it.name == "assemble$capitalised" }.configureEach { dependsOn(check) }
        }
        variant.outputs.forEach { output ->
            if (variant.buildType == "beta") output.versionCode.set(versionCodeFor(betaNumber.get()))
            val date = SimpleDateFormat("ddMMyyyyHHmmss").format(Date())
            output.outputFileName.set(
                "${rootProject.name}-${output.versionName.get()}(${output.versionCode.get()})-${variant.name}-$date.apk"
            )
        }
    }
}

dependencies {
    implementation(libs.dadb)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.datastore.preferences)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)

    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
