package dev.curfew.app

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.curfew.app.data.Clock
import dev.curfew.app.data.ConfigStore
import dev.curfew.app.data.CurfewDatabase
import dev.curfew.app.data.CurfewRuntime
import dev.curfew.policy.Policy
import java.io.File

/**
 * A runtime with a real policy core and a throwaway database.
 *
 * The core is the real one, loaded from the real library: a test that fakes the thing under
 * discussion proves nothing about whether Kotlin and Rust agree. Only the database is swapped, and
 * only because SQLCipher's native library is not present on a JVM test run.
 */
object TestRuntime {

    /** A config with one of every rule shape the app has to handle. */
    const val CONFIG = """
schema_version = 1
timezone = "Europe/London"

[[profiles]]
id = "deep-work"
name = "Deep work"

[[profiles.rules]]
target = { kind = "app_package", package = "com.instagram.android" }
action = { kind = "block" }

[[profiles.rules]]
target = { kind = "app_package", package = "com.slack" }
action = { kind = "delay", seconds = 15 }

[[profiles.rules]]
target = { kind = "domain", domain = "reddit.com" }
action = { kind = "budget", seconds = 1200, refill = { kind = "daily", at_minute = 240 } }

[[profiles.rules]]
target = { kind = "app_package", package = "com.twitter.android" }
action = { kind = "launch_limit", count = 3, refill = { kind = "daily", at_minute = 240 } }

[[profiles.rules]]
target = { kind = "notification_source", package = "com.whatsapp" }
action = { kind = "mute_notifications" }

[[weekly]]
id = "mornings"
profile = "deep-work"
days = [0, 1, 2, 3, 4]
start_minute = 540
end_minute = 720
locks = [{ kind = "timer" }]
"""

    /** 2026-09-04 09:30 Europe/London: a Friday, inside the `mornings` window. */
    const val FRIDAY_0930 = 1_788_510_600L

    fun create(
        now: Long = FRIDAY_0930,
        configToml: String = CONFIG,
        context: Context = ApplicationProvider.getApplicationContext(),
        clock: MovableClock = MovableClock(now),
    ): CurfewRuntime {
        val db = Room.inMemoryDatabaseBuilder(context, CurfewDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val file = File.createTempFile("curfew", ".toml").apply { deleteOnExit() }
        val store = ConfigStore(file)
        store.write(configToml).getOrThrow()
        return CurfewRuntime(
            context = context,
            policy = Policy.load(configToml),
            config = store,
            db = db,
            clock = clock,
        )
    }
}

/**
 * A second runtime over the same database, which is what a reboot looks like from inside the app:
 * the process is gone, the policy core is fresh, and the only thing that carried over is what was
 * written down.
 */
object CurfewRuntimeFactory {
    fun reopen(
        from: CurfewRuntime,
        now: Long,
        clock: MovableClock = MovableClock(now),
    ): CurfewRuntime = CurfewRuntime(
        context = ApplicationProvider.getApplicationContext(),
        policy = Policy.load(from.config.read()),
        config = from.config,
        db = from.db,
        clock = clock,
    )
}

/**
 * A clock a test can move, so a budget can run out without anyone waiting twenty minutes.
 *
 * The wall clock and the monotonic clock move together under [advance], which is what an honest
 * device does. [wind] moves only the wall clock, which is what a user trying to end a lock early
 * does, and [reboot] restarts uptime the way a real restart does.
 */
class MovableClock(private var seconds: Long) : Clock {
    private var uptimeSeconds: Long = 1_000
    private var boot: Long = 1

    override fun now(): Long = seconds

    override fun uptime(): Long = uptimeSeconds

    override fun bootId(): Long = boot

    /** Real time passing: both clocks agree, because nothing has been tampered with. */
    fun advance(by: Long) {
        seconds += by
        uptimeSeconds += by
    }

    fun set(to: Long) {
        seconds = to
    }

    /** Move the wall clock and nothing else. The monotonic clock is what gives this away. */
    fun wind(by: Long) {
        seconds += by
    }

    /** Switch the device off for [downtime] seconds and back on. */
    fun reboot(downtime: Long) {
        seconds += downtime
        uptimeSeconds = 0
        boot += 1
    }
}
