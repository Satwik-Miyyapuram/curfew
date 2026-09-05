package dev.curfew.app.ui

import android.app.Activity
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.Ndef

/**
 * Reading the physical tag that ends a session.
 *
 * A tag is the one condition Curfew cannot argue with: it is somewhere else, and getting it means
 * standing up. That is the whole mechanism, so the reading is kept as dumb as possible — hold a tag
 * near the phone, get the bytes, hand them to the core, forget them.
 *
 * Nothing here decides whether a tag is the right one. The core holds only fingerprints, compares
 * them itself, and answers a stranger's tag exactly as it answers a tag belonging to another lock,
 * so scanning cannot be used to find out which tags exist.
 */
object Tags {

    /**
     * The text on the tag, or its serial number when it carries no text.
     *
     * The serial number is the fallback rather than the primary because it cannot be changed: a
     * blank tag is still usable as a key, but a tag someone wrote a text record onto is identified
     * by what they wrote, which they can rewrite if it leaks.
     */
    fun payload(tag: Tag): String {
        val ndef = Ndef.get(tag)
        val text = runCatching {
            ndef?.cachedNdefMessage?.records?.firstNotNullOfOrNull { record ->
                // A well-known text record: one status byte, then a language code that many bytes
                // long, then the text itself in UTF-8.
                val bytes = record.payload
                if (bytes.isEmpty()) {
                    null
                } else {
                    val language = bytes[0].toInt() and 0x3F
                    if (bytes.size <= language + 1) {
                        null
                    } else {
                        String(bytes, language + 1, bytes.size - language - 1, Charsets.UTF_8)
                    }
                }
            }
        }.getOrNull()
        return text?.trim()?.takeIf { it.isNotEmpty() } ?: tag.id.joinToString("") { "%02x".format(it) }
    }

    /** Whether this device can read a tag at all, so the UI knows whether to mention tapping. */
    fun isAvailable(activity: Activity): Boolean =
        NfcAdapter.getDefaultAdapter(activity)?.isEnabled == true

    /**
     * Listen for a tag for as long as the dialog is on screen, and no longer.
     *
     * Reader mode rather than foreground dispatch: it keeps the read inside this activity instead
     * of bouncing through an intent, and it silences the platform's own tag sound, which otherwise
     * fires before Curfew has decided anything.
     *
     * Returns the function that stops listening. Calling it is not optional — a reader left running
     * would keep the radio on and would take a tag meant for something else.
     */
    fun listen(activity: Activity, onTag: (String) -> Unit): () -> Unit {
        val adapter = NfcAdapter.getDefaultAdapter(activity) ?: return {}
        val flags = NfcAdapter.FLAG_READER_NFC_A or
            NfcAdapter.FLAG_READER_NFC_B or
            NfcAdapter.FLAG_READER_NFC_F or
            NfcAdapter.FLAG_READER_NFC_V
        // The NDEF check is deliberately not skipped: it is what fills in the cached message a
        // written tag is read from, and a blank tag still arrives, identified by its serial number.
        adapter.enableReaderMode(activity, { tag -> onTag(payload(tag)) }, flags, null)
        return { runCatching { adapter.disableReaderMode(activity) } }
    }
}
