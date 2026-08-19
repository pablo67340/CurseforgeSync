package com.curseforgesync.core;

import java.util.Locale;

/** Which side of the game a mod (or the running process) belongs to. */
public enum Side {
    /** Needed on both the client and the server. */
    BOTH,
    /** Only useful on a client; installing it on a server is at best wasted memory. */
    CLIENT,
    /** Only useful on a dedicated server. */
    SERVER;

    public static Side parse(String value, Side fallback) {
        if (value == null) {
            return fallback;
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        if (normalized.isEmpty() || normalized.equals("AUTO")) {
            return fallback;
        }
        for (Side side : values()) {
            if (side.name().equals(normalized)) {
                return side;
            }
        }
        return fallback;
    }

    /** True when a mod classified as {@code this} should be installed on a {@code running} process. */
    public boolean neededOn(Side running) {
        return this == BOTH || this == running;
    }
}
