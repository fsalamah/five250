package com.acabes.five250;

/**
 * Converts between the two orders right-to-left text (Arabic, Hebrew) exists in here.
 *
 * VISUAL order is what a 5250 screen holds: one character per cell, left to right, exactly as it
 * is displayed. An Arabic word therefore sits in the buffer back to front - the host (or the
 * operator who keyed it) already laid it out for a display that never reorders anything.
 *
 * LOGICAL order is what every other piece of software expects: characters in reading order, with
 * the renderer doing the right-to-left layout. A browser, a text editor, Excel, a JSON consumer
 * and a string comparison against text someone typed normally all assume it.
 *
 * Hand visual-order text to a bidi-aware renderer and it reverses the word a second time - that
 * is the "Arabic appears reversed" symptom. So the split is: anything that is a picture of the
 * screen (the GUI grid, a screenshot, `five250 screen`, replay snapshots) stays visual and is
 * drawn cell by cell with no reordering; anything that is DATA taken off the screen (check /
 * extract values, script vars, results files) is converted to logical here; and a label a suite
 * author typed normally is converted the other way before being searched for in the buffer.
 *
 * The conversion reverses each maximal right-to-left run (RTL letters plus whatever spaces and
 * punctuation sit between them) and then restores any number inside it, since digits read left
 * to right in both orders. It is its own inverse, which is why toVisual and toLogical share it.
 * It is deliberately not the full Unicode bidi algorithm: screen rows are short, fixed-width and
 * have no embedding marks, and a self-inverse rule keeps the two directions exactly symmetric.
 *
 * Set FIVE250_BIDI=off to disable all of it and get raw cell order everywhere.
 */
final class Bidi {

    private static final boolean ENABLED = !"off".equalsIgnoreCase(String.valueOf(System.getenv("FIVE250_BIDI")).trim());

    private Bidi() {}

    /** Screen (cell) order to reading order, with Arabic presentation forms folded to plain letters. */
    static String toLogical(String visual) {
        if (!ENABLED || visual == null || !hasRtl(visual)) return visual;
        return reorder(foldPresentationForms(visual));
    }

    /** Reading order to screen (cell) order - for finding normally-typed text in the buffer. */
    static String toVisual(String logical) {
        if (!ENABLED || logical == null || !hasRtl(logical)) return logical;
        return reorder(logical);
    }

    /**
     * Same length as the input, cell for cell, with Arabic presentation forms replaced by their
     * plain letters - so a buffer the host filled with already-shaped glyph codes can be searched
     * for a label typed with ordinary letters, and a match index is still a real screen position.
     */
    static String foldPresentationForms(String s) {
        if (!ENABLED || s == null) return s;
        StringBuilder out = null;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (isPresentationForm(c)) {
                String n = java.text.Normalizer.normalize(String.valueOf(c), java.text.Normalizer.Form.NFKC);
                // Only a one-to-one fold keeps positions intact; a ligature (lam-alef) expands to
                // two letters and is left as it is.
                if (n.length() == 1 && n.charAt(0) != c) {
                    if (out == null) out = new StringBuilder(s);
                    out.setCharAt(i, n.charAt(0));
                }
            }
        }
        return out == null ? s : out.toString();
    }

    static boolean hasRtl(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (isRtl(s.charAt(i))) return true;
        }
        return false;
    }

    private static String reorder(String s) {
        char[] chars = s.toCharArray();
        int i = 0;
        while (i < chars.length) {
            if (!isRtl(chars[i])) { i++; continue; }
            // Extend the run across anything that isn't strongly left-to-right, then trim it
            // back to the last RTL letter so trailing spaces/punctuation stay where they are.
            int end = i;
            int j = i;
            while (j < chars.length && !isStrongLtr(chars[j])) {
                if (isRtl(chars[j])) end = j;
                j++;
            }
            reverse(chars, i, end);
            // Numbers were reversed along with everything else - put each one back.
            int k = i;
            while (k <= end) {
                if (!isDigit(chars[k])) { k++; continue; }
                int numEnd = k;
                int m = k;
                while (m <= end && (isDigit(chars[m]) || (isNumberJoiner(chars[m]) && m + 1 <= end && isDigit(chars[m + 1])))) {
                    numEnd = m;
                    m++;
                }
                reverse(chars, k, numEnd);
                k = numEnd + 1;
            }
            i = end + 1;
        }
        return new String(chars);
    }

    private static void reverse(char[] chars, int from, int to) {
        while (from < to) {
            char t = chars[from];
            chars[from++] = chars[to];
            chars[to--] = t;
        }
    }

    private static boolean isRtl(char c) {
        byte d = Character.getDirectionality(c);
        return d == Character.DIRECTIONALITY_RIGHT_TO_LEFT || d == Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC;
    }

    private static boolean isStrongLtr(char c) {
        return Character.getDirectionality(c) == Character.DIRECTIONALITY_LEFT_TO_RIGHT;
    }

    private static boolean isDigit(char c) {
        byte d = Character.getDirectionality(c);
        return d == Character.DIRECTIONALITY_EUROPEAN_NUMBER || d == Character.DIRECTIONALITY_ARABIC_NUMBER;
    }

    private static boolean isNumberJoiner(char c) {
        return c == '.' || c == ',' || c == ':' || c == '/' || c == '-';
    }

    private static boolean isPresentationForm(char c) {
        return (c >= 'ﭐ' && c <= '﷿') || (c >= 'ﹰ' && c <= 'ﻼ');
    }
}
