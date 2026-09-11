package dev.curfew.app.ui

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Opening the app is also the moment to make sure enforcement is up: a user who force-stops
        // Curfew and then opens it has re-armed it by doing so.
        EnforcementService.start(applicationContext)

        setContent {
            CurfewTheme {
                CurfewApp()
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

/** Routes Settings links to, so a Simple user can still reach every screen that exists. */
object Routes {
    const val CALENDAR = "calendar"
    const val USAGE = "usage"
    const val DEVICES = "devices"
    const val HEALTH = "health"
    const val PROFILE_NEW = "profile/new"
    const val TIMER = "timer"

    /** Editing an existing profile. The id travels in the route and never onto the screen. */
    fun profile(id: String) = "profile/$id"
}

@Composable
fun CurfewApp(model: CurfewViewModel = viewModel()) {
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

    // No Scaffold: the bar floats over the content rather than sitting in a slot below it, so a
    // list scrolls *under* glass instead of stopping dead at an opaque edge. Screens already end
    // their content with Dsn.BottomRoom, which is the space this leaves them.
    Box(modifier = Modifier.fillMaxSize().background(Palette.Ink)) {
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
            GlassBar(
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
                // Above the nav bar rather than under it: the bar floats over the content, so a
                // banner at the very bottom would be behind glass.
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .padding(horizontal = Dsn.Gutter, vertical = if (onATab) 128.dp else 16.dp),
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
 * Tap to dismiss, and it does not time out on its own. A sentence that disappears before it is read
 * is worse than one that stays slightly too long, and nothing here is a decision the user can miss by
 * being slow.
 */
@Composable
private fun MessageBanner(notice: Notice, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    val tone = if (notice.bad) Palette.Bad else Palette.Ok
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Dsn.CtlRadius))
            .background(tone.copy(alpha = 0.16f))
            .border(1.dp, tone.copy(alpha = 0.45f), RoundedCornerShape(Dsn.CtlRadius))
            .clickable(onClick = onDismiss)
            .padding(horizontal = 14.dp, vertical = 12.dp)
            .semantics(mergeDescendants = true) {
                // One sentence, and how to get rid of it. Without this a screen reader reads the text
                // and gives no clue that the banner is tappable.
                contentDescription = "${notice.text}. Tap to dismiss."
            },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            if (notice.bad) "!" else "✓",
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
            color = tone,
        )
        Text(notice.text, fontSize = 13.sp, lineHeight = 19.sp, color = Palette.Text)
    }
}

/**
 * The one piece of furniture in the app, and the only thing always on screen.
 *
 * It is a floating slab rather than a bar welded to the bottom edge: lifted off the edge, rounded
 * on every corner, translucent enough that content moving underneath shows through it, with a
 * hairline top edge catching a little light and a shadow underneath doing the rest. That pair —
 * lit edge, shaded belly — is the whole trick: it reads as a pane of etched glass laid over the
 * page, which is the depth the app was missing when it was flat cards on a flat ground.
 *
 * Drawn by hand rather than taken from Material, because a Material bottom bar is opaque by
 * construction and makes the app look like every other app on the phone.
 */
@Composable
private fun GlassBar(
    isSelected: (Tab) -> Boolean,
    onPick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val inset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 14.dp, end = 14.dp, bottom = inset + 10.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                // The app's one elevated surface, defined in `Design.kt` with the block screen and the
                // sheets. The two layers — a translucent ground so the page shows through, a top-down
                // sheen so the slab has a lit edge rather than one flat tone — were written out here
                // and again on the block screen, and had already drifted apart.
                .glass(RoundedCornerShape(26.dp), elevation = 22.dp)
                .padding(horizontal = 6.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Tab.entries.forEach { tab -> GlassTab(tab, isSelected(tab)) { onPick(tab.route) } }
        }
    }
}

/**
 * One tab. The icon lights up rather than a pill sliding in behind it.
 *
 * The hit area is pinned to [MIN_TOUCH] and the press feedback is back. Both were wrong, in opposite
 * directions from what the review assumed:
 *
 * * It was **not** too small. The clickable spans the icon box plus padding plus the label, so it
 *   measured roughly 50×55dp — comfortably over Material's 48dp, and the review's "roughly 38dp",
 *   which counted the icon and the padding but not the label or the gap, was a miscount. What was
 *   wrong is that the size was an *accident* of the label's line height: at 10sp it clears 48dp, and
 *   a smaller label style or a shorter one would quietly take the most-touched control in the app
 *   below the floor. It is now a stated minimum rather than a coincidence.
 * * It had **no press feedback at all**. `indication = null` was the only such suppression in the
 *   app, and it was there to stop Material's sliding pill, which is not what a ripple is. Every other
 *   clickable on this screen — `Switch`, `GhostButton`, the row bodies — shows a ripple. The nav, the
 *   control every user touches every session, showed nothing, so a tap that registered and a tap that
 *   missed felt identical.
 *
 * `minimumInteractiveComponentSize` is the enforcement rather than a comment: it is Material's own
 * guarantee that the *touch* target is at least 48dp while the visual size stays whatever was drawn.
 */
@Composable
private fun GlassTab(tab: Tab, selected: Boolean, onClick: () -> Unit) {
    val lit by animateFloatAsState(if (selected) 1f else 0f, label = "tab")
    val tint = lerp(Palette.Dim, Palette.Accent, lit)
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp),
        modifier = Modifier
            .clip(RoundedCornerShape(18.dp))
            .clickable(onClick = onClick)
            .minimumInteractiveComponentSize()
            .heightIn(min = Dsn.MinTouch)
            .widthIn(min = Dsn.MinTouch)
            .padding(horizontal = 10.dp, vertical = 4.dp)
            .semantics(mergeDescendants = true) {
                contentDescription = if (selected) tab.label + ", selected" else tab.label
            },
    ) {
        Box(
            modifier = Modifier
                .size(30.dp)
                .clip(RoundedCornerShape(11.dp))
                .background(Palette.Accent.copy(alpha = 0.14f * lit)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(tab.icon, contentDescription = null, tint = tint, modifier = Modifier.size(19.dp))
        }
        Text(
            tab.short,
            fontSize = 10.sp,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = tint,
        )
    }
}
