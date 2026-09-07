package dev.pocketpc.core.storage

import android.content.Context
import android.content.SharedPreferences
import dev.pocketpc.core.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets

data class PocketPcProfileBackupResult(
    val entry: StorageEntry,
    val preferenceFiles: Int,
    val keys: Int,
)

data class PocketPcProfileRestoreResult(
    val fileName: String,
    val preferenceFiles: Int,
    val keys: Int,
)

class PocketPcProfileBackup(
    context: Context,
    private val storage: StorageRepository,
) {
    private val appContext =
        context.applicationContext

    suspend fun create(): Result<PocketPcProfileBackupResult> =
        withContext(Dispatchers.IO) {
            runCatching {
                val payload =
                    buildBackupPayload()
                val bytes =
                    payload.toString(2)
                        .toByteArray(
                            StandardCharsets.UTF_8
                        )
                val fileName =
                    "PocketPC-profile-" +
                        System.currentTimeMillis() +
                        ".json"

                val entry =
                    ByteArrayInputStream(bytes)
                        .use { input ->
                            storage
                                .importIntoPocketDrive(
                                    directory =
                                        PocketDriveDirectory
                                            .BACKUPS,
                                    fileName = fileName,
                                    mimeType =
                                        "application/json",
                                    source = input,
                                )
                                .getOrThrow()
                        }

                val preferenceObject =
                    payload.getJSONObject(
                        KEY_PREFERENCES
                    )
                var keyCount = 0
                preferenceObject.keys()
                    .forEach { prefName ->
                        keyCount +=
                            preferenceObject
                                .getJSONObject(
                                    prefName
                                )
                                .length()
                    }

                PocketPcProfileBackupResult(
                    entry = entry,
                    preferenceFiles =
                        preferenceObject.length(),
                    keys = keyCount,
                )
            }
        }

    suspend fun restoreLatest():
        Result<PocketPcProfileRestoreResult> =
        withContext(Dispatchers.IO) {
            runCatching {
                val backupUri =
                    storage
                        .pocketDirectoryUri(
                            PocketDriveDirectory
                                .BACKUPS
                        )
                        .getOrThrow()

                val listing =
                    storage.list(backupUri)
                        .getOrThrow()

                val latest =
                    listing.entries
                        .asSequence()
                        .filter { !it.directory }
                        .filter {
                            it.name.startsWith(
                                "PocketPC-profile-"
                            ) &&
                                it.name.endsWith(
                                    ".json",
                                    ignoreCase = true,
                                )
                        }
                        .maxByOrNull {
                            it.lastModified
                        }
                        ?: error(
                            "Nenhum backup de perfil " +
                                "foi encontrado em P:\\Backups."
                        )

                val raw =
                    appContext.contentResolver
                        .openInputStream(
                            android.net.Uri.parse(
                                latest.uri
                            )
                        )
                        ?.bufferedReader(
                            StandardCharsets.UTF_8
                        )
                        ?.use { it.readText() }
                        ?: error(
                            "Não foi possível ler o backup."
                        )

                restorePayload(
                    JSONObject(raw)
                ).copy(
                    fileName = latest.name
                )
            }
        }

    private fun buildBackupPayload(): JSONObject {
        val preferences =
            JSONObject()

        BACKUP_SPECS.forEach { spec ->
            val source =
                appContext.getSharedPreferences(
                    spec.name,
                    Context.MODE_PRIVATE,
                )
            val encoded =
                JSONObject()

            source.all.forEach {
                (key, value) ->
                if (
                    spec.allowedKeys != null &&
                    key !in spec.allowedKeys
                ) {
                    return@forEach
                }

                encodePreferenceValue(value)
                    ?.let { encodedValue ->
                        encoded.put(
                            key,
                            encodedValue,
                        )
                    }
            }

            preferences.put(
                spec.name,
                encoded,
            )
        }

        return JSONObject()
            .put("schemaVersion", SCHEMA_VERSION)
            .put(
                "pocketPcVersion",
                BuildConfig.VERSION_NAME,
            )
            .put(
                "createdAtMillis",
                System.currentTimeMillis(),
            )
            .put(
                KEY_PREFERENCES,
                preferences,
            )
    }

    private fun restorePayload(
        payload: JSONObject,
    ): PocketPcProfileRestoreResult {
        require(
            payload.optInt(
                "schemaVersion",
                -1,
            ) == SCHEMA_VERSION
        ) {
            "Schema de backup incompatível."
        }

        val preferences =
            payload.optJSONObject(
                KEY_PREFERENCES
            )
                ?: error(
                    "Backup sem seção de preferências."
                )

        var restoredFiles = 0
        var restoredKeys = 0

        BACKUP_SPECS.forEach { spec ->
            val encoded =
                preferences.optJSONObject(
                    spec.name
                )
                    ?: return@forEach

            val target =
                appContext.getSharedPreferences(
                    spec.name,
                    Context.MODE_PRIVATE,
                )
            val editor = target.edit()
            var fileKeys = 0

            encoded.keys()
                .forEach { key ->
                    if (
                        spec.allowedKeys != null &&
                        key !in spec.allowedKeys
                    ) {
                        return@forEach
                    }

                    val value =
                        encoded.optJSONObject(key)
                            ?: return@forEach

                    if (
                        restorePreferenceValue(
                            editor = editor,
                            key = key,
                            encoded = value,
                        )
                    ) {
                        fileKeys++
                    }
                }

            if (fileKeys > 0) {
                editor.apply()
                restoredFiles++
                restoredKeys += fileKeys
            }
        }

        return PocketPcProfileRestoreResult(
            fileName = "",
            preferenceFiles =
                restoredFiles,
            keys = restoredKeys,
        )
    }

    private fun encodePreferenceValue(
        value: Any?,
    ): JSONObject? =
        when (value) {
            is String ->
                typedValue("string", value)
            is Boolean ->
                typedValue("boolean", value)
            is Int ->
                typedValue("int", value)
            is Long ->
                typedValue("long", value)
            is Float ->
                typedValue(
                    "float",
                    value.toDouble(),
                )
            is Set<*> -> {
                val values =
                    value.filterIsInstance<String>()
                if (
                    values.size != value.size
                ) {
                    null
                } else {
                    typedValue(
                        "stringSet",
                        JSONArray(values),
                    )
                }
            }
            else ->
                null
        }

    private fun typedValue(
        type: String,
        value: Any,
    ): JSONObject =
        JSONObject()
            .put("type", type)
            .put("value", value)

    private fun restorePreferenceValue(
        editor: SharedPreferences.Editor,
        key: String,
        encoded: JSONObject,
    ): Boolean {
        val type =
            encoded.optString("type")
        if (!encoded.has("value")) {
            return false
        }

        return when (type) {
            "string" -> {
                editor.putString(
                    key,
                    encoded.getString("value"),
                )
                true
            }

            "boolean" -> {
                editor.putBoolean(
                    key,
                    encoded.getBoolean("value"),
                )
                true
            }

            "int" -> {
                editor.putInt(
                    key,
                    encoded.getInt("value"),
                )
                true
            }

            "long" -> {
                editor.putLong(
                    key,
                    encoded.getLong("value"),
                )
                true
            }

            "float" -> {
                editor.putFloat(
                    key,
                    encoded
                        .getDouble("value")
                        .toFloat(),
                )
                true
            }

            "stringSet" -> {
                val array =
                    encoded.getJSONArray(
                        "value"
                    )
                val values =
                    buildSet {
                        for (
                            index in
                            0 until array.length()
                        ) {
                            add(
                                array.getString(index)
                            )
                        }
                    }
                editor.putStringSet(
                    key,
                    values,
                )
                true
            }

            else ->
                false
        }
    }

    private data class BackupSpec(
        val name: String,
        val allowedKeys: Set<String>?,
    )

    private companion object {
        const val SCHEMA_VERSION = 1
        const val KEY_PREFERENCES =
            "preferences"

        val BACKUP_SPECS =
            listOf(
                BackupSpec(
                    name = "pocketpc-desktop",
                    allowedKeys =
                        setOf(
                            "theme",
                            "wallpaper",
                            "performance_hud",
                            "pinned_apps",
                        ),
                ),
                BackupSpec(
                    name =
                        "pocketpc-window-layout-v2",
                    allowedKeys = null,
                ),
                BackupSpec(
                    name =
                        "pocketpc-game-compatibility",
                    allowedKeys = null,
                ),
            )
    }
}
