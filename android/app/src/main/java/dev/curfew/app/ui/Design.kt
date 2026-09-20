package dev.curfew.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import kotlin.math.roundToInt
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The measurements the design canvas actually specifies, as constants rather than as habits.
 *
 * Every number here was read off the artboards' stylesheet rather than chosen again in Kotlin,
 * because "roughly the same dark card" is how a design and its implementation drift apart over a
 * few weeks until neither is the truth. A 22dp card radius and a 14dp control radius are a
 * deliberate pair — containers are softer than the things inside them — and rounding either to a
 * tidier number quietly removes that distinction.
 */
object Dsn {
    /** The page gutter. Every screen's content starts and ends here. */
    val Gutter = 20.dp

    val CardRadius = 22.dp
    val CtlRadius = 14.dp

    /** Card padding, and the tighter variant list rows use. */
    val CardPad = 18.dp

    /** A primary button, and the shorter secondary one that sits under a card. */
    val ButtonHeight = 52.dp
    val GhostHeight = 46.dp

    /**
     * The smallest a *touch* target may be, whatever it looks like.
     *
     * Material's figure, and the one this app's own buttons already meet at 52 and 46dp — so the
     * number is the project's existing standard written down rather than a new rule. It is separate
     * from visual size on purpose: a 44×26dp toggle and a 36dp day circle are the right *look* and
     * the wrong *hit area*, and Material's `minimumInteractiveComponentSize` exists to hold both at
     * once.
     *
     * WCAG 2.2 SC 2.5.8 (Level AA) asks for 24×24 CSS px and the app already clears that everywhere;
     * 48dp is the stricter Material guidance, and the reason to prefer it here is that the controls
     * below it are the ones used most — a nav tab every session, a plan toggle several times a day.
     */
    val MinTouch = 48.dp

    /**
     * Room under the last element so a scrolled screen clears the navigation bar.
     *
     * The bar floats over the content rather than sitting beside it, so this has to cover the bar
     * itself, the margin it floats on and the system's own gesture inset. At 28dp it did not: the
     * last control on every screen sat behind the bar, and on the Timer screen that control was
     * "Lock it in for 25m" — the one button the whole screen exists to offer.
     */
    val BottomRoom = 116.dp
}

/**
 * The three numbers the app's one elevated surface is made of.
 *
 * They were written out **twice** — once in the nav bar and once on the block screen — and had already
 * drifted: the nav used `Surface` at 0.86 with a 0.06 sheen and a 0.09 edge, the block screen `Raised`
 * at 0.88 with 0.07 and 0.10. The block screen's own comment read *"The same pane of glass as the nav
 * bar"*, which was **false**, and false in the way this codebase keeps producing: a comment asserting a
 * consistency the code does not have.
 *
 * A one-hundredth of an alpha is not the point. The point is that two surfaces claiming to be one
 * material will keep diverging until somebody makes it one value, and the third caller — the sheet,
 * which is what this was extracted for — would have been a third opinion.
 */
object Glass {
    /** How much of the page shows through the ground. */
    const val Ground = 0.87f

    /** The lit top edge: white at this alpha fading to nothing, so the pane catches light. */
    const val Sheen = 0.065f

    /** The hairline around it, just brighter than the surface behind. */
    const val Edge = 0.095f
}

/**
 * The pane of glass: what the nav bar, the block screen and the sheets are all made of.
 *
 * `ground` stays a parameter because the three genuinely sit on different things — the nav floats over
 * a page, the block button over a full-bleed dark screen, a sheet over the page it rose from — and a
 * surface that was *exactly* the same colour in all three would read as a hole in one of them. The
 * **glass** is identical; the thing behind it differs.
 *
 * `elevation` is zero for a surface that is not floating. The nav bar casts a shadow because it hovers
 * over content that scrolls under it; a sheet is anchored to an edge and a shadow under it would fall
 * on nothing.
 */
fun Modifier.glass(
    shape: Shape,
    ground: Color = Palette.Surface,
    elevation: Dp = 0.dp,
): Modifier = this
    .then(
        if (elevation > 0.dp) {
            Modifier.shadow(elevation, shape, clip = false)
        } else {
            Modifier
        },
    )
    .clip(shape)
    .background(ground.copy(alpha = Glass.Ground))
    // The sheen is a second background rather than a colour stop in the first, because it has to sit
    // *over* the translucent ground: a gradient replacing the ground would make the pane opaque.
    .background(
        Brush.verticalGradient(
            listOf(Color.White.copy(alpha = Glass.Sheen), Color.Transparent),
        ),
    )
    .border(1.dp, Color.White.copy(alpha = Glass.Edge), shape)

/**
 * A screen: the gutter, the scroll, and the space at the bottom, in one place.
 *
 * Every screen in the app had its own copy of this triple, which is why some of them scrolled
 * their last button under the navigation bar and some did not.
 */
@Composable
fun Screen(
    modifier: Modifier = Modifier,
    spacing: androidx.compose.ui.unit.Dp = 0.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Dsn.Gutter),
        verticalArrangement = Arrangement.spacedBy(spacing),
    ) {
        Gap(14.dp)
        content()
        Gap(Dsn.BottomRoom)
    }
}

/** Vertical space, spelled the way the artboards spell it. */
@Composable
fun Gap(height: androidx.compose.ui.unit.Dp) {
    androidx.compose.foundation.layout.Spacer(Modifier.height(height))
}

/** The page title: 28sp, bold, tightened. One per screen. */
@Composable
fun Title(text: String, size: Int = 28) {
    Text(
        text,
        fontSize = size.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = (-0.6).sp,
        lineHeight = (size * 1.15f).sp,
        color = Palette.Text,
        modifier = Modifier.semantics { heading() },
    )
}

/** The sentence under a title. Always muted, always a sentence. */
@Composable
fun Sub(text: String) {
    Text(text, fontSize = 14.sp, lineHeight = 20.sp, color = Palette.Muted)
}

/**
 * A section label.
 *
 * Uppercase with wide tracking at 11sp — small enough to read as furniture rather than as content,
 * which is the whole job: it names the group under it without competing with it.
 */
@Composable
fun SectionLabel(text: String) {
    Text(
        text.uppercase(),
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.4.sp,
        color = Palette.Dim,
    )
}

/** The surface everything sits on: filled, hairline-outlined, 22dp. */
@Composable
fun DCard(
    modifier: Modifier = Modifier,
    padding: androidx.compose.ui.unit.Dp = Dsn.CardPad,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Dsn.CardRadius))
            .background(Palette.Surface)
            .border(1.dp, Palette.Line, RoundedCornerShape(Dsn.CardRadius))
            .padding(padding),
        content = content,
    )
}

/** A card whose children draw to its edges — list rows with dividers between them. */
@Composable
fun DCardFlush(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Dsn.CardRadius))
            .background(Palette.Surface)
            .border(1.dp, Palette.Line, RoundedCornerShape(Dsn.CardRadius)),
        content = content,
    )
}

/** The hairline between rows inside a flush card. */
@Composable
fun Rule() {
    Box(Modifier.fillMaxWidth().height(1.dp).background(Palette.Line))
}

/**
 * A pill: a small piece of state, or a small choice.
 *
 * [selected] fills it with the accent, which in this app means *touchable* and nothing else — so a
 * pill that is only reporting a fact ("18 apps") is never selected, whatever colour would look
 * livelier.
 */
@Composable
fun Pill(
    text: String,
    selected: Boolean = false,
    tint: Color? = null,
    onClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val background = when {
        selected -> Palette.Accent
        tint != null -> tint.copy(alpha = 0.14f)
        else -> Palette.Raised
    }
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(999.dp))
            .background(background)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            fontSize = 13.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.SemiBold,
            color = when {
                selected -> Palette.Ink
                tint != null -> tint
                else -> Palette.Muted
            },
        )
    }
}

/** The one button a screen is really about. Accent unless a block is what it starts. */
@Composable
fun PrimaryButton(
    text: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    colour: Color = Palette.Accent,
    onClick: () -> Unit,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(Dsn.ButtonHeight)
            .clip(RoundedCornerShape(Dsn.CtlRadius))
            .background(if (enabled) colour else Palette.Raised)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
            color = if (enabled) Palette.Ink else Palette.Dim,
        )
    }
}

/** The alternative to the primary button, and never a competitor to it. */
@Composable
fun GhostButton(
    text: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    colour: Color = Palette.Text,
    onClick: () -> Unit,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(Dsn.GhostHeight)
            .clip(RoundedCornerShape(Dsn.CtlRadius))
            .border(1.dp, Palette.Line, RoundedCornerShape(Dsn.CtlRadius))
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            // A control that cannot do anything says so by looking like it, rather than by
            // silently swallowing the tap.
            color = if (enabled) colour else Palette.Dim,
        )
    }
}

/**
 * The rounded square that stands in front of a row.
 *
 * It carries a tint at 16% rather than a solid fill, so a list of six of them reads as one list
 * with six subjects rather than as six coloured buttons.
 */
@Composable
fun Glyph(
    tint: Color,
    size: androidx.compose.ui.unit.Dp = 44.dp,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(RoundedCornerShape(Dsn.CtlRadius))
            .background(tint.copy(alpha = 0.16f)),
        contentAlignment = Alignment.Center,
        content = content,
    )
}

/** A row inside a flush card: glyph, two lines of text, and whatever sits on the right. */
@Composable
fun DRow(
    title: String,
    note: String,
    modifier: Modifier = Modifier,
    noteColour: Color = Palette.Muted,
    leading: @Composable (() -> Unit)? = null,
    trailing: @Composable (RowScope.() -> Unit)? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(Dsn.CardPad),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        leading?.invoke()
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Palette.Text)
            Text(note, fontSize = 13.sp, lineHeight = 18.sp, color = noteColour)
        }
        trailing?.invoke(this)
    }
}

/**
 * The toggle on a plan row. Drawn rather than themed, so it matches the canvas exactly.
 *
 * The drawn pill is 44×26dp — the artboard's size, and the right *look*. The touch target was not:
 * at 26dp tall it was the shortest control in the app, less than half the 52dp primary button it
 * usually sits opposite, and a miss on it is silent. A switch is the control that decides whether an
 * app is blocked, so it is worth being able to hit.
 *
 * `minimumInteractiveComponentSize` is what separates the two: it expands the *measured* box to
 * [Dsn.MinTouch] and leaves the drawing alone. A plain `padding` would have moved the pill inside the
 * row and changed the layout instead of the hit area.
 */
@Composable
fun Switch(on: Boolean, onChange: (Boolean) -> Unit) {
    Box(
        modifier = Modifier
            .minimumInteractiveComponentSize()
            .clickable { onChange(!on) },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(width = 44.dp, height = 26.dp)
                .clip(RoundedCornerShape(999.dp))
                .background(if (on) Palette.Accent else Palette.Raised)
                .then(
                    if (on) Modifier
                    else Modifier.border(1.dp, Palette.Line, RoundedCornerShape(999.dp)),
                ),
            contentAlignment = if (on) Alignment.CenterEnd else Alignment.CenterStart,
        ) {
            Box(
                Modifier
                    .padding(horizontal = 3.dp)
                    .size(20.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .background(if (on) Palette.Ink else Color(0xFF3B4553)),
            )
        }
    }
}

/**
 * The dial.
 *
 * Both the Today screen and the timer are built around one circle: a track, and an amber arc for
 * however much of the block is left. It is the only chart in the app, and it exists because "1:12"
 * on its own does not say whether that is nearly over or barely started.
 *
 * [fraction] is how much of the arc to draw, 0 for a bare track. Drawing starts at twelve o'clock
 * and runs clockwise, which is the only direction anyone reads a remaining-time ring.
 *
 * [colour] defaults to [Palette.Live] and *that* is right here, unlike [DragDial]'s — worth saying
 * because the two look like the same decision. The arc is only drawn when `fraction > 0f`, so this
 * colour appears exactly when there is time left to show, which is exactly when a block is running.
 * An idle dial is a bare grey track and is never amber.
 */
@Composable
fun Dial(
    fraction: Float,
    diameter: androidx.compose.ui.unit.Dp,
    stroke: androidx.compose.ui.unit.Dp = 11.dp,
    colour: Color = Palette.Live,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(Modifier.size(diameter), contentAlignment = Alignment.Center) {
        androidx.compose.foundation.Canvas(Modifier.size(diameter)) {
            val width = stroke.toPx()
            val inset = width / 2
            val box = androidx.compose.ui.geometry.Size(size.width - width, size.height - width)
            drawArc(
                color = Color(0xFF232B36),
                startAngle = 0f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = androidx.compose.ui.geometry.Offset(inset, inset),
                size = box,
                style = androidx.compose.ui.graphics.drawscope.Stroke(width),
            )
            if (fraction > 0f) {
                drawArc(
                    color = colour,
                    startAngle = -90f,
                    sweepAngle = 360f * fraction.coerceIn(0f, 1f),
                    useCenter = false,
                    topLeft = androidx.compose.ui.geometry.Offset(inset, inset),
                    size = box,
                    style = androidx.compose.ui.graphics.drawscope.Stroke(
                        width,
                        cap = androidx.compose.ui.graphics.StrokeCap.Round,
                    ),
                )
            }
        }
        content()
    }
}

/**
 * A [Dial] you can set with your thumb, the way a kitchen timer is set.
 *
 * The stepper next to it moves in fives, which is right for "make it a bit longer" and wrong for
 * "thirty-seven minutes, because that is when the train gets in". So the ring itself is draggable:
 * one full turn is [perTurn] minutes, and turns accumulate, which keeps a minute a comfortable
 * six degrees of arc instead of the half a degree it would be if the whole range were mapped onto
 * one revolution. Dragging past either end stops at the end rather than wrapping, because a timer
 * that silently jumps from twelve hours to one minute under a thumb is a timer nobody trusts.
 *
 * The handle is drawn where the value is, so there is something to aim at, and every whole minute
 * crossed ticks the phone — the feedback that makes a dial feel like a physical control rather than
 * like a slider with a round hitbox.
 *
 * [colour] defaults to [Palette.Accent], not to [Palette.Live], and that is the point rather than a
 * detail. Its only caller is the setup screen, where the whole question is "how long do you want?" and
 * nothing is running yet — so an amber dial there made the *planning* screen wear the colour that
 * means *a block is running now*, which is the one thing amber is reserved for (`Theme.kt`). A caller
 * that wants the live dial passes it explicitly.
 */
@Composable
fun DragDial(
    minutes: Int,
    max: Int,
    onChange: (Int) -> Unit,
    diameter: androidx.compose.ui.unit.Dp,
    stroke: androidx.compose.ui.unit.Dp = 12.dp,
    perTurn: Int = 60,
    colour: Color = Palette.Accent,
    content: @Composable BoxScope.() -> Unit,
) {
    val haptics = androidx.compose.ui.platform.LocalHapticFeedback.current
    // The value being dragged, in minutes, kept as a float so a slow drag accumulates fractions
    // instead of rounding every one of them away to nothing.
    var live by remember { mutableStateOf(minutes.toFloat()) }
    var dragging by remember { mutableStateOf(false) }
    if (!dragging && live.roundToInt() != minutes) live = minutes.toFloat()

    // The ring is a lap, not a progress bar. A progress bar over the whole range would mean the
    // handle moved at one rate and the thumb at another, so the handle slid out from under the
    // finger holding it and the value went wherever the mismatch took it. One turn is [perTurn]
    // minutes and the handle sits exactly where the thumb is; longer than a turn simply goes round
    // again, which is how a phone's own timer has always behaved.
    val laps = (live / perTurn).toInt()
    val fraction = ((live % perTurn) / perTurn).coerceIn(0f, 1f)
    Box(
        Modifier
            .size(diameter)
            .pointerInput(max, perTurn) {
                val centre = androidx.compose.ui.geometry.Offset(size.width / 2f, size.height / 2f)
                var last = 0f
                detectDragGestures(
                    onDragStart = { at ->
                        dragging = true
                        last = angleOf(at - centre)
                    },
                    onDragEnd = { dragging = false },
                    onDragCancel = { dragging = false },
                ) { change, _ ->
                    change.consume()
                    val now = angleOf(change.position - centre)
                    val delta = shortestTurn(last, now)
                    last = now
                    val before = live.roundToInt()
                    live = (live + delta / 360f * perTurn).coerceIn(1f, max.toFloat())
                    val after = live.roundToInt()
                    if (after != before) {
                        haptics.performHapticFeedback(
                            androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove,
                        )
                        onChange(after)
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        androidx.compose.foundation.Canvas(Modifier.size(diameter)) {
            val width = stroke.toPx()
            val inset = width / 2
            val box = androidx.compose.ui.geometry.Size(size.width - width, size.height - width)
            drawArc(
                color = Color(0xFF232B36),
                startAngle = 0f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = androidx.compose.ui.geometry.Offset(inset, inset),
                size = box,
                style = androidx.compose.ui.graphics.drawscope.Stroke(width),
            )
                        // A completed lap stays on the ring, dimmed, so an hour and a half does not read the
            // same as half an hour.
            if (laps > 0) {
                drawArc(
                    color = colour.copy(alpha = 0.28f),
                    startAngle = 0f,
                    sweepAngle = 360f,
                    useCenter = false,
                    topLeft = androidx.compose.ui.geometry.Offset(inset, inset),
                    size = box,
                    style = androidx.compose.ui.graphics.drawscope.Stroke(width),
                )
            }
            if (fraction > 0f) {
                drawArc(
                    color = colour,
                    startAngle = -90f,
                    sweepAngle = 360f * fraction,
                    useCenter = false,
                    topLeft = androidx.compose.ui.geometry.Offset(inset, inset),
                    size = box,
                    style = androidx.compose.ui.graphics.drawscope.Stroke(
                        width,
                        cap = androidx.compose.ui.graphics.StrokeCap.Round,
                    ),
                )
            }
            // The handle: something to put a thumb on, and the only part of the ring that says
            // this one can be dragged at all.
            val radians = Math.toRadians((360f * fraction - 90f).toDouble())
            val radius = (size.minDimension - width) / 2f
            val at = androidx.compose.ui.geometry.Offset(
                size.width / 2f + (radius * kotlin.math.cos(radians)).toFloat(),
                size.height / 2f + (radius * kotlin.math.sin(radians)).toFloat(),
            )
            drawCircle(color = Palette.Ink, radius = width * 0.95f, center = at)
            drawCircle(color = colour, radius = width * 0.62f, center = at)
        }
        content()
    }
}

/**
 * The turn from [from] to [to], in degrees, taking the short way round.
 *
 * Two samples a frame apart are never more than a few degrees apart in reality, so anything that
 * looks like more than half a turn is the seam at twelve o'clock being crossed, not a thumb that
 * teleported. Reading it literally there would jump the timer by most of an hour in the wrong
 * direction every time a drag passed the top of the dial, which is the one place a drag passes.
 */
internal fun shortestTurn(from: Float, to: Float): Float {
    var delta = to - from
    if (delta > 180f) delta -= 360f
    if (delta < -180f) delta += 360f
    return delta
}

/** Where a point sits around the centre, in degrees clockwise from twelve o'clock. */
internal fun angleOf(offset: androidx.compose.ui.geometry.Offset): Float {
    val degrees = Math.toDegrees(kotlin.math.atan2(offset.y.toDouble(), offset.x.toDouble())).toFloat()
    return (degrees + 90f + 360f) % 360f
}

/** The big numeral in the middle of a [Dial]. */
@Composable
fun DialNumber(text: String, size: Int = 46) {
    Text(
        text,
        fontSize = size.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = (-size / 28f).sp,
        color = Palette.Text,
    )
}

/**
 * A form that rises from the bottom edge, in the app's own material.
 *
 * The schedule forms used to be Material `AlertDialog`s, which meant every control inside them —
 * chips, text fields, the buttons — came from a theme this app otherwise never shows: lighter
 * surfaces, different corners, a different idea of what a label is. Against the Plan page behind
 * it the form read as a screen borrowed from another application, which is exactly what it was.
 *
 * A sheet instead of a centred box because the form is a continuation of the row that was tapped:
 * it comes up from the same edge the thumb is on, keeps the page visible above it, and ends in the
 * two buttons every other screen ends in.
 */
@Composable
fun DSheet(
    title: String,
    sub: String?,
    onDismiss: () -> Unit,
    confirm: String,
    confirmEnabled: Boolean,
    onConfirm: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    SheetFrame(
        title = title,
        sub = sub,
        onDismiss = onDismiss,
        content = content,
        buttons = {
            GhostButton("Cancel", Modifier.weight(1f), onClick = onDismiss)
            Box(Modifier.weight(1f)) {
                PrimaryButton(confirm, enabled = confirmEnabled, onClick = onConfirm)
            }
        },
    )
}

/**
 * The chrome every sheet in the app is made of: the scrim, the pane, the grip, the title, the body and
 * a row of buttons.
 *
 * Extracted so the material lives in **one** place. `DSheet` was the only sheet, and the three
 * confirmation dialogs on the Now screen were Material `AlertDialog`s — a different surface colour, a
 * different corner radius, a different type scale and different buttons, on the screen a user sees
 * most. That is F-40 in the interaction review, and the fix is not "restyle three dialogs" but "have
 * one sheet", so every sheet added later is the app's material by construction rather than by care.
 *
 * `glass` rather than a flat `Palette.Surface`, which is F-39: the design canvas asks for the sheets to
 * be *"the page's own material, not a platform dialog — same surface, same hairline, same 22px corner
 * as every card behind it"*, and the app's own answer to "the page's own material" is the pane the nav
 * bar and the block screen already wear. It was the only elevated surface without it.
 */
@Composable
private fun SheetFrame(
    title: String,
    sub: String?,
    onDismiss: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
    buttons: @Composable RowScope.() -> Unit,
) {
    androidx.compose.ui.window.Dialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .glass(
                    shape = RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp),
                    // `Raised` rather than `Surface`: a sheet lies over a page made of Surface, and the
                    // same colour on both would read as a hole rather than as a pane laid on top.
                    ground = Palette.Raised,
                )
                .padding(horizontal = Dsn.Gutter)
                .padding(top = 8.dp, bottom = 20.dp),
        ) {
            // The grip is not a control: it says which edge this came from, and that it goes back.
            Box(
                modifier = Modifier
                    .padding(vertical = 6.dp)
                    .align(Alignment.CenterHorizontally)
                    .clip(RoundedCornerShape(999.dp))
                    .background(Palette.Line)
                    .size(width = 38.dp, height = 4.dp),
            )
            Gap(8.dp)
            Text(
                title,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = (-0.4).sp,
                color = Palette.Text,
                modifier = Modifier.semantics { heading() },
            )
            if (sub != null) {
                Text(
                    sub,
                    fontSize = 13.sp,
                    lineHeight = 19.sp,
                    color = Palette.Muted,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            Column(
                modifier = Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
            ) {
                content()
            }
            Gap(22.dp)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) { buttons() }
        }
    }
}

/**
 * A decision: a sentence, and two buttons.
 *
 * The shape every "are you sure" in the app takes, so they are all the same thing. `destructive`
 * changes *which button is the prominent one*, and that is a deliberate reading of the design canvas
 * rather than a preference: the canvas styles the app's one destructive control — Delete on a profile
 * — as a **ghost button in the warning colour**, and puts the filled accent on the action that is safe.
 * So when the confirm is the destructive one, the emphasis moves to the way out.
 *
 * That is also why there is no "danger" fill: a solid red button invites the tap it is warning about,
 * and the canvas never draws one.
 */
@Composable
fun DConfirm(
    title: String,
    sub: String? = null,
    body: String? = null,
    dismiss: String,
    confirm: String,
    destructive: Boolean = false,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    SheetFrame(
        title = title,
        sub = sub,
        onDismiss = onDismiss,
        content = {
            if (body != null) {
                Text(body, fontSize = 14.sp, lineHeight = 21.sp, color = Palette.Muted)
            }
        },
        buttons = {
            if (destructive) {
                Box(Modifier.weight(1f)) {
                    PrimaryButton(dismiss, onClick = onDismiss)
                }
                GhostButton(
                    confirm,
                    Modifier.weight(1f),
                    colour = Palette.Bad,
                    onClick = onConfirm,
                )
            } else {
                GhostButton(dismiss, Modifier.weight(1f), onClick = onDismiss)
                Box(Modifier.weight(1f)) {
                    PrimaryButton(confirm, onClick = onConfirm)
                }
            }
        },
    )
}

/**
 * Something the user asked for and cannot have, or cannot do.
 *
 * One button, because there is no decision — the point is the sentence. It is a sheet rather than a
 * platform dialog for the same reason the others are: the app has one material, and a refusal is not
 * an occasion to borrow another one.
 */
@Composable
fun DNote(title: String, body: String, dismiss: String = "OK", onDismiss: () -> Unit) {
    SheetFrame(
        title = title,
        sub = null,
        onDismiss = onDismiss,
        content = { Text(body, fontSize = 14.sp, lineHeight = 21.sp, color = Palette.Muted) },
        buttons = { Box(Modifier.weight(1f)) { PrimaryButton(dismiss, onClick = onDismiss) } },
    )
}

/** A block of the form: its label, and what it asks for. */
@Composable
fun ColumnScope.SheetSection(label: String, content: @Composable ColumnScope.() -> Unit) {
    Gap(20.dp)
    SectionLabel(label)
    Gap(9.dp)
    content()
}

/**
 * One editable value, set at the size of a value.
 *
 * A time is read at a glance and typed rarely, so it is shown the way the Now screen shows a time —
 * large, tabular, on the raised surface — rather than as body text inside an outlined box.
 */
@Composable
fun ValueField(
    label: String,
    value: String,
    bad: Boolean,
    modifier: Modifier = Modifier,
    onChange: (String) -> Unit,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(Dsn.CtlRadius))
            .background(Palette.Raised)
            .border(
                width = 1.dp,
                color = if (bad) Palette.Bad else Palette.Line,
                shape = RoundedCornerShape(Dsn.CtlRadius),
            )
            .padding(horizontal = 14.dp, vertical = 11.dp),
    ) {
        Text(
            label.uppercase(),
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.2.sp,
            color = Palette.Dim,
        )
        androidx.compose.foundation.text.BasicTextField(
            value = value,
            onValueChange = onChange,
            singleLine = true,
            textStyle = androidx.compose.ui.text.TextStyle(
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = (-0.5).sp,
                color = if (bad) Palette.Bad else Palette.Text,
            ),
            cursorBrush = androidx.compose.ui.graphics.SolidColor(Palette.Accent),
            modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
        )
    }
}

/** The sentence a form says back to the reader about what it has understood. */
@Composable
fun SheetNote(text: String, bad: Boolean = false) {
    Text(
        text,
        fontSize = 12.sp,
        lineHeight = 18.sp,
        color = if (bad) Palette.Bad else Palette.Muted,
        modifier = Modifier.padding(top = 10.dp),
    )
}

/**
 * A line of text the user types: label above, value in the field, placeholder when it is empty.
 *
 * The same tile as [ValueField] at body size, so a form asking for a word and a form asking for a
 * time are visibly the same form. An empty field shows what it would accept rather than a floating
 * label that moves when touched — nothing here is ever asked for twice, so there is no label to
 * preserve once the answer is in.
 */
@Composable
fun TextField(
    label: String,
    value: String,
    placeholder: String = "",
    modifier: Modifier = Modifier,
    onChange: (String) -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Dsn.CtlRadius))
            .background(Palette.Raised)
            .border(1.dp, Palette.Line, RoundedCornerShape(Dsn.CtlRadius))
            .padding(horizontal = 14.dp, vertical = 11.dp),
    ) {
        Text(
            label.uppercase(),
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.2.sp,
            color = Palette.Dim,
        )
        Box(Modifier.padding(top = 3.dp)) {
            if (value.isEmpty() && placeholder.isNotEmpty()) {
                Text(placeholder, fontSize = 16.sp, color = Palette.Dim)
            }
            androidx.compose.foundation.text.BasicTextField(
                value = value,
                onValueChange = onChange,
                singleLine = true,
                textStyle = androidx.compose.ui.text.TextStyle(
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                    color = Palette.Text,
                ),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(Palette.Accent),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
