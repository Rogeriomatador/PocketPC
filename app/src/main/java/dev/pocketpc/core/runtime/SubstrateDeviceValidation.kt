package dev.pocketpc.core.runtime

import android.content.Context
import java.io.File
import java.security.MessageDigest
import org.json.JSONObject

data class SubstrateDeviceValidationCandidate(
    val schemaVersion: Int,
    val status: String,
    val productionApproved: Boolean,
    val sourceLockSha256: String,
    val artifactContractSha256: String,
    val artifacts:
        List<ApprovedSubstrateArtifact>,
    val sourceAudit: Boolean,
    val elfAudit: Boolean,
    val licenseAudit: Boolean,
    val packagingAudit: Boolean,
    val deviceAudit: Boolean,
    val allowedExecution: Set<String>,
)

data class SubstrateDeviceValidationVerification(
    val readyForDeviceValidation: Boolean,
    val state: String,
    val errors: List<String>,
    val artifacts:
        List<ArtifactIntegrityRecord>,
)

object SubstrateDeviceValidationCodec {
    private const val ASSET =
        "proot-device-validation.json"
    private const val SOURCE_LOCK =
        "proot/LOCK.json"
    private const val CONTRACT =
        "proot/ARTIFACT_CONTRACT.json"

    fun read(
        context: Context,
    ): Result<SubstrateDeviceValidationCandidate> =
        runCatching {
            context.assets
                .open(ASSET)
                .bufferedReader()
                .use {
                    parse(
                        it.readText(),
                    )
                }
        }

    fun parse(
        json: String,
    ): SubstrateDeviceValidationCandidate {
        val root =
            JSONObject(json)
        val review =
            root.getJSONObject(
                "review",
            )
        val artifactsJson =
            root.getJSONArray(
                "artifacts",
            )
        val artifacts =
            buildList {
                for (
                    index in
                    0 until
                        artifactsJson
                            .length()
                ) {
                    val item =
                        artifactsJson
                            .getJSONObject(
                                index,
                            )
                    add(
                        ApprovedSubstrateArtifact(
                            role =
                                item.getString(
                                    "role",
                                ),
                            fileName =
                                item.getString(
                                    "fileName",
                                ),
                            sha256 =
                                item.getString(
                                    "sha256",
                                ).lowercase(),
                            bytes =
                                item.getLong(
                                    "bytes",
                                ),
                            executableRequired =
                                item.getBoolean(
                                    "executableRequired",
                                ),
                        ),
                    )
                }
            }

        val allowedJson =
            root.getJSONArray(
                "allowedExecution",
            )
        val allowed =
            buildSet {
                for (
                    index in
                    0 until
                        allowedJson.length()
                ) {
                    add(
                        allowedJson
                            .getString(index),
                    )
                }
            }

        return SubstrateDeviceValidationCandidate(
            schemaVersion =
                root.getInt(
                    "schemaVersion",
                ),
            status =
                root.getString(
                    "status",
                ),
            productionApproved =
                root.getBoolean(
                    "productionApproved",
                ),
            sourceLockSha256 =
                root.getString(
                    "sourceLockSha256",
                ).lowercase(),
            artifactContractSha256 =
                root.getString(
                    "artifactContractSha256",
                ).lowercase(),
            artifacts = artifacts,
            sourceAudit =
                review.getBoolean(
                    "sourceAudit",
                ),
            elfAudit =
                review.getBoolean(
                    "elfAudit",
                ),
            licenseAudit =
                review.getBoolean(
                    "licenseAudit",
                ),
            packagingAudit =
                review.getBoolean(
                    "packagingAudit",
                ),
            deviceAudit =
                review.getBoolean(
                    "deviceAudit",
                ),
            allowedExecution =
                allowed,
        )
    }

    fun verify(
        context: Context,
        nativeLibraryDir: File,
        candidate:
            SubstrateDeviceValidationCandidate,
    ): SubstrateDeviceValidationVerification {
        val errors =
            validatorErrors(
                candidate,
            ).toMutableList()

        val sourceActual =
            digestAsset(
                context,
                SOURCE_LOCK,
            )
        if (
            sourceActual == null ||
            sourceActual !=
            candidate.sourceLockSha256
        ) {
            errors +=
                "DEVICE_VALIDATION_SOURCE_LOCK_MISMATCH"
        }

        val contractActual =
            digestAsset(
                context,
                CONTRACT,
            )
        if (
            contractActual == null ||
            contractActual !=
            candidate.artifactContractSha256
        ) {
            errors +=
                "DEVICE_VALIDATION_CONTRACT_MISMATCH"
        }

        val root =
            runCatching {
                nativeLibraryDir
                    .canonicalFile
            }.getOrNull()
        if (root == null) {
            errors +=
                "DEVICE_VALIDATION_NATIVE_DIR_INVALID"
        }

        val records =
            if (
                root == null ||
                errors.isNotEmpty()
            ) {
                emptyList()
            } else {
                candidate.artifacts
                    .map {
                        verifyArtifact(
                            root,
                            it,
                        )
                    }
            }

        errors +=
            records
                .filterNot {
                    it.valid
                }
                .map {
                    it.message
                }

        val ready =
            errors.isEmpty()

        return SubstrateDeviceValidationVerification(
            readyForDeviceValidation =
                ready,
            state =
                if (ready) {
                    "DEVICE_VALIDATION_CANDIDATE_ATTESTED"
                } else {
                    "DEVICE_VALIDATION_CANDIDATE_BLOCKED"
                },
            errors =
                errors.distinct(),
            artifacts = records,
        )
    }

    private fun validatorErrors(
        candidate:
            SubstrateDeviceValidationCandidate,
    ): List<String> {
        val errors =
            mutableListOf<String>()
        val sha =
            Regex(
                "^[0-9a-f]{64}$",
            )
        val required =
            mapOf(
                "proot" to
                    "libproot.so",
                "loader64" to
                    "libproot_loader.so",
                "libandroid-shmem" to
                    "libandroid-shmem.so",
                "libtalloc" to
                    "libtalloc.so",
            )

        if (
            candidate.schemaVersion !=
            1
        ) {
            errors +=
                "DEVICE_VALIDATION_SCHEMA_UNSUPPORTED"
        }
        if (
            candidate.status !=
            "DEVICE_VALIDATION_CANDIDATE"
        ) {
            errors +=
                "DEVICE_VALIDATION_STATUS_INVALID"
        }
        if (
            candidate.productionApproved
        ) {
            errors +=
                "DEVICE_VALIDATION_CANNOT_APPROVE_PRODUCTION"
        }
        if (
            !sha.matches(
                candidate
                    .sourceLockSha256,
            ) ||
            !sha.matches(
                candidate
                    .artifactContractSha256,
            )
        ) {
            errors +=
                "DEVICE_VALIDATION_POLICY_DIGEST_INVALID"
        }
        if (
            !candidate.sourceAudit ||
            !candidate.elfAudit ||
            !candidate.licenseAudit ||
            !candidate.packagingAudit
        ) {
            errors +=
                "DEVICE_VALIDATION_PRE_DEVICE_AUDITS_REQUIRED"
        }
        if (candidate.deviceAudit) {
            errors +=
                "DEVICE_VALIDATION_DEVICE_AUDIT_MUST_START_FALSE"
        }
        if (
            candidate.allowedExecution !=
            setOf(
                "SHELL",
                "ROOTFS",
            )
        ) {
            errors +=
                "DEVICE_VALIDATION_EXECUTION_SCOPE_INVALID"
        }

        val byRole =
            candidate.artifacts
                .associateBy {
                    it.role
                }
        if (
            byRole.keys !=
            required.keys ||
            candidate.artifacts.size !=
            required.size
        ) {
            errors +=
                "DEVICE_VALIDATION_ROLE_SET_INVALID"
        }

        required.forEach {
            (role, fileName) ->
            val artifact =
                byRole[role]
            if (artifact == null) {
                return@forEach
            }
            if (
                artifact.fileName !=
                fileName
            ) {
                errors +=
                    "DEVICE_VALIDATION_ALIAS_INVALID:" +
                        role
            }
            if (
                !sha.matches(
                    artifact.sha256,
                )
            ) {
                errors +=
                    "DEVICE_VALIDATION_SHA_INVALID:" +
                        role
            }
            if (artifact.bytes <= 0L) {
                errors +=
                    "DEVICE_VALIDATION_BYTES_INVALID:" +
                        role
            }
            val shouldExecute =
                role == "proot" ||
                    role == "loader64"
            if (
                artifact.executableRequired !=
                shouldExecute
            ) {
                errors +=
                    "DEVICE_VALIDATION_EXECUTABLE_POLICY_INVALID:" +
                        role
            }
        }

        return errors
    }

    private fun verifyArtifact(
        root: File,
        artifact:
            ApprovedSubstrateArtifact,
    ): ArtifactIntegrityRecord {
        val file =
            File(
                root,
                artifact.fileName,
            )
        val canonical =
            runCatching {
                file.canonicalFile
            }.getOrNull()

        if (
            canonical == null ||
            canonical.parentFile !=
            root ||
            !canonical.isFile ||
            !canonical.canRead()
        ) {
            return record(
                artifact,
                null,
                null,
                false,
                "DEVICE_VALIDATION_ARTIFACT_MISSING:" +
                    artifact.role,
            )
        }
        if (
            artifact.executableRequired &&
            !canonical.canExecute()
        ) {
            return record(
                artifact,
                null,
                canonical.length(),
                false,
                "DEVICE_VALIDATION_ARTIFACT_NOT_EXECUTABLE:" +
                    artifact.role,
            )
        }
        if (
            canonical.length() !=
            artifact.bytes
        ) {
            return record(
                artifact,
                null,
                canonical.length(),
                false,
                "DEVICE_VALIDATION_ARTIFACT_SIZE_MISMATCH:" +
                    artifact.role,
            )
        }

        val actual =
            sha256(canonical)
        return record(
            artifact,
            actual,
            canonical.length(),
            actual ==
                artifact.sha256,
            if (
                actual ==
                artifact.sha256
            ) {
                "DEVICE_VALIDATION_ATTEST_OK"
            } else {
                "DEVICE_VALIDATION_ARTIFACT_SHA_MISMATCH:" +
                    artifact.role
            },
        )
    }

    private fun record(
        artifact:
            ApprovedSubstrateArtifact,
        actualSha256: String?,
        actualBytes: Long?,
        valid: Boolean,
        message: String,
    ): ArtifactIntegrityRecord =
        ArtifactIntegrityRecord(
            role = artifact.role,
            fileName =
                artifact.fileName,
            expectedSha256 =
                artifact.sha256,
            actualSha256 =
                actualSha256,
            expectedBytes =
                artifact.bytes,
            actualBytes =
                actualBytes,
            valid = valid,
            message = message,
        )

    private fun digestAsset(
        context: Context,
        name: String,
    ): String? =
        runCatching {
            val digest =
                MessageDigest.getInstance(
                    "SHA-256",
                )
            context.assets
                .open(name)
                .buffered()
                .use { input ->
                    val buffer =
                        ByteArray(
                            64 * 1024,
                        )
                    while (true) {
                        val read =
                            input.read(
                                buffer,
                            )
                        if (read < 0) {
                            break
                        }
                        digest.update(
                            buffer,
                            0,
                            read,
                        )
                    }
                }
            digest.digest()
                .joinToString("") {
                    "%02x".format(
                        it.toInt() and
                            0xff,
                    )
                }
        }.getOrNull()

    private fun sha256(
        file: File,
    ): String {
        val digest =
            MessageDigest.getInstance(
                "SHA-256",
            )
        file.inputStream()
            .buffered()
            .use { input ->
                val buffer =
                    ByteArray(
                        64 * 1024,
                    )
                while (true) {
                    val read =
                        input.read(buffer)
                    if (read < 0) {
                        break
                    }
                    digest.update(
                        buffer,
                        0,
                        read,
                    )
                }
            }
        return digest.digest()
            .joinToString("") {
                "%02x".format(
                    it.toInt() and
                        0xff,
                )
            }
    }
}
