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

val pocketPcVersionCode =
    providers.environmentVariable("POCKETPC_VERSION_CODE")
        .orNull
        ?.trim()
        ?.toIntOrNull()
        ?.takeIf { it > 0 }
        ?: 22

val pocketPcVersionName =
    providers.environmentVariable("POCKETPC_VERSION_NAME")
        .orNull
        ?.trim()
        ?.takeIf {
            it.isNotEmpty() &&
                it.length <= 96 &&
                Regex("^[A-Za-z0-9._+-]+$").matches(it)
        }
        ?: "0.1.0-alpha22"

val pocketPcSigningStoreFile =
    providers.environmentVariable(
        "POCKETPC_SIGNING_STORE_FILE"
    ).orNull
val pocketPcSigningStorePassword =
    providers.environmentVariable(
        "POCKETPC_SIGNING_STORE_PASSWORD"
    ).orNull
val pocketPcSigningKeyAlias =
    providers.environmentVariable(
        "POCKETPC_SIGNING_KEY_ALIAS"
    ).orNull
val pocketPcSigningKeyPassword =
    providers.environmentVariable(
        "POCKETPC_SIGNING_KEY_PASSWORD"
    ).orNull

val pocketPcReleaseSigningConfigured =
    listOf(
        pocketPcSigningStoreFile,
        pocketPcSigningStorePassword,
        pocketPcSigningKeyAlias,
        pocketPcSigningKeyPassword,
    ).all { !it.isNullOrBlank() }

val pocketPcSkipNativeBuild =
    providers.gradleProperty(
        "pocketpc.skipNativeBuild"
    )
        .orNull
        ?.toBooleanStrictOrNull()
        ?: false

val pocketPcProotValidationCandidatePath =
    providers.gradleProperty(
        "pocketpc.prootValidationCandidateDir"
    )
        .orNull
        ?.trim()
        ?.takeIf { it.isNotEmpty() }

val pocketPcProotValidationCandidateDir =
    pocketPcProotValidationCandidatePath
        ?.let {
            rootProject.file(it)
                .canonicalFile
        }

if (
    pocketPcProotValidationCandidateDir !=
    null
) {
    require(
        pocketPcProotValidationCandidateDir
            .isDirectory
    ) {
        "PRoot validation candidate directory does not exist."
    }
    require(
        pocketPcProotValidationCandidateDir
            .resolve(
                "assets/proot-device-validation.json"
            )
            .isFile
    ) {
        "PRoot validation manifest is missing."
    }
    for (
        fileName in
        listOf(
            "libproot.so",
            "libproot_loader.so",
            "libandroid-shmem.so",
            "libtalloc.so",
        )
    ) {
        require(
            pocketPcProotValidationCandidateDir
                .resolve(
                    "jniLibs/arm64-v8a/" +
                        fileName
                )
                .isFile
        ) {
            "PRoot validation artifact missing: " +
                fileName
        }
    }
}

android {
    namespace = "dev.pocketpc.core"
    compileSdk = 37
    if (!pocketPcSkipNativeBuild) {
        ndkVersion = "29.0.14206865"
    }

    defaultConfig {
        applicationId = "dev.pocketpc.core"
        minSdk = 26
        targetSdk = 37
        versionCode = pocketPcVersionCode
        versionName = pocketPcVersionName

        if (!pocketPcSkipNativeBuild) {
            ndk {
                abiFilters +=
                    listOf("arm64-v8a", "x86_64")
            }
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
        buildConfigField(
            "boolean",
            "POCKETPC_PROOT_VALIDATION_CANDIDATE_PACKAGED",
            "false",
        )

        manifestPlaceholders[
            "pocketPcSourceRevision"
        ] = pocketPcSourceRevision
        manifestPlaceholders[
            "pocketPcSourceRevisionPinned"
        ] = pocketPcSourceRevisionPinned.toString()

        if (!pocketPcSkipNativeBuild) {
            externalNativeBuild {
                cmake {
                    cppFlags += "-std=c++20"
                }
            }
        }
    }

    signingConfigs {
        if (pocketPcReleaseSigningConfigured) {
            create("pocketPcRelease") {
                storeFile =
                    file(
                        requireNotNull(
                            pocketPcSigningStoreFile
                        )
                    )
                storePassword =
                    pocketPcSigningStorePassword
                keyAlias =
                    pocketPcSigningKeyAlias
                keyPassword =
                    pocketPcSigningKeyPassword
            }
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            signingConfig =
                signingConfigs.findByName(
                    "pocketPcRelease"
                )
            buildConfigField(
                "boolean",
                "POCKETPC_PROOT_VALIDATION_CANDIDATE_PACKAGED",
                "false",
            )
        }

        create("validation") {
            initWith(
                getByName("debug")
            )
            isDebuggable = true
            versionNameSuffix =
                "-validation"
            matchingFallbacks +=
                listOf("debug")
            buildConfigField(
                "boolean",
                "POCKETPC_PROOT_VALIDATION_CANDIDATE_PACKAGED",
                (
                    pocketPcProotValidationCandidateDir !=
                        null
                    ).toString(),
            )
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    sourceSets.getByName("main").assets.directories.add(
        rootProject.file("third_party").absolutePath
    )

    if (
        pocketPcProotValidationCandidateDir !=
        null
    ) {
        sourceSets
            .getByName("validation")
            .jniLibs
            .srcDir(
                pocketPcProotValidationCandidateDir
                    .resolve("jniLibs")
            )
        sourceSets
            .getByName("validation")
            .assets
            .srcDir(
                pocketPcProotValidationCandidateDir
                    .resolve("assets")
            )
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }

    if (!pocketPcSkipNativeBuild) {
        externalNativeBuild {
            cmake {
                path = file("src/main/cpp/CMakeLists.txt")
                version = "3.22.1"
            }
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
    implementation("androidx.work:work-runtime:2.11.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")

    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:4.13.2")
}
