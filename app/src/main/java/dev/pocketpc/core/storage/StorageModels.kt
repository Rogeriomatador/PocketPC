package dev.pocketpc.core.storage

data class StorageEntry(
    val name: String,
    val uri: String,
    val directory: Boolean,
    val size: Long,
    val mimeType: String?,
    val lastModified: Long,
)

data class StorageListing(
    val directoryName: String,
    val entries: List<StorageEntry>,
)
