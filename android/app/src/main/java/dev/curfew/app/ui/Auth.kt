package dev.curfew.app.ui

import android.content.Context
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
 * `DEVICE_CREDENTIAL` is included alongside biometrics deliberately. A lock that can only be opened
 * by a fingerprint is a lock that a cut finger or a failed sensor turns into a device the user
 * cannot get back — and the design's hard rule is that Curfew never makes a device unrecoverable.
 */
object Auth {

    private const val ALLOWED =
        BiometricManager.Authenticators.BIOMETRIC_STRONG or
            BiometricManager.Authenticators.DEVICE_CREDENTIAL

    /** Whether the device can satisfy a [Lock.DeviceCredential] at all. */
    fun isAvailable(context: Context): Boolean =
        BiometricManager.from(context).canAuthenticate(ALLOWED) ==
            BiometricManager.BIOMETRIC_SUCCESS

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
}
