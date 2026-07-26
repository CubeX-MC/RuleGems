package org.cubexmc.storage

import org.bukkit.configuration.file.FileConfiguration

enum class StorageLoadStatus {
    SUCCESS,
    NOT_FOUND,
    FAILURE,
}

/**
 * Explicit storage read result.
 *
 * SUCCESS and NOT_FOUND always carry data. FAILURE never carries data, so an
 * I/O or parsing error cannot be mistaken for a new empty installation.
 */
class StorageLoadResult private constructor(
    val status: StorageLoadStatus,
    val data: FileConfiguration?,
    val error: Throwable?,
) {
    val isUsable: Boolean
        get() = status != StorageLoadStatus.FAILURE && data != null

    companion object {
        @JvmStatic
        fun success(data: FileConfiguration): StorageLoadResult =
            StorageLoadResult(StorageLoadStatus.SUCCESS, data, null)

        @JvmStatic
        fun notFound(emptyData: FileConfiguration): StorageLoadResult =
            StorageLoadResult(StorageLoadStatus.NOT_FOUND, emptyData, null)

        @JvmStatic
        fun failure(error: Throwable): StorageLoadResult =
            StorageLoadResult(StorageLoadStatus.FAILURE, null, error)
    }
}

class StorageSaveResult private constructor(
    val successful: Boolean,
    val error: Throwable?,
) {
    companion object {
        @JvmStatic
        fun success(): StorageSaveResult = StorageSaveResult(true, null)

        @JvmStatic
        fun failure(error: Throwable): StorageSaveResult = StorageSaveResult(false, error)
    }
}

class StorageException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
