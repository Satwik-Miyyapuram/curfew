package dev.curfew.app.ui

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.os.Build
import androidx.activity.result.contract.ActivityResultContracts
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import dev.curfew.policy.Lock

/**
 * Proving to the core that the person asking is the person who set the lock.
 *
 * This is the one place where the platform, not the core, decides whether a condition is met — the
 * core cannot verify a fingerprint. So the seam is kept as narrow as it can be: the platform proves
 * one named [Lock] and hands that single fact back, and it is still the core that decides whether
 * the set of proven facts is enough to end the session.
 *
 * The credential asked for is the device PIN, pattern or password — *only* that. A fingerprint or
 * a face is not accepted, and the exclusion is the whole point: a biometric is touched by reflex,
 * and a lock that opens by reflex is not the friction the user asked for. Typing a PIN is a small
 * act, but it is an act. (The Windows build makes the same choice: the account password, never a
 * Hello PIN or face.) And a device credential is something every phone with a screen lock has, so
 * a failed sensor cannot turn a session into a device the user cannot get back — the design's hard
 * rule is that Curfew never makes a device unrecoverable.
 *
 * Before Android 11 the biometric prompt cannot ask for the credential on its own; there, the
 * system's own confirm-credential screen is used instead. Same question, same answer.
 */
object Auth {

    private const val ALLOWED = BiometricManager.Authenticators.DEVICE_CREDENTIAL

    /** Whether the device can satisfy a [Lock.DeviceCredential] at all: is there a screen lock? */
    fun isAvailable(context: Context): Boolean =
        context.getSystemService(KeyguardManager::class.java)?.isDeviceSecure == true

    /**
     * Ask for the screen lock. [onResult] receives whether the prompt actually succeeded; `false`
     * for a cancellation, which the caller must treat as "not proven" rather than as an error.
     *
     * The result is a fact, not a [Lock], and on purpose: a caller holding `Lock.DeviceCredential`
     * could hand it to the core whether or not the prompt ever ran. Success is reported to the core
     * separately, by `recordCredential`, which is the only route by which it counts.
     */
    fun prove(
        activity: FragmentActivity,
        title: String,
        subtitle: String,
        onResult: (Boolean) -> Unit,
    ) {
        if (!isAvailable(activity)) {
            // No screen lock set on the device. Saying so is better than a prompt that cannot open:
            // the user's next step is to set one, and only they can do that.
            onResult(false)
            return
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            confirmWithKeyguard(activity, title, subtitle, onResult)
            return
        }
        val prompt = BiometricPrompt(
            activity,
            ContextCompat.getMainExecutor(activity),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    onResult(true)
                }

                override fun onAuthenticationError(code: Int, message: CharSequence) {
                    onResult(false)
                }

                // A single failed attempt is not a decision; the prompt stays up and asks again.
                override fun onAuthenticationFailed() = Unit
            },
        )
        prompt.authenticate(
            BiometricPrompt.PromptInfo.Builder()
                .setTitle(title)
                .setSubtitle(subtitle)
                .setAllowedAuthenticators(ALLOWED)
                .build(),
        )
    }

    /**
     * The pre-Android-11 route: the keyguard's own confirm screen, which only ever asks for the
     * PIN, pattern or password. Registered under a fresh key for each ask and released as soon as
     * it answers, so nothing outlives the prompt.
     */
    @Suppress("DEPRECATION")
    private fun confirmWithKeyguard(
        activity: FragmentActivity,
        title: String,
        subtitle: String,
        onResult: (Boolean) -> Unit,
    ) {
        val keyguard = activity.getSystemService(KeyguardManager::class.java)
        val intent = keyguard?.createConfirmDeviceCredentialIntent(title, subtitle)
        if (intent == null) {
            onResult(false)
            return
        }
        val key = "curfew-credential-${System.nanoTime()}"
        lateinit var launcher: androidx.activity.result.ActivityResultLauncher<android.content.Intent>
        launcher = activity.activityResultRegistry.register(
            key,
            ActivityResultContracts.StartActivityForResult(),
        ) { result ->
            launcher.unregister()
            onResult(result.resultCode == Activity.RESULT_OK)
        }
        launcher.launch(intent)
    }
}
