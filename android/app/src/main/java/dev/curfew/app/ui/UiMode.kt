package dev.curfew.app.ui

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * How much of itself the app shows.
 *
 * Curfew has two kinds of user in the same install: someone who wants their phone to stop handing
 * them Instagram at midnight and never wants to see the word "profile", and someone who wants to
 * read the rule that matched, the id it came from and the raw TOML underneath. Building for the
 * average of those two produced a screen that served neither.
 *
 * So there is a switch, and one rule about what it may do: **Simple hides tabs, not powers.**
 * Every screen in [Mode.Power] is still reachable from Settings while Simple is on, and nothing a
 * user has already set up stops working because they moved the switch. Turning Simple on is a
 * statement about what they want to look at, never about what Curfew is allowed to do.
 */
enum class Mode {
    /** Four tabs, plain sentences, no ids, no syntax. What a fresh install starts as. */
    Simple,

    /** Seven tabs, exact timers, rule provenance, the config file itself. */
    Power,
    ;

    val isPower get() = this == Power
}

/**
 * The mode, remembered.
 *
 * Deliberately not in the policy database: this is a fact about the person holding the phone, not
 * about what is enforced, and it must never be able to fail a config validation or travel to
 * another device in a sync. A preference file is the honest home for it.
 */
class UiModeStore(context: Context) {

    private val prefs = context.getSharedPreferences("ui", Context.MODE_PRIVATE)

    private val _mode = MutableStateFlow(
        if (prefs.getBoolean(KEY, false)) Mode.Power else Mode.Simple,
    )

    val mode: StateFlow<Mode> = _mode.asStateFlow()

    fun set(mode: Mode) {
        prefs.edit().putBoolean(KEY, mode.isPower).apply()
        _mode.value = mode
    }

    private companion object {
        const val KEY = "power_mode"
    }
}
