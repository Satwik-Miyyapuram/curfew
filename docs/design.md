# Curfew Redesign Design Specification

Version: 1.0
Status: implementation source of truth
Product: Curfew, a private self-control blocker for Windows and Android

## 1. Product Direction

Curfew is a self-control tool, not parental-control software. The product must help a person answer three questions immediately:

1. What is blocked right now?
2. What will be blocked next?
3. How do I start or change a block without reading documentation?

The redesign should feel direct, physical and memorable. The neo-brutalist visual language gives Curfew a strong identity while keeping the circular time setter as the product's distinctive interaction.

The product must be:

- Obvious before it is powerful.
- Honest about what is and is not enforced.
- Fast enough to start a first block in under 60 seconds.
- Identical in vocabulary and behavior across desktop and mobile.
- Safe to experiment with because destructive actions have undo or a clear recovery path.
- Fully usable without dragging, a mouse or a high-resolution display.

## 2. Non-Negotiable Product Invariants

Do not design or implement a feature that violates these rules:

1. No account, server, telemetry or remote monitoring role.
2. A running block is a promise. Editing a plan cannot shorten or weaken a block that is already running.
3. Every block has a visible end condition.
4. Every block has a visible last-resort delayed release.
5. Curfew never draws or stores its own password field for unlocking. Use the operating system credential prompt.
6. Revoking a permission weakens enforcement visibly. It must never appear as if protection is still fully live.
7. The plan file is the source of truth. Anything done in the interface must be representable in the plan file.
8. A paired device can contribute to a block, but disconnecting or revoking it cannot shorten a block on another device.
9. Curfew is for the device owner. No parent mode, hidden mode, employee-monitoring mode or covert mode.

## 3. Visual Identity

### 3.1 Overall Look

Use neo-brutalism with a restrained product interface rather than a decorative collage.

Required visual traits:

- Square corners by default.
- Thick 3px black or ink-colored borders.
- No shadows. Surfaces are told apart by border and fill alone.
- High-contrast flat color blocks.
- Heavy, uppercase headings and labels.
- Mono text for times, technical values and labels.
- A warm paper background with a visible grid texture.
- Small number of accent colors used consistently.
- No gradients, glass effects, soft shadows, floating pills or glossy surfaces.

The dial is the exception to the square language. It remains circular and tactile so it reads as a deliberate instrument inside the brutalist layout.

### 3.2 Color Tokens

Use semantic tokens. Never hardcode colors inside screen components.

Light theme defaults:

| Token | Value | Meaning |
|---|---|---|
| `bg` | `#FAFAFA` | page background |
| `panel` | `#FFFFFF` | main surface |
| `raise` | `#F4F4F5` | secondary surface |
| `raise2` | `#E4E4E7` | selected or raised surface |
| `line` | `#18181B` | borders |
| `ink` | `#09090B` | primary text |
| `mut` | `#52525B` | supporting text |
| `dim` | `#71717A` | hints and low priority metadata |
| `brand` | `#4F46E5` | the one accent: primary action and navigation, white text on it |
| `run` | `#FF6A2B` | active/running block |
| `ok` | `#12874A` | live or successful |
| `warn` | `#E0A100` | degraded or needs attention |
| `bad` | `#E02B45` | refused, dangerous or destructive |
| `cal` | `#0B8FA8` | calendar-derived state |

Dark theme should preserve the same semantic relationships. Use pure black and untinted greys (`#000000` bg, `#0A0A0A` panel, `#161616` raised), `#333333` borders, `#FAFAFA` ink, `#A3A3A3` muted and `#818CF8` brand. Do not make dark mode a separate visual language.

### 3.3 Typography

Use a bold grotesk/sans for interface text and a mono font for system-like values.

Recommended tokens:

| Token | Size | Use |
|---|---:|---|
| `micro` | 11px | labels, helper text, status metadata |
| `meta` | 12.5px | supporting copy and rows |
| `body` | 14.5px | default readable text |
| `h3` | 16px | card heading |
| `h2` | 20px | section heading |
| `h1` | 26px | screen heading |
| `display` | 36px | onboarding or key statement |
| `mega` | 46px | large dial value |

Two derived tokens: `label` (micro, black, wide tracking, uppercase: section labels and eyebrows) and `button` (body, black, slight tracking: the words on a button or segment). Any token can be set in the mono face for times, durations, codes and counts.

These are the only sizes. A call site may choose the weight (regular, bold or black) and the colour, and nothing else; a size, line height or tracking outside this table is a bug. Android keeps them in `ui/Type.kt`.

Minimum body text must remain readable at user-configured scale. Never use color as the only difference between states.

### 3.3a Spacing

Every padding and gap is one step of a 4px scale:

| Token | Size | Use |
|---|---:|---|
| `xxs` | 2px | a nudge inside a control |
| `xs` | 4px | a label and its value |
| `s` | 8px | a row and the next |
| `m` | 12px | a field and the next group |
| `l` | 16px | card padding and the page gutter |
| `xl` | 24px | a section and the next |
| `xxl` | 32px | a major break |

Component sizes (button heights, touch targets, borders) are not spacing and keep their own named values. Android keeps the scale in `Space` in `ui/Design.kt`.

### 3.4 Borders, Shadows and Radius

- Card border: 3px.
- Control border: 3px.
- No shadows anywhere, hero, modal or control.
- Pressed state: scale down slightly; nothing moves.
- Default radius: 0.
- Circular controls: true circles only.

### 3.5 Interaction States

Every interactive control must have:

- Default.
- Hover.
- Focus-visible.
- Active/pressed.
- Disabled.
- Loading where an operation can take time.
- Error where an operation can fail.

Focus-visible uses a 3px brand outline with a visible offset.

## 4. Responsive Product Modes

The product is two complete mocks over the same plan state, switched by a persistent `Phone / Desktop` control.

### 4.1 Desktop Mock

Desktop is a wide application window:

- Left sidebar with grouped navigation.
- Top status bar.
- Command palette shortcut using Cmd/Ctrl+K.
- Multi-column layouts where useful.
- Sheets centered in the window.
- Dense plan and device views.

Desktop navigation groups:

**Today**

- Now
- Start a block

**The plan**

- Plan
- Profiles
- Calendar
- Plan file

**Fleet**

- Devices
- Insights

**Trust**

- Is it working
- Settings
- Review and mock

### 4.2 Mobile Mock

Mobile is a phone-like single-column application:

- Device status strip.
- Compact brand header with current screen name and live protection status.
- One-column content.
- Five-item bottom navigation:
  - Now
  - Start
  - Plan
  - Insights
  - Is it working
- An `Every screen` action opens the remaining routes in a sheet.
- Sheets rise from the bottom, span the viewport width and leave the bottom navigation visible behind the scrim only when appropriate.
- Toasts appear in a reserved footer area above the bottom navigation. They must never cover the bottom navigation or scroll with content.

### 4.3 Mode Toggle

The toggle must be available:

- Desktop top bar.
- Mobile mock header.
- Settings screen.

The toggle changes layout only. It does not change the plan, profiles, rules, sessions, devices or audit data.

## 5. Navigation and Screen Specifications

### 5.1 Now

Purpose: show what is true right now.

Top content:

- `Now` heading.
- Current running block or a strong empty state: `Nothing is blocked right now`.
- Primary action: `Start a block`.

When a block is active, show:

- Profile name.
- Rule or origin that started it.
- Countdown ring.
- Absolute end time.
- Strength: `Just ask me`, `Make me wait`, or `Lock it`.
- Contributing rules if blocks merged from multiple devices.
- Origin device for each contribution.
- Visible last-resort release line from the first second.
- `End this block early` action.
- `Which rule is this?` action.

Also show:

- Future preview scrubber.
- Next scheduled rules.
- Allowances with remaining time.
- Activity feed.
- Timeline.
- Android-only coverage notice when the mobile mock is active.

### 5.2 Start a Block

Use a three-step order:

1. Strength.
2. Time.
3. Targets/profile.

This order prevents a person from setting a long duration before understanding how hard it will be to leave.

Strength choices:

- Just ask me: ends immediately.
- Make me wait: five-minute hold before exit.
- Lock it: operating system credential required to end early.

Every choice must state its consequence in one sentence.

### 5.3 Plan

The Plan screen is the most important planning screen. It must not be a vague list of rules.

Required sections in order:

1. Page header with `Pause plan` and `Add a rule`.
2. Week at a glance.
3. Weekly windows.
4. Calendar rules.
5. Other starters.
6. Preset gallery.
7. Pause-all control.

Week at a glance:

- Seven columns: Mon through Sun.
- Time labels: 00:00, 06:00, 12:00, 18:00, 24:00.
- Day and calendar bands must be clipped to their day column.
- Overnight rules render in two pieces: late-night segment and faded next-morning segment.
- Each band has a tooltip/title with the rule name and time.
- No band can visually overflow its column or cover controls below the chart.

Weekly rule row:

- Icon/type marker.
- Rule name.
- Strength.
- Profile.
- Origin/device scope.
- Human sentence, for example: `Every day · 22:45 -> 06:30, into next morning · 7h 45m`.
- Enable/pause toggle.
- Edit.
- Remove with undo.

Calendar rule row:

- Match count.
- Never-fired state.
- Matcher text.
- Padding before/after.
- Last fired.
- Matching event examples.
- Near-miss suggestions when count is zero.

### 5.4 Rule Editor

Rule editor is a modal sheet with a real focus trap and explicit Save/Cancel actions.

Fields:

- Rule name.
- Rule type: weekly window, calendar match, network trigger.
- Profile.
- Strength.

For weekly windows:

- Day selector with Weekdays, Weekends and All shortcuts.
- Time input mode selector:
  - Exact fields.
  - Circular dial.
  - Week grid.
- Summary sentence.
- `Spans past midnight` explicit checkbox.

For calendar rules:

- Match title/location.
- Search text.
- Lead-in minutes.
- Lead-out minutes.
- Live test against loaded calendar events.

For network rules:

- SSID field.
- Explanation of when it starts and ends.

### 5.5 Profiles

Targets belong to profiles. Do not create a competing global Apps page.

Profile screen layout:

- Profile rail on desktop, selector/sheet on mobile.
- Profile name and explanation.
- Enabled/paused state.
- Target count.
- Rules using this profile.
- Target list.
- Inline action per target: block, allowance, wait, launch limit.
- Target library rail with search and category filters.
- `Used by N profiles` metadata.
- Never-read apps disclosure.

### 5.6 Calendar

Show:

- Today, Tomorrow and Later this week groups.
- Event title, time, calendar, attendees and busy/free.
- Matching rule on each event.
- `Block during this` action.
- Search/test panel for matcher text.
- Calendar sources and connection states.

### 5.7 Devices

Show:

- Every known device.
- This-device marker.
- Platform.
- Last seen.
- Plan revision.
- Per-device enforcement layers.
- Degraded/off state with explanation.
- Test link.
- Pair.
- Revoke with consequence explanation.

Pairing flow:

1. Select transport.
2. Show QR and copyable fallback code.
3. Compare six digits.
4. Show first-sync receipt.
5. Explain what travels, what does not and how revoke behaves.

### 5.8 Insights

Use local-only data:

- Focused hours.
- Blocked/refused attempts.
- Streak.
- Weekly bars.
- Hour heatmap.
- Target leaderboard.
- Weekly digest.
- Export CSV/JSON.

### 5.9 Is It Working

This is a trust screen, not a checkbox page.

Show:

- Live/degraded/off layers.
- Last heartbeat.
- Downtime incidents.
- Coverage boundaries.
- Access ledger: what Curfew reads and what it never reads.
- Restricted Settings guidance for Android sideloads.
- OEM background/autostart guidance.
- Proof panel: no telemetry, no analytics dependency, no outbound connection.
- Test enforcement action.

### 5.10 Plan File

Show:

- Exact enforced path.
- Applied revision.
- TOML/JSON document.
- Field-level diff.
- UI-to-file mapping.
- Import flow.
- Parse error with plain-language recovery.
- Conflict error with merge/discard options.
- Rollback.

### 5.11 Settings

Settings sections:

- Phone/Desktop mock switch.
- Appearance.
- Theme.
- Accent.
- Density.
- Text scale.
- High contrast.
- Reduced motion.
- Language and clock.
- Notifications.
- Release and safety.
- Freeze policy.
- Passes.
- Usage data and exports.
- Replay onboarding.

## 6. Circular Time Setter Specification

The circular time setter is the signature interaction. It must be beautiful, usable and precise.

### 6.1 Visual Structure

For durations over one hour:

- Outer ring represents hours from 0 to 12.
- Inner ring represents minutes from 0 to 59.
- Track is a thin 1px ink outline.
- Active value arc is 8px to 10px, using brand or running color.
- Hour and minute handles are circular, not square.
- Handle hit area is at least 44px even if the visible handle is smaller.
- Use minimal tick marks: 12 clear marks, not 60 noisy marks.
- Keep the center clean. Show value, label and end-time sentence.

For short values:

- Use one ring with a clear value arc.
- Keep the same circular plate and handle treatment.

For exact clock times:

- Use a clock face from 00:00 to 24:00.
- Show calendar events as thin cyan arcs.
- Show night/rest period as a muted track segment.
- Use a circular brand handle with a center dot.

### 6.2 Dial Content

Center content:

- Small mono uppercase label: `HOW LONG`, `STARTS AT`, `ENDS AT`, or `RESUME AT`.
- Large value: `45m`, `2h 30m`, `18:00`.
- Optional end-time or duration sentence below.

Below the dial:

- Native range sliders for hours and minutes.
- Preset buttons: 15m, 25m, 45m, 90m, 2h, 4h.
- Exact numeric input.
- One short instruction sentence.

### 6.3 Dial Interaction

Support all of these:

- Drag outer ring.
- Drag inner ring.
- Click ring location to set.
- Keyboard arrows.
- PageUp/PageDown.
- Home/End.
- Native range slider.
- Numeric input.

Do not require a person to drag.

### 6.4 Dial Rules

- Do not show a 12-hour value as an almost-empty 360-degree arc without an inner minute control.
- Do not use square handles on a circular control.
- Do not use thick tracks that obscure tick marks.
- Do not use more than two strong accent colors inside the same dial.
- Do not let helper text push the dial off-screen on mobile.
- Long durations must show the wall-clock end time before confirmation.
- Four hours or more must use a receipt-style confirmation.

## 7. Planning-Screen Bug Prevention

The following rules are mandatory:

1. Daily times use the range 0 to 1439. Never use 10080 modulo for a daily interval.
2. `spanOf` for a daily rule must return:
   - `end - start` for same-day rules.
   - `end - start + 1440` modulo 1440 for overnight rules.
3. An overnight rule must have an explicit `wrap` boolean or satisfy `end < start`.
4. All visual schedule bands must be clipped with `overflow: hidden` inside their day column.
5. A band height must be clamped to the available day height.
6. The week chart must never create a visual element taller than its day column.
7. Empty rules must not render a zero-height invisible element over buttons.
8. Week grid and exact fields must write to the same `start`, `end` and `wrap` values.
9. The Save action must show a readable summary before applying.
10. The rule editor must not call state setters during render. Use state initialization, effects or a key-based remount.
11. Updating a rule that affects a live block must be rejected or scheduled after the live block ends.
12. Disabled rules must not appear as active bands in Week at a glance.

## 8. Accessibility

Required:

- Native buttons, inputs, selects and checkboxes wherever possible.
- Dialog role, `aria-modal`, labelled heading, focus trap and focus restore.
- Status conveyed by word, shape and color, not color alone.
- Minimum 44px touch target for mobile controls.
- Minimum 3:1 non-text contrast for borders, handles and chart marks.
- Minimum 14px default body text.
- Reduced motion support.
- No timing-only interaction.
- Drag operations always have click, field or keyboard alternatives.
- Dial has meaningful `aria-valuetext`, not only raw numbers.
- Calendar match count is exposed as readable text.
- Screen readers should hear one throttled countdown announcement per minute, not one per second.

## 9. Content Rules

Use the vocabulary below in user-facing copy:

| Use | Meaning |
|---|---|
| plan | the saved configuration |
| rule | one schedule or trigger |
| profile | named group of targets |
| target | app, site, path, keyword or window |
| allowance | daily time amount |
| wait | delay before exit/opening |
| block | restriction currently running |
| strength | how hard it is to end |

Avoid exposing internal words such as CRDT, lattice, op-log, enforcement cadence, ration, freeze or policy unless the user is on the technical detail view.

## 10. Quality Bar

The implementation is not complete until:

- Desktop and mobile mocks are both navigable.
- Every route has populated, empty, loading, error and degraded states.
- The Planning screen supports day windows, overnight windows, calendar rules and network rules.
- The circular setter works with pointer, keyboard, slider and numeric input.
- The plan chart never overlaps content below it.
- A rule can be created, edited, paused, resumed, removed and undone.
- A rule with zero matches visibly says it has never fired.
- The exact enforced plan path is visible.
- `npm run build` passes.
