package dev.curfew.app.enforce

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.view.accessibility.AccessibilityEvent

/**
 * The declared accessibility surface, kept no larger than the rules can justify.
 *
 * **Why this exists.** `url_reader_config.xml` subscribes to `typeWindowContentChanged` for ever,
 * because the manifest is static and the app cannot know at install time whether this user will ever
 * write a site rule. A user who only blocks apps pays for that subscription twice: the content-event
 * firehose costs wake-ups even with a 250 ms coalescer, and it is the visible signal a risk engine
 * looks at. `setServiceInfo` is the only way to narrow a live service, and the surface should match
 * the plan.
 *
 * **Why this is careful.** `canRetrieveWindowContent` cannot be changed at runtime — it is fixed by
 * the XML — so this narrows what is *delivered and looked at*, not the privilege. And it can only
 * ever be a narrowing of a correct baseline, never the source of correctness: the XML is the
 * baseline, and if anything about this fails the service keeps the XML's subscription, which is the
 * safe direction because it is the more capable one.
 *
 * The flag that must survive is `flagReportViewIds`. Android strips view resource ids from the node
 * tree unless the service asks for them, and without it `findAccessibilityNodeInfosByViewId` returns
 * an empty list — which is how site blocking was silently dead for two revisions. So this never sets
 * [AccessibilityServiceInfo.flags] itself; it carries the service's own flags through untouched and
 * only toggles which events are delivered. That is the fallback: a code path that cannot damage the
 * thing whose absence was the bug.
 */
object ServiceSurface {

    /**
     * Whether this config has anything that a content event could serve.
     *
     * A URL or keyword rule is the only thing that needs the address bar, and the address bar is only
     * re-read on a content event. Everything else — app blocks, budgets, launch limits, schedules,
     * `allow_only` — is decided from the window-state event, which carries the package name and
     * nothing else.
     *
     * [webRulesExist] comes from `Policy.hasUrlLevelRules`, which is the one authority on whether any
     * profile holds a rule only a window reader could enforce.
     */
    fun desired(webRulesExist: Boolean): Subscription = if (webRulesExist) {
        Subscription(
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            notificationTimeout = CONTENT_TIMEOUT_MILLIS,
        )
    } else {
        Subscription(
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            notificationTimeout = 0,
        )
    }

    /**
     * [eventTypes] as `AccessibilityServiceInfo.eventTypes` wants it — an `Int` bitmask — and
     * [notificationTimeout] as that class wants it, a `Long` count of milliseconds. The platform's
     * types are not negotiable, even though the XML attributes are written without a suffix.
     */
    data class Subscription(val eventTypes: Int, val notificationTimeout: Long)

    /**
     * Apply [desired] to a live service, in place.
     *
     * Returns true when the subscription is what the config justifies — whether or not this call was
     * the one that made it so. Every failure is swallowed on purpose: this is a narrowing applied on
     * top of a config that already works, and a service that threw here would stop enforcing over a
     * subscription it did not manage to narrow. The caller logs the false.
     *
     * **Mutated rather than rebuilt, and that is the fallback.** `AccessibilityServiceInfo` is read
     * back from the platform and only the two fields this class is responsible for are changed, so the
     * flags — including `flagReportViewIds`, whose absence was the silent bug — the feedback type and
     * the package filter are all carried through by construction rather than by this code remembering
     * to copy them. A rebuilt object is one forgotten field away from removing the flag again. If the
     * platform reports no flags at all, this abandons the change rather than applying it, because an
     * empty flag set is exactly that failure.
     */
    fun apply(service: AccessibilityService, webRulesExist: Boolean): Boolean = runCatching {
        val current = service.serviceInfo ?: return false
        val wanted = desired(webRulesExist)
        if (current.eventTypes == wanted.eventTypes &&
            current.notificationTimeout == wanted.notificationTimeout
        ) {
            return true
        }
        if (current.flags == 0) return false
        current.eventTypes = wanted.eventTypes
        current.notificationTimeout = wanted.notificationTimeout
        service.serviceInfo = current
        true
    }.getOrDefault(false)

    /**
     * The coalescer to use when content events are subscribed.
     *
     * Matches `notificationTimeout` in `url_reader_config.xml` and
     * `ScreenWatcher.URL_RECHECK_MILLIS`, so a burst of address-bar repaints is dropped by the
     * framework and the in-code throttle is the belt rather than the only defence.
     *
     * A `Long` because `AccessibilityServiceInfo.notificationTimeout` is one: it is milliseconds since
     * the last event, and the platform's own type is not negotiable even though the XML attribute is
     * written without a suffix.
     */
    const val CONTENT_TIMEOUT_MILLIS = 250L
}
