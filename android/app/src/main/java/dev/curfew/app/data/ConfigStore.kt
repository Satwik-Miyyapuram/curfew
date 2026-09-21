package dev.curfew.app.data

import dev.curfew.app.enforce.EnforcementMode
import dev.curfew.app.enforce.SensitiveApps
import dev.curfew.policy.Policy
import dev.curfew.policy.Target
import dev.curfew.policy.label
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
class ConfigStore(
    private val file: File,
    /**
     * Which detector is running, for the cross-check below.
     *
     * A function rather than a value because the mode changes while the app is running: the user picks
     * one, grants it, and the config must be judged against the new mode from that moment. A snapshot
     * taken at construction would keep answering with whatever was true when the process started.
     */
    private val mode: () -> EnforcementMode = { EnforcementMode.DEFAULT },
) {

    /** The default config for a fresh install: valid, empty of rules, and blocking nothing. */
    private val empty =
        """
        schema_version = 1
        timezone = "${java.util.TimeZone.getDefault().id}"
        """.trimIndent() + "\n"

    fun read(): String {
        val text = if (file.exists()) file.readText() else empty
        return guard(text)
    }

    /** The file as it is on disk, with no guard applied. For diagnostics and the raw editor. */
    fun readRaw(): String = if (file.exists()) file.readText() else empty

    private var dropped: List<String> = emptyList()

    /**
     * The rules the last [read] removed, in the words the user wrote them in.
     *
     * Empty in the ordinary case. Non-empty means the file on disk asks for something this device
     * cannot enforce — a URL rule while the app-only detector is running — and the screen that reports
     * health says so, rather than leaving the gap between the file and the behaviour to be discovered.
     *
     * Kept as state rather than returned from [read], because [read] is on the path that opens the
     * database and a pair would be threaded through three callers to reach one screen.
     */
    fun droppedRules(): List<String> = dropped

    /**
     * Validate, then replace. An invalid config is rejected before the old one is touched, so a bad
     * edit leaves the working config in place instead of leaving the device unprotected.
     *
     * One thing is refused beyond what the core itself rejects: a URL or keyword rule in a mode whose
     * detector cannot read a window, because such a rule is not weaker there — it is dead, and a rule
     * that silently does nothing is the failure this whole pass was about.
     *
     * **Blocking a payment or banking app is not refused.** It was, briefly, and that was wrong: which
     * apps a person may block is theirs to decide, and the reading guarantee is a property of the
     * accessibility service rather than a limit on the config. Curfew still never *reads* those apps,
     * and their notifications are still never suppressed — see `SensitiveApps` and
     * `CurfewNotificationListener` — so blocking one costs a block screen and nothing else.
     */
    fun write(toml: String): Result<Unit> {
        val check = Policy.check(toml)
        if (check.isFailure) return Result.failure(check.exceptionOrNull()!!)
        readRuleKinds(toml)?.let { kinds ->
            unenforceableInMode(kinds)?.let { return Result.failure(IllegalArgumentException(it)) }
        }
        return runCatching { writeAtomically(toml) }
    }

    /** The targets a config names, for the cross-check below. */
    private data class RuleKinds(val targets: List<Target>) {
        val urlOrKeyword: List<Target>
            get() = targets.filter { it is Target.Url || it is Target.Keyword }
    }

    private fun readRuleKinds(toml: String): RuleKinds? = runCatching {
        val policy = Policy.load(toml)
        val profilesToml = policy.configToml()
        RuleKinds(
            targets = Policy.profiles(profilesToml).flatMap { profile ->
                policy.rules(profile.id).map { it.target }
            },
        )
    }.getOrNull()

    /**
     * The reason a URL-level rule cannot be enforced by the current detector, or null.
     *
     * The two accessibility services are not two strengths of one thing: the app-only one may not read
     * a window at all, so a `url` or `keyword` rule is not "weaker" there — it is dead.
     */
    private fun unenforceableInMode(kinds: RuleKinds): String? {
        if (mode().readsWindowContent) return null
        val offending = kinds.urlOrKeyword
        if (offending.isEmpty()) return null
        return "App blocking only cannot read a window, so these rules cannot be enforced: " +
            "${offending.joinToString(", ") { it.label() }}. Switch to app and site blocking, or " +
            "remove them."
    }

    /**
     * Drop the rules that cannot mean what they say, and say why in the log.
     *
     * This is the backstop for a file that reached the disk some other way: a hand edit, a sync, a
     * restored backup, or an edit made before the rule that refuses it existed. **Only the offending
     * rules are removed**, not the whole config — somebody who blocks thirty apps and two domains
     * must not lose all thirty-two because one of them is their bank. Whatever comes out is validated
     * by the core; if the surgery did not produce a valid config the baseline is loaded instead,
     * because a device with no policy is worse off than one missing a single rule.
     *
     * Nothing here rewrites the file: the edit stays the user's, and the screens that can make it
     * already refuse to.
     */
    private fun guard(text: String): String {
        dropped = emptyList()
        // The common case: a detector that can read a window can enforce everything the core accepts,
        // so there is nothing to look at.
        if (mode().readsWindowContent) return text

        val kinds = readRuleKinds(text) ?: return text
        if (kinds.urlOrKeyword.isEmpty()) return text

        // Not narrowed here, because a URL or keyword rule in this mode *is* the thing that cannot
        // work: there is no part of it left over that could. The whole config is loaded without rules
        // rather than the file being rewritten — the edit stays the user's, and it becomes enforceable
        // again the moment they switch to the mode that can read a window.
        dropped = kinds.urlOrKeyword.map { it.label() }
        android.util.Log.w(TAG, "${unenforceableInMode(kinds)}")
        return empty
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

    private companion object {
        const val TAG = "Curfew"
    }
}
