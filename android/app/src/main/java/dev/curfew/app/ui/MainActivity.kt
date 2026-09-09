package dev.curfew.app.ui

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
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
 * [inSimple] is the whole of the Simple/Power split in the navigation. Four tabs answer the four
 * questions an ordinary user actually has — what is happening now, what should happen when, what
 * is blocked, and is this thing set up. The other three answer questions only someone who has
 * gone looking asks, and they stay one tap away in Settings rather than taking a seventh of a
 * phone-width bar from everyone.
 */
private enum class Tab(
    val route: String,
    val label: String,
    val short: String,
    val icon: ImageVector,
    val inSimple: Boolean,
) {
    Now("now", "Now", "Now", Icons.Filled.CheckCircle, inSimple = true),
    Schedule("schedule", "Plan", "Plan", Icons.Filled.Edit, inSimple = true),
    Apps("apps", "Blocked apps and sites", "Apps", Icons.Filled.Lock, inSimple = true),
    Settings("settings", "Settings", "Settings", Icons.Filled.Settings, inSimple = true),
    Calendar("calendar", "Calendar", "Events", Icons.Filled.DateRange, inSimple = false),
    Usage("usage", "Usage", "Usage", Icons.AutoMirrored.Filled.List, inSimple = false),
    Devices("devices", "Devices", "Sync", Icons.Filled.Share, inSimple = false),
    Health("health", "Health", "Health", Icons.Filled.CheckCircle, inSimple = false),
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
    val navController = rememberNavController()
    val backStack by navController.currentBackStackEntryAsState()
    val mode by model.mode.collectAsStateWithLifecycle()

    // Power adds tabs; it never takes one away, so a tab a user learned the position of stays
    // where it was when they switch.
    val tabs = Tab.entries.filter { it.inSimple || mode.isPower }

    fun go(route: String) {
        navController.navigate(route) {
            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }

    Scaffold(
        bottomBar = {
            NavigationBar {
                val current = backStack?.destination
                tabs.forEach { tab ->
                    NavigationBarItem(
                        selected = current?.hierarchy?.any { it.route == tab.route } == true,
                        onClick = { go(tab.route) },
                        // Named here rather than left to the label: an unselected tab shows no
                        // label at all in Power, so the icon is the only thing a screen reader
                        // could read.
                        icon = { Icon(tab.icon, contentDescription = tab.label) },
                        // Four labels fit across a phone and seven do not, so Simple spells its
                        // tabs out and Power names only the selected one. Power is where the
                        // icons have been learned; Simple is where they have not.
                        alwaysShowLabel = !mode.isPower,
                        label = {
                            Text(
                                tab.short,
                                maxLines = 1,
                                softWrap = false,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                    )
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = Tab.Now.route,
            modifier = Modifier.padding(padding),
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
            composable(Tab.Usage.route) { UsageScreen(model) }
            composable(Tab.Devices.route) { DevicesScreen(model) }
            composable(Tab.Health.route) { HealthScreen(model) }
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
    }
}
