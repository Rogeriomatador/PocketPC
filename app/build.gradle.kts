plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

val pocketPcSourceRevision = providers.environmentVariable("GITHUB_SHA")
    .orElse(providers.environmentVariable("POCKETPC_SOURCE_REVISION"))
    .getOrElse("LOCAL_UNPINNED")
    .trim()
    .take(128)

val pocketPcSourceRevisionPinned =
    Regex("^[0-9a-fA-F]{40}$").matches(pocketPcSourceRevision)

android {
    namespace = "dev.pocketpc.core"
    compileSdk = 37
    ndkVersion = "29.0.14206865"

    defaultConfig {
        applicationId = "dev.pocketpc.core"
        minSdk = 26
        targetSdk = 37
        versionCode = 20
        versionName = "0.1.0-alpha20"

        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }

        buildConfigField(
            "String",
            "POCKETPC_SOURCE_REVISION",
            "\"${pocketPcSourceRevision.replace("\\", "\\\\").replace("\"", "\\\"")}\"",
        )
        buildConfigField(
            "boolean",
            "POCKETPC_SOURCE_REVISION_PINNED",
            pocketPcSourceRevisionPinned.toString(),
        )

        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++20"
            }
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    sourceSets.getByName("main").assets.directories.add(
        rootProject.file("third_party").absolutePath
    )

    packaging {
        jniLibs {
            // A future PRoot loader must exist as a real extracted file in nativeLibraryDir.
            useLegacyPackaging = true
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.08.00")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
    implementation("androidx.documentfile:documentfile:1.1.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")

    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:4.13.2")
}
