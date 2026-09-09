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
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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

    /** Room under the last element so a scrolled screen clears the navigation bar. */
    val BottomRoom = 28.dp
}

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
) {
    val background = when {
        selected -> Palette.Accent
        tint != null -> tint.copy(alpha = 0.14f)
        else -> Palette.Raised
    }
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(background)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 12.dp, vertical = 8.dp),
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

/** The toggle on a plan row. Drawn rather than themed, so it matches the canvas exactly. */
@Composable
fun Switch(on: Boolean, onChange: (Boolean) -> Unit) {
    Box(
        modifier = Modifier
            .size(width = 44.dp, height = 26.dp)
            .clip(RoundedCornerShape(999.dp))
            .background(if (on) Palette.Accent else Palette.Raised)
            .then(
                if (on) Modifier else Modifier.border(1.dp, Palette.Line, RoundedCornerShape(999.dp)),
            )
            .clickable { onChange(!on) },
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

/**
 * The dial.
 *
 * Both the Today screen and the timer are built around one circle: a track, and an amber arc for
 * however much of the block is left. It is the only chart in the app, and it exists because "1:12"
 * on its own does not say whether that is nearly over or barely started.
 *
 * [fraction] is how much of the arc to draw, 0 for a bare track. Drawing starts at twelve o'clock
 * and runs clockwise, which is the only direction anyone reads a remaining-time ring.
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
