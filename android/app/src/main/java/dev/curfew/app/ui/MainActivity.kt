package dev.curfew.app.ui

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.automirrored.filled.List
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
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import dev.curfew.app.enforce.EnforcementService

/**
 * The app's own UI.
 *
 * Four places, because there are exactly four questions a user has: what is running now, what
 * should run when, where is my time going, and is Curfew actually working. Anything that does not
 * answer one of those does not get a tab.
 */
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
 * [short] is what fits under an icon on a phone showing seven of them — anything longer came back
 * as "Sche…", "Cale…", "Devic…", a row of words with their ends cut off, which is worse than
 * icons alone. [label] is the full name, and it is what the icon is described as, so shortening
 * the visible one costs nothing.
 */
private enum class Tab(
    val route: String,
    val label: String,
    val short: String,
    val icon: ImageVector,
) {
    Now("now", "Now", "Now", Icons.Filled.CheckCircle),
    Schedule("schedule", "Schedule", "Rules", Icons.Filled.Edit),
    Calendar("calendar", "Calendar", "Events", Icons.Filled.DateRange),
    Apps("apps", "Apps", "Apps", Icons.Filled.Lock),
    Usage("usage", "Usage", "Usage", Icons.AutoMirrored.Filled.List),
    Devices("devices", "Devices", "Sync", Icons.Filled.Share),
    Health("health", "Health", "Health", Icons.Filled.Settings),
}

@Composable
fun CurfewApp(model: CurfewViewModel = viewModel()) {
    val navController = rememberNavController()
    val backStack by navController.currentBackStackEntryAsState()

    Scaffold(
        bottomBar = {
            NavigationBar {
                val current = backStack?.destination
                Tab.entries.forEach { tab ->
                    NavigationBarItem(
                        selected = current?.hierarchy?.any { it.route == tab.route } == true,
                        onClick = {
                            navController.navigate(tab.route) {
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        // Named here rather than left to the label: an unselected tab shows no
                        // label at all, so the icon is the only thing a screen reader could read.
                        icon = { Icon(tab.icon, contentDescription = tab.label) },
                        // Only the selected tab is named. Seven labels do not fit across a phone:
                        // left to wrap they broke as "Schedul/e", and forced onto one line they
                        // came out as "Sche…", "Calen…", "Devic…" — a row of words with their
                        // ends cut off, which is worse than icons alone. The selected one has the
                        // room to be spelled out, and the icons stay described for screen readers.
                        alwaysShowLabel = false,
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
            composable(Tab.Now.route) { NowScreen(model) }
            composable(Tab.Schedule.route) { ScheduleScreen(model) }
            composable(Tab.Calendar.route) { CalendarScreen(model) }
            composable(Tab.Apps.route) { AppPickerScreen(model) }
            composable(Tab.Usage.route) { UsageScreen(model) }
            composable(Tab.Devices.route) { DevicesScreen(model) }
            composable(Tab.Health.route) { HealthScreen(model) }
        }
    }
}
