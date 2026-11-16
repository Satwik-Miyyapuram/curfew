package dev.curfew.app.ui

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * The type ramp, and the only text styles in the app.
 *
 * `docs/design.md` §3.3's tokens, one face for words and one for values. Every `Text` takes its size,
 * line height and tracking from here; a call site may say how heavy (`FontWeight.Normal`, `Bold` or
 * `Black`, nothing in between) and in what colour, and nothing else. A size that is not on this list
 * is a size the design does not have.
 *
 *  - [micro] status metadata and helper text that sits beside something larger.
 *  - [label] a section label or eyebrow: micro, black, wide tracking, written uppercase.
 *  - [meta] supporting copy and the second line of a row.
 *  - [body] default readable text, and the size Material's own `Text` falls back to.
 *  - [button] the words on a button or a segment: body, black, a little tracking.
 *  - [h3] a card or row heading. [h2] a section heading. [h1] a screen heading.
 *  - [display] a key statement or a code. [mega] the dial's numeral.
 *
 * [mono] turns any of them into the value face: times, durations, codes and counts, which are read
 * in columns and jitter in a proportional font.
 */
object Type {
    val micro = TextStyle(fontSize = 11.sp, lineHeight = 15.sp)
    val label = micro.copy(fontWeight = FontWeight.Black, letterSpacing = 1.2.sp)
    val meta = TextStyle(fontSize = 12.5.sp, lineHeight = 18.sp)
    val body = TextStyle(fontSize = 14.5.sp, lineHeight = 21.sp)
    val button = body.copy(fontWeight = FontWeight.Black, letterSpacing = 0.6.sp)
    val h3 = TextStyle(fontSize = 16.sp, lineHeight = 21.sp, fontWeight = FontWeight.Bold)
    val h2 = TextStyle(fontSize = 20.sp, lineHeight = 24.sp, fontWeight = FontWeight.Black, letterSpacing = (-0.2).sp)
    val h1 = TextStyle(fontSize = 26.sp, lineHeight = 29.sp, fontWeight = FontWeight.Black, letterSpacing = (-0.3).sp)
    val display = TextStyle(fontSize = 36.sp, lineHeight = 40.sp, fontWeight = FontWeight.Black, letterSpacing = (-0.6).sp)
    val mega = TextStyle(fontSize = 46.sp, lineHeight = 50.sp, fontWeight = FontWeight.Black, letterSpacing = (-1).sp)
}

/** The same style in the value face. Tracking is dropped: a mono face is already evenly spaced. */
val TextStyle.mono: TextStyle get() = copy(fontFamily = FontFamily.Monospace, letterSpacing = 0.sp)
