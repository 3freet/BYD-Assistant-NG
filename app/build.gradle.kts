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

val appVersionCode = 1
val appVersionName = "1.0.0"

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

android {
    namespace = "com.bydassistantng"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.bydassistantng"
        minSdk = 24
        targetSdk = 37
        versionCode = appVersionCode
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
}

androidComponents {
    onVariants { variant ->
        variant.outputs.forEach { output ->
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
