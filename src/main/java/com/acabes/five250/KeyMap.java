package com.acabes.five250;

import org.tn5250j.keyboard.KeyMnemonic;

/** Resolves human-friendly AID key names to tn5250j KeyMnemonic constants. */
public final class KeyMap {

    private KeyMap() {}

    public static KeyMnemonic resolve(String raw) {
        if (raw == null) throw new IllegalArgumentException("key name is required");
        String k = raw.trim().toUpperCase().replace('-', '_').replace(' ', '_');

        switch (k) {
            case "PGUP": case "PAGEUP": k = "PAGE_UP"; break;
            case "PGDN": case "PAGEDOWN": k = "PAGE_DOWN"; break;
            case "ERASEEOF": k = "ERASE_EOF"; break;
            case "ERASEFIELD": k = "ERASE_FIELD"; break;
            case "BACKTAB": k = "BACK_TAB"; break;
            case "BACKSPACE": k = "BACK_SPACE"; break;
            default: break;
        }

        // F1..F24 -> PF1..PF24
        if (k.matches("F([1-9]|1[0-9]|2[0-4])")) {
            k = "P" + k;
        }

        try {
            return KeyMnemonic.valueOf(k);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unknown key: " + raw
                + " (expected ENTER, PF1-PF24, PA1-PA3, PAGE_UP, PAGE_DOWN, TAB, HOME, CLEAR, HELP, SYSREQ, ...)");
        }
    }
}
