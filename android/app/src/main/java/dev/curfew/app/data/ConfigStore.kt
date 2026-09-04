package dev.curfew.app.data

import dev.curfew.policy.Policy
import java.io.File
import java.io.IOException

/**
 * The config file, and the rules about replacing it.
 *
 * The config is plain TOML on disk rather than rows in the database, because it is the one thing a
 * user may reasonably want to read, diff, back up and carry to another device by hand. That is also
 * why writes are atomic: a half-written config after a crash would be a config that fails to load,
 * and a Curfew that fails to load its config is a Curfew that blocks nothing.
 */
class ConfigStore(private val file: File) {

    /** The default config for a fresh install: valid, empty of rules, and blocking nothing. */
    private val empty =
        """
        schema_version = 1
        timezone = "${java.util.TimeZone.getDefault().id}"
        """.trimIndent() + "\n"

    fun read(): String =
        if (file.exists()) file.readText() else empty

    /**
     * Validate, then replace. An invalid config is rejected before the old one is touched, so a bad
     * edit leaves the working config in place instead of leaving the device unprotected.
     */
    fun write(toml: String): Result<Unit> {
        val check = Policy.check(toml)
        if (check.isFailure) return Result.failure(check.exceptionOrNull()!!)
        return runCatching { writeAtomically(toml) }
    }

    private fun writeAtomically(toml: String) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, "${file.name}.tmp")
        tmp.writeText(toml)
        if (!tmp.renameTo(file)) {
            // Some filesystems refuse a rename onto an existing file; fall back to copy-then-delete
            // rather than deleting first, so there is never a moment with no config at all.
            file.writeText(toml)
            if (!tmp.delete()) throw IOException("could not clean up ${tmp.name}")
        }
    }
}
