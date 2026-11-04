// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop.core.ssh

import java.util.Base64

/**
 * Stateful stream filter that intercepts **OSC 52** (clipboard write) sequences
 * (`ESC ] 52 ; Pc ; Pd BEL` or `... ESC \`, `Pd` being base64 text) and removes
 * them from the stream before it reaches JediTerm.
 *
 * Why: the bundled JediTerm 3.40 only dispatches OSC 0, 1, 2, 7, 8, 10, 11,
 * 104 and 1341, so an OSC 52 is silently dropped and the remote copy never
 * lands in the local clipboard. Claude Code in full-screen mode (it captures
 * the mouse and owns its selection), tmux and vim all copy this way over SSH.
 * The decoded text is handed to [onClipboardWrite] instead. Android already
 * behaves the same way (the Termux fork handles OSC 52 writes), this brings
 * the Desktop terminal to parity.
 *
 * Security policy:
 *  - **Write only.** A read request (`Pd` = `?`) is ignored and never answered:
 *    serving it would let any remote host exfiltrate the local clipboard.
 *  - The payload (`Pc;Pd`) is capped at [MAX_PAYLOAD_CHARS]. A longer sequence
 *    is consumed up to its terminator and dropped, so a hostile host cannot
 *    make us buffer an unbounded amount of memory.
 *  - Nothing about the clipboard content (text, base64, length) is ever logged.
 *  - An exception thrown by [onClipboardWrite] never breaks the stream.
 *
 * Everything that is not a well-formed `ESC ] 5 2 ;` prefix passes through
 * untouched, including the other OSC sequences (`ESC ] 0 ;` title,
 * `ESC ] 8 ;` hyperlinks, `ESC ] 5 2 0 ;`...). A prefix split across two calls
 * is held back (up to [MAX_HELD_CHARS] characters) until the next call decides
 * whether it matches; on a mismatch the held characters are re-emitted verbatim.
 *
 * Not thread-safe: meant to be driven by a single reader thread.
 */
internal class Osc52Filter(private val onClipboardWrite: (String) -> Unit) {

    private enum class State {
        /** Outside any OSC 52, possibly holding a partial prefix ([matched] characters). */
        NORMAL,

        /** Inside the payload of an OSC 52, accumulating into [payload]. */
        PAYLOAD,

        /** Payload exceeded [MAX_PAYLOAD_CHARS]: discard everything up to the terminator. */
        REJECT,
    }

    private var state = State.NORMAL

    /** Number of leading [PREFIX] characters already seen and held back (state NORMAL only). */
    private var matched = 0

    /** An ESC was seen inside the payload (or reject) and its follow-up character is pending. */
    private var escPending = false

    private var payload: StringBuilder? = null

    /**
     * Number of characters this filter may still emit on top of the next input
     * chunk: a partial prefix held back, or the ESC that aborted a sequence and
     * is re-emitted once the following character is known. The caller must
     * reserve `heldCount + inLength` characters of room in the output buffer.
     */
    val heldCount: Int
        get() = when {
            state == State.NORMAL -> matched
            escPending -> 1
            else -> 0
        }

    /**
     * Filters [inLength] characters of [input] starting at [inOffset] and writes
     * what must reach JediTerm into [out] starting at [outOffset]. [input] and
     * [out] must not overlap. The output never exceeds `heldCount (before the
     * call) + inLength` characters. Returns the number of characters written,
     * which may be 0 when the chunk was entirely consumed by a sequence.
     */
    fun filter(input: CharArray, inOffset: Int, inLength: Int, out: CharArray, outOffset: Int): Int {
        var i = inOffset
        val end = inOffset + inLength
        var o = outOffset
        while (i < end) {
            when (state) {
                State.NORMAL -> {
                    if (matched == 0) {
                        // Fast path: plain copy until the next ESC.
                        while (i < end) {
                            val c = input[i]
                            i++
                            if (c == ESC) {
                                matched = 1
                                break
                            }
                            out[o++] = c
                        }
                    } else if (input[i] == PREFIX[matched]) {
                        i++
                        matched++
                        if (matched == PREFIX.size) {
                            matched = 0
                            state = State.PAYLOAD
                            payload = StringBuilder()
                        }
                    } else {
                        // Not an OSC 52: release the held prefix verbatim and
                        // reprocess this character from a clean slate (it may
                        // itself be the ESC of a new prefix).
                        for (k in 0 until matched) out[o++] = PREFIX[k]
                        matched = 0
                    }
                }

                State.PAYLOAD -> {
                    val sb = payload ?: StringBuilder().also { payload = it }
                    if (escPending) {
                        escPending = false
                        if (input[i] == BACKSLASH) {
                            // ST: end of sequence.
                            i++
                            dispatch(sb)
                            payload = null
                            state = State.NORMAL
                        } else {
                            // ESC + anything else aborts the sequence. The ESC
                            // goes back to the normal flow as a held prefix
                            // start, the next character is reprocessed there.
                            payload = null
                            state = State.NORMAL
                            matched = 1
                        }
                    } else {
                        val runStart = i
                        while (i < end && !isStop(input[i])) i++
                        if (i > runStart) {
                            sb.append(input, runStart, i - runStart)
                            if (sb.length > MAX_PAYLOAD_CHARS) {
                                payload = null
                                state = State.REJECT
                                continue
                            }
                        }
                        if (i < end) {
                            val c = input[i]
                            i++
                            when (c) {
                                BEL -> {
                                    dispatch(sb)
                                    payload = null
                                    state = State.NORMAL
                                }
                                ESC -> escPending = true
                                else -> {
                                    // CAN / SUB: abort, the character is consumed.
                                    payload = null
                                    state = State.NORMAL
                                }
                            }
                        }
                    }
                }

                State.REJECT -> {
                    if (escPending) {
                        escPending = false
                        state = State.NORMAL
                        if (input[i] == BACKSLASH) i++ else matched = 1
                    } else {
                        while (i < end && !isStop(input[i])) i++
                        if (i < end) {
                            val c = input[i]
                            i++
                            if (c == ESC) escPending = true else state = State.NORMAL
                        }
                    }
                }
            }
        }
        return o - outOffset
    }

    private fun isStop(c: Char): Boolean = c == BEL || c == ESC || c == CAN || c == SUB

    /**
     * Decodes `Pc;Pd` and forwards the text. Silently ignores anything that is
     * not a plain clipboard write: no separator, empty `Pd`, the `?` read
     * request, invalid base64, or an empty decoded text.
     */
    private fun dispatch(sequence: CharSequence) {
        var sep = -1
        for (k in 0 until sequence.length) {
            if (sequence[k] == ';') {
                sep = k
                break
            }
        }
        if (sep < 0) return
        val pd = sequence.subSequence(sep + 1, sequence.length).toString()
        if (pd.isEmpty() || pd == "?") return
        val bytes = try {
            Base64.getMimeDecoder().decode(pd)
        } catch (_: IllegalArgumentException) {
            return
        }
        val text = String(bytes, Charsets.UTF_8)
        if (text.isEmpty()) return
        try {
            onClipboardWrite(text)
        } catch (_: Exception) {
            // A failing sink must never break the terminal stream.
        }
    }

    companion object {
        /** Maximum accepted length of `Pc;Pd`, in characters (1 Mi). */
        const val MAX_PAYLOAD_CHARS = 1 shl 20

        /**
         * Longest partial prefix that can be held back between two calls:
         * `PREFIX.size - 1` (pinned by a test, `SshTtyConnector` sizes its
         * spill buffer from it).
         */
        const val MAX_HELD_CHARS = 4

        private const val ESC = '\u001b'
        private const val BEL = '\u0007'
        private const val CAN = '\u0018'
        private const val SUB = '\u001a'
        private const val BACKSLASH = '\\'

        /** `ESC ] 5 2 ;` */
        private val PREFIX = charArrayOf(ESC, ']', '5', '2', ';')
    }
}
