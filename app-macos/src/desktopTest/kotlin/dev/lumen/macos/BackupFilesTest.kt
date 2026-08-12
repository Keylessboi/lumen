package dev.lumen.macos

import dev.lumen.core.export.ExportCodec
import dev.lumen.core.export.ExportFile
import dev.lumen.core.export.ExportHeader
import dev.lumen.core.export.ExportResult
import dev.lumen.core.export.KdfParams
import dev.lumen.core.model.DeviceId
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BackupFilesTest {

    private val tmp: File = Files.createTempDirectory("lumen-backup-test").toFile()

    @AfterTest
    fun cleanup() {
        tmp.deleteRecursively()
    }

    private fun sampleFile() = ExportFile(
        header = ExportHeader(
            exportedAtMs = 1_000L,
            exportedByDevice = DeviceId("11111111-2222-3333-4444-555555555555"),
            kdf = KdfParams(
                memoryKib = KdfParams.DEFAULT_MEMORY_KIB,
                iterations = KdfParams.DEFAULT_ITERATIONS,
                parallelism = 4,
                salt = ByteArray(16) { it.toByte() },
            ),
            nonce = ByteArray(12) { it.toByte() },
        ),
        ciphertext = ByteArray(64) { it.toByte() },
    )

    @Test
    fun `a written backup reads back as the same file`() {
        val destination = File(tmp, "backup.lumen")
        val original = sampleFile()

        assertTrue(BackupFiles.write(original, destination).isSuccess)
        val text = BackupFiles.read(destination).getOrThrow()
        assertEquals(original, (ExportCodec.decodeFile(text) as ExportResult.Success).value)
    }

    @Test
    fun `no partial file is left behind on success`() {
        // The .part file exists only during the write; a leftover would look
        // like a second, broken backup.
        val destination = File(tmp, "backup.lumen")
        BackupFiles.write(sampleFile(), destination)
        assertEquals(
            listOf("backup.lumen"),
            tmp.listFiles()!!.map { it.name }.sorted(),
        )
    }

    @Test
    fun `missing directories are created`() {
        val nested = File(tmp, "a/b/c/backup.lumen")
        assertTrue(BackupFiles.write(sampleFile(), nested).isSuccess)
        assertTrue(nested.exists())
    }

    @Test
    fun `an existing backup is replaced, not corrupted`() {
        val destination = File(tmp, "backup.lumen")
        BackupFiles.write(sampleFile(), destination)
        val second = sampleFile().copy(ciphertext = ByteArray(32) { 9 })
        BackupFiles.write(second, destination)

        val text = BackupFiles.read(destination).getOrThrow()
        assertEquals(second, (ExportCodec.decodeFile(text) as ExportResult.Success).value)
    }

    @Test
    fun `the default name carries the date so backups do not overwrite each other`() {
        val path = BackupFiles.defaultPath("2026-08-12")
        assertTrue(path.name.contains("2026-08-12"), path.name)
        assertTrue(path.name.endsWith(".lumen"))
    }

    @Test
    fun `reading a file that is not there fails rather than returning empty`() {
        // An empty string would parse as malformed and read as "not a backup",
        // which is a different and more confusing answer than "no such file".
        assertTrue(BackupFiles.read(File(tmp, "nope.lumen")).isFailure)
    }
}
