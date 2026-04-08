package com.anthropic.claudecode.util;

/**
 * Peer address parsing utilities.
 * Translated from src/utils/peerAddress.ts
 *
 * Parses URI-style peer addresses into scheme and target.
 */
public class PeerAddressUtils {

    public enum AddressScheme {
        UDS("uds"),
        BRIDGE("bridge"),
        OTHER("other");

        private final String value;
        AddressScheme(String value) { this.value = value; }
        public String getValue() { return value; }
    }

    public record ParsedAddress(AddressScheme scheme, String target) {}

    /**
     * Parse a URI-style address into scheme and target.
     * Translated from parseAddress() in peerAddress.ts
     */
    public static ParsedAddress parseAddress(String to) {
        if (to == null) return new ParsedAddress(AddressScheme.OTHER, "");

        if (to.startsWith("uds:")) {
            return new ParsedAddress(AddressScheme.UDS, to.substring(4));
        }
        if (to.startsWith("bridge:")) {
            return new ParsedAddress(AddressScheme.BRIDGE, to.substring(7));
        }
        // Legacy: bare socket paths
        if (to.startsWith("/")) {
            return new ParsedAddress(AddressScheme.UDS, to);
        }
        return new ParsedAddress(AddressScheme.OTHER, to);
    }

    private PeerAddressUtils() {}
}
