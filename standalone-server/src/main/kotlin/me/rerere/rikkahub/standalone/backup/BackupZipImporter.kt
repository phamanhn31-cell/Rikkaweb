package me.rerere.rikkahub.standalone.backup

import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import kotlin.io.path.absolute
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.isRegularFile
import kotlin.io.path.name

data class BackupImportOptions(
    val includeSettings: Boolean = true,
    val includeDatabase: Boolean = true,
    val includeFiles: Boolean = true,
    val overwriteExisting: Boolean = true,
    val maxEntryBytes: Long = 1024L * 1024 * 1024,
    val maxTotalBytes: Long = 10L * 1024 * 1024 * 1024,
)

data class BackupImportResult(
    val importedSettings: Boolean,
    val importedDatabaseFiles: List<String>,
    val importedUploadFiles: Int,
    val skippedEntries: Int,
    val warnings: List<String>,
)

object BackupZipImporter {
    private const val SETTINGS_ENTRY = "settings.json"
    private const val DB_ENTRY = "rikka_hub.db"
    private const val WAL_ENTRY = "rikka_hub-wal"
    private const val SHM_ENTRY = "rikka_hub-shm"
    private const val UPLOAD_PREFIX = "upload/"

    fun importZip(zipFile: Path, dataDir: Path, options: BackupImportOptions = BackupImportOptions()): BackupImportResult {
        require(zipFile.exists() && zipFile.isRegularFile()) { "zip file does not exist: $zipFile" }

        val tmpDir = dataDir.resolve("tmp").createDirectoriesAndReturn()
        val stagingDir = tmpDir.resolve("import-staging-${Instant.now().toEpochMilli()}").createDirectoriesAndReturn()
        val stagingDbDir = stagingDir.resolve("db").createDirectoriesAndReturn()
        val stagingUploadDir = stagingDir.resolve("upload").createDirectoriesAndReturn()

        val warnings = mutableListOf<String>()
        val importedDb = mutableListOf<String>()
        var importedSettings = false
        var importedUploads = 0
        var skipped = 0
        var totalBytes = 0L

        try {
            ZipInputStream(FileInputStream(zipFile.toFile())).use { zipIn ->
                while (true) {
                    val entry = zipIn.nextEntry ?: break
                    val name = entry.name
                    if (entry.isDirectory) {
                        zipIn.closeEntry()
                        continue
                    }
                    if (name.contains('\\')) {
                        throw IllegalArgumentException("invalid zip entry name (backslash): $name")
                    }

                    when {
                        name == SETTINGS_ENTRY && options.includeSettings -> {
                            val raw = readUtf8TextWithLimit(zipIn, options.maxEntryBytes) { readBytes ->
                                totalBytes += readBytes
                                if (totalBytes > options.maxTotalBytes) {
                                    throw IllegalArgumentException("backup exceeds maxTotalBytes")
                                }
                            }
                            val migrated = SettingsJsonMigrator.migrate(raw)
                            val target = stagingDir.resolve(SETTINGS_ENTRY)
                            Files.writeString(target, migrated, StandardCharsets.UTF_8)
                            importedSettings = true
                        }

                        (name == DB_ENTRY || name == WAL_ENTRY || name == SHM_ENTRY) && options.includeDatabase -> {
                            val target = stagingDbDir.resolve(name)
                            val bytes = copyToFileWithLimits(zipIn, target, options.maxEntryBytes) { copied ->
                                totalBytes += copied
                                if (totalBytes > options.maxTotalBytes) {
                                    throw IllegalArgumentException("backup exceeds maxTotalBytes")
                                }
                            }
                            if (bytes == 0L) {
                                warnings.add("db entry extracted as empty: $name")
                            }
                            importedDb.add(name)
                        }

                        name.startsWith(UPLOAD_PREFIX) && options.includeFiles -> {
                            val fileName = name.removePrefix(UPLOAD_PREFIX)
                            if (fileName.isBlank() || fileName.contains('/')) {
                                throw IllegalArgumentException("invalid upload entry name: $name")
                            }
                            val target = stagingUploadDir.resolve(fileName)
                            copyToFileWithLimits(zipIn, target, options.maxEntryBytes) { copied ->
                                totalBytes += copied
                                if (totalBytes > options.maxTotalBytes) {
                                    throw IllegalArgumentException("backup exceeds maxTotalBytes")
                                }
                            }
                            importedUploads += 1
                        }

                        else -> {
                            skipped += 1
                        }
                    }

                    zipIn.closeEntry()
                }
            }

            applyToDataDir(
                stagingDir = stagingDir,
                stagingDbDir = stagingDbDir,
                stagingUploadDir = stagingUploadDir,
                dataDir = dataDir,
                options = options,
                warnings = warnings,
            )
        } finally {
            runCatching { stagingDir.toFile().deleteRecursively() }
        }

        return BackupImportResult(
            importedSettings = importedSettings,
            importedDatabaseFiles = importedDb.sorted(),
            importedUploadFiles = importedUploads,
            skippedEntries = skipped,
            warnings = warnings.toList(),
        )
    }

    private fun applyToDataDir(
        stagingDir: Path,
        stagingDbDir: Path,
        stagingUploadDir: Path,
        dataDir: Path,
        options: BackupImportOptions,
        warnings: MutableList<String>,
    ) {
        val backupDir = dataDir.resolve("tmp")
            .resolve("import-backup-${Instant.now().toEpochMilli()}")
            .createDirectoriesAndReturn()

        val finalDbDir = dataDir.resolve("db").createDirectoriesAndReturn()
        val finalUploadDir = dataDir.resolve("upload").createDirectoriesAndReturn()

        val stagedSettings = stagingDir.resolve(SETTINGS_ENTRY)
        if (stagedSettings.exists()) {
            val finalSettings = dataDir.resolve(SETTINGS_ENTRY)
            moveAsideIfExists(finalSettings, backupDir, options.overwriteExisting)
            Files.move(stagedSettings, finalSettings)
        }

        if (stagingDbDir.exists()) {
            Files.list(stagingDbDir).use { stream ->
                stream.forEach { stagedFile ->
                    val name = stagedFile.name
                    val finalFile = finalDbDir.resolve(name)
                    moveAsideIfExists(finalFile, backupDir, options.overwriteExisting)
                    Files.move(stagedFile, finalFile)
                }
            }
        }

        if (stagingUploadDir.exists()) {
            Files.list(stagingUploadDir).use { stream ->
                stream.forEach { stagedFile ->
                    val name = stagedFile.name
                    val finalFile = finalUploadDir.resolve(name)
                    moveAsideIfExists(finalFile, backupDir, options.overwriteExisting)
                    Files.move(stagedFile, finalFile)
                }
            }
        }

        if (backupDir.toFile().list()?.isEmpty() == true) {
            runCatching { backupDir.toFile().deleteRecursively() }
        } else {
            warnings.add("previous data was moved to: ${backupDir.absolute()}")
        }
    }
}

private fun Path.createDirectoriesAndReturn(): Path {
    createDirectories()
    return this
}

private fun moveAsideIfExists(target: Path, backupDir: Path, overwrite: Boolean) {
    if (!target.exists()) return
    if (!overwrite) {
        throw IllegalArgumentException("target already exists: $target")
    }
    backupDir.createDirectories()
    val movedTo = backupDir.resolve(target.name)
    Files.move(target, movedTo)
}

private fun readUtf8TextWithLimit(
    input: InputStream,
    maxBytes: Long,
    onBytesRead: (Long) -> Unit,
): String {
    val bytes = readBytesWithLimit(input, maxBytes, onBytesRead)
    return String(bytes, StandardCharsets.UTF_8)
}

private fun readBytesWithLimit(
    input: InputStream,
    maxBytes: Long,
    onBytesRead: (Long) -> Unit,
): ByteArray {
    val output = java.io.ByteArrayOutputStream()
    var total = 0L
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    while (true) {
        val read = input.read(buffer)
        if (read < 0) break
        total += read
        if (total > maxBytes) {
            throw IllegalArgumentException("entry exceeds maxEntryBytes")
        }
        output.write(buffer, 0, read)
    }
    onBytesRead(total)
    return output.toByteArray()
}

private fun copyToFileWithLimits(
    input: InputStream,
    target: Path,
    maxBytes: Long,
    onBytesCopied: (Long) -> Unit,
): Long {
    target.parent?.createDirectories()

    FileOutputStream(target.toFile()).use { out ->
        return copyWithLimit(input, out, maxBytes, onBytesCopied)
    }
}

private fun copyWithLimit(
    input: InputStream,
    output: OutputStream,
    maxBytes: Long,
    onBytesCopied: (Long) -> Unit,
): Long {
    var total = 0L
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    while (true) {
        val read = input.read(buffer)
        if (read < 0) break
        total += read
        if (total > maxBytes) {
            throw IllegalArgumentException("entry exceeds maxEntryBytes")
        }
        output.write(buffer, 0, read)
    }
    onBytesCopied(total)
    return total
}
