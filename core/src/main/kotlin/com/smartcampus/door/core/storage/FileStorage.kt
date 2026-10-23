package com.smartcampus.door.core.storage

import java.io.File

/**
 * Small key/value store on plain files. State is written to a temporary file and renamed, so a power
 * cut in the middle of a write leaves the previous version intact instead of a half written one.
 */
class FileStorage(private val directory: File) {
    init {
        directory.mkdirs()
    }

    fun read(key: String): String? = File(directory, key).takeIf { it.isFile }?.readText()

    fun write(key: String, value: String) {
        val target = File(directory, key)
        val temporary = File(directory, "$key.tmp")
        temporary.writeText(value)
        if (!temporary.renameTo(target)) {
            // Some file systems refuse to rename over an existing file.
            target.delete()
            check(temporary.renameTo(target)) { "could not store $key" }
        }
    }

    fun delete(key: String) {
        File(directory, key).delete()
    }
}
