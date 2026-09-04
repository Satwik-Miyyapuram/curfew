package dev.curfew.app

import dev.curfew.app.data.ConfigStore
import dev.curfew.policy.Policy
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The config file, and the rule that a bad edit must never leave the device unprotected.
 *
 * This is one of the few places where a plain bug becomes a security failure: a Curfew that fails
 * to load its config blocks nothing at all, and it does so silently.
 */
class ConfigStoreTest {

    @get:Rule
    val folder = TemporaryFolder()

    private fun store(): Pair<ConfigStore, File> {
        val file = File(folder.newFolder(), "curfew.toml")
        return ConfigStore(file) to file
    }

    @Test
    fun `a fresh install has a config the core will load`() {
        val (store, file) = store()

        assertTrue(!file.exists())
        assertTrue(Policy.check(store.read()).isSuccess)
    }

    @Test
    fun `a valid config is written and read back unchanged`() {
        val (store, _) = store()

        store.write(TestRuntime.CONFIG).getOrThrow()

        assertEquals(TestRuntime.CONFIG, store.read())
    }

    @Test
    fun `an invalid config is rejected before the working one is touched`() {
        val (store, _) = store()
        store.write(TestRuntime.CONFIG).getOrThrow()

        val result = store.write("schema_version = ")

        assertTrue(result.isFailure)
        assertEquals(TestRuntime.CONFIG, store.read())
    }

    @Test
    fun `a rule referring to a profile that does not exist is rejected`() {
        val (store, _) = store()

        val result = store.write(
            """
            schema_version = 1
            timezone = "Europe/London"

            [[weekly]]
            id = "mornings"
            profile = "no-such-profile"
            days = [0]
            start_minute = 540
            end_minute = 720
            """.trimIndent(),
        )

        assertTrue(result.isFailure)
    }

    @Test
    fun `the temporary file is not left behind`() {
        val (store, file) = store()

        store.write(TestRuntime.CONFIG).getOrThrow()

        val leftovers = file.parentFile!!.listFiles()!!.map { it.name }
        assertEquals(listOf("curfew.toml"), leftovers)
    }
}
