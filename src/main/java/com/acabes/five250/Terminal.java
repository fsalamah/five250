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
    // volatile: forceClose() reads this from a DIFFERENT thread than the one that wrote it via
    // connect() (see forceClose()'s doc for why it can't just call the synchronized disconnect())
    // - without this, that read has no happens-before guarantee and could see a stale null.
    private volatile Session5250 session;
    private Screen5250 screen;

    public Terminal(String sessionId) {
        this.sessionId = sessionId;
    }

    public synchronized void connect(String host, int port, boolean ssl, long timeoutMs) {
        connect(host, port, ssl, false, null, timeoutMs);
    }

    public synchronized void connect(String host, int port, boolean ssl, boolean wide, long timeoutMs) {
        connect(host, port, ssl, wide, null, timeoutMs);
    }

    /** Env var naming the code page every connect uses when the caller doesn't pass one - lets a
     * whole install (recorded suites, scripts, the GUI) target e.g. an Arabic host without
     * editing each connect call. */
    static final String CODEPAGE_ENV = "FIVE250_CODEPAGE";

    /**
     * Resolves which host code page (EBCDIC CCSID) a connect should use: the explicit request,
     * else the FIVE250_CODEPAGE env var, else null (tn5250j's own default, CCSID 37 - US
     * English). Validated here because tn5250j itself does NOT fail on a name it can't resolve:
     * CharMappings.getCodePage silently falls back to CCSID 37, which is exactly the "Arabic
     * screen comes back as accented-Latin garbage" symptom this option exists to fix - a typo'd
     * or unavailable code page has to be a loud connect error, not more garbage.
     *
     * tn5250j ships built-in tables for a fixed list of Latin/Cyrillic/Greek/Hebrew CCSIDs
     * (CharMappings.getAvailableCodePages()); anything else - Arabic 420 included - goes through
     * the JDK's own charset of that name, which lives in the jdk.charsets module (part of every
     * full JDK, and added to the jlink'd dist runtime in pom.xml for this reason).
     */
    static String resolveCodePage(String requested) {
        String cp = requested == null ? "" : requested.trim();
        if (cp.isEmpty()) {
            String env = System.getenv(CODEPAGE_ENV);
            cp = env == null ? "" : env.trim();
        }
        if (cp.isEmpty()) return null;
        // "CCSID420"/"ccsid 420"/"cp420" -> "420": tn5250j's built-in tables are keyed by the
        // bare number, and the JDK accepts the bare number as an alias for its IBMnnn charsets.
        String bare = cp.replaceFirst("(?i)^(ccsid|cp|ibm)[\\s_-]*(?=\\d)", "");
        for (String builtIn : org.tn5250j.encoding.CharMappings.getAvailableCodePages()) {
            if (builtIn.equalsIgnoreCase(bare)) return builtIn;
        }
        for (String candidate : new String[] {bare, "Cp" + bare, cp}) {
            try {
                if (java.nio.charset.Charset.isSupported(candidate)) return candidate;
            } catch (IllegalArgumentException ignored) {
                // not even a legal charset name - try the next spelling
            }
        }
        throw new IllegalArgumentException("Unsupported code page '" + cp + "'. Use an EBCDIC CCSID number, "
            + "e.g. 37 (US English, the default), 420 (Arabic), 424 (Hebrew), 500 (International), 1140 (US + euro).");
    }

    /** The code page this session was connected with ("37" when none was requested). */
    public synchronized String codePage() {
        return codePage;
    }

    private String codePage = "37";

    /** wide requests the real 5250 "27x132 extended" screen size (tn5250j's
     * TN5250jConstants.SESSION_SCREEN_SIZE=1, vs. the default "0" for 24x80) instead of just
     * rendering the normal 24x80 buffer bigger - the host has to actually support/grant the wider
     * device too (most IBM i systems do); it silently stays 24x80 if the host doesn't. Every row/
     * col call in this class already reads screen.getColumns()/getRows() live rather than a
     * hardcoded 80/24, so nothing else needs to change once the host grants the wider screen. */
    public synchronized void connect(String host, int port, boolean ssl, boolean wide, String codePage, long timeoutMs) {
        String resolvedCodePage = resolveCodePage(codePage); // throws before any socket is opened
        Properties props = new Properties();
        props.setProperty("SESSION_HOST", host);
        props.setProperty("SESSION_HOST_PORT", String.valueOf(port));
        if (ssl) {
            props.setProperty("-sslType", "TLS");
        }
        if (wide) {
            props.setProperty("SESSION_SCREEN_SIZE", "1");
        }
        if (resolvedCodePage != null) {
            props.setProperty("SESSION_CODE_PAGE", resolvedCodePage);
        }
        this.codePage = resolvedCodePage == null ? "37" : resolvedCodePage;

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

    /** Force-closes the connection from ANOTHER thread, deliberately WITHOUT the synchronized
     * keyword every other Terminal method has - a real "kill" (RunTracker.cancel()) needs to
     * PREEMPT whatever's currently in flight, not politely queue up behind it. If the run thread
     * is stuck inside a synchronized call (sendKey() waiting on a slow/unresponsive host, say),
     * a normal disconnect() from the cancelling thread would block trying to acquire the same
     * monitor and never actually run until the blocked call itself gives up. Session5250.disconnect()
     * has no synchronization of its own (confirmed against the actual tn5250j bytecode), so
     * calling it directly, bypassing Terminal's own lock, closes the real underlying socket out
     * from under that blocked call - which is what actually unblocks a real network read, not
     * merely asking nicely. */
    public void forceClose() {
        Session5250 s = session;
        if (s != null) {
            try { s.disconnect(); } catch (Throwable ignored) {}
        }
    }

    public synchronized boolean isConnected() {
        return session != null && session.isConnected();
    }

    /** Waits for the keyboard to unlock, then for the buffer to stop changing. Both loops check
     * Thread.currentThread().isInterrupted() too, not just the deadline - RunTracker.cancel()
     * interrupts the run thread specifically so a kill mid-waitReady() returns almost
     * immediately (next 50ms poll tick) instead of running out the FULL remaining timeoutMs
     * (up to 15s) before this call would otherwise ever return control to the run loop. */
    public synchronized void waitReady(long timeoutMs) {
        requireConnected();
        long deadline = System.currentTimeMillis() + timeoutMs;

        while (System.currentTimeMillis() < deadline && !Thread.currentThread().isInterrupted()) {
            ScreenOIA oia = screen.getOIA();
            if (!oia.isKeyBoardLocked() && oia.getInputInhibited() == ScreenOIA.INPUTINHIBITED_NOTINHIBITED) {
                break;
            }
            sleep(50);
        }

        int stableRounds = 0;
        int lastHash = Integer.MIN_VALUE;
        while (System.currentTimeMillis() < deadline && stableRounds < 3 && !Thread.currentThread().isInterrupted()) {
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

    /** Blocks until the terminal is genuinely ready for new input (keyboard not locked, host not
     * inhibiting input) or THROWS if it never clears within timeoutMs - "the console isn't busy"
     * as an explicit, fail-loud precondition before a step acts, not just a courtesy wait after
     * the PREVIOUS step's AID key. waitReady()'s own screen-hash-stability check (a handful of
     * 40ms polls) can settle on what looks like a final screen while the host is actually still
     * mid-update on a slower real network than local testing exposes - the NEXT step would then
     * act on a screen that's still changing underneath it. Re-checking immediately before EVERY
     * step, not only after an AID key, closes exactly that gap. */
    public synchronized void waitUntilInputAllowed(long timeoutMs) {
        requireConnected();
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline && !Thread.currentThread().isInterrupted()) {
            ScreenOIA oia = screen.getOIA();
            if (!oia.isKeyBoardLocked() && oia.getInputInhibited() == ScreenOIA.INPUTINHIBITED_NOTINHIBITED) {
                return;
            }
            sleep(50);
        }
        if (Thread.currentThread().isInterrupted()) return; // killed mid-wait - let the caller unwind, not this
        throw new RuntimeException("Timed out after " + timeoutMs
            + "ms waiting for the terminal to become ready for input (keyboard locked or host still processing)");
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
        requireKeyboardUnlocked();
        ScreenField f = null;
        for (String label : labelCandidates) {
            f = findFieldRightOfLabel(label);
            if (f != null) break;
        }
        if (f == null) {
            throw new RuntimeException("No input field found near labels: " + String.join(", ", labelCandidates));
        }
        typeIntoField(f, value);
    }

    public synchronized void typeAt(int row, int col, String value) {
        requireConnected();
        requireKeyboardUnlocked();
        int cols = screen.getColumns();
        int pos = (row - 1) * cols + (col - 1);
        ScreenField f = screen.getScreenFields().findByPosition(pos);
        if (f == null) {
            throw new RuntimeException("No input field at row " + row + " col " + col);
        }
        typeIntoField(f, value);
    }

    /** Moves the cursor to the field's start and types via the same character-simulated
     * keystroke path (screen.sendKeys) manual GUI typing and row/col suite replay
     * (setCursor+sendText, in StepActions.doType) already use - confirmed, by direct
     * side-by-side testing against a real host, to be the one that actually reaches the host
     * correctly every time. ScreenField.setString() writes the local screen buffer directly and
     * DOES set the field's MDT (confirmed against the tn5250j bytecode - not a missing-MDT bug),
     * so the typed value is visibly correct immediately afterward (byte-for-byte confirmed via a
     * same-call readback) - but a suite recorded with a verified-correct password could still
     * fail sign-on on replay while the exact same value typed as simulated real keystrokes into
     * the exact same field succeeds every time. Whatever tn5250j's precise remaining internal
     * difference between the two paths is, simulating actual keystrokes is the one path proven
     * to behave identically to a live user, so label/position-targeted typing goes through it
     * too now instead of the more "direct" but apparently incomplete buffer write. */
    private void typeIntoField(ScreenField f, String value) {
        int cols = screen.getColumns();
        int pos = f.startPos();
        screen.setCursor(pos / cols + 1, pos % cols + 1);
        screen.sendKeys(value);
    }

    public synchronized void sendKey(KeyMnemonic key) {
        requireConnected();
        // Recover a STALE lock left over from a previous call BEFORE sending this key, not just
        // after - a key sent while still locked gets silently dropped by tn5250j (beeped and
        // discarded, never queued), not merely delayed. Without this, the first keystroke after
        // an error is the one that happens to trigger recovery and gets eaten in the process; the
        // user has to press it again for it to actually land. Safe to do unconditionally (even
        // ahead of an AID key) because any lock still present when a NEW call starts can only be
        // this stale client-side kind - a genuine "waiting for the host" lock from a prior AID
        // key would already have been fully waited out by that prior call's waitReady() before
        // this one ever began (Terminal's own synchronized serializes every call).
        if (key != KeyMnemonic.RESET) autoRecoverKeyboardLock();
        screen.sendKeys(key);
        // RESET itself is excluded here too, separately from TRANSMITS_TO_HOST - not because it
        // transmits (it doesn't), but to avoid recursing into autoRecoverKeyboardLock() from
        // inside that same method's own sendKeys(RESET) call.
        if (key != KeyMnemonic.RESET && !TRANSMITS_TO_HOST.contains(key)) autoRecoverKeyboardLock();
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
        autoRecoverKeyboardLock(); // see sendKey()'s comment - clear a stale lock before typing, not just after
        screen.sendKeys(text);
        autoRecoverKeyboardLock();
    }

    /** AID/attention keys that actually transmit to the host and legitimately keep the keyboard
     * locked until a response arrives — auto-recovery must never touch these, or it would cancel
     * a real in-flight submission, and callers (SessionService's "key" case) should wait for a
     * response after these. Every other key (BACK_SPACE, cursor movement, DELETE, TAB, RESET
     * itself, ...) is purely local — it never talks to the host at all, so if tn5250j locks the
     * keyboard right after one of those, it's unambiguously the client-side X-error rejection
     * (e.g. backspacing past a field's start, or typing into a protected area) — never "waiting
     * for the host". RESET deliberately does NOT belong in this set even though it's an
     * attention-style key: it doesn't transmit either (it's the client-local "acknowledge the
     * error" action), and there's nothing meaningful to waitReady() for after it — see
     * autoRecoverKeyboardLock()'s doc for why waiting on it is actively harmful. */
    private static final java.util.Set<KeyMnemonic> TRANSMITS_TO_HOST = java.util.EnumSet.of(
        KeyMnemonic.ENTER, KeyMnemonic.CLEAR, KeyMnemonic.SYSREQ, KeyMnemonic.ATTN, KeyMnemonic.HELP,
        KeyMnemonic.PRINT,
        KeyMnemonic.PF1, KeyMnemonic.PF2, KeyMnemonic.PF3, KeyMnemonic.PF4, KeyMnemonic.PF5,
        KeyMnemonic.PF6, KeyMnemonic.PF7, KeyMnemonic.PF8, KeyMnemonic.PF9, KeyMnemonic.PF10,
        KeyMnemonic.PF11, KeyMnemonic.PF12, KeyMnemonic.PF13, KeyMnemonic.PF14, KeyMnemonic.PF15,
        KeyMnemonic.PF16, KeyMnemonic.PF17, KeyMnemonic.PF18, KeyMnemonic.PF19, KeyMnemonic.PF20,
        KeyMnemonic.PF21, KeyMnemonic.PF22, KeyMnemonic.PF23, KeyMnemonic.PF24,
        KeyMnemonic.PA1, KeyMnemonic.PA2, KeyMnemonic.PA3
    );

    /** See TRANSMITS_TO_HOST - if the keyboard is locked right after a purely local edit key
     * (never called for an AID key), it's the tn5250j X-error state, and nothing but a RESET
     * clears it (see Session5250.signalBell's Toolkit.beep() - real, audible, on THIS machine).
     * Recovering here means the operator often never even notices a stuck keyboard.
     *
     * A single fire-and-forget sendKeys(RESET) is NOT reliable enough on its own: confirmed
     * empirically that isKeyBoardLocked() can stay true for a beat after RESET was already sent
     * (tn5250j settles it on its own schedule, not synchronously within this call) - and since
     * sendKey()/sendText() call this BEFORE sending the real keystroke too (a key sent while
     * still locked gets silently dropped, not queued - without a reliable pre-check the user's
     * very first keystroke after an error would be eaten and need a second press), a single
     * attempt isn't enough. So poll for a SHORT bounded window instead. This is safe to do inside
     * Terminal's own monitor lock (unlike the old approach here) precisely because
     * SessionService's "key" handler no longer chains a 15-SECOND waitReady() onto local keys -
     * the only thing blocked by this loop is other requests against this same session, for at
     * most ~½s, not the multi-second hang that combination used to cause. */
    private void autoRecoverKeyboardLock() {
        for (int attempt = 0; attempt < 5 && screen.getOIA().isKeyBoardLocked(); attempt++) {
            screen.sendKeys(KeyMnemonic.RESET);
            sleep(100);
        }
    }

    /** Used by typeLabel()/typeAt() - deliberately the OPPOSITE of autoRecoverKeyboardLock():
     * FAILS instead of silently pressing RESET and continuing. A lock at this point can't be
     * distinguished from "the client-side X-error autoRecoverKeyboardLock() exists for" and
     * "the host just legitimately REJECTED the previous submission" (e.g. a failed sign-on) -
     * only sendKey()/sendText() know it's safe to auto-clear, because they're only ever called
     * for keys in the local-only, never-transmits-to-host set (see TRANSMITS_TO_HOST) - a lock
     * there genuinely can only be the client-side kind. typeLabel()/typeAt() have no such
     * guarantee: whatever ran right before them could easily have been an AID key (ENTER) that
     * the host just rejected. Silently RESETting past a real rejection and typing into whatever
     * field happens to be at that row/col on a completely different, unexpected screen is worse
     * than failing loudly - it's exactly how one bad step turns into a suite that blunders
     * through its entire remaining recording on the wrong screen, each mistyped field digging
     * the hole deeper, instead of stopping right where the real problem is. */
    private void requireKeyboardUnlocked() {
        if (screen.getOIA().isKeyBoardLocked()) {
            throw new RuntimeException("Keyboard is locked - the host likely rejected the previous "
                + "action (e.g. an incorrect sign-on) - press Reset and check the screen before retrying");
        }
    }

    /** Whether sending this key actually transmits to the host (see TRANSMITS_TO_HOST) - exposed
     * so callers (SessionService's "key" case) know when it's meaningful to wait for a host
     * response (waitReady()) versus when the key was purely local and there's nothing to wait
     * for at all. */
    public static boolean transmitsToHost(KeyMnemonic key) {
        return TRANSMITS_TO_HOST.contains(key);
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
        int idx = indexOfLabel(text, label);
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

    /** Where a label sits in the screen buffer: exact match, then case-insensitive, then - for a
     * right-to-left label typed normally (reading order) - its cell-order form, searched in a
     * copy of the buffer with Arabic presentation forms folded to plain letters. Every variant
     * is the same length as the label, so the index is a real screen position either way. See
     * Bidi for why the buffer holds such text back to front. */
    private static int indexOfLabel(String text, String label) {
        int idx = text.indexOf(label);
        if (idx < 0) idx = text.toUpperCase().indexOf(label.toUpperCase());
        if (idx < 0 && Bidi.hasRtl(label)) {
            idx = Bidi.foldPresentationForms(text).indexOf(Bidi.toVisual(label));
        }
        return idx;
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
        int idx = indexOfLabel(text, label);
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

    /** 1-based row text, an explicit 1-based inclusive column range, trimmed — for pulling just
     * one column of a subfile/list row (job name, status, ...) instead of the whole 80-char line. */
    public synchronized String rowText(int row, int colStart, int colEnd) {
        requireConnected();
        char[] all = screen.getScreenAsChars();
        int cols = screen.getColumns();
        int rowStart = (row - 1) * cols;
        int start = rowStart + Math.max(0, colStart - 1);
        int end = Math.min(rowStart + Math.min(colEnd, cols), rowStart + cols);
        if (start >= end) return "";
        return new String(all, start, end - start).stripTrailing();
    }

    /** Same range as rowText(row, colStart, colEnd) but WITHOUT stripping trailing whitespace -
     * table column-boundary detection (StepActions.doExtractTable) needs to see every actual
     * blank character position, not a trimmed string, to find the gaps between columns. */
    public synchronized String rowTextRaw(int row, int colStart, int colEnd) {
        requireConnected();
        char[] all = screen.getScreenAsChars();
        int cols = screen.getColumns();
        int rowStart = (row - 1) * cols;
        int start = rowStart + Math.max(0, colStart - 1);
        int end = Math.min(rowStart + Math.min(colEnd, cols), rowStart + cols);
        if (start >= end) return "";
        return new String(all, start, end - start);
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
