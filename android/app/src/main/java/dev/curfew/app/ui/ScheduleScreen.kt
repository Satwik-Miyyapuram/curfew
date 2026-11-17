package dev.curfew.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.curfew.app.R
import dev.curfew.policy.CalendarEvent
import dev.curfew.policy.CalendarSchedule

/**
 * The plan: one card per profile. Tap a card to edit that profile; "+" creates one.
 */
@Composable
fun ScheduleScreen(
    model: CurfewViewModel,
    onNewProfile: () -> Unit = {},
    onEditProfile: (String) -> Unit = {},
) {
    val state by model.state.collectAsStateWithLifecycle()

    val running = state.weekly.count { it.enabled } + state.calendarRules.count { it.enabled }

    Screen(spacing = 0.dp) {
        Row(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.weight(1f).padding(end = Space.m)) {
                Title("Plan")
                Sub(
                    summarise(
                        profiles = state.profiles.size,
                        triggers = running,
                        loading = state.loading,
                    ),
                )
            }
            Box(
                modifier = Modifier
                    .padding(top = Space.xs)
                    .size(42.dp)
                    .clip(RoundedCornerShape(Dsn.CtlRadius))
                    .background(Palette.accent)
                    .clickable(onClickLabel = "Add a profile", onClick = onNewProfile),
                contentAlignment = Alignment.Center,
            ) {
                Text("+", style = Type.h2, fontWeight = FontWeight.Bold, color = Palette.onFill)
            }
        }

        Gap(Space.xl)

        // -----------------------------------------------------------------------------------------
        // Week at a glance — `docs/design.md` section 5.3.
        //
        // Above the rule list, because it is the one thing on this screen that answers "what will be
        // blocked next" without being read: seven days across, twenty-four hours down, and every
        // window in the plan drawn where it falls.
        //
        // **Disabled rules are filtered out first** (section 7 rule 12). A chart that drew a paused
        // window would be a chart claiming something will happen that the core will not do.
        //
        // The bands are folded by [foldWeekWindows] rather than drawn one per rule: sync stores the
        // same weekly window once per paired device, so a user with two phones has each of their
        // rules in the file twice, and drawing them literally put two identical bands on top of each
        // other. Folding is a *view* decision — the rules stay two rules and stay independently
        // editable on its own profile.
        // -----------------------------------------------------------------------------------------
        val names = state.profiles.associate { it.id to it.name }
        val enabled = state.weekly.filter { it.enabled }
        val chartWindows = foldWeekWindows(
            windows = enabled.map {
                Triple(it.days, it.startMinute, it.endMinute)
            },
            profileOf = { index -> names[enabled[index].profile].orEmpty() },
        )
        // Which calendar events Curfew would block, and by whose rule.
        //
        // This is the half of the chart the legend has always promised ("From your calendar") and
        // that nothing ever drew — the week showed weekly windows only, so a plan whose blocking is
        // mostly meeting-driven looked empty. Each enabled calendar rule is matched against the
        // events Curfew can currently see, using the same `matches` the calendar rules use, so the
        // chart and the cards cannot disagree about what a rule catches.
        val calendarBlocks = state.calendarRules
            .filter { it.enabled }
            .flatMap { rule ->
                val owner = names[rule.profile].orEmpty()
                state.calendarEvents.filter { matches(rule, it) }.mapNotNull { event ->
                    calendarBlockAt(
                        startEpochSeconds = event.start,
                        endEpochSeconds = event.end,
                        padBeforeSeconds = rule.padBeforeSeconds,
                        padAfterSeconds = rule.padAfterSeconds,
                        profileName = owner,
                    )
                }
            }
        SectionLabel("Week at a glance")
        Gap(Space.m)
        DCard(modifier = Modifier.landIn(from = 12f)) {
            WeekAtAGlance(windows = chartWindows, calendarBlocks = calendarBlocks)
        }

        Gap(Space.xl)

        if (state.profiles.isEmpty()) {
            DCard {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(vertical = Space.m),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text("No profiles yet", style = Type.h3, color = Palette.text)
                    Gap(Space.s)
                    Text(
                        "A profile is a list of apps and sites to block.",
                        style = Type.meta,
                        color = Palette.muted,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        } else {
            state.profiles.forEachIndexed { index, profile ->
                val tint = Palette.profileColours[index % Palette.profileColours.size]
                val windows = state.weekly.filter { it.profile == profile.id }
                val calendar = state.calendarRules.filter { it.profile == profile.id }
                val live = windows.any { it.enabled } || calendar.any { it.enabled }
                val editLabel = stringResource(R.string.edit_profile_named, profile.name)

                // Cards land in a fast cascade, staggered by their position. The stagger is what
                // makes a plan read as *assembled* rather than *switched on*, and it is capped at
                // eight places inside `landIn` so a long plan never makes a reader wait.
                DCardFlush(modifier = Modifier.landIn(index = index)) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(onClickLabel = editLabel) { onEditProfile(profile.id) }
                            .padding(Dsn.CardPad),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Space.m),
                    ) {
                        Glyph(tint, size = 42.dp) {
                            Text(
                                profile.name.take(1).uppercase(),
                                style = Type.h3,
                                color = Palette.onFill,
                            )
                        }
                        Column(Modifier.weight(1f)) {
                            Text(
                                profile.name,
                                style = Type.h3,
                                color = Palette.text,
                            )
                            Text(
                                model.describeBlocks(profile.id),
                                style = Type.meta,
                                color = Palette.muted,
                                modifier = Modifier.padding(top = Space.xxs),
                            )
                        }

                        // Pauses every trigger in this profile at once.
                        if (windows.isNotEmpty() || calendar.isNotEmpty()) {
                            Switch(live) { on ->
                                windows.forEach { model.saveWeekly(it.copy(enabled = on)) }
                                calendar.forEach { model.saveCalendarRule(it.copy(enabled = on)) }
                            }
                        }

                        Text("›", style = Type.h3, color = Palette.dim)
                    }
                }
                Gap(Space.l)
            }
        }
    }
}

/**
 * Whether a rule would catch an event, for listing purposes only.
 *
 * Deliberately the loose half of the core's matcher \u2014 title, calendar and busy \u2014 because this
 * is a label under a row, not the decision to block: the core does that, on the same data, and
 * anything shown here that it would not catch is a wrong caption rather than a wrong block.
 */
private fun matches(rule: CalendarSchedule, event: CalendarEvent): Boolean {
    val m = rule.matcher
    val title = m.title
    if (!title.isNullOrBlank() && !event.title.contains(title.trim('*'), ignoreCase = true)) {
        return false
    }
    val calendar = m.calendar
    if (!calendar.isNullOrBlank() && !event.calendar.equals(calendar, ignoreCase = true)) {
        return false
    }
    if (m.busyOnly && !event.busy) return false
    return true
}

/**
 * The line under the title, counted the way the page is now laid out.
 *
 * It used to count blocks, which was the flat list talking: the page showed one card per rule, so
 * "four blocks" matched what was on screen. The page shows profiles now, each with its reasons
 * nested inside, so the honest summary is both numbers \u2014 how many profiles, and how many
 * things can start one.
 */
private fun summarise(profiles: Int, triggers: Int, loading: Boolean): String {
    if (loading) return "Reading your plan\u2026"
    if (profiles == 0) return "Nothing set up yet."
    return "${count(profiles, "profile")} · ${count(triggers, "trigger")}"
}

