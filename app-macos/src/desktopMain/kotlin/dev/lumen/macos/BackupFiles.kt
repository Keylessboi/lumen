package dev.lumen.macos

import dev.lumen.core.export.ExportCodec
import dev.lumen.core.export.ExportFile
import java.io.File

/**
 * Where a backup goes on macOS, and how it gets there safely.
 *
 * No native save panel: that needs AppKit through a JNI bridge, and the dev
 * build is an unpackaged JVM. Writing to `~/Documents` with a dated name is
 * findable, predictable, and does not depend on a bridge that only exists in
 * the packaged app. A real picker is worth doing when `app-macos` gets its
 * AppKit bridge; the file this writes is identical either way.
 */
object BackupFiles {

    /** `~/Documents/Lumen backup 2026-08-12.lumen` */
    fun defaultPath(dayLocal: String): File =
        File(File(System.getProperty("user.home"), "Documents"), "Lumen backup $dayLocal.lumen")

    /**
     * Write atomically: full file to a temp path, then rename.
     *
     * A backup half-written because the process died mid-write is worse than
     * no backup at all — the user has a file, believes they are covered, and
     * finds out otherwise on the day they need it. Rename within the same
     * directory is atomic on the filesystems macOS ships.
     */
    fun write(file: ExportFile, destination: File): Result<File> = runCatching {
        destination.parentFile?.mkdirs()
        val temp = File(destination.parentFile, "${destination.name}.part")
        temp.writeText(ExportCodec.encodeFile(file))
        // Deliberately not deleting the destination first: on failure the
        // previous backup survives rather than being replaced by nothing.
        if (!temp.renameTo(destination)) {
            temp.copyTo(destination, overwrite = true)
            temp.delete()
        }
        destination
    }

    /** Most recent `.lumen` file in ~/Documents, for a one-tap restore. */
    fun mostRecentBackup(): File? =
        File(System.getProperty("user.home"), "Documents")
            .listFiles { f -> f.isFile && f.name.endsWith(".lumen") }
            ?.maxByOrNull { it.lastModified() }

    fun read(source: File): Result<String> = runCatching { source.readText() }
}
