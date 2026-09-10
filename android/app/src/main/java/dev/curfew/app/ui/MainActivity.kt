package dev.curfew.app.ui

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
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
 * There is one bar, and it is the same in both modes. Power used to add three more tabs to it,
 * which turned the one surface people navigate by into the most crowded thing on screen — and a
 * mode that rearranges the furniture is a mode nobody dares turn on. Power now means more inside
 * a screen, not more screens along the bottom. Usage, Health and Devices are one tap away in
 * Settings for everyone, which is also where a Simple user can finally find syncing.
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
    Apps("apps", "Blocked apps and sites", "Apps", Icons.Filled.Lock),
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
                )
            }
            composable(Tab.Apps.route) { AppPickerScreen(model) }
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
        GlassBar(
            isSelected = { tab -> current?.hierarchy?.any { it.route == tab.route } == true },
            onPick = { go(it) },
            modifier = Modifier.align(Alignment.BottomCenter),
        )
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
                .shadow(22.dp, RoundedCornerShape(26.dp), clip = false)
                .clip(RoundedCornerShape(26.dp))
                // Two layers: a translucent ground so the page shows through, then a top-down
                // sheen so the slab has a lit edge rather than one flat tone.
                .background(Palette.Surface.copy(alpha = 0.86f))
                .background(
                    Brush.verticalGradient(
                        listOf(Color.White.copy(alpha = 0.06f), Color.Transparent),
                    ),
                )
                .border(1.dp, Color.White.copy(alpha = 0.09f), RoundedCornerShape(26.dp))
                .padding(horizontal = 6.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Tab.entries.forEach { tab -> GlassTab(tab, isSelected(tab)) { onPick(tab.route) } }
        }
    }
}

/** One tab. The icon lights up rather than a pill sliding in behind it. */
@Composable
private fun GlassTab(tab: Tab, selected: Boolean, onClick: () -> Unit) {
    val lit by animateFloatAsState(if (selected) 1f else 0f, label = "tab")
    val tint = lerp(Palette.Dim, Palette.Accent, lit)
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp),
        modifier = Modifier
            .clip(RoundedCornerShape(18.dp))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
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
