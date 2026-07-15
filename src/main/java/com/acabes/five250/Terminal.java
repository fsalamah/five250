package com.acabes.five250;

import org.tn5250j.Session5250;
import org.tn5250j.SessionConfig;
import org.tn5250j.framework.tn5250.Screen5250;
import org.tn5250j.framework.tn5250.ScreenField;
import org.tn5250j.framework.tn5250.ScreenOIA;
import org.tn5250j.keyboard.KeyMnemonic;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * Wraps a single tn5250j headless session: connect, sign on, type, send AID keys,
 * and snapshot the presentation buffer as plain data (no rendering).
 */
public final class Terminal {

    private final String sessionId;
    private Session5250 session;
    private Screen5250 screen;

    public Terminal(String sessionId) {
        this.sessionId = sessionId;
    }

    public synchronized void connect(String host, int port, boolean ssl, long timeoutMs) {
        Properties props = new Properties();
        props.setProperty("SESSION_HOST", host);
        props.setProperty("SESSION_HOST_PORT", String.valueOf(port));
        if (ssl) {
            props.setProperty("-sslType", "TLS");
        }

        SessionConfig cfg = new SessionConfig("five250-" + sessionId, "five250-" + sessionId);
        session = new Session5250(props, "five250-" + sessionId, "five250-" + sessionId, cfg);
        session.connect();
        screen = session.getScreen();
        screen.setUseGUIInterface(false);

        long deadline = System.currentTimeMillis() + timeoutMs;
        while (!session.isConnected()) {
            if (System.currentTimeMillis() > deadline) {
                throw new RuntimeException("Timed out connecting to " + host + ":" + port);
            }
            sleep(50);
        }
        waitReady(timeoutMs);
    }

    public synchronized void disconnect() {
        if (session != null) {
            session.disconnect();
        }
    }

    public synchronized boolean isConnected() {
        return session != null && session.isConnected();
    }

    /** Waits for the keyboard to unlock, then for the buffer to stop changing. */
    public synchronized void waitReady(long timeoutMs) {
        requireConnected();
        long deadline = System.currentTimeMillis() + timeoutMs;

        while (System.currentTimeMillis() < deadline) {
            ScreenOIA oia = screen.getOIA();
            if (!oia.isKeyBoardLocked() && oia.getInputInhibited() == ScreenOIA.INPUTINHIBITED_NOTINHIBITED) {
                break;
            }
            sleep(50);
        }

        int stableRounds = 0;
        int lastHash = Integer.MIN_VALUE;
        while (System.currentTimeMillis() < deadline && stableRounds < 3) {
            int hash = new String(screen.getScreenAsChars()).hashCode();
            if (hash == lastHash) {
                stableRounds++;
            } else {
                stableRounds = 0;
                lastHash = hash;
            }
            sleep(40);
        }
    }

    public synchronized void signon(String user, String pass, long timeoutMs) {
        typeLabel(new String[]{"User", "User ID", "Userid", "USER"}, user);
        typeLabel(new String[]{"Password", "PASSWORD"}, pass);
        sendKey(KeyMnemonic.ENTER);
        waitReady(timeoutMs);

        // Forced password-change screens re-prompt for old/new/new; caller decides via screen text.
    }

    public synchronized void typeLabel(String[] labelCandidates, String value) {
        requireConnected();
        ScreenField f = null;
        for (String label : labelCandidates) {
            f = findFieldRightOfLabel(label);
            if (f != null) break;
        }
        if (f == null) {
            throw new RuntimeException("No input field found near labels: " + String.join(", ", labelCandidates));
        }
        f.setString(value);
    }

    public synchronized void typeAt(int row, int col, String value) {
        requireConnected();
        int cols = screen.getColumns();
        int pos = (row - 1) * cols + (col - 1);
        ScreenField f = screen.getScreenFields().findByPosition(pos);
        if (f == null) {
            throw new RuntimeException("No input field at row " + row + " col " + col);
        }
        f.setString(value);
    }

    public synchronized void sendKey(KeyMnemonic key) {
        requireConnected();
        screen.sendKeys(key);
    }

    /**
     * Types literal characters at the CURRENT cursor position, exactly like a real terminal:
     * the cursor advances naturally, field boundaries and protected areas behave exactly as
     * tn5250j's own keyboard handling dictates (this is the same entry point the Swing UI's key
     * listener uses) — no row/col/label targeting needed. Pure local buffer edit, no host
     * round-trip, same as typeAt/typeLabel.
     */
    public synchronized void sendText(String text) {
        requireConnected();
        screen.sendKeys(text);
    }

    /** Current cursor position as {row, col}, 1-based — cheap, no full snapshot needed. */
    public synchronized int[] cursor() {
        requireConnected();
        return new int[]{screen.getCurrentRow(), screen.getCurrentCol()};
    }

    /**
     * Moves the real cursor to an exact position, for click-to-place-cursor in the GUI.
     * screen.setCursor() uses the same coordinate convention as getCurrentRow()/getCurrentCol()
     * (verified by round-trip: setCursor(row, col) followed by a snapshot reports the identical
     * row/col back) — unlike ScreenField.startRow()/startCol(), which really are 0-based. No
     * conversion here.
     */
    public synchronized void setCursor(int row, int col) {
        requireConnected();
        screen.setCursor(row, col);
    }

    /** Reads the current value of the (editable) field to the right of a label. */
    public synchronized String readLabel(String label) {
        requireConnected();
        ScreenField f = findFieldRightOfLabel(label);
        if (f == null) {
            throw new RuntimeException("No field found near label: " + label);
        }
        String v = f.getString();
        return v == null ? "" : v.stripTrailing();
    }

    /**
     * Reads the text immediately after a label on the same row, straight from the character
     * buffer — works for protected/display-only text (a balance, a job count, a status) AND
     * editable fields alike, unlike readLabel() which only sees editable ScreenFields. Stops at
     * two-or-more consecutive spaces (the next label) or end of row. This is what "check
     * label:..." and "extract label:..." actually use.
     */
    public synchronized String readAfterLabel(String label) {
        requireConnected();
        String text = new String(screen.getScreenAsChars());
        int cols = screen.getColumns();
        int idx = text.indexOf(label);
        if (idx < 0) {
            idx = text.toUpperCase().indexOf(label.toUpperCase());
        }
        if (idx < 0) {
            throw new RuntimeException("Label not found on screen: " + label);
        }

        int labelEnd = idx + label.length();
        int row = labelEnd / cols;
        int rowEnd = Math.min((row + 1) * cols, text.length());
        String rest = text.substring(labelEnd, rowEnd).replaceFirst("^ +", "");
        String[] parts = rest.split(" {2,}", 2);
        return parts.length > 0 ? parts[0].trim() : "";
    }

    /**
     * SPIKE (recording feature, unverified until this method): given a position that was just
     * typed into, infers the most robust "type" target for a recorded step — "label:<text>" if
     * a unique, colon-terminated label precedes it on the same row, else "<row>,<col>" as a
     * fallback. Deliberately conservative: a label that appears more than once on screen (e.g.
     * a subfile's repeating "Opt" column header) is ambiguous and falls back to coordinates
     * rather than risk anchoring to the wrong row on replay.
     */
    public synchronized String inferTarget(int row, int col) {
        requireConnected();
        String text = new String(screen.getScreenAsChars());
        int cols = screen.getColumns();
        int rowStart = (row - 1) * cols;
        int pos = rowStart + (col - 1);

        String candidate = findPrecedingColonLabel(text, rowStart, pos);
        if (candidate != null) {
            int occurrences = countOccurrences(text, candidate);
            if (occurrences == 1) {
                return "label:" + candidate;
            }
        }
        return row + "," + col;
    }

    /** Scans left from `pos` on the row starting at `rowStart` for the nearest "...text:" run. */
    private static String findPrecedingColonLabel(String text, int rowStart, int pos) {
        int colonPos = -1;
        for (int i = Math.min(pos, text.length()) - 1; i >= rowStart; i--) {
            if (text.charAt(i) == ':') {
                colonPos = i;
                break;
            }
        }
        if (colonPos < 0) return null;

        int labelStart = colonPos;
        while (labelStart > rowStart && !isFieldSeparator(text, labelStart - 1)) {
            labelStart--;
        }
        String label = text.substring(labelStart, colonPos + 1).trim();
        return label.isEmpty() ? null : label;
    }

    private static boolean isFieldSeparator(String text, int i) {
        return i > 0 && text.charAt(i) == ' ' && text.charAt(i - 1) == ' ';
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0, from = 0;
        while (true) {
            int idx = haystack.indexOf(needle, from);
            if (idx < 0) break;
            count++;
            from = idx + 1;
        }
        return count;
    }

    private ScreenField findFieldRightOfLabel(String label) {
        String text = new String(screen.getScreenAsChars());
        int cols = screen.getColumns();
        int idx = text.indexOf(label);
        if (idx < 0) {
            idx = text.toUpperCase().indexOf(label.toUpperCase());
        }
        if (idx < 0) return null;

        int labelEndPos = idx + label.length();
        int labelRow = labelEndPos / cols;

        ScreenField best = null;
        long bestScore = Long.MAX_VALUE;
        for (ScreenField f : screen.getScreenFields().getFields()) {
            if (f.isBypassField()) continue;
            int fStart = f.startPos();
            if (fStart < labelEndPos) continue;
            int fRow = f.startRow();
            long score = (fRow == labelRow ? 0L : 100000L) + (fStart - labelEndPos);
            if (score < bestScore) {
                bestScore = score;
                best = f;
            }
        }
        return best;
    }

    /** Plain-data snapshot of the current screen: text, cursor, OIA, and fields. No pixels involved. */
    public synchronized Map<String, Object> snapshot() {
        requireConnected();
        Map<String, Object> out = new LinkedHashMap<>();
        int rows = screen.getRows();
        int cols = screen.getColumns();
        out.put("rows", (long) rows);
        out.put("cols", (long) cols);
        out.put("text", new String(screen.getScreenAsChars()));
        out.put("cursor", cursorMap());
        out.put("oia", oiaMap());
        out.put("fields", fieldsList());
        return out;
    }

    private Map<String, Object> cursorMap() {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("row", (long) screen.getCurrentRow());
        c.put("col", (long) screen.getCurrentCol());
        return c;
    }

    private Map<String, Object> oiaMap() {
        ScreenOIA oia = screen.getOIA();
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("inputInhibited", oia.getInputInhibited() != ScreenOIA.INPUTINHIBITED_NOTINHIBITED);
        o.put("keyboardLocked", oia.isKeyBoardLocked());
        o.put("messageWait", oia.isMessageWait());
        return o;
    }

    public synchronized List<Object> fieldsList() {
        requireConnected();
        List<Object> list = new ArrayList<>();
        for (ScreenField f : screen.getScreenFields().getFields()) {
            Map<String, Object> m = new LinkedHashMap<>();
            // ScreenField.startRow()/startCol() are 0-based; every other row/col in this codebase
            // (typeAt, CLI --row/--col, the CSV "row,col" target) is 1-based. Convert here so
            // `fields` output can be fed straight into `type --row --col` without an off-by-one.
            m.put("row", (long) (f.startRow() + 1));
            m.put("col", (long) (f.startCol() + 1));
            m.put("length", (long) f.getFieldLength());
            m.put("protected", f.isBypassField());
            m.put("numeric", f.isNumeric());
            m.put("value", f.getString() == null ? "" : f.getString().stripTrailing());
            list.add(m);
        }
        return list;
    }

    /** Row 24 (or last row) message-line text, trimmed. */
    public synchronized String messageLine() {
        return rowText(screen.getRows());
    }

    /** 1-based row text, trimmed. */
    public synchronized String rowText(int row) {
        requireConnected();
        char[] all = screen.getScreenAsChars();
        int cols = screen.getColumns();
        int start = (row - 1) * cols;
        return new String(all, start, cols).stripTrailing();
    }

    private void requireConnected() {
        if (session == null || screen == null || !session.isConnected()) {
            throw new RuntimeException("Session '" + sessionId + "' is not connected");
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
