package dev.curfew.app.ui

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextOverflow
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import dev.curfew.app.enforce.EnforcementService

/**
 * A [FragmentActivity] rather than a plain ComponentActivity because [Auth] shows a
 * `BiometricPrompt`, which needs a fragment host. Ending a locked session is the one action here
 * that has to prove who is asking.
 */
class MainActivity : FragmentActivity() {

    private val pendingPairingCode = androidx.compose.runtime.mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Appearance.attach(this)
        Juice.attach(this)
        enableEdgeToEdge()
        // Opening the app is also the moment to make sure enforcement is up: a user who force-stops
        // Curfew and then opens it has re-armed it by doing so.
        EnforcementService.start(applicationContext)
        handlePairingIntent(intent)

        setContent {
            CurfewTheme {
                CurfewApp(
                    pendingPairingCode = pendingPairingCode.value,
                    onPairingConsumed = { pendingPairingCode.value = null },
                )
            }
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handlePairingIntent(intent)
    }

    private fun handlePairingIntent(intent: android.content.Intent?) {
        val uri = intent?.data ?: return
        val code = uri.getQueryParameter("code") ?: uri.toString()
        if (code.isNotBlank()) {
            pendingPairingCode.value = code
        }
    }

    @Suppress("DEPRECATION")
    override fun startActivityForResult(intent: android.content.Intent, requestCode: Int, options: Bundle?) {
        try {
            super.startActivityForResult(intent, requestCode, options)
        } catch (e: IllegalArgumentException) {
            if (e.message?.contains("lower 16 bits") == true) {
                val superMethod = androidx.activity.ComponentActivity::class.java.getMethod(
                    "startActivityForResult",
                    android.content.Intent::class.java,
                    Int::class.javaPrimitiveType,
                    Bundle::class.java,
                )
                superMethod.invoke(this, intent, requestCode, options)
            } else {
                throw e
            }
        }
    }
}

/**
 * A destination in the bottom bar.
 *
 * Two names, because the bar has room for one word and a screen reader has room for a sentence.
 * [short] is what fits under an icon; [label] is the full name, and it is what the icon is
 * described as, so shortening the visible one costs nothing.
 *
 * There is one bar, and it is short. It used to carry seven tabs, which turned the one surface
 * people navigate by into the most crowded thing on screen. Usage, Health and Devices are one tap
 * away in Settings instead, which is also where syncing lives.
 *
 * There is no Apps tab. Apps and sites belong to a profile, so a tab for them had to open by
 * asking which profile was meant — usually the one the user had just been editing. It is a row
 * inside the profile now, where the answer to that question is already known.
 */
private enum class Tab(
    val route: String,
    val label: String,
    val short: String,
    val icon: ImageVector,
) {
    Now("now", "Now", "Now", Icons.Filled.CheckCircle),
    Schedule("schedule", "Plan", "Plan", Icons.Filled.Edit),
    Calendar("calendar", "Calendar", "Events", Icons.Filled.DateRange),
    Settings("settings", "Settings", "Settings", Icons.Filled.Settings),
}

/** Routes Settings links to, so every screen that exists is reachable from the bar of four. */
object Routes {
    const val CALENDAR = "calendar"
    const val USAGE = "usage"
    const val DEVICES = "devices"
    const val HEALTH = "health"

    /**
     * Which apps Curfew may not read.
     *
     * Reached from the accessibility section rather than from a list of features, because what it
     * governs is the accessibility privilege and not a preference — and because the one thing a user
     * needs it for is the bank app the shipped list does not know about.
     */
    const val SENSITIVE_APPS = "sensitive-apps"
    const val PROFILE_NEW = "profile/new"
    const val TIMER = "timer"

    /** Editing an existing profile. The id travels in the route and never onto the screen. */
    fun profile(id: String) = "profile/$id"
}

@Composable
fun CurfewApp(
    model: CurfewViewModel = viewModel(),
    pendingPairingCode: String? = null,
    onPairingConsumed: () -> Unit = {},
) {
    // A second between ticks while someone is watching a countdown, five while nobody is.
    androidx.lifecycle.compose.LifecycleResumeEffect(Unit) {
        model.onForeground(true)
        onPauseOrDispose { model.onForeground(false) }
    }
    val navController = rememberNavController()
    val backStack by navController.currentBackStackEntryAsState()
    // Read once here rather than inside each branch, so the banner below reacts to a message raised
    // on any screen without the nav graph having to re-read the whole state flow for it.
    val state by model.state.collectAsStateWithLifecycle()

    fun go(route: String) {
        navController.navigate(route) {
            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }

    androidx.compose.runtime.LaunchedEffect(pendingPairingCode) {
        pendingPairingCode?.let { code ->
            go(Routes.DEVICES)
            model.answerPairing(code)
            onPairingConsumed()
        }
    }

    // No Scaffold: the bar is drawn over the bottom of the content rather than in a slot below it.
    // Screens end their content with Dsn.BottomRoom, which is the space this leaves them.
    Box(modifier = Modifier.fillMaxSize().background(Palette.ground)) {
        NavHost(
            navController = navController,
            startDestination = Tab.Now.route,
            // The window is edge to edge so the bar can float over content; the pages themselves
            // still start below the status bar.
            modifier = Modifier.windowInsetsPadding(WindowInsets.statusBars),
        ) {
            composable(Tab.Now.route) { NowScreen(model, onStartTimer = { go(Routes.TIMER) }) }
            composable(Tab.Schedule.route) {
                ScheduleScreen(
                    model,
                    onNewProfile = { go(Routes.PROFILE_NEW) },
                    onEditProfile = { go(Routes.profile(it)) },
                    onPickFromCalendar = { go(Tab.Calendar.route) },
                )
            }
            composable(Tab.Settings.route) { SettingsScreen(model, onOpen = ::go) }
            composable(Tab.Calendar.route) { CalendarScreen(model) }
            composable(Routes.USAGE) { UsageScreen(model) }
            composable(Routes.DEVICES) { DevicesScreen(model) }
            composable(Routes.HEALTH) { HealthScreen(model) }
            composable(Routes.SENSITIVE_APPS) { SensitiveAppsScreen(model) }
            composable(Routes.TIMER) { TimerScreen(model, onDone = { navController.popBackStack() }) }
            composable(Routes.PROFILE_NEW) {
                ProfileEditScreen(model, id = null, onDone = { navController.popBackStack() })
            }
            composable("profile/{id}") { entry ->
                ProfileEditScreen(
                    model,
                    id = entry.arguments?.getString("id"),
                    onDone = { navController.popBackStack() },
                )
            }
        }

        val current = backStack?.destination
        // Only the four tabs carry the bar. A pushed screen — the timer, a profile, usage — has a
        // back arrow and one job, and a tab bar floating over it both invites a user to abandon
        // what they were half way through and, being glass with nothing reserved beneath it,
        // covers the bottom of the screen it is sitting on. The timer's own lock choices were
        // underneath it.
        val onATab = Tab.entries.any { tab ->
            current?.hierarchy?.any { it.route == tab.route } == true
        }
        if (onATab) {
            NavBar(
                isSelected = { tab -> current?.hierarchy?.any { it.route == tab.route } == true },
                onPick = { go(it) },
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }

        // One place for the app to say something, whatever screen raised it.
        //
        // This is here because it was nowhere. `UiState.message` was set from eight places — a failed
        // app toggle, an import that would not parse, a device that could not be revoked, a profile
        // that could not be deleted — and rendered on two: Now and the profile editor. So a user
        // tapped a switch on the app picker, watched nothing happen, and was told why on a screen
        // they were not looking at, sometimes several minutes later when the modal finally surfaced
        // on Now. The message surface belonged to whoever raised it, which is the bug.
        state.message?.let { notice ->
            MessageBanner(
                notice = notice,
                onDismiss = model::dismissMessage,
                // Above the nav bar rather than under it: the bar is drawn over the content, so a
                // banner at the very bottom would be hidden by it.
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .padding(horizontal = Dsn.Gutter, vertical = if (onATab) 80.dp else 16.dp),
            )
        }
    }
}

/**
 * One sentence about something that just happened, where the user already is.
 *
 * Deliberately *not* a dialog. Principle 4 of `UX-FLOWS.md` is that the receipt is the changed thing
 * and dialogs are for decisions — and an acknowledgement is not a decision. A modal here was also
 * actively harmful: it was rendered by two screens, so a failure raised anywhere else either vanished
 * or interrupted whatever the user did next, minutes later.
 *
 * Colour follows [Notice.bad] rather than one alarming red, because this surface carries both "That
 * could not be saved" and "Paired." — showing a success as an error is its own kind of lie.
 *
 * A success goes by itself after a moment. A failure stays until it is tapped away: a reason that
 * disappears before it is read is worse than one that stays slightly too long, and it used to vanish
 * after two and a half seconds whatever it said.
 *
 * Drawn as a solid block of its colour with the reason under the headline, so it cannot be mistaken
 * for part of the page beneath it.
 */
@Composable
internal fun MessageBanner(notice: Notice, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    if (!notice.bad) {
        androidx.compose.runtime.LaunchedEffect(notice) {
            kotlinx.coroutines.delay(2500)
            onDismiss()
        }
    }
    val tone = if (notice.bad) Palette.bad else Palette.ok
    val said = listOfNotNull(notice.text, notice.detail).joinToString(". ")
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Dsn.CtlRadius))
            .background(tone)
            .clickable(onClick = onDismiss)
            .padding(horizontal = Space.l, vertical = Space.m)
            .semantics(mergeDescendants = true) {
                // One sentence, and how to get rid of it. Without this a screen reader reads the text
                // and gives no clue that the banner is tappable.
                contentDescription = "$said. Tap to dismiss."
            },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.m),
    ) {
        Text(
            if (notice.bad) "!" else "✓",
            style = Type.h3,
            fontWeight = FontWeight.Black,
            color = Palette.surface,
        )
        Column(Modifier.weight(1f)) {
            Text(notice.text, style = Type.body, fontWeight = FontWeight.Bold, color = Palette.surface)
            notice.detail?.let { reason ->
                Text(
                    reason,
                    style = Type.meta,
                    color = Palette.surface,
                    maxLines = 4,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
            }
        }
        if (notice.bad) Text("✕", style = Type.body, fontWeight = FontWeight.Bold, color = Palette.surface)
    }
}

/**
 * The one piece of furniture in the app, and the only thing always on screen.
 *
 * A full-width bar welded to the bottom edge: the surface colour, a single rule along its top, and
 * nothing else. It once floated as a plate with a hard shadow, and a floating slab is one more thing
 * on screen competing with the page; a bar that is simply where the page ends is not.
 *
 * Drawn by hand rather than taken from Material, because a Material bottom bar makes the app look
 * like every other app on the phone.
 */
@Composable
private fun NavBar(
    isSelected: (Tab) -> Boolean,
    onPick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val inset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(Palette.surface)
            // Swallows taps between tabs, so they never land on the list scrolled beneath the bar.
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) {},
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Tab.entries.forEach { tab -> NavTab(tab, isSelected(tab)) { onPick(tab.route) } }
        }
        Box(Modifier.height(inset))
    }
}

/**
 * One tab: an icon over an uppercase label, and an accent underline when it is the current one.
 *
 * Each tab takes an equal share of the width, so the whole bar is touchable rather than four islands,
 * and the label is [Palette.muted] rather than [Palette.dim] when unselected so it stays readable.
 * `minimumInteractiveComponentSize` keeps the touch target at least 48dp whatever is drawn.
 */
@Composable
private fun RowScope.NavTab(tab: Tab, selected: Boolean, onClick: () -> Unit) {
    val lit by animateFloatAsState(if (selected) 1f else 0f, label = "tab")
    val tint = lerp(Palette.muted, Palette.accent, lit)
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Space.xs),
        modifier = Modifier
            .weight(1f)
            .clickable(onClick = onClick)
            .minimumInteractiveComponentSize()
            .padding(top = Space.m, bottom = Space.s)
            .semantics(mergeDescendants = true) {
                contentDescription = if (selected) tab.label + ", selected" else tab.label
            },
    ) {
        Icon(tab.icon, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
        Text(
            tab.short.uppercase(),
            style = Type.micro,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
            color = tint,
        )
        Box(
            Modifier
                .width(24.dp)
                .height(3.dp)
                .background(Palette.accent.copy(alpha = lit)),
        )
    }
}
