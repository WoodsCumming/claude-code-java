package com.anthropic.claudecode.util.ink;

import java.util.ArrayList;
import java.util.List;

/**
 * Input tokenizer — escape sequence boundary detection.
 *
 * <p>Splits terminal input into {@link Token tokens}: plain text chunks and raw
 * escape sequences. Unlike {@link TermioParser} which interprets sequences
 * semantically, this class only identifies boundaries, and is used by the
 * keyboard input parser.
 *
 * <p>Translated from {@code src/ink/termio/tokenize.ts}.
 */
public final class TermioTokenizer {

    // -------------------------------------------------------------------------
    // Token
    // -------------------------------------------------------------------------

    /** A single output unit: either a text run or a raw escape sequence. */
    public sealed interface Token permits Token.Text, Token.Sequence {
        record Text(String value)     implements Token {}
        record Sequence(String value) implements Token {}
    }

    // -------------------------------------------------------------------------
    // Tokenizer state
    // -------------------------------------------------------------------------

    private enum State {
        GROUND, ESCAPE, ESCAPE_INTERMEDIATE, CSI, SS3, OSC, DCS, APC
    }

    private State  state  = State.GROUND;
    private String buffer = "";
    private final boolean x10Mouse;

    /** Create a tokenizer with X10 mouse support disabled (suitable for output streams). */
    public TermioTokenizer() {
        this(false);
    }

    /**
     * Create a tokenizer.
     *
     * @param x10Mouse {@code true} to treat {@code CSI M} as an X10 mouse
     *                 event prefix and consume 3 payload bytes. Only enable for
     *                 stdin — {@code ESC [ M} is also CSI DL in output streams.
     */
    public TermioTokenizer(boolean x10Mouse) {
        this.x10Mouse = x10Mouse;
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /** Feed input and return the resulting tokens. */
    public List<Token> feed(String input) {
        InternalResult r = tokenize(input, state, buffer, false);
        state  = r.state;
        buffer = r.buffer;
        return r.tokens;
    }

    /** Flush any buffered incomplete sequences and return them as tokens. */
    public List<Token> flush() {
        InternalResult r = tokenize("", state, buffer, true);
        state  = r.state;
        buffer = r.buffer;
        return r.tokens;
    }

    /** Reset the tokenizer to its initial ground state. */
    public void reset() {
        state  = State.GROUND;
        buffer = "";
    }

    /** Return the currently buffered, incomplete sequence (may be empty). */
    public String getBuffer() {
        return buffer;
    }

    // -------------------------------------------------------------------------
    // Core tokenisation logic
    // -------------------------------------------------------------------------

    private record InternalResult(List<Token> tokens, State state, String buffer) {}

    private InternalResult tokenize(
            String input, State initialState, String initialBuffer, boolean flush) {

        List<Token> tokens  = new ArrayList<>();
        State       curState = initialState;
        String      curBuffer = "";   // output buffer (returned at end)

        String data = initialBuffer + input;
        int i         = 0;
        int textStart = 0;
        int seqStart  = 0;

        while (i < data.length()) {
            int code = data.charAt(i);

            switch (curState) {
                case GROUND -> {
                    if (code == AnsiCodes.C0_ESC) {
                        // Flush pending text
                        if (i > textStart) {
                            String text = data.substring(textStart, i);
                            if (!text.isEmpty()) tokens.add(new Token.Text(text));
                        }
                        seqStart = i;
                        curState = State.ESCAPE;
                        i++;
                    } else {
                        i++;
                    }
                }
                case ESCAPE -> {
                    if (code == AnsiCodes.ESC_TYPE_CSI) {
                        curState = State.CSI; i++;
                    } else if (code == AnsiCodes.ESC_TYPE_OSC) {
                        curState = State.OSC; i++;
                    } else if (code == AnsiCodes.ESC_TYPE_DCS) {
                        curState = State.DCS; i++;
                    } else if (code == AnsiCodes.ESC_TYPE_APC) {
                        curState = State.APC; i++;
                    } else if (code == 0x4f) { // 'O' - SS3
                        curState = State.SS3; i++;
                    } else if (isCsiIntermediate(code)) {
                        curState = State.ESCAPE_INTERMEDIATE; i++;
                    } else if (AnsiCodes.isEscFinal(code)) {
                        i++;
                        String seq = data.substring(seqStart, i);
                        if (!seq.isEmpty()) tokens.add(new Token.Sequence(seq));
                        curState = State.GROUND;
                        textStart = i;
                    } else if (code == AnsiCodes.C0_ESC) {
                        // Double ESC — emit first, start new
                        String seq = data.substring(seqStart, i);
                        if (!seq.isEmpty()) tokens.add(new Token.Sequence(seq));
                        curState = State.GROUND;
                        textStart = i;
                        seqStart = i;
                        curState = State.ESCAPE;
                        i++;
                    } else {
                        // Invalid — treat ESC as text
                        curState = State.GROUND;
                        textStart = seqStart;
                    }
                }
                case ESCAPE_INTERMEDIATE -> {
                    if (isCsiIntermediate(code)) {
                        i++;
                    } else if (AnsiCodes.isEscFinal(code)) {
                        i++;
                        String seq = data.substring(seqStart, i);
                        if (!seq.isEmpty()) tokens.add(new Token.Sequence(seq));
                        curState = State.GROUND;
                        textStart = i;
                    } else {
                        curState = State.GROUND;
                        textStart = seqStart;
                    }
                }
                case CSI -> {
                    // X10 mouse: CSI M + 3 raw payload bytes
                    if (x10Mouse && code == 0x4d /* M */ && i - seqStart == 2) {
                        boolean safe =
                            (i + 1 >= data.length() || data.charAt(i + 1) >= 0x20) &&
                            (i + 2 >= data.length() || data.charAt(i + 2) >= 0x20) &&
                            (i + 3 >= data.length() || data.charAt(i + 3) >= 0x20);
                        if (safe) {
                            if (i + 4 <= data.length()) {
                                i += 4;
                                String seq = data.substring(seqStart, i);
                                if (!seq.isEmpty()) tokens.add(new Token.Sequence(seq));
                                curState = State.GROUND;
                                textStart = i;
                            } else {
                                i = data.length(); // incomplete — buffer
                            }
                            break;
                        }
                    }
                    if (isCsiFinal(code)) {
                        i++;
                        String seq = data.substring(seqStart, i);
                        if (!seq.isEmpty()) tokens.add(new Token.Sequence(seq));
                        curState = State.GROUND;
                        textStart = i;
                    } else if (isCsiParam(code) || isCsiIntermediate(code)) {
                        i++;
                    } else {
                        curState = State.GROUND;
                        textStart = seqStart;
                    }
                }
                case SS3 -> {
                    if (code >= 0x40 && code <= 0x7e) {
                        i++;
                        String seq = data.substring(seqStart, i);
                        if (!seq.isEmpty()) tokens.add(new Token.Sequence(seq));
                        curState = State.GROUND;
                        textStart = i;
                    } else {
                        curState = State.GROUND;
                        textStart = seqStart;
                    }
                }
                case OSC -> {
                    if (code == AnsiCodes.C0_BEL) {
                        i++;
                        String seq = data.substring(seqStart, i);
                        if (!seq.isEmpty()) tokens.add(new Token.Sequence(seq));
                        curState = State.GROUND;
                        textStart = i;
                    } else if (code == AnsiCodes.C0_ESC
                            && i + 1 < data.length()
                            && data.charAt(i + 1) == AnsiCodes.ESC_TYPE_ST) {
                        i += 2;
                        String seq = data.substring(seqStart, i);
                        if (!seq.isEmpty()) tokens.add(new Token.Sequence(seq));
                        curState = State.GROUND;
                        textStart = i;
                    } else {
                        i++;
                    }
                }
                case DCS, APC -> {
                    if (code == AnsiCodes.C0_BEL) {
                        i++;
                        String seq = data.substring(seqStart, i);
                        if (!seq.isEmpty()) tokens.add(new Token.Sequence(seq));
                        curState = State.GROUND;
                        textStart = i;
                    } else if (code == AnsiCodes.C0_ESC
                            && i + 1 < data.length()
                            && data.charAt(i + 1) == AnsiCodes.ESC_TYPE_ST) {
                        i += 2;
                        String seq = data.substring(seqStart, i);
                        if (!seq.isEmpty()) tokens.add(new Token.Sequence(seq));
                        curState = State.GROUND;
                        textStart = i;
                    } else {
                        i++;
                    }
                }
            }
        }

        // Handle end of input
        if (curState == State.GROUND) {
            if (i > textStart) {
                String text = data.substring(textStart, i);
                if (!text.isEmpty()) tokens.add(new Token.Text(text));
            }
        } else if (flush) {
            String remaining = data.substring(seqStart);
            if (!remaining.isEmpty()) tokens.add(new Token.Sequence(remaining));
            curState = State.GROUND;
        } else {
            curBuffer = data.substring(seqStart);
        }

        return new InternalResult(tokens, curState, curBuffer);
    }

    // -------------------------------------------------------------------------
    // CSI byte classification (mirrors csi.ts helpers)
    // -------------------------------------------------------------------------

    private static boolean isCsiParam(int b)        { return b >= 0x30 && b <= 0x3f; }
    private static boolean isCsiIntermediate(int b)  { return b >= 0x20 && b <= 0x2f; }
    private static boolean isCsiFinal(int b)         { return b >= 0x40 && b <= 0x7e; }
}
