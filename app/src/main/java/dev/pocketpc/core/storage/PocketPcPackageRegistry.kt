package dev.pocketpc.core.storage

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

enum class PocketPcPackageState {
    STORED,
    RUNTIME_REQUIRED,
}

data class PocketPcPackageRecord(
    val uri: String,
    val name: String,
    val size: Long,
    val importedAtMillis: Long,
    val state: PocketPcPackageState,
)

class PocketPcPackageRegistry(
    context: Context,
) {
    private val prefs =
        context.applicationContext
            .getSharedPreferences(
                "pocketpc-pc-packages",
                Context.MODE_PRIVATE,
            )

    fun records(): List<PocketPcPackageRecord> =
        parseRecords(
            prefs.getString(KEY_RECORDS, null)
        )

    fun record(entry: StorageEntry) {
        if (
            classifyPocketFile(entry.name) !=
            PocketFileClass.PC_INSTALLER
        ) {
            return
        }

        val current =
            records()
                .filterNot {
                    it.uri == entry.uri
                }
                .toMutableList()

        current +=
            PocketPcPackageRecord(
                uri = entry.uri,
                name = entry.name,
                size = entry.size,
                importedAtMillis =
                    System.currentTimeMillis(),
                state =
                    PocketPcPackageState
                        .RUNTIME_REQUIRED,
            )

        save(current)
    }

    fun remove(uri: String) {
        save(
            records().filterNot {
                it.uri == uri
            }
        )
    }

    fun update(
        oldUri: String,
        entry: StorageEntry,
    ) {
        val withoutOld =
            records().filterNot {
                it.uri == oldUri
            }

        if (
            classifyPocketFile(entry.name) ==
            PocketFileClass.PC_INSTALLER
        ) {
            save(
                withoutOld +
                    PocketPcPackageRecord(
                        uri = entry.uri,
                        name = entry.name,
                        size = entry.size,
                        importedAtMillis =
                            System.currentTimeMillis(),
                        state =
                            PocketPcPackageState
                                .RUNTIME_REQUIRED,
                    )
            )
        } else {
            save(withoutOld)
        }
    }

    private fun save(
        records: List<PocketPcPackageRecord>,
    ) {
        val normalized =
            records
                .distinctBy { it.uri }
                .sortedByDescending {
                    it.importedAtMillis
                }
                .take(MAX_RECORDS)

        val array = JSONArray()
        normalized.forEach { record ->
            array.put(
                JSONObject()
                    .put("uri", record.uri)
                    .put("name", record.name)
                    .put("size", record.size)
                    .put(
                        "importedAtMillis",
                        record.importedAtMillis,
                    )
                    .put(
                        "state",
                        record.state.name,
                    )
            )
        }

        prefs.edit()
            .putString(
                KEY_RECORDS,
                array.toString(),
            )
            .apply()
    }

    private fun parseRecords(
        raw: String?,
    ): List<PocketPcPackageRecord> {
        if (raw.isNullOrBlank()) {
            return emptyList()
        }

        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (
                    index in 0 until array.length()
                ) {
                    val item =
                        array.optJSONObject(index)
                            ?: continue
                    val uri =
                        item.optString("uri")
                    val name =
                        item.optString("name")
                    if (
                        uri.isBlank() ||
                        name.isBlank()
                    ) {
                        continue
                    }

                    val state =
                        runCatching {
                            PocketPcPackageState
                                .valueOf(
                                    item.optString(
                                        "state",
                                        PocketPcPackageState
                                            .RUNTIME_REQUIRED
                                            .name,
                                    )
                                )
                        }.getOrDefault(
                            PocketPcPackageState
                                .RUNTIME_REQUIRED
                        )

                    add(
                        PocketPcPackageRecord(
                            uri = uri,
                            name = name,
                            size =
                                item.optLong(
                                    "size",
                                    0L,
                                ),
                            importedAtMillis =
                                item.optLong(
                                    "importedAtMillis",
                                    0L,
                                ),
                            state = state,
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    private companion object {
        const val KEY_RECORDS =
            "records"
        const val MAX_RECORDS = 512
    }
}
