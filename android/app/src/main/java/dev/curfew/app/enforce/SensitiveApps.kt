package dev.curfew.app.enforce

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import androidx.core.content.edit

/**
 * The apps whose screens Curfew will never look at.
 *
 * **Why this exists at all.** Android enforces the "can this service read my window" question per
 * *service*, not per package: the moment an accessibility service declares
 * `canRetrieveWindowContent`, every payment, banking and wallet app on the device is entitled to
 * refuse to run — Google Pay says the device's accessibility settings could let another app see the
 * screen, and it means it. Nothing Curfew does can narrow that, because the check is on the
 * capability and not on behaviour. `android:packageNames` is a delivery filter, not a security
 * boundary, and the apps' risk engines know it.
 *
 * What Curfew *can* do is make non-inspection structural rather than incidental: a package on this
 * list is short-circuited in every accessibility entry point before the uninstall guard, before
 * browser detection, and before any `rootInActiveWindow` call. The guarantee is "no node of a
 * sensitive app is ever touched, on any code path", and it holds by construction rather than by
 * review — which is what makes it survive the next change to those methods.
 *
 * A sensitive app is still *blockable as an app*. The package name arrives in the window-state event
 * without any node access, so a user who asks Curfew to block one still gets that; what they do not get
 * is Curfew reading its screen, its windows, or its notifications. This list is a restriction on
 * **reading**, and on nothing else — it is not consulted by the app picker's filter, and `ConfigStore`
 * does not refuse a rule that blocks one. Deciding what a person may block is not this app's business.
 *
 * The one place it is consulted outside the readers is `CurfewNotificationListener`, which delivers
 * financial notifications unconditionally. That is what makes blocking a bank app safe: a block
 * normally suppresses the blocked target's notifications, and a one-time password that never arrives
 * is a payment that never completes.
 *
 * **Three sources, and the two configurable ones are deliberately separate.**
 *
 *  1. [CURATED] — shipped, and not removable. These are unambiguously payment, banking, wallet or
 *     credential apps, and a user who could switch one off would be switching off a guarantee they
 *     were shown on the permission screen.
 *  2. [extraPackages] — added by the user in Settings, for a regional bank the shipped list does
 *     not know. This only ever *widens* the guarantee.
 *  3. [looksLikeFinanceApp] — installed apps that both request a biometric permission and carry a
 *
 * A user who needs to *narrow* the list — a budgeting app wrongly categorised as finance, which
 * they genuinely want to block and have read — removes it with [removeUserPackage], which records
 * an exemption. Without that, the category signal could make an app unexemptable and the user would
 * have no way to say so.
 *
 * **This list is load-bearing in both directions.** Too short and a bank is exposed; too long and a
 * user who asked to block an app finds it silently unblockable, with nothing on any screen saying
 * why. Both directions are what the tests check.
 */
object SensitiveApps {

    /**
     * Payment, banking, wallet and credential apps.
     *
     * Curated rather than guessed at. The Indian UPI stack is the bulk of it because that is where
     * the accessibility checks are strictest and best documented, followed by the international
     * wallets and the banks whose apps are widely installed. A regional bank that is not here is
     * picked up by [looksLikeFinanceApp] when its label and permissions give it away, and added by the
     * user when they do not.
     *
     * Shipped and not removable: an app on this list is one a user was promised Curfew would not
     * read, and a promise with an off switch is not a promise.
     */
    val CURATED: Set<String> = setOf(
        // UPI and payment
        "com.google.android.apps.nbu.paisa.user", // Google Pay (India)
        "com.google.android.apps.walletnfcrel", // Google Wallet
        "com.phonepe.app",
        "net.one97.paytm",
        "in.org.npci.upiapp", // BHIM
        "in.amazon.mShop.android.shopping", // Amazon Pay (India)
        // The global Amazon app, which is a *different package* from the India one and is the one a
        // device outside India has. Found because a real config blocked it by name while the shipped
        // list held only the other spelling — which is the difference between a guarantee and a
        // guarantee that does not apply to the phone it is installed on.
        "com.amazon.mShop.android.shopping",
        "com.dreamplug.androidapp", // CRED
        "com.mobikwik_new",
        "com.freecharge.android",
        "com.myairtelapp",
        "com.jio.myjio",
        "com.samsung.android.spay",
        "com.samsung.android.spaymini",
        "com.paypal.android.p2pmobile",
        "com.venmo",
        "com.squareup.cash",
        "com.whatsapp.w4b", // WhatsApp Business: payment flows and business chat

        // Banking
        "com.sbi.lotusintouch",
        "com.sbierc.lotusintouch",
        "com.csam.icici.bank.imobile",
        "com.icicibank.pockets",
        "com.snapwork.hdfc",
        "com.hdfc.lotussms",
        // The rebranded HDFC app, found on the development device — where it was the installed HDFC
        // app and `com.snapwork.hdfc` was not. The two coexist during a migration, so both are listed
        // rather than one replacing the other.
        "com.hdfcbank.payzapp",
        "com.axis.mobile",
        "com.msf.kbank.mobile", // Kotak
        "com.kotak.mahindra",
        "com.idfcfirstbank.optimus",
        "com.bankofbaroda.upi",
        "com.pnb.netbanking",
        "com.canarabank.mobility",
        "com.unionbankofindia.unionmobile",
        "com.indusind.indusmobile",
        "com.yesbank.yesmobilebanking",
        "com.federalbank.fedmobile",
        "com.rblbank.mobilebanking",

        // International banks and wallets, also from the development device: a phone in the
        // Netherlands is as ordinary as one in India and the argument is the same for both.
        "com.abnamro.nl.mobile.payments",
        "com.lebara.wallet",
        "com.ge.capital.konysbiapp",
        "com.samsung.android.samsungpay.gear",

        // Credential and authenticator apps: the same reasoning as the banks, and a screen Curfew
        // has no business reading under any rule a user could write.
        "com.google.android.apps.authenticator2",
        "com.authy.authy",
        "com.bitwarden.premium",
        "com.x8bit.bitwarden",
        "com.lastpass.lpandroid",
        "com.onepassword.android",
        "com.agilebits.onepassword",
        "com.dashlane",
        "com.keepersecurity.passwordmanager",
    )

    private const val PREFS = "curfew_sensitive"
    private const val KEY_EXTRA = "extra_packages"
    private const val KEY_EXEMPT = "exempt_packages"

    /**
     * The resolved list for this device.
     *
     * **Resolved once, when a reader starts, and then held as a plain set.** The reader runs on the
     * first line of every accessibility event — the hot path of every app switch on the device — and
     * two of the three sources need a `Context` and a `PackageManager`. Doing that work per event, or
     * reaching for a process-wide singleton to avoid it, are both worse than resolving at the one
     * moment the answer cannot change underneath the caller.
     *
     * The set can only change when a package is installed, removed or replaced, and the app already
     * re-creates these services on `MY_PACKAGE_REPLACED` and `BOOT_COMPLETED`. The staleness window
     * is therefore the lifetime of a service instance, and the direction it fails in is safe: a bank
     * installed a moment ago is not yet on the list, and nothing already on the list is dropped.
     */
    fun resolve(context: Context): Set<String> {
        val exempt = exemptPackages(context)
        return buildSet {
            addAll(CURATED)
            addAll(extraPackages(context))
            addAll(looksLikeFinanceApp(context.applicationContext, exempt))
            // Last, so an exemption wins over the category signal. The curated list is untouchable
            // either way, which is why it is re-added rather than merely not removed.
            removeAll(exempt - CURATED)
        }
    }

    /** Packages the user added themselves. */
    fun extraPackages(context: Context): Set<String> =
        prefs(context).getStringSet(KEY_EXTRA, emptySet()).orEmpty()

    /** Packages the user has taken *off* the list, which only a dynamic signal could have added. */
    fun exemptPackages(context: Context): Set<String> =
        prefs(context).getStringSet(KEY_EXEMPT, emptySet()).orEmpty()

    /**
     * Add a package to the list.
     *
     * Recorded even when another signal would already have covered it, so removing it later does what
     * the user expects rather than depending on which signal happened to catch it.
     */
    fun addUserPackage(context: Context, packageName: String) {
        val trimmed = packageName.trim()
        if (trimmed.isEmpty() || trimmed in CURATED) return
        prefs(context).edit {
            putStringSet(KEY_EXTRA, extraPackages(context) + trimmed)
            // An explicit addition outranks any earlier explicit exemption.
            putStringSet(KEY_EXEMPT, exemptPackages(context) - trimmed)
        }
    }

    /**
     * Take a package off the list.
     *
     * A [CURATED] package cannot be removed — see the class comment. Anything else is exempted rather
     * than merely deleted from the user's own additions, because the finance category would otherwise
     * put it straight back on the next resolve.
     */
    fun removeUserPackage(context: Context, packageName: String) {
        if (packageName in CURATED) return
        prefs(context).edit {
            putStringSet(KEY_EXTRA, extraPackages(context) - packageName)
            putStringSet(KEY_EXEMPT, exemptPackages(context) + packageName)
        }
    }

    fun isUserAdded(context: Context, packageName: String): Boolean =
        packageName in extraPackages(context)

    fun isUserExempt(context: Context, packageName: String): Boolean =
        packageName in exemptPackages(context)

    fun isCurated(packageName: String): Boolean = packageName in CURATED

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * Installed packages that look like a bank, for the regional one the curated list does not know.
     *
     * **This is not the signal it was planned to be, and the reason is worth recording.**
     * `ApplicationInfo.category` was the intended source, on the strength of a `CATEGORY_FINANCE`
     * constant. There is no such constant — the platform defines `CATEGORY_GAME`, `AUDIO`, `VIDEO`,
     * `IMAGE`, `SOCIAL`, `NEWS`, `MAPS`, `PRODUCTIVITY`, `ACCESSIBILITY` and `UNDEFINED`, and nothing
     * for finance, in either API 35 or API 37. So `category` cannot answer this question at all, and a
     * check written against it would have compiled into a comparison that is false for every app on
     * every device — a privacy feature that silently did nothing.
     *
     * What is used instead is a conjunction of two weak signals, both of which have to agree:
     *
     *  - the app **holds** `USE_BIOMETRIC` (or the pre-28 `USE_FINGERPRINT`), which almost every
     *    banking and payment app does and almost no game or feed does; and
     *  - its **label contains a word that is unambiguously about money or a bank**, from a list chosen
     *    to exclude generic terms. "Bank", "UPI", "wallet", "banking", "pay", "credit", "insurance",
     *    "mutual fund", "demat", "biller", "recharge" — an app called "Wallet of Wonders" that
     *    requests biometrics would be caught, which is the price of catching a regional bank, and the
     *    user can always take it off with [removeUserPackage].
     *
     * Deliberately *not* included: "finance", "money", "bank" in a package name, or any of the
     * package-name heuristics this codebase has spent this work removing everywhere else. A label is
     * what a person reads and what the publisher chose; a substring of a package id is neither.
     *
     * Failures produce the empty set: a `PackageManager` that will not answer must not be able to stop
     * the accessibility path from running.
     */
    private fun looksLikeFinanceApp(context: Context, exempt: Set<String>): Set<String> {
        return runCatching {
            val manager = context.packageManager
            // The label filter first, because it is free: `getInstalledApplications` gives every
            // label in one call, while the permission list costs a `getPackageInfo` per package. On a
            // device with two hundred apps that is the difference between one binder call and a
            // handful.
            manager.getInstalledApplications(PackageManager.GET_META_DATA)
                .asSequence()
                .filter { it.packageName !in exempt }
                .filter { labelLooksFinancial(it, manager) }
                .filter { requestsBankingPermissions(it, manager) }
                .map { it.packageName }
                .toSet()
        }.getOrDefault(emptySet())
    }

    /** The first weak signal: the name the publisher chose, read by the user. */
    private fun labelLooksFinancial(info: ApplicationInfo, manager: PackageManager): Boolean {
        val label = runCatching { manager.getApplicationLabel(info).toString() }.getOrNull()
            ?: return false
        val lowered = label.lowercase()
        return FINANCIAL_LABEL_WORDS.any { it in lowered }
    }

    /**
     * The second weak signal: the permissions the package asked for.
     *
     * From `PackageInfo.requestedPermissions`, which is what the platform recorded at install time.
     * `ApplicationInfo` does not carry it, so this is one `getPackageInfo` per candidate — which is
     * why it is asked second and only of apps whose label already looked financial.
     */
    private fun requestsBankingPermissions(info: ApplicationInfo, manager: PackageManager): Boolean {
        val requested = runCatching {
            manager.getPackageInfo(info.packageName, PackageManager.GET_PERMISSIONS)
                .requestedPermissions
        }.getOrNull() ?: return false
        return requested.any { it in BANKING_PERMISSIONS }
    }

    /**
     * The permissions a banking, payment or wallet app almost always asks for and a distraction app
     * almost never does.
     */
    private val BANKING_PERMISSIONS = setOf(
        android.Manifest.permission.USE_BIOMETRIC,
        "android.permission.USE_FINGERPRINT",
        "android.permission.USE_IRIS",
    )

    /**
     * Words in an app's own display name that mean money.
     *
     * Chosen so that the second signal cannot carry the decision on its own: each of these is unusual
     * outside a financial app, and a match still has to be paired with [BANKING_PERMISSIONS]. The
     * generic words that would make this unusable — "pay" is the obvious one, since it is also in
     * "Paywall" and "Playtime" — are kept because the permission gate is what stops them mattering.
     */
    private val FINANCIAL_LABEL_WORDS = listOf(
        "bank",
        "upi",
        "wallet",
        "paytm",
        "phonepe",
        "cred",
        "demat",
        "mutual fund",
        "insurance",
        "credit card",
        "netbanking",
        "biller",
        "remittance",
        "forex",
        "nbfc",
    )
}
