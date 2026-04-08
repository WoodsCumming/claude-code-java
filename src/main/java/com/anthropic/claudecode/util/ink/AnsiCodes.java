package com.anthropic.claudecode.util.ink;

/**
 * ANSI Control Characters and Escape Sequence Introducers.
 *
 * <p>Based on ECMA-48 / ANSI X3.64 standards. Translated from
 * src/ink/termio/ansi.ts.
 */
public final class AnsiCodes {

    private AnsiCodes() {}

    // -------------------------------------------------------------------------
    // C0 (7-bit) control character byte values
    // -------------------------------------------------------------------------

    public static final int C0_NUL = 0x00;
    public static final int C0_SOH = 0x01;
    public static final int C0_STX = 0x02;
    public static final int C0_ETX = 0x03;
    public static final int C0_EOT = 0x04;
    public static final int C0_ENQ = 0x05;
    public static final int C0_ACK = 0x06;
    public static final int C0_BEL = 0x07;
    public static final int C0_BS  = 0x08;
    public static final int C0_HT  = 0x09;
    public static final int C0_LF  = 0x0a;
    public static final int C0_VT  = 0x0b;
    public static final int C0_FF  = 0x0c;
    public static final int C0_CR  = 0x0d;
    public static final int C0_SO  = 0x0e;
    public static final int C0_SI  = 0x0f;
    public static final int C0_DLE = 0x10;
    public static final int C0_DC1 = 0x11;
    public static final int C0_DC2 = 0x12;
    public static final int C0_DC3 = 0x13;
    public static final int C0_DC4 = 0x14;
    public static final int C0_NAK = 0x15;
    public static final int C0_SYN = 0x16;
    public static final int C0_ETB = 0x17;
    public static final int C0_CAN = 0x18;
    public static final int C0_EM  = 0x19;
    public static final int C0_SUB = 0x1a;
    public static final int C0_ESC = 0x1b;
    public static final int C0_FS  = 0x1c;
    public static final int C0_GS  = 0x1d;
    public static final int C0_RS  = 0x1e;
    public static final int C0_US  = 0x1f;
    public static final int C0_DEL = 0x7f;

    // -------------------------------------------------------------------------
    // String constants for output generation
    // -------------------------------------------------------------------------

    /** ESC character as a string. */
    public static final String ESC = "\u001b";

    /** BEL character as a string. */
    public static final String BEL = "\u0007";

    /** Separator used between CSI/SGR parameters. */
    public static final String SEP = ";";

    // -------------------------------------------------------------------------
    // Escape sequence type introducer byte values (byte after ESC)
    // -------------------------------------------------------------------------

    /** CSI: [ (0x5B) - Control Sequence Introducer */
    public static final int ESC_TYPE_CSI = 0x5b;
    /** OSC: ] (0x5D) - Operating System Command */
    public static final int ESC_TYPE_OSC = 0x5d;
    /** DCS: P (0x50) - Device Control String */
    public static final int ESC_TYPE_DCS = 0x50;
    /** APC: _ (0x5F) - Application Program Command */
    public static final int ESC_TYPE_APC = 0x5f;
    /** PM: ^ (0x5E) - Privacy Message */
    public static final int ESC_TYPE_PM  = 0x5e;
    /** SOS: X (0x58) - Start of String */
    public static final int ESC_TYPE_SOS = 0x58;
    /** ST: \ (0x5C) - String Terminator */
    public static final int ESC_TYPE_ST  = 0x5c;

    // -------------------------------------------------------------------------
    // Helper predicates
    // -------------------------------------------------------------------------

    /**
     * Returns {@code true} if the given byte is a C0 control character
     * (0x00–0x1F or 0x7F).
     */
    public static boolean isC0(int b) {
        return b < 0x20 || b == 0x7f;
    }

    /**
     * Returns {@code true} if the given byte is a valid ESC sequence final
     * byte (0x30–0x7E). ESC sequences have a wider final-byte range than CSI.
     */
    public static boolean isEscFinal(int b) {
        return b >= 0x30 && b <= 0x7e;
    }
}
