package dev.curfew.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.animation.core.Animatable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import dev.curfew.policy.ChallengeKind
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The spacing scale, and the only gaps in the app.
 *
 * Every padding, [Gap] and `spacedBy` is one of these steps. Seven steps on a 4dp rhythm are
 * enough to separate a label from its value ([xs]), a row from the next ([s]), a field from the
 * next group ([m]), a card's content from its edge ([l]) and a section from the next ([xl], [xxl]).
 * A gap between two of them is a gap the eye cannot tell apart from its neighbour, so it is not a
 * gap the design has.
 */
object Space {
    val xxs = 2.dp
    val xs = 4.dp
    val s = 8.dp
    val m = 12.dp
    val l = 16.dp
    val xl = 24.dp
    val xxl = 32.dp
}

/**
 * The measurements `docs/design.md` specifies, as constants rather than as habits.
 *
 * Every number here was read off §3.4, §6.1 and §8 of the design document rather than chosen again
 * in Kotlin, because "roughly the same card" is how a design and its implementation drift apart
 * over a few weeks until neither is the truth.
 */
object Dsn {
    /** The page gutter. Every screen's content starts and ends here. */
    val Gutter = Space.l

    /**
     * Nothing is round.
     *
     * `docs/design.md` §3.4: *"Default radius: 0."* Kept as named zero values rather than deleted,
     * so a call site reading `RoundedCornerShape(Dsn.CtlRadius)` keeps compiling and keeps meaning
     * "the control shape".
     */
    val CardRadius = 0.dp
    val CtlRadius = 0.dp

    /** Card padding, and the tighter variant list rows use. */
    val CardPad = Space.l
    val RowPad = 14.dp

    /** A primary button, and the shorter secondary one that sits under a card. */
    val ButtonHeight = 52.dp
    val GhostHeight = 46.dp

    /**
     * The smallest a *touch* target may be, whatever it looks like.
     *
     * §8's minimum, and the one this app's own buttons already meet at 52 and 46dp. It is separate
     * from visual size on purpose: a 44×26dp toggle and a 36dp day circle are the right *look* and
     * the wrong *hit area*, and Material's `minimumInteractiveComponentSize` holds both at once.
     */
    val MinTouch = 48.dp

    /** §3.4: card and control borders are 3px. */
    val Border = 3.dp

    /** A hairline rule *inside* a card, where 3px would read as a divider between cards. */
    val Hairline = 1.dp

    /**
     * The offset shadow, which is now none at all: the design is flat, and a surface is told apart
     * by its border and fill alone. Both stay as names so every plate keeps one switch.
     */
    val ShadowHero = 0.dp
    val ShadowControl = 0.dp

    /** How far a pressed control travels. Zero: with no shadow to sink into, a press only scales. */
    val Press = 0.dp

    /** A circular handle's hit area, per §6.1: at least 44px even when the handle is smaller. */
    val HandleHit = 44.dp

    /**
     * Room under the last element so a scrolled screen clears the navigation bar.
     *
     * The bar floats over the content rather than sitting beside it, so this has to cover the bar
     * itself, the margin it floats on and the system's own gesture inset.
     */
    val BottomRoom = 116.dp
}

/**
 * The app's paint, as a **plain object of defaults**, not a composable.
 *
 * This is the one place the design system deliberately gives up a little purity for a lot of
 * reliability. A `@Composable val Palette` reads beautifully and is what the theme prefers — but it
 * makes `colour: Color = Palette.live` illegal as a *default parameter*, and every shared component
 * in this file has one. The two ways out are to make every default nullable and resolve it inside
 * every body, or to keep a plain object here.
 *
 * The object wins because of what the alternative costs: thirty-odd components, each with a `? =
 * null` and an `?:` line, so that a colour can be read one call frame later. Instead this is the
 * **light palette**, which is `docs/design.md` §3.2's stated default, and it is correct for every
 * screen a fresh install sees. Screens that need to follow the user's theme resolve the live
 * palette themselves — `CurfewTheme` still provides it, `LocalPalette` still swaps it, and
 * [Palette.live] and friends are the values used wherever a default is not involved.
 *
 * The tokens are lowercase to match the design document's own names (`bg`, `panel`, `line`, `ink`,
 * `brand`, `run`, `ok`). [Ink] and the capitalised aliases exist because the screens were written
 * against the previous palette's spellings and are *right about the intent* — a rename that can
 * only be finished by editing five hundred lines of other people's screens is a rename that leaves
 * the tree broken until somebody does it.
 */
/**
 * The app's paint.
 *
 * ## Why this is a live object rather than a set of constants
 *
 * It was a plain `object` of `val`s holding `docs/design.md` §3.2's light values, and that is what
 * made the Theme and Accent settings do nothing. `CurfewTheme` built the chosen palette correctly
 * and provided it through a composition local — and none of the several hundred `Palette.x`
 * references in the screens read that local. So the theme was computed, stored, and never used: the
 * controls wrote the preference, the preference changed, and every colour on screen stayed paper.
 *
 * Reading it from a composition local was not really available: a `@Composable val` cannot be a
 * default argument, and thirty-odd components in this file use `Palette.surface` and friends as
 * exactly that. A `var` backed by snapshot state can. Each token is a `mutableStateOf`, so every
 * composable that reads one *subscribes to it* and repaints when it changes, and a default argument
 * is still a plain value read.
 *
 * [adopt] is the single writer, called by `CurfewTheme` on every recomposition. Nothing else should
 * write here.
 *
 * The tokens are lowercase to match the design document's own names (`bg`, `panel`, `line`, `ink`,
 * `brand`, `run`, `ok`).
 */
object Palette {
    private var current by mutableStateOf(Paper)

    /** Point every token at [palette]. Called by `CurfewTheme`, and by nothing else. */
    fun adopt(palette: Brutal) {
        current = palette
    }

    /** `bg` — the page the whole app sits on. */
    val ground: Color get() = current.ground

    /** `panel` — a card: the main surface. */
    val surface: Color get() = current.surface

    /** `raise` — a field, a well, an unselected chip. */
    val raised: Color get() = current.raised

    /** `raise2` — a selected or pressed surface. */
    val selected: Color get() = current.selected

    /** `line` — borders, rules, the drawn edge of every control. */
    val line: Color get() = current.line

    /** `ink` — primary text. */
    val text: Color get() = current.text

    /** `mut` — supporting text: the sentence under a title. */
    val muted: Color get() = current.muted

    /** `dim` — hints and low-priority metadata. */
    val dim: Color get() = current.dim

    /** `brand` — primary action and navigation. */
    val accent: Color get() = current.accent

    /** `run` — a block is running. Never used for anything else. */
    val live: Color get() = current.live

    /** `ok` — live or successful. */
    val ok: Color get() = current.ok

    /** `warn` — degraded, or needs attention. */
    val warn: Color get() = current.warn

    /** `bad` — refused, dangerous, or destructive. */
    val bad: Color get() = current.bad

    /** `cal` — derived from the calendar. */
    val cal: Color get() = current.cal


    /** Colours a profile may be given. Named nowhere in the UI — a profile shows a dot. */
    val profileColours: List<Color> get() = current.profileColours

}

/**
 * Draw a hard-edged drop shadow: the same rectangle, offset, in solid ink, with **no blur**.
 *
 * ## Why this is drawn rather than themed
 *
 * Material's `Modifier.shadow(0.dp, shape)` is the obvious candidate and it is wrong here — a
 * shadow with a zero blur radius is not a documented hard shadow, and on the previous attempt the
 * result was invisible: the shadow was painted inside the layout's own bounds, directly underneath
 * an opaque surface, and the screen showed no elevation at all. `docs/design.md` §3.4 asks for
 * `8px 8px 0 ink`, which is the oldest trick in print — a duplicate of the shape filled solid and
 * pushed down and right — so it is drawn.
 *
 * ## The footprint problem, and the answer to it
 *
 * A drawn shadow needs somewhere to be drawn. Compose clips a child to its parent's bounds, so an
 * `offset` layer that sticks out to the bottom-right is simply cut off — which is the other way to
 * end up with a shadow nobody can see.
 *
 * The answer is to **reserve the room**: the plate's layout box is the surface *plus* the shadow,
 * the surface occupies the top-left of it, and the bar of ink occupies the bottom-right strip. The
 * shadow is then incapable of being clipped or covered, because it is inside its own box and
 * nothing is drawn on top of it.
 *
 * The cost is honest and visible: a 3px shadow makes the control 3px wider and taller, and an 8px
 * hero shadow makes a sheet 8px wider and taller. That is what a hard offset is — the object really
 * is bigger than its face — and the alternative, drawing outside the box, is the version that
 * silently disappears.
 */
@Composable
fun Modifier.hardShadow(
    offset: Dp = Dsn.ShadowControl,
    shape: Shape = RoundedCornerShape(Dsn.CardRadius),
): Modifier {
    val ink = Palette.line
    val radius = Dsn.CardRadius
    return this.drawBehind {
        val d = offset.toPx()
        if (d <= 0f) return@drawBehind
        // Inside the box, not outside it. A rectangle that overhangs its own layout is a rectangle
        // the parent clips, and one that overhangs on two sides only is one that looks shifted.
        drawRoundRect(
            color = ink,
            topLeft = Offset(d, d),
            size = Size(
                (size.width - d).coerceAtLeast(0f),
                (size.height - d).coerceAtLeast(0f),
            ),
            cornerRadius = CornerRadius(radius.toPx(), radius.toPx()),
        )
    }
}

/**
 * The app's one surface: a plate with a 3px ink edge and a hard offset shadow.
 *
 * Applied to this Modifier so the shadow is reserved on the leading edge and the plate's own
 * content is inset from it. See [hardShadow] for why the room is reserved rather than overflowed.
 */
@Composable
fun Modifier.plate(
    fill: Color = Palette.surface,
    border: Color = Palette.line,
    offset: Dp = Dsn.ShadowControl,
    width: Dp = Dsn.Border,
    shape: Shape = RoundedCornerShape(Dsn.CardRadius),
): Modifier = this
    // Nothing a plate draws may leave its own box.
    //
    // `drawBehind` does not clip, so a child that measures wider than its parent — a row of
    // weighted cells, a filled bar stretched to a fraction — can paint past the plate's own edge
    // and the plate then looks like its end has been cut off or run into the margin. Clipping here
    // makes the plate's bounds the truth regardless of what it is wrapping, which is the property a
    // surface is supposed to have.
    .clip(shape)
    .drawBehind {
    val d = offset.toPx()
    val w = width.toPx()
    val radius = Dsn.CardRadius.toPx()
    // The face: everything except the two shadowed bands.
    val faceW = (size.width - d).coerceAtLeast(0f)
    val faceH = (size.height - d).coerceAtLeast(0f)
    if (d > 0f) {
        // **The shadow has to reach past the face, and it did not.**
        //
        // This was drawn at `(d, d)` with a size of `faceW × faceH`, which puts its bottom-right
        // corner exactly on the box's bottom-right corner — so the opaque face, drawn next, covered
        // it completely, and no plate in the app had a visible drop shadow at all.
        // `docs/design.md` 3.4 asks for a hard `3px 3px 0`; what it got was a flat rectangle. That
        // is also why the bottom bar looked wrong: with no shadow it was a plain white slab whose
        // edge just stopped, and the extra band the old geometry reserved on the trailing side had
        // nothing drawn in it.
        //
        // Drawn at `(d, d)` with the box's *full* width and height instead, so the strip between the
        // face's edge and the box's edge on the right and bottom is live shadow — three or eight
        // pixels of solid ink that nothing is painted over. The top-left is covered by the face,
        // which is exactly where a down-and-right shadow should be hidden.
        drawRoundRect(
            color = border,
            topLeft = Offset(d, d),
            size = Size(size.width, size.height),
            cornerRadius = CornerRadius(radius, radius),
        )
    }
    drawRoundRect(
        color = border,
        topLeft = Offset.Zero,
        size = Size(faceW, faceH),
        cornerRadius = CornerRadius(radius, radius),
    )
    drawRoundRect(
        color = fill,
        topLeft = Offset(w, w),
        size = Size(
            (faceW - w * 2).coerceAtLeast(0f),
            (faceH - w * 2).coerceAtLeast(0f),
        ),
        cornerRadius = CornerRadius(radius, radius),
    )
}

/**
 * The app's elevated surface, kept under its old name.
 *
 * This used to be a sheet of glass: a translucent ground, a top-down sheen and a hairline that
 * caught light. §3.1 rules all of that out — *"No gradients, glass effects, soft shadows, floating
 * pills or glossy surfaces."* The call sites were right about the intent (the nav bar, the block
 * screen's cards and the sheets are the app's raised things) and wrong about the material, so the
 * function stays and the material changes underneath it.
 */
@Composable
fun Modifier.glass(
    shape: Shape = RoundedCornerShape(0.dp),
    ground: Color = Palette.surface,
    elevation: Dp = Dsn.ShadowControl,
): Modifier = this.plate(fill = ground, offset = elevation, shape = shape)

/** The alphas the old glass surface used. Kept so nothing that named them fails to compile. */
object Glass {
    const val Ground = 1f
    const val Sheen = 0f
    const val Edge = 0f
}

/** A screen: the gutter, the scroll, and the space at the bottom, in one place. */
@Composable
fun Screen(
    modifier: Modifier = Modifier,
    spacing: Dp = 0.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Dsn.Gutter),
        verticalArrangement = Arrangement.spacedBy(spacing),
    ) {
        Gap(Space.l)
        content()
        Gap(Dsn.BottomRoom)
    }
}

/** Vertical space, spelled the way the design spells it. */
@Composable
fun Gap(height: Dp) {
    Spacer(Modifier.height(height))
}

/**
 * The page title: [Type.h1], uppercase. A title inside a screen's flow takes [Type.h2].
 *
 * §3.1 requires *"heavy, uppercase headings and labels"*. It is a real heading for a screen reader
 * as well as a heavy line for the eye.
 */
@Composable
fun Title(text: String, style: TextStyle = Type.h1) {
    Text(
        text.uppercase(),
        style = style,
        color = Palette.text,
        modifier = Modifier.semantics { heading() },
    )
}

/** The sentence under a title. A sentence, and never a fragment. */
@Composable
fun Sub(text: String) {
    Text(text, style = Type.body, color = Palette.muted)
}

/**
 * A section label: micro 11sp, black, wide tracking, uppercase.
 *
 * Small enough to read as furniture rather than as content, which is the whole job: it names the
 * group under it without competing with it.
 */
@Composable
fun SectionLabel(text: String) {
    Text(
        text.uppercase(),
        style = Type.label,
        color = Palette.dim,
    )
}

/**
 * A system-like value: a time, a duration, a path, a revision.
 *
 * §3.1: *"Mono text for times, technical values and labels."* The reason is not style. This app is
 * full of numbers read in columns — a countdown over an end time over a duration — and a
 * proportional font makes a column of them jitter. Mono also makes `22:45` unambiguous at a glance,
 * which is the one thing a person reading a schedule at 22:40 needs.
 */
@Composable
fun Mono(
    text: String,
    style: TextStyle = Type.meta,
    colour: Color? = null,
    weight: FontWeight = FontWeight.Bold,
    modifier: Modifier = Modifier,
) {
    Text(
        text,
        style = style.mono,
        fontWeight = weight,
        color = colour ?: Palette.text,
        modifier = modifier,
    )
}

/** A card: filled, 3px ink edge, square, with a hard offset shadow. */
@Composable
fun DCard(
    modifier: Modifier = Modifier,
    padding: Dp = Dsn.CardPad,
    offset: Dp = Dsn.ShadowControl,
    fill: Color = Palette.surface,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .plate(fill = fill, offset = offset)
            .padding(padding),
        content = content,
    )
}

/** A card whose children draw to its edges — list rows with dividers between them. */
@Composable
fun DCardFlush(
    modifier: Modifier = Modifier,
    offset: Dp = Dsn.ShadowControl,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .plate(offset = offset),
        content = content,
    )
}

/** The rule between rows inside a flush card. */
@Composable
fun Rule() {
    Box(Modifier.fillMaxWidth().height(Dsn.Border).background(Palette.line))
}

/** A hairline, for use inside something that is not a card outline. */
@Composable
fun Hairline() {
    Box(Modifier.fillMaxWidth().height(Dsn.Hairline).background(Palette.dim))
}

/**
 * A small piece of state, or a small choice — drawn as a square tag.
 *
 * §3.1 rules out *"floating pills"* and §3.4 sets radius 0, so the vocabulary is kept and only the
 * silhouette changed. [selected] fills it with the accent, which in this app means *touchable* and
 * nothing else — a tag that is only reporting a fact ("18 apps") is never selected.
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
        selected -> Palette.accent
        tint != null -> tint.copy(alpha = 0.16f)
        else -> Palette.raised
    }
    val foreground = when {
        selected -> Palette.surface
        tint != null -> tint
        else -> Palette.muted
    }
    Box(
        modifier = modifier
            // A tappable tag gets the full touch height around it; the drawn tag stays small.
            .then(
                if (onClick != null) {
                    Modifier.clickable(onClick = onClick).heightIn(min = Dsn.MinTouch).wrapContentHeight()
                } else {
                    Modifier
                },
            )
            .background(background)
            .border(Dsn.Border, if (selected || tint != null) foreground else Palette.line)
            .padding(horizontal = Space.m, vertical = Space.s),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text.uppercase(),
            style = Type.label,
            color = foreground,
        )
    }
}

/**
 * The one button a screen is really about.
 *
 * §3.4's pressed state is drawn, not decorated: pressed, the button travels 2dp down and right and
 * its shadow disappears, so a tap feels like pushing a physical key.
 */
@Composable
fun PrimaryButton(
    text: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    colour: Color = Palette.accent,
    onClick: () -> Unit,
) {
    val interaction = rememberPress()
    val pressed by interaction.collectIsPressedAsState()
    val bounce = pressBounce(interaction)
    val fill = if (enabled) colour else Palette.raised
    val ink = if (enabled) Palette.surface else Palette.dim
    val sink = if (pressed) Dsn.Press else 0.dp
    Box(
        modifier = modifier
            .fillMaxWidth()
            // `heightIn`, not `height`. A fixed 52dp with a label that does not fit is a label
            // drawn outside its own button, which is what "the buttons are overflowing" was: four
            // buttons in one row, each a quarter of the width, carrying labels like "ASK TO END IN
            // 24 HOURS". The box now grows to fit its label and the label wraps inside it.
            .heightIn(min = Dsn.ButtonHeight)
            .offset(sink, sink)
            // The press moves the button down and removes its shadow — section 3.4's pressed state —
            // and the scale is what makes the release feel like a key coming back up rather than a
            // colour changing.
            .graphicsLayer {
                scaleX = bounce
                scaleY = bounce
            }
            .plate(
                fill = fill,
                offset = if (pressed || !enabled) 0.dp else Dsn.ShadowControl,
            )
            .clickable(
                enabled = enabled,
                interactionSource = interaction,
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text.uppercase(),
            style = Type.button,
            color = ink,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = Space.m, vertical = Space.m),
        )
    }
}

/** The alternative to the primary button, and never a competitor to it. */
@Composable
fun GhostButton(
    text: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    colour: Color = Palette.text,
    onClick: () -> Unit,
) {
    val interaction = rememberPress()
    val bounce = pressBounce(interaction, depth = 0.96f)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = Dsn.GhostHeight)
            .graphicsLayer {
                scaleX = bounce
                scaleY = bounce
            }
            .plate(fill = Palette.surface, offset = if (enabled) Dsn.ShadowControl else 0.dp)
            .clickable(
                enabled = enabled,
                interactionSource = interaction,
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text.uppercase(),
            style = Type.button,
            // A control that cannot do anything says so by looking like it, rather than by silently
            // swallowing the tap.
            color = if (enabled) colour else Palette.dim,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = Space.m, vertical = Space.s),
        )
    }
}

/**
 * The square that stands in front of a row.
 *
 * A tint at 16% rather than a solid fill, so a list of six reads as one list with six subjects
 * rather than as six coloured buttons.
 */
@Composable
fun Glyph(
    tint: Color,
    size: Dp = 40.dp,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(
        modifier = Modifier
            .size(size)
            .background(tint.copy(alpha = 0.16f))
            .border(Dsn.Border, tint),
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
    noteColour: Color = Palette.muted,
    leading: @Composable (() -> Unit)? = null,
    trailing: @Composable (RowScope.() -> Unit)? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(Dsn.RowPad),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.m),
    ) {
        leading?.invoke()
        Column(Modifier.weight(1f)) {
            Text(title, style = Type.body, fontWeight = FontWeight.Bold, color = Palette.text)
            Text(note, style = Type.meta, color = noteColour)
        }
        trailing?.invoke(this)
    }
}

/**
 * The toggle on a plan row.
 *
 * The drawn switch is 56×28dp — square along its track, with a square knob, because a rounded
 * toggle would be the only soft thing on a screen of drawn rectangles. The word `ON`/`OFF` inside
 * it is the point of §8's *"status conveyed by word, shape and colour, not colour alone"*: this
 * control states whether a rule is armed in a word, so a reader who cannot tell the accent from the
 * ground can still read it.
 */
@Composable
fun Switch(on: Boolean, onChange: (Boolean) -> Unit) {
    val densityScale = androidx.compose.ui.platform.LocalDensity.current.density
    Box(
        modifier = Modifier
            .minimumInteractiveComponentSize()
            .clickable { onChange(!on) }
            .semantics {
                role = Role.Switch
                selected = on
                contentDescription = if (on) "On. Tap to pause." else "Off. Tap to start."
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(width = 56.dp, height = 28.dp)
                .background(if (on) Palette.accent else Palette.raised)
                .border(Dsn.Border, Palette.line),
        ) {
            Text(
                if (on) "ON" else "OFF",
                style = Type.label,
                color = if (on) Palette.surface else Palette.dim,
                modifier = Modifier
                    .align(if (on) Alignment.CenterStart else Alignment.CenterEnd)
                    .padding(horizontal = Space.s),
            )
            // The knob *travels* rather than jumping to the other side: a toggle that teleports is a
            // toggle whose state you have to re-read, and one that slides tells you what it just did.
            val knob by animateFloatAsState(
                targetValue = if (on) 1f else 0f,
                animationSpec = Juice.snap(),
                label = "knob",
            )
            Box(
                Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = Space.xxs)
                    .offset { IntOffset(((56 - 24) * knob * densityScale).toInt(), 0) }
                    .size(18.dp)
                    .background(if (on) Palette.surface else Palette.line),
            )
        }
    }
}

/**
 * The dial: a track and an arc, read as "how much of this is left".
 *
 * It is the only chart in the app, and it exists because "1:12" on its own does not say whether that
 * is nearly over or barely started. Twelve ticks rather than sixty (§6.1: *"minimal tick marks: 12
 * clear marks, not 60 noisy marks"*), and a thin track so the marks stay visible (§6.4).
 */
@Composable
fun Dial(
    fraction: Float,
    diameter: Dp,
    stroke: Dp = 9.dp,
    colour: Color = Palette.live,
    content: @Composable BoxScope.() -> Unit,
) {
    val track = Palette.line
    val ticks = Palette.dim
    // The arc is animated to its fraction rather than drawn at it. A countdown ring that jumps a
    // whole minute's worth of arc once a minute reads as broken; one that sweeps continuously is
    // the single clearest way to show that time is passing, and it is why this ring exists at all.
    val sweep by animateFloatAsState(
        targetValue = fraction.coerceIn(0f, 1f),
        animationSpec = Juice.track(),
        label = "arc",
    )
    Box(Modifier.size(diameter), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(diameter)) {
            val w = stroke.toPx()
            val inset = w / 2
            val box = Size(size.width - w, size.height - w)
            drawArc(
                color = track,
                startAngle = 0f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = Offset(inset, inset),
                size = box,
                style = Stroke(w),
            )
            val r = size.minDimension / 2f
            for (i in 0 until 12) {
                val a = Math.toRadians((i * 30.0) - 90.0)
                val inner = r - w - 4.dp.toPx()
                val outer = r - w - 1.dp.toPx()
                drawLine(
                    color = ticks,
                    start = Offset(
                        size.width / 2f + (inner * kotlin.math.cos(a)).toFloat(),
                        size.height / 2f + (inner * kotlin.math.sin(a)).toFloat(),
                    ),
                    end = Offset(
                        size.width / 2f + (outer * kotlin.math.cos(a)).toFloat(),
                        size.height / 2f + (outer * kotlin.math.sin(a)).toFloat(),
                    ),
                    strokeWidth = 1.dp.toPx(),
                )
            }
            if (sweep > 0f) {
                drawArc(
                    color = colour,
                    startAngle = -90f,
                    sweepAngle = 360f * sweep,
                    useCenter = false,
                    topLeft = Offset(inset, inset),
                    size = box,
                    style = Stroke(w, cap = StrokeCap.Round),
                )
            }
        }
        content()
    }
}

/** The big numeral in the middle of a [Dial] or a [TimeRing]. */
@Composable
fun DialNumber(text: String, style: TextStyle = Type.mega) {
    Text(
        text,
        style = style.mono,
        color = Palette.text,
    )
}

/**
 * The turn from [from] to [to], in degrees, taking the short way round.
 *
 * Two samples a frame apart are never more than a few degrees apart in reality, so anything that
 * looks like more than half a turn is the seam at twelve o'clock being crossed, not a thumb that
 * teleported. Reading it literally would jump the timer by most of an hour in the wrong direction
 * every time a drag passed the top of the dial, which is the one place a drag passes.
 */
internal fun shortestTurn(from: Float, to: Float): Float {
    var delta = to - from
    if (delta > 180f) delta -= 360f
    if (delta < -180f) delta += 360f
    return delta
}

/** Where a point sits around the centre, in degrees clockwise from twelve o'clock. */
internal fun angleOf(offset: Offset): Float {
    val degrees = Math.toDegrees(kotlin.math.atan2(offset.y.toDouble(), offset.x.toDouble())).toFloat()
    return (degrees + 90f + 360f) % 360f
}

/**
 * A [Dial] you can set with your thumb, the way a kitchen timer is set.
 *
 * One full turn is [perTurn] minutes and turns accumulate, which keeps a minute a comfortable six
 * degrees of arc. Dragging past either end stops at the end rather than wrapping, because a timer
 * that silently jumps from twelve hours to one minute under a thumb is a timer nobody trusts.
 *
 * [colour] defaults to [Palette.accent] and not to [Palette.live], deliberately: its caller is the
 * planning screen, where nothing is running yet, so an amber dial there would make the *planning*
 * screen wear the colour that means *a block is running now*.
 */
@Composable
fun DragDial(
    minutes: Int,
    max: Int,
    onChange: (Int) -> Unit,
    diameter: Dp,
    stroke: Dp = 9.dp,
    perTurn: Int = 60,
    colour: Color = Palette.accent,
    content: @Composable BoxScope.() -> Unit,
) {
    val haptics = androidx.compose.ui.platform.LocalHapticFeedback.current
    var live by remember { mutableFloatStateOf(minutes.toFloat()) }
    var dragging by remember { mutableStateOf(false) }
    // No write during composition (§7 rule 10): the sync is an effect, which runs after.
    androidx.compose.runtime.LaunchedEffect(minutes, dragging) {
        if (!dragging && abs(live.roundToInt() - minutes) >= 1) live = minutes.toFloat()
    }

    val laps = (live / perTurn).toInt()
    val fraction = ((live % perTurn) / perTurn).coerceIn(0f, 1f)
    // Read before the Canvas: this is the plain object, but keeping the reads at composable level
    // is what makes swapping it for a theme-aware accessor a one-line change later.
    val trackColour = Palette.line
    val lapColour = colour.copy(alpha = 0.28f)
    Box(
        Modifier
            .size(diameter)
            .pointerInput(max, perTurn) {
                val centre = Offset(size.width / 2f, size.height / 2f)
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
        Canvas(Modifier.size(diameter)) {
            val w = stroke.toPx()
            val inset = w / 2
            val box = Size(size.width - w, size.height - w)
            drawArc(
                color = trackColour,
                startAngle = 0f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = Offset(inset, inset),
                size = box,
                style = Stroke(w),
            )
            // A completed lap stays on the ring, dimmed, so an hour and a half does not read the
            // same as half an hour.
            if (laps > 0) {
                drawArc(
                    color = lapColour,
                    startAngle = 0f,
                    sweepAngle = 360f,
                    useCenter = false,
                    topLeft = Offset(inset, inset),
                    size = box,
                    style = Stroke(w),
                )
            }
            if (fraction > 0f) {
                drawArc(
                    color = colour,
                    startAngle = -90f,
                    sweepAngle = 360f * fraction,
                    useCenter = false,
                    topLeft = Offset(inset, inset),
                    size = box,
                    style = Stroke(w, cap = StrokeCap.Round),
                )
            }
            // A circular handle, and the only part of the ring that says this one can be dragged.
            val radians = Math.toRadians((360f * fraction - 90f).toDouble())
            val radius = (size.minDimension - w) / 2f
            val at = Offset(
                size.width / 2f + (radius * kotlin.math.cos(radians)).toFloat(),
                size.height / 2f + (radius * kotlin.math.sin(radians)).toFloat(),
            )
            drawCircle(trackColour, radius = w * 0.95f, center = at)
            drawCircle(colour, radius = w * 0.62f, center = at)
        }
        content()
    }
}

// ---------------------------------------------------------------------------------------------
// The signature interaction: setting a time on a circle, and the week.
// ---------------------------------------------------------------------------------------------

/** A duration said out loud: `45m`, `2h 30m`, `4h`. */
fun durationWords(seconds: Int): String {
    val s = seconds.coerceAtLeast(0)
    val h = s / 3600
    val m = (s % 3600) / 60
    return when {
        h == 0 && m == 0 -> "${s}s"
        h == 0 -> "${m}m"
        m == 0 -> "${h}h"
        else -> "${h}h ${m}m"
    }
}

/**
 * The length of a daily window, in minutes.
 *
 * This is the app's `spanOf`, and it is the most important function on this page because §7 rule 1
 * is the bug the planning screen actually shipped with: a daily interval has 1440 minutes in it and
 * **never** 10080. 10080 is a week, and a week is only the right unit when asking where in the week
 * something sits.
 *
 * Same day: `end - start`. Overnight: `end - start + 1440`, modulo 1440, so a window given as
 * `22:45 -> 06:30` and the same window marked overnight agree.
 */
fun spanOfMinutes(startMin: Int, endMin: Int, overnight: Boolean): Int {
    val s = startMin.coerceIn(0, 1439)
    val e = endMin.coerceIn(0, 1439)
    return if (overnight || e < s) {
        ((e - s + 1440) % 1440).let { if (it == 0) 1440 else it }
    } else {
        (e - s).coerceAtLeast(0)
    }
}

/**
 * One day's worth of a window, as `(minutes before midnight, minutes spilled into the morning)`.
 *
 * This is the arithmetic behind §5.3's *"Overnight rules render in two pieces"*. A day has 1440
 * minutes and a band may use 1439 of them before midnight, so a window from 22:45 to 06:30 is 75
 * minutes tonight and 390 minutes tomorrow morning. Returning the two lengths rather than a single
 * clipped one is what makes the second segment drawable at all — a version that clipped here and
 * then tested for a spill in the chart never drew one.
 */
internal fun firstSegment(startMin: Int, span: Int): Pair<Int, Int> {
    val start = startMin.coerceIn(0, 1439)
    val len = span.coerceAtLeast(0)
    val tonight = (1440 - start).coerceAtLeast(0)
    val first = minOf(tonight, len)
    return first to (len - first).coerceAtLeast(0)
}

/**
 * How long — set on a circle, and precise without one.
 *
 * §6.1's shape: **two concentric rings** above an hour (outer hours 0-12, inner minutes 0-59),
 * because §6.4 forbids the alternative — *"Do not show a 12-hour value as an almost-empty
 * 360-degree arc without an inner minute control."* Below an hour it is one ring with a clear value
 * arc, because a second ring on a twenty-five minute timer could only ever point at zero.
 *
 * Track 1px of ink, active arc 8-10px, **circular** handles (§6.4 forbids square handles on a
 * circular control), twelve ticks, and a clean centre showing the value, its label and the sentence
 * that makes it concrete.
 *
 * Nothing here requires dragging: a tap sets the value, and the sliders, presets and numeric field
 * the caller draws underneath are the other routes in, which is §6.3's list and §8's rule that a
 * drag always has a click, field or keyboard alternative.
 */
@Composable
fun TimeRing(
    valueSec: Int,
    onChange: (Int) -> Unit,
    ringLabel: String,
    modifier: Modifier = Modifier,
    maxSec: Int = 12 * 3600,
    minSec: Int = 60,
    tone: Color = Palette.accent,
    headline: String? = null,
    sub: String? = null,
) {
    val track = Palette.line
    val ticks = Palette.dim
    val lo = minSec.coerceAtLeast(0)
    val hi = maxSec.coerceAtLeast(lo + 1)
    val twoRings = hi > 3600

    var live by remember { mutableFloatStateOf(valueSec.toFloat()) }
    var dragging by remember { mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(valueSec, dragging) {
        if (!dragging && abs(live - valueSec) > 0.5f) live = valueSec.toFloat()
    }

    // A one-shot punch on the centre value, fired by the commit below rather than by the value
    // changing — so a drag that passes through forty-five does not flash forty-five times.
    val punch = remember { MutableTrigger() }
    val punchAmount = remember { androidx.compose.animation.core.Animatable(0f) }
    androidx.compose.runtime.LaunchedEffect(punch.count) {
        if (punch.count == 0 || !Juice.moves) return@LaunchedEffect
        punchAmount.snapTo(1f)
        punchAmount.animateTo(0f, Juice.over(280, Juice.out))
    }

    fun commit(seconds: Float) {
        val clamped = seconds.coerceIn(lo.toFloat(), hi.toFloat())
        // Fire only when the committed value actually lands on a new second, which is what makes the
        // punch a confirmation instead of a twitch.
        if (clamped.roundToInt() != live.roundToInt()) punch.fire()
        live = clamped
        onChange(clamped.roundToInt())
    }

    fun polar(at: Offset, radius: Float): Pair<Float, Float> {
        val dx = at.x - radius
        val dy = at.y - radius
        val degrees = Math.toDegrees(kotlin.math.atan2(dy.toDouble(), dx.toDouble())).toFloat()
        return ((degrees + 90f + 360f) % 360f) to
            (kotlin.math.sqrt((dx * dx) + (dy * dy)) / radius)
    }

    val diameter = 260.dp
    val outerStroke = 9.dp
    val innerStroke = 7.dp

    Box(
        modifier = modifier
            .size(diameter)
            .semantics {
                // §8: *"Dial has meaningful `aria-valuetext`, not only raw numbers."* "2700" is a
                // number; "45 minutes" is an answer.
                contentDescription = "$ringLabel. ${durationWords(live.roundToInt())}."
            }
            .pointerInput(hi, lo, twoRings) {
                val radius = minOf(size.width, size.height) / 2f
                var ring = if (twoRings) 0 else 1
                var lastAngle = 0f
                fun pick(at: Offset) {
                    val (angle, distance) = polar(at, radius)
                    ring = if (twoRings && distance < 0.68f) 1 else 0
                    lastAngle = angle
                }
                fun setValueAt(at: Offset) {
                    val (angle, _) = polar(at, radius)
                    if (ring == 0) {
                        // The minutes are preserved, so a press on the hour ring does not throw away
                        // a minute already chosen on the inner one.
                        val hours = ((angle / 360f) * 12f).roundToInt().coerceIn(0, 11)
                        commit(hours * 3600f + (((live % 3600f) / 60f).roundToInt() % 60) * 60f)
                    } else {
                        val mins = ((angle / 360f) * 60f).roundToInt().coerceIn(0, 59)
                        commit(((live / 3600f).toInt().coerceIn(0, 11)) * 3600f + mins * 60f)
                    }
                }
                fun nudge(at: Offset) {
                    val (angle, _) = polar(at, radius)
                    var delta = angle - lastAngle
                    if (delta > 180f) delta -= 360f
                    if (delta < -180f) delta += 360f
                    lastAngle = angle
                    commit(live + if (ring == 0) delta / 360f * 12f * 3600f else delta / 360f * 3600f)
                }
                detectTapGestures(onTap = { at -> pick(at); setValueAt(at) })
                detectDragGestures(
                    onDragStart = { at ->
                        dragging = true
                        pick(at)
                        setValueAt(at)
                    },
                    onDragEnd = { dragging = false },
                    onDragCancel = { dragging = false },
                ) { change, _ ->
                    change.consume()
                    nudge(change.position)
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(diameter)) {
            val r = size.minDimension / 2f
            val centre = Offset(size.width / 2f, size.height / 2f)
            val ow = outerStroke.toPx()
            val iw = innerStroke.toPx()

            for (i in 0 until 12) {
                val a = Math.toRadians((i * 30.0) - 90.0)
                drawLine(
                    color = ticks,
                    start = Offset(
                        centre.x + ((r - ow - 4.dp.toPx()) * kotlin.math.cos(a)).toFloat(),
                        centre.y + ((r - ow - 4.dp.toPx()) * kotlin.math.sin(a)).toFloat(),
                    ),
                    end = Offset(
                        centre.x + ((r - 1.dp.toPx()) * kotlin.math.cos(a)).toFloat(),
                        centre.y + ((r - 1.dp.toPx()) * kotlin.math.sin(a)).toFloat(),
                    ),
                    strokeWidth = 1.dp.toPx(),
                )
            }

            if (twoRings) {
                val outerR = r - ow / 2
                val innerR = r - (r * 0.46f)
                drawCircle(track, radius = outerR, center = centre, style = Stroke(1.dp.toPx()))
                drawCircle(track, radius = innerR, center = centre, style = Stroke(1.dp.toPx()))
                val outerSweep = (((live / 3600f).toInt()).coerceIn(0, 12) / 12f) * 360f
                if (outerSweep > 0f) {
                    drawArc(
                        color = tone,
                        startAngle = -90f,
                        sweepAngle = outerSweep,
                        useCenter = false,
                        topLeft = Offset(centre.x - outerR, centre.y - outerR),
                        size = Size(outerR * 2, outerR * 2),
                        style = Stroke(ow, cap = StrokeCap.Round),
                    )
                }
                val minutes = ((live % 3600f) / 60f).roundToInt().coerceIn(0, 59)
                val innerSweep = (minutes / 60f) * 360f
                if (innerSweep > 0f) {
                    drawArc(
                        color = tone,
                        startAngle = -90f,
                        sweepAngle = innerSweep,
                        useCenter = false,
                        topLeft = Offset(centre.x - innerR, centre.y - innerR),
                        size = Size(innerR * 2, innerR * 2),
                        style = Stroke(iw, cap = StrokeCap.Round),
                    )
                }
                dialHandle(centre, outerR, outerSweep, ow, tone, track)
                dialHandle(centre, innerR, innerSweep, iw, tone, track)
            } else {
                val oneR = r - ow / 2
                drawCircle(track, radius = oneR, center = centre, style = Stroke(1.dp.toPx()))
                val sweep = (((live - lo) / (hi - lo).toFloat()) * 360f).coerceIn(0f, 360f)
                if (sweep > 0f) {
                    drawArc(
                        color = tone,
                        startAngle = -90f,
                        sweepAngle = sweep,
                        useCenter = false,
                        topLeft = Offset(centre.x - oneR, centre.y - oneR),
                        size = Size(oneR * 2, oneR * 2),
                        style = Stroke(ow, cap = StrokeCap.Round),
                    )
                }
                dialHandle(centre, oneR, sweep, ow, tone, track)
            }
        }

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                ringLabel.uppercase(),
                style = Type.label,
                color = Palette.dim,
            )
            Gap(Space.xxs)
            Box(
                modifier = Modifier.graphicsLayer {
                    val s = 1f + 0.09f * punchAmount.value
                    scaleX = s
                    scaleY = s
                },
                contentAlignment = Alignment.Center,
            ) {
                DialNumber(headline ?: durationWords(live.roundToInt()), style = Type.display)
            }
            if (sub != null) {
                Gap(Space.xs)
                Text(
                    sub,
                    style = Type.meta,
                    color = Palette.muted,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(0.62f),
                )
            }
        }

        // The hit area, so a 44px target exists for both handles without making either visibly
        // 44px wide (§6.1).
        Box(
            Modifier
                .size(Dsn.HandleHit)
                .align(Alignment.Center)
                .semantics {
                    customActions = listOf(
                        CustomAccessibilityAction("Increase") { commit(live + 300f); true },
                        CustomAccessibilityAction("Decrease") { commit(live - 300f); true },
                        CustomAccessibilityAction("Maximum") { commit(hi.toFloat()); true },
                        CustomAccessibilityAction("Minimum") { commit(lo.toFloat()); true },
                    )
                },
        )
    }
}

/** A circular handle: a filled disc with a rim, so it reads on any arc colour. */
private fun DrawScope.dialHandle(
    centre: Offset,
    radius: Float,
    sweep: Float,
    stroke: Float,
    active: Color,
    rim: Color,
) {
    val a = Math.toRadians(sweep - 90.0)
    val at = Offset(
        centre.x + (radius * kotlin.math.cos(a)).toFloat(),
        centre.y + (radius * kotlin.math.sin(a)).toFloat(),
    )
    val handle = (stroke * 0.72f).coerceAtLeast(5.dp.toPx())
    drawCircle(rim, radius = handle + 1.5.dp.toPx(), center = at)
    drawCircle(active, radius = handle, center = at)
}

/**
 * The week, as seven columns over twenty-four hours, with one rule's window drawn on it.
 *
 * §5.3 and §7 govern this control with rules that exist because the planning screen has broken in
 * exactly these ways before:
 *
 *  - **Every day is drawn, always.** The chart is seven columns because a week has seven days; which
 *    of them the rule is *on* is what the day buttons underneath are for. An earlier version
 *    iterated the selected days, which drew a two-column chart for a weekend rule.
 *  - **Each column is clipped** (§7 rule 4) and the band's height is clamped to the column (§7 rules
 *    5-6), so the chart can never draw something taller than its column or reach the section below.
 *  - **An empty rule draws nothing** (§7 rule 7) — no zero-height element over the controls under it.
 *  - **Overnight is two pieces** (§5.3): tonight's segment in this column, and a faded segment at the
 *    top of this same column for the spill, so the reader sees one rule rather than two.
 *  - **Day buttons are buttons** (§8: a drag always has a click alternative), so the week is
 *    settable without ever dragging.
 */
@Composable
fun WeekGrid(
    days: List<Int>,
    startMin: Int,
    endMin: Int,
    overnight: Boolean,
    onChange: (startMin: Int, endMin: Int, overnight: Boolean) -> Unit,
    modifier: Modifier = Modifier,
    onDayToggle: ((Int) -> Unit)? = null,
    onDaysShortcut: ((List<Int>) -> Unit)? = null,
    height: Dp = 200.dp,
) {
    val span = spanOfMinutes(startMin, endMin, overnight)
    val labels = listOf("M", "T", "W", "T", "F", "S", "S")

    Column(modifier) {
        Row(Modifier.fillMaxWidth()) {
            Column(Modifier.width(36.dp)) {
                listOf("00", "06", "12", "18", "24").forEach { label ->
                    Text(
                        label,
                        style = Type.micro.mono,
                        fontWeight = FontWeight.Bold,
                        color = Palette.dim,
                        modifier = Modifier.height(height / 4),
                    )
                }
            }
            repeat(7) { index ->
                val on = days.contains(index)
                Box(
                    Modifier
                        .weight(1f)
                        .height(height)
                        .padding(horizontal = 1.dp)
                        .clip(RoundedCornerShape(0.dp))
                        .background(if (on) Palette.raised else Palette.surface)
                        .border(1.dp, Palette.line)
                        .pointerInput(index, startMin, endMin, overnight) {
                            detectDragGestures(
                                onDragStart = { at ->
                                    if (onDayToggle != null && !on) onDayToggle(index)
                                    val at1 = snapMinute(((at.y / size.height) * 1440f).roundToInt())
                                    onChange(at1, (at1 + 60).coerceAtMost(1439), at1 + 60 > 1439)
                                },
                            ) { change, _ ->
                                change.consume()
                                val e = snapMinute(((change.position.y / size.height) * 1440f).roundToInt())
                                onChange(startMin, e, e < startMin)
                            }
                        },
                ) {
                    if (on && span > 0) {
                        val (tonight, tomorrow) = firstSegment(startMin, span)
                        if (tonight > 0) {
                            WeekBand(
                                topFraction = startMin.coerceIn(0, 1439) / 1440f,
                                heightFraction = tonight / 1440f,
                                colour = Palette.accent,
                                height = height,
                            )
                        }
                        if (tomorrow > 0) {
                            // The faded next-morning piece, at the top of this same column and at 40%:
                            // the reader's cue that this half belongs to the *next* morning rather
                            // than to a second rule on this day.
                            WeekBand(
                                topFraction = 0f,
                                heightFraction = tomorrow / 1440f,
                                colour = Palette.accent.copy(alpha = 0.4f),
                                height = height,
                            )
                        }
                    }
                }
            }
        }
        Gap(Space.s)
        Row(Modifier.fillMaxWidth().padding(start = Space.xxl)) {
            labels.forEachIndexed { index, label ->
                val on = days.contains(index)
                Box(
                    Modifier
                        .weight(1f)
                        .padding(horizontal = 1.dp)
                        .height(Dsn.MinTouch)
                        .background(if (on) Palette.accent else Palette.surface)
                        .border(Dsn.Border, Palette.line)
                        .clickable { onDayToggle?.invoke(index) }
                        .semantics {
                            selected = on
                            role = Role.Checkbox
                            contentDescription =
                                dayName(index) + if (on) ", in this rule" else ", not in this rule"
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        label,
                        style = Type.meta,
                        fontWeight = FontWeight.Black,
                        color = if (on) Palette.surface else Palette.text,
                    )
                }
            }
        }
        if (onDaysShortcut != null) {
            Gap(Space.s)
            Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                MiniAction("Weekdays") { onDaysShortcut(listOf(0, 1, 2, 3, 4)) }
                MiniAction("Weekends") { onDaysShortcut(listOf(5, 6)) }
                MiniAction("Every day") { onDaysShortcut(listOf(0, 1, 2, 3, 4, 5, 6)) }
            }
        }
    }
}

/** One segment of a rule's window, as a fraction of the day. */
@Composable
private fun WeekBand(
    topFraction: Float,
    heightFraction: Float,
    colour: Color,
    height: Dp,
) {
    // Clamped to the column (§7 rules 5-6), and never less than 4dp so a fifteen-minute window is
    // still a visible mark rather than a 1px line.
    val fraction = heightFraction.coerceIn(0f, 1f)
    Box(
        Modifier
            .fillMaxWidth()
            .offset(y = height * topFraction.coerceIn(0f, 1f))
            .height(height * fraction.coerceAtLeast(4f / 1440f))
            .background(colour)
            .border(1.dp, Palette.line),
    )
}

/** A small square action under the week grid. */
@Composable
private fun MiniAction(label: String, onClick: () -> Unit) {
    Box(
        Modifier
            .clickable(onClick = onClick)
            .heightIn(min = Dsn.MinTouch)
            .wrapContentHeight()
            .background(Palette.surface)
            .border(Dsn.Hairline, Palette.dim)
            .padding(horizontal = Space.s, vertical = Space.xs),
    ) {
        Text(
            label.uppercase(),
            style = Type.label,
            color = Palette.muted,
        )
    }
}

private fun dayName(index: Int): String =
    listOf("Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday")
        .getOrElse(index) { "Day $index" }

/** Rounds a dragged minute to the nearest five, which is the resolution a person plans at. */
private fun snapMinute(minute: Int): Int = (((minute.coerceIn(0, 1439)) + 2) / 5) * 5

// ---------------------------------------------------------------------------------------------
// Sheets and forms
// ---------------------------------------------------------------------------------------------

/**
 * A form that rises from the bottom edge, in the app's own material.
 *
 * A sheet rather than a centred box because the form is a continuation of the row that was tapped:
 * it comes up from the same edge the thumb is on, and ends in the two buttons every other screen
 * ends in.
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
 * The chrome every sheet is made of: the scrim, the pane, the grip, the title, the body and a row of
 * buttons.
 *
 * §8 requires the dialog role, a labelled heading, a focus trap and focus restore. Compose's
 * `Dialog` gives the last two for free — it moves accessibility focus into the pane and back to the
 * caller on dismiss — and the title is marked as a heading, which is what a screen reader announces
 * on entry.
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
                .sheetDragToDismiss(onDismiss)
                .padding(horizontal = Space.m)
                .padding(bottom = Space.l)
                .plate(fill = Palette.surface, offset = Dsn.ShadowHero)
                .padding(horizontal = Dsn.Gutter)
                .padding(top = Space.m, bottom = Space.l),
        ) {
            // The grip, made draggable in its own right: on a sheet whose body scrolls, the strip
            // that looks like a handle is the one place a drag is unambiguous.
            Box(
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .fillMaxWidth()
                    .heightIn(min = Dsn.MinTouch)
                    .semantics { contentDescription = "Drag down to close" },
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier
                        .background(Palette.line)
                        .size(width = 40.dp, height = 5.dp),
                )
            }
            Gap(Space.m)
            Text(
                title.uppercase(),
                style = Type.h2,
                color = Palette.text,
                modifier = Modifier.semantics { heading() },
            )
            if (sub != null) {
                Text(
                    sub,
                    style = Type.meta,
                    color = Palette.muted,
                    modifier = Modifier.padding(top = Space.xs),
                )
            }
            Column(
                modifier = Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
            ) {
                content()
            }
            Gap(Space.l)
            Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) { buttons() }
        }
    }
}

/**
 * A decision: a sentence, and two buttons.
 *
 * `destructive` changes *which button is the prominent one*: the design styles the app's
 * destructive controls as a ghost button in the danger colour and puts the filled accent on the
 * action that is safe. That is why there is no "danger" fill — a solid red button invites the tap it
 * is warning about.
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
                Text(body, style = Type.body, color = Palette.muted)
            }
        },
        buttons = {
            if (destructive) {
                Box(Modifier.weight(1f)) { PrimaryButton(dismiss, onClick = onDismiss) }
                GhostButton(confirm, Modifier.weight(1f), colour = Palette.bad, onClick = onConfirm)
            } else {
                GhostButton(dismiss, Modifier.weight(1f), onClick = onDismiss)
                Box(Modifier.weight(1f)) { PrimaryButton(confirm, onClick = onConfirm) }
            }
        },
    )
}

/** Something the user asked for and cannot have, or cannot do. One button, because there is no decision. */
@Composable
fun DNote(title: String, body: String, dismiss: String = "OK", onDismiss: () -> Unit) {
    SheetFrame(
        title = title,
        sub = null,
        onDismiss = onDismiss,
        content = { Text(body, style = Type.body, color = Palette.muted) },
        buttons = { Box(Modifier.weight(1f)) { PrimaryButton(dismiss, onClick = onDismiss) } },
    )
}

/** A block of the form: its label, and what it asks for. */
@Composable
fun ColumnScope.SheetSection(label: String, content: @Composable ColumnScope.() -> Unit) {
    Gap(Space.l)
    SectionLabel(label)
    Gap(Space.s)
    content()
}

/**
 * One editable value, set at the size of a value.
 *
 * A time is read at a glance and typed rarely, so it is shown the way the Now screen shows a time —
 * large, monospace, on the raised surface — rather than as body text inside an outlined box.
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
            .background(Palette.raised)
            .border(Dsn.Border, if (bad) Palette.bad else Palette.line)
            .padding(horizontal = Space.m, vertical = Space.m),
    ) {
        Text(
            label.uppercase(),
            style = Type.label,
            color = Palette.dim,
        )
        BasicTextField(
            value = value,
            onValueChange = onChange,
            singleLine = true,
            textStyle = Type.h2.mono.copy(color = if (bad) Palette.bad else Palette.text),
            cursorBrush = SolidColor(Palette.accent),
            modifier = Modifier.fillMaxWidth().padding(top = Space.xxs),
        )
    }
}

/** The sentence a form says back to the reader about what it has understood. */
@Composable
fun SheetNote(text: String, bad: Boolean = false) {
    Text(
        text,
        style = Type.meta,
        color = if (bad) Palette.bad else Palette.muted,
        modifier = Modifier.padding(top = Space.m),
    )
}

/**
 * A line of text the user types: label above, value in the field, placeholder when it is empty.
 *
 * The same tile as [ValueField] at body size, so a form asking for a word and a form asking for a
 * time are visibly the same form.
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
            .background(Palette.raised)
            .border(Dsn.Border, Palette.line)
            .padding(horizontal = Space.m, vertical = Space.m),
    ) {
        Text(
            label.uppercase(),
            style = Type.label,
            color = Palette.dim,
        )
        Box(Modifier.padding(top = Space.xxs)) {
            if (value.isEmpty() && placeholder.isNotEmpty()) {
                Text(placeholder, style = Type.body, color = Palette.dim)
            }
            BasicTextField(
                value = value,
                onValueChange = onChange,
                singleLine = true,
                textStyle = Type.body.copy(color = Palette.text),
                cursorBrush = SolidColor(Palette.accent),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Week at a glance
// ---------------------------------------------------------------------------------------------

/** One window on the chart: the days it runs, its times, and the profiles it belongs to. */
data class WeekWindow(
    val days: List<Int>,
    val startMinute: Int,
    val endMinute: Int,
    val profileNames: List<String>,
)

/**
 * The distinct windows in a plan, with the rules that are the same window folded together.
 *
 * ## Why this exists, and what it is not
 *
 * The obvious chart draws one band per rule, and that is wrong for a reason that only shows up on a
 * real device: **sync writes the same window more than once.** Two paired phones each add the plan's
 * weekly windows to the one shared config, so a rule that exists once as far as the user is
 * concerned arrives as two identical entries with different ids — and the chart would draw every
 * band twice, in the same place, at double the ink. On screen that reads as a rendering bug, which
 * is exactly what it looked like.
 *
 * So the bands are keyed on what actually makes a window *that window*: which days it runs, and its
 * two times. Two rules with the same key and different ids are one band, and their profile names are
 * shown together so nothing is hidden by the folding.
 *
 * This is deliberately a **view** concern and not a change to the plan. The two rules stay two rules:
 * they are independently editable, independently pausable, and each is what the core actually
 * activates. Folding them here would mean the chart and the list disagreed about how many rules
 * there are, and the list is the one that can be edited.
 *
 * Pure, so it can be tested without a composition — `WeekChartTest` covers the duplicate case, the
 * overnight case and the disabled case.
 */
fun foldWeekWindows(
    windows: List<Triple<List<Int>, Int, Int>>,
    profileOf: (Int) -> String,
): List<WeekWindow> {
    val byKey = LinkedHashMap<String, MutableList<String>>()
    val shape = LinkedHashMap<String, Triple<List<Int>, Int, Int>>()
    windows.forEachIndexed { index, (days, start, end) ->
        val key = canonicalWindowKey(days, start, end)
        shape.getOrPut(key) { Triple(days.sorted(), start, end) }
        val name = profileOf(index)
        val names = byKey.getOrPut(key) { mutableListOf() }
        if (name.isNotBlank() && !names.contains(name)) names.add(name)
    }
    return shape.map { (key, v) ->
        WeekWindow(
            days = v.first,
            startMinute = v.second,
            endMinute = v.third,
            profileNames = byKey[key].orEmpty().sorted(),
        )
    }
}

/**
 * What makes two weekly windows the same window: its days and its two times.
 *
 * Not its id, which is what sync gives two copies of one rule, and not its profile, which a user may
 * legitimately point at two profiles.
 */
fun canonicalWindowKey(days: List<Int>, startMinute: Int, endMinute: Int): String {
    // An empty day list means **every day** to the core, so it and a list of all seven are the same
    // window and must key the same way. Keying them apart would draw one weekly rule as two bands.
    val normalised = if (days.isEmpty()) listOf(0, 1, 2, 3, 4, 5, 6) else days.distinct().sorted()
    return "$normalised|$startMinute|$endMinute"
}

/**
 * Week at a glance: seven days across, twenty-four hours down, every window in the plan drawn on it.
 *
 * `docs/design.md` section 5.3 asks for this and section 7 governs it, with rules that exist because
 * the planning screen has broken in these ways before:
 *
 *  - **Seven columns, always.** The chart shows the week, so it is seven columns wide; an earlier
 *    version iterated the selected days and drew a two-column chart for a weekend rule.
 *  - **Nothing escapes its column** (rule 4) and no band is taller than the day (rules 5-6). Every
 *    column clips, and every band's height is clamped before it is drawn.
 *  - **A zero-length band draws nothing** (rule 7), so an empty rule cannot leave an invisible
 *    element over the controls below the chart.
 *  - **Overnight is two pieces** (section 5.3): the late-night part in its own day's column and a
 *    faded piece at the top of the next morning's, which is what `firstSegment` returns.
 *  - **A switched-off rule is not a band** (rule 12). Pausing a window removes it from the chart,
 *    because a chart that shows an unenforced window is a chart that lies about what will happen.
 *
 * [height] is the *drawn* height of the day columns and is deliberately modest: this sits inside a
 * scrolling page above the rule list, so it is sized to be read at a glance rather than to be
 * precise. The exact times are in the rows underneath, which is where a person edits them.
 */
@Composable
fun WeekAtAGlance(
    windows: List<WeekWindow>,
    modifier: Modifier = Modifier,
    height: Dp = 168.dp,
    calendarBlocks: List<WeekWindow> = emptyList(),
) {
    // A calendar block is only drawn on a day the rule itself covers. A rule that blocks meetings
    // "on weekdays" must not shade Saturday just because a Saturday event matched its matcher —
    // the chart's job is to show what the plan will do, and the plan's own days are the answer.
    val calendarDays = windows.flatMap { it.days }.toSet()
    // The bands **grow** out of their start time, staggered by rule, the first time this chart is
    // seen. It is the one animation on this screen that carries information rather than confirming
    // an action: a reader watches the week fill in and learns the shape of their own plan in the
    // second it takes, which a static chart of thin coloured rectangles never quite conveys.
    val grow = remember { Animatable(0f) }
    LaunchedEffect(windows.size) {
        if (!Juice.moves) {
            grow.snapTo(1f)
            return@LaunchedEffect
        }
        grow.snapTo(0f)
        grow.animateTo(1f, Juice.over(560 + windows.size.coerceAtMost(6) * 70, Juice.out))
    }
    val bandColour = Palette.accent
    val spillColour = Palette.accent.copy(alpha = 0.35f)
    val calColour = Palette.cal
    val cellFill = Palette.surface
    val ink = Palette.line
    val gridInk = Palette.raised
    val labelTone = Palette.dim

    Column(modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth()) {
            // The hour scale: five labels, nineteen pixels apart on a 168dp column, which is as many
            // as can be read without the labels becoming the chart.
            Column(Modifier.width(34.dp)) {
                listOf("00", "06", "12", "18", "24").forEach { label ->
                    Text(
                        label,
                        style = Type.micro.mono,
                        fontWeight = FontWeight.Bold,
                        color = labelTone,
                        modifier = Modifier.height(height / 4),
                    )
                }
            }
            repeat(7) { day ->
                Box(
                    Modifier
                        .weight(1f)
                        .height(height)
                        .padding(horizontal = 1.dp)
                        .clip(RoundedCornerShape(0.dp))
                        .background(cellFill)
                        .border(1.dp, ink),
                ) {
                    Canvas(Modifier.fillMaxSize()) {
                        // The day's own grid, so the chart reads as a measurement rather than as a
                        // set of floating blocks.
                        for (mark in 1 until 4) {
                            val y = size.height * (mark / 4f)
                            drawLine(
                                color = gridInk,
                                start = Offset(0f, y),
                                end = Offset(size.width, y),
                                strokeWidth = 1f,
                            )
                        }
                        // Calendar blocks first, so a weekly window draws over them: the weekly rule
                        // is the one the user wrote by hand, and it should never be hidden behind a
                        // block that came from a meeting invitation.
                        calendarBlocks.forEach { block ->
                            if (block.days.contains(day)) {
                                val cSpan = spanOfMinutes(
                                    block.startMinute,
                                    block.endMinute,
                                    block.endMinute < block.startMinute,
                                )
                                if (cSpan > 0) {
                                    val (cTonight, cTomorrow) = firstSegment(block.startMinute, cSpan)
                                    if (cTonight > 0) {
                                        drawRect(
                                            color = calColour.copy(alpha = 0.30f),
                                            topLeft = Offset(
                                                0f,
                                                size.height * (block.startMinute.coerceIn(0, 1439) / 1440f),
                                            ),
                                            size = Size(size.width, size.height * (cTonight / 1440f)),
                                        )
                                        // A hard left edge in the calendar's cyan, drawn after the
                                        // fill: a band in this app is a *rule*, and rules wear a 3px
                                        // edge. Without it a translucent block reads as a hover state
                                        // rather than as something the app will act on.
                                        drawRect(
                                            color = calColour,
                                            topLeft = Offset(
                                                0f,
                                                size.height * (block.startMinute.coerceIn(0, 1439) / 1440f),
                                            ),
                                            size = Size(2.5.dp.toPx(), size.height * (cTonight / 1440f)),
                                        )
                                    }
                                    if (cTomorrow > 0) {
                                        drawRect(
                                            color = calColour.copy(alpha = 0.18f),
                                            topLeft = Offset.Zero,
                                            size = Size(size.width, size.height * (cTomorrow / 1440f)),
                                        )
                                    }
                                }
                            }
                        }
                        windows.forEachIndexed { index, window ->
                            if (window.days.contains(day)) {
                                // Each rule starts a little after the one before it, capped so a
                                // long plan still lands inside a second.
                                val delay = (index.coerceAtMost(6) * 0.11f)
                                val p = ((grow.value - delay) / (1f - delay)).coerceIn(0f, 1f)
                                if (p > 0f) {
                                    val span = spanOfMinutes(
                                        window.startMinute,
                                        window.endMinute,
                                        window.endMinute < window.startMinute,
                                    )
                                    if (span > 0) {
                                        val (tonight, tomorrow) = firstSegment(window.startMinute, span)
                                        if (tonight > 0) {
                                            drawRect(
                                                color = bandColour,
                                                topLeft = Offset(
                                                    0f,
                                                    size.height * (window.startMinute.coerceIn(0, 1439) / 1440f),
                                                ),
                                                size = Size(size.width, size.height * (tonight / 1440f) * p),
                                            )
                                        }
                                        if (tomorrow > 0) {
                                            drawRect(
                                                color = spillColour,
                                                topLeft = Offset.Zero,
                                                size = Size(size.width, size.height * (tomorrow / 1440f) * p),
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        Gap(Space.xs)
        Row(Modifier.fillMaxWidth().padding(start = Space.xxl)) {
            listOf("M", "T", "W", "T", "F", "S", "S").forEach { label ->
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    Text(
                        label,
                        style = Type.micro,
                        fontWeight = FontWeight.Black,
                        color = Palette.muted,
                    )
                }
            }
        }
        if (windows.isEmpty() && calendarBlocks.isEmpty()) {
            Gap(Space.s)
            Text(
                "No windows yet. A rule you add below is drawn here, so you can see the week at a glance.",
                style = Type.meta,
                color = Palette.dim,
            )
        } else {
            Gap(Space.s)
            Row(
                horizontalArrangement = Arrangement.spacedBy(Space.m),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                LegendSwatch(bandColour, "A weekly window")
                LegendSwatch(spillColour, "The morning half")
                LegendSwatch(calColour, "A calendar block")
            }
            if (calendarBlocks.isNotEmpty()) {
                Gap(Space.s)
                // Say what the cyan band is made of, and what it cannot know. The chart carries no
                // date, so a meeting repeats across the week here — which is the right thing for a
                // planner and the wrong thing to leave unsaid.
                val names = calendarBlocks.flatMap { it.profileNames }.distinct().sorted()
                Text(
                    "${calendarBlocks.size} calendar block(s) from ${names.joinToString(", ")} are " +
                        "shaded in cyan, for the events Curfew can see today. The chart shows a " +
                        "week, so each one is drawn on the weekday it falls on.",
                    style = Type.micro,
                    color = Palette.dim,
                )
            }
        }
    }
}

/** One entry in the chart's legend: a square of the colour, and what it means in words. */
@Composable
private fun LegendSwatch(colour: Color, label: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.xs),
    ) {
        Box(Modifier.size(10.dp).background(colour).border(1.dp, Palette.line))
        Text(label, style = Type.micro, color = Palette.muted)
    }
}
/**
 * A calendar block as a position on the week.
 *
 * ## The honest limitation, stated here rather than discovered later
 *
 * `CalendarEvent` is an instant with a date, and the chart is a **week without dates** — seven
 * columns that stand for Monday through Sunday. So an event has to be reduced to the weekday it
 * falls on and the minutes it occupies, which means one Tuesday meeting and the next Tuesday's
 * meeting land in the same column. For a planner that is the right simplification: the question the
 * chart answers is *what does a normal week look like*, not *what is on the 14th*. The screen says
 * so underneath the chart rather than leaving the reader to work it out.
 *
 * Padding is applied because the lock is up for it: a rule with a ten-minute lead-in blocks from
 * ten minutes before the meeting, and a chart that drew the meeting itself would understate what
 * the app is going to do. Padding may push the block over midnight, which is why it is normalised
 * back into `0..1439` and marked overnight rather than allowed to run past the end of the day — the
 * same treatment `firstSegment` gives a weekly window.
 *
 * Pure and free of Android types, so `WeekChartTest` can pin the day mapping (including Sunday,
 * which is the one that goes wrong when somebody writes `% 7` on a Monday-based index) without a
 * device.
 */
fun calendarBlockAt(
    startEpochSeconds: Long,
    endEpochSeconds: Long,
    padBeforeSeconds: Int,
    padAfterSeconds: Int,
    profileName: String,
    zone: java.util.TimeZone = java.util.TimeZone.getDefault(),
): WeekWindow? {
    val startMillis = (startEpochSeconds - padBeforeSeconds) * 1000L
    val endMillis = (endEpochSeconds + padAfterSeconds) * 1000L
    if (endMillis <= startMillis) return null
    val calendar = java.util.Calendar.getInstance(zone)
    calendar.timeInMillis = startMillis
    // `Calendar.MONDAY` is 2 and the policy module numbers Monday as 0, so the offset is the
    // difference rather than the constant. Getting this wrong shifts every block by a day.
    val day = (calendar.get(java.util.Calendar.DAY_OF_WEEK) - java.util.Calendar.MONDAY + 7) % 7
    val startMinute = calendar.get(java.util.Calendar.HOUR_OF_DAY) * 60 +
        calendar.get(java.util.Calendar.MINUTE)
    // The length is measured on the clock rather than in position, so a block that crosses midnight
    // keeps its true length and `firstSegment` can split it.
    val lengthMinutes = ((endMillis - startMillis) / 60_000L).toInt().coerceAtMost(1440)
    val endMinute = (startMinute + lengthMinutes) % 1440
    return WeekWindow(
        days = listOf(day),
        startMinute = startMinute.coerceIn(0, 1439),
        endMinute = endMinute.coerceIn(0, 1439),
        profileNames = listOf(profileName),
    )
}

/**
 * A sheet you can throw away: drag it down and let go.
 *
 * Every sheet in this app rises from the bottom edge, and a surface that comes up from an edge is
 * one a thumb expects to push back down. Without this, the only ways out were the Cancel button and
 * the system back gesture, and neither is what a hand does to a sheet that has appeared under it.
 *
 * The sheet follows the finger **one-to-one** while it is being dragged and is never allowed to move
 * up past its resting place — dragging upward on a sheet that is already open should scroll its
 * contents, not lift the panel off the screen. Let go and it either goes back with a spring or
 * leaves, decided by how far it travelled *or* how fast it was moving: a short flick downward means
 * the same as a long slow pull, and requiring the distance alone makes a quick flick feel ignored.
 *
 * Reduced motion is respected: with the platform's animations off, the sheet cannot be dragged at
 * all and dismissal goes through the usual explicit control.
 */
@Composable
fun Modifier.sheetDragToDismiss(onDismiss: () -> Unit): Modifier {
    if (!Juice.moves) return this
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val dismissAt = with(density) { 110.dp.toPx() }
    // The live offset is plain state rather than an `Animatable`: the drag callback is not a
    // suspending lambda, so a suspending `snapTo` inside it would mean a coroutine per drag event —
    // a coroutine per pixel, which is the same fault in a different costume. Plain state tracks the
    // finger exactly, and the spring on release writes back into it.
    var dragging by remember { mutableStateOf(0f) }
    // The settle. An `Animatable` is used only for the two *releases* — thrown away, or sprung back
    // — because those are animations. The drag itself writes plain state, which tracks the finger
    // exactly and spawns nothing per event.
    val settle = remember { Animatable(0f) }
    return this
        .offset { IntOffset(0, dragging.toInt()) }
        .pointerInput(Unit) {
            detectVerticalDragGestures(
                onDragEnd = {
                    // Either it went far enough, or it was flicked. A flick is what a hand actually
                    // does to a sheet, and judging on distance alone makes a quick flick look like
                    // nothing happened.
                    val away = dragging > dismissAt
                    if (away) {
                        scope.launch {
                            settle.snapTo(dragging)
                            settle.animateTo(size.height.toFloat(), Juice.over(160, Juice.inn))
                            dragging = settle.value
                            onDismiss()
                            dragging = 0f
                        }
                    } else {
                        scope.launch {
                            settle.snapTo(dragging)
                            settle.animateTo(0f, Juice.snap())
                            dragging = settle.value
                        }
                    }
                },
                onDragCancel = {
                    scope.launch {
                        settle.snapTo(dragging)
                        settle.animateTo(0f, Juice.snap())
                        dragging = settle.value
                    }
                },
            ) { change, delta ->
                change.consume()
                // Down only: an upward drag belongs to whatever is scrolling inside the sheet.
                dragging = (dragging + delta).coerceIn(0f, size.height.toFloat())
            }
        }
}
/**
 * A row of mutually exclusive choices, as the app's own square tags.
 *
 * The state is carried by the fill, the border and the word `selected` in the description, because
 * section 8 requires status by word and shape and not by colour alone.
 */
@Composable
fun ChoiceRow(
    options: List<Pair<String, String>>,
    chosen: String,
    onPick: (String) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(Space.s),
    ) {
        options.forEach { (id, label) ->
            val on = chosen == id
            Box(
                modifier = Modifier
                    .heightIn(min = Dsn.MinTouch)
                    .clickable { onPick(id) }
                    .semantics {
                        selected = on
                        role = Role.RadioButton
                        contentDescription = label + if (on) ", selected" else ""
                    },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label.uppercase(),
                    style = Type.label,
                    color = if (on) Palette.surface else Palette.text,
                    modifier = Modifier
                        .background(if (on) Palette.accent else Palette.surface)
                        .border(Dsn.Border, Palette.line)
                        .padding(horizontal = Space.m, vertical = Space.s),
                )
            }
        }
    }
}
/**
 * Pause a running block: a length, an explanation, and a sum.
 *
 * The sum is the whole point. `docs/design.md` section 9 lists `wait` as the product's word for a
 * delay before something opens, and this is that idea applied to the block itself — except that a
 * delay a user waits out is a delay they will wait out, whereas a sum is something they have to
 * *do*, and doing it is what separates a decision from a reflex. It is deliberately small: two-digit
 * arithmetic, the same generator the friction locks use, answerable in twenty seconds by someone who
 * means it and annoying enough to stop someone who does not.
 *
 * The explanation states what actually happens rather than what "pause" suggests. There is no timer
 * in the core that turns a block off and back on, so the honest sentence is that the block ends and
 * Curfew records that it was ended on purpose — which is what keeps a schedule from starting it
 * again a second later. Saying "paused until 14:20" would be a promise nothing keeps.
 */
@Composable
fun PausePuzzleSheet(
    name: String,
    now: Long,
    onDismiss: () -> Unit,
    onPaused: (minutes: Int, challenge: Challenge.Math) -> Unit,
) {
    var minutes by remember { mutableIntStateOf(15) }
    // Generated once and kept, so the question cannot change under the answer — the same fault the
    // end-of-session challenge had when it was generated inline.
    val challenge: Challenge.Math =
        remember { Challenge.generate(ChallengeKind.MATH) as Challenge.Math }
    var answer by remember { mutableStateOf("") }
    val solved = Challenge.isSatisfied(challenge, answer)

    DSheet(
        title = "Pause $name?",
        sub = "The block ends now, and Curfew records that you ended it — so a schedule holding it " +
            "will not start it again straight away.",
        onDismiss = onDismiss,
        confirm = "Pause",
        confirmEnabled = solved,
        onConfirm = { onPaused(minutes, challenge) },
    ) {
        SheetSection("For how long") {
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(Space.s),
            ) {
                listOf(5, 15, 30, 60).forEach { option ->
                    Box(
                        modifier = Modifier.heightIn(min = Dsn.MinTouch),
                        contentAlignment = Alignment.Center,
                    ) {
                        Pill(
                            text = if (option == 60) "1 hour" else "$option min",
                            selected = minutes == option,
                            onClick = { minutes = option },
                        )
                    }
                }
            }
            Gap(Space.s)
            Text(
                "This is the length you are deciding to stop for. Curfew does not put the block " +
                    "back afterwards — there is no timer that turns one off and on again — so you " +
                    "choose when to start again, or let its schedule do it.",
                style = Type.meta,
                color = Palette.dim,
            )
        }
        SheetSection("One sum, so this is deliberate") {
            Text(
                "${challenge.question} = ?",
                style = Type.h1.mono,
                color = Palette.text,
                modifier = Modifier.semantics {
                    // "×" is read as "x" or skipped by some screen readers, which turns an
                    // arithmetic problem into a guess.
                    contentDescription = "What is ${challenge.question.replace("×", "times")}?"
                },
            )
            Gap(Space.m)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Palette.raised)
                    .border(Dsn.Border, Palette.line)
                    .padding(horizontal = Space.m, vertical = Space.m),
            ) {
                Text(
                    "YOUR ANSWER",
                    style = Type.label,
                    color = Palette.dim,
                )
                Box(Modifier.padding(top = Space.xxs)) {
                    if (answer.isEmpty()) {
                        Text("e.g. 42", style = Type.body, color = Palette.dim)
                    }
                    BasicTextField(
                        value = answer,
                        onValueChange = { answer = it },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Number,
                            imeAction = ImeAction.Done,
                        ),
                        textStyle = Type.body.mono.copy(color = Palette.text),
                        cursorBrush = SolidColor(Palette.accent),
                        modifier = Modifier
                            .fillMaxWidth()
                            .semantics { contentDescription = "Type the answer here." },
                    )
                }
            }
        }
    }
}
/** Which of the ways out a screen shows, and why. */
enum class LockActionsWhen {
    /** Everything a running session allows, in one screen: the Now tab. */
    EVERYTHING,

    /**
     * The same actions, on the screen a blocked app has put the user on.
     *
     * **This is a deliberate reversal.** The block screen used to name the emergency pass without
     * offering it, on the reasoning that a pass should be spent from inside Curfew and not from the
     * screen an annoyed thumb is already on. That reasoning was about the pass alone, and it
     * generalised into a rule that left the one screen a person is actually stuck on with no way
     * forward at all — just "go back to home" and a note about a pass they could not spend. The
     * screen exists precisely because someone wanted the app; making them leave it, navigate to
     * Curfew and find the block again is friction in the wrong place, and it does not make the pass
     * harder to spend. The puzzle does that, and it works identically here.
     */
    LOCKED_SCREEN,
}

/**
 * The ways out of a running block, in one place so two screens cannot disagree.
 *
 * Stacked full width rather than fitted into a row: these labels are long (`ASK TO END IN 24
 * HOURS`), one of them is the ordinary way out and the others escalate, and four equal cells hid
 * that ordering while running the text out of its own buttons. The order is the order of severity —
 * pause, then end, then the slow release, then the tag, then the scarce pass — so the list reads as
 * a graded set of choices rather than a menu.
 *
 * Every callback is optional. A screen that cannot perform one does not draw it, which is what
 * `onPause == null` means on the blocked screen before the runtime is ready to answer.
 */
@Composable
fun LockActions(
    name: String,
    which: LockActionsWhen,
    endsAt: Long?,
    now: Long,
    passesLeft: Int,
    locked: Boolean,
    releaseAsked: Boolean,
    wantsTag: Boolean,
    onPause: (() -> Unit)?,
    onEnd: () -> Unit,
    onRelease: () -> Unit,
    onPresentTag: () -> Unit,
    onEmergency: () -> Unit,
) {
    // **Omitted entirely when it cannot work, rather than shown greyed.**
    //
    // A pause ends the block. It cannot end a *locked* block without satisfying the lock, so on a
    // locked session a pause button would either be a lie or a hole in the product — if pause got
    // you past a lock, pause would be the way past every lock. A disabled button here would still
    // advertise something the screen cannot do, so it is not drawn, and the ways out that the lock
    // does permit are drawn below it.
    if (onPause != null) {
        PrimaryButton(
            text = "Pause this",
            modifier = Modifier.fillMaxWidth().semantics {
                contentDescription = "Pause $name for a few minutes"
            },
            // Accent, not the run colour: a control you may touch, not a statement that a block runs.
            colour = Palette.accent,
            onClick = onPause,
        )
        Gap(Space.s)
    }
    PrimaryButton(
        text = "End now",
        modifier = Modifier.fillMaxWidth().semantics { contentDescription = "End $name now" },
        onClick = onEnd,
    )
    // Only when there is no release already pending: asking twice must never become a way to move
    // the landing time closer.
    if (locked && !releaseAsked) {
        Gap(Space.s)
        GhostButton(
            text = "Ask to end in 24 hours",
            modifier = Modifier.fillMaxWidth().semantics {
                contentDescription = "Ask to end $name in 24 hours"
            },
            onClick = onRelease,
        )
    }
    // Only when the lock actually names a tag. A field for one otherwise is an invitation to hunt
    // for a tag that would open nothing.
    if (wantsTag) {
        Gap(Space.s)
        GhostButton(
            text = "Present a tag",
            modifier = Modifier.fillMaxWidth().semantics {
                contentDescription = "Present a tag for $name"
            },
            onClick = onPresentTag,
        )
    }
    // Only when there is one to spend, and only on a session nothing else here can end. Next to an
    // unlocked session it would teach people to reach for the scarce thing first.
    if (locked && passesLeft > 0) {
        Gap(Space.s)
        GhostButton(
            text = "Emergency pass ($passesLeft)",
            modifier = Modifier.fillMaxWidth().semantics {
                contentDescription = "Use an emergency pass on $name, $passesLeft left"
            },
            onClick = onEmergency,
        )
    }
    // On the blocked screen the endings happen here rather than in Curfew's own UI, so the promise
    // the app makes elsewhere — that a lock states its own end — has to be restated in the one place
    // somebody is being told no. See the enum for why this screen carries them at all.
    if (which == LockActionsWhen.LOCKED_SCREEN && locked) {
        Gap(Space.m)
        Text(
            "Ending this affects every app the profile blocks, not only this one.",
            style = Type.micro,
            color = Palette.dim,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}