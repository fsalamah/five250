package com.acabes.five250;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Executes one "type"/"key"/"check"/"wait"/"disconnect"/"extract"/"screenshot" row against a live Terminal.
 * Shared by GenericStepFlow (CSV-driven execution, including its "if"/"loop" interpreter) and
 * JsSuiteRunner (JS-orchestrated execution) so both drive the terminal through identical logic -
 * no behavior duplicated or allowed to drift between the two authoring styles.
 *
 * "vars" is the SAME map that {@code ${NAME}} substitution reads from - unifying "variables I
 * set" and "values extracted off the screen" into one live bag, the way JsSuiteRunner's
 * {@code suiteX.vars} needs it to work (set a var, execute a range, read back whatever an
 * "extract" step inside that range just wrote). GenericStepFlow, which doesn't yet expose vars
 * back to anything, can pass an empty/throwaway map if it has no live vars bag to share.
 *
 * Control-flow actions (if/else/endif/loop/endloop), "include", and "connect" are NOT handled
 * here - they're resolved by callers before a row ever reaches this class (GenericStepFlow's own
 * interpreter, HttpApi.autoConnectIfNeeded, and Flow.preprocess, respectively).
 */
final class StepActions {

    private static final long WAIT_READY_MS = 8000;
    /** Timeout for waitUntilInputAllowed()'s pre-step "console isn't busy" check - deliberately
     * more generous than WAIT_READY_MS (8s): that constant times a wait AFTER an AID key we just
     * sent ourselves, so we know something is genuinely in flight; this one times how long we're
     * willing to wait for the terminal to become ready BEFORE any step at all, including e.g. a
     * "type" step ends up right after an unusually slow host response. 30s errs toward not
     * false-failing a legitimately slow (but still working) real host under load. */
    private static final long INPUT_ALLOWED_TIMEOUT_MS = 30000;
    private static final double MAX_WAIT_SECONDS = 120;

    private StepActions() {}

    /** Default gap held between steps, after each one finishes - see executeAction's stepDelayMs
     * param. 200ms: enough real wall-clock time for a slower/real host's screen update to fully
     * arrive before the NEXT step reads or acts on it, without making a normal run noticeably
     * slower. Configurable per-run (HttpApi's "stepDelayMs" request field, JsSuiteRunner's script
     * args) - suites/scripts against a known-fast host can lower it, a flaky one can raise it. */
    static final long DEFAULT_STEP_DELAY_MS = 200;

    /** Runs one action row, records checks/extracted values into result, and captures a screen
     * snapshot under "label" for the replay viewer.
     * @param stepDelayMs held AFTER this step completes, before returning control to the caller's
     *     loop - a real, configurable gap between steps (not just the reactive waitReady()/
     *     waitUntilInputAllowed() checks), so a step that technically doesn't need to wait for a
     *     host response still doesn't fire the very next step in the same instant. */
    static void executeAction(Terminal t, ScenarioResult result, Map<String, String> vars,
                               Map<String, String> row, int stepNo, String label, long stepDelayMs) {
        String action = row.getOrDefault("action", "").trim().toLowerCase();
        String target = row.getOrDefault("target", "").trim();
        String value = row.getOrDefault("value", "");
        String expected = row.getOrDefault("expected", "");

        switch (action) {
            case "type":
                // "The console isn't busy" as an explicit precondition, not just a courtesy wait
                // after the PREVIOUS step's AID key - see Terminal.waitUntilInputAllowed's doc
                // for the race this closes (a slower/real host still mid-update when the next
                // step acts, something a fast local test against a quick host can fully mask).
                t.waitUntilInputAllowed(INPUT_ALLOWED_TIMEOUT_MS);
                doType(t, target, value);
                break;
            case "key":
                t.waitUntilInputAllowed(INPUT_ALLOWED_TIMEOUT_MS);
                t.sendKey(KeyMap.resolve(value));
                t.waitReady(WAIT_READY_MS);
                break;
            case "check": {
                t.waitUntilInputAllowed(INPUT_ALLOWED_TIMEOUT_MS);
                String actual = doCheck(t, target);
                boolean pass = expected.isBlank() || actual.toUpperCase().contains(expected.toUpperCase());
                result.check("step" + stepNo + ":" + target, expected, actual, pass);
                break;
            }
            case "wait":
                doWait(value);
                break;
            case "disconnect":
                t.disconnect();
                break;
            case "screenshot": {
                // value = the image's name (target accepted too, for a row that put it there);
                // blank falls back to "step<N>" so a bare "screenshot" row still produces a file.
                // No waitUntilInputAllowed() here on purpose: a screenshot is often wanted
                // precisely BECAUSE the screen is in an odd state (locked on an error), and the
                // previous step's own waitReady() has already let a normal screen settle.
                String name = !value.isBlank() ? value.trim() : !target.isBlank() ? target : "step" + stepNo;
                result.screenshot(HttpApi.safeName(name), ScreenImage.png(t.snapshot()));
                break;
            }
            case "extract": {
                if (value.isBlank()) {
                    throw new RuntimeException("extract step needs an output field name in 'value', at step " + stepNo);
                }
                t.waitUntilInputAllowed(INPUT_ALLOWED_TIMEOUT_MS);
                String name = value.trim();
                if (target.startsWith("table:")) {
                    List<List<String>> table = doExtractTable(t, target);
                    result.extractTable(name, table);
                    if (vars != null) vars.put(name, flattenTable(table));
                } else if (target.startsWith("rows:")) {
                    List<String> rows = doExtractRows(t, target);
                    result.extractRows(name, rows);
                    // Real "\n" between rows, matching flattenTable()'s "table:" treatment just
                    // above - a script reading suite.vars.NAME (saveJson, console.log, ...) gets
                    // the box back as genuine multi-line text, not " | "-joined onto one line.
                    if (vars != null) vars.put(name, String.join("\n", rows));
                } else {
                    String extracted = doCheck(t, target);
                    result.extract(name, extracted);
                    if (vars != null) vars.put(name, extracted);
                }
                break;
            }
            default:
                throw new RuntimeException("Unknown action '" + action + "' at step " + stepNo
                    + " (expected type, key, check, extract, screenshot, wait, or disconnect)");
        }

        try {
            result.step(label, t.snapshot());
        } catch (Exception ignored) {
            // best-effort — don't let capture failure abort an otherwise-fine run
        }

        // Held even for actions that didn't strictly need to wait for anything (a local "type"),
        // deliberately - see stepDelayMs's doc. Absorbs (not propagates) an interrupt from
        // RunTracker.cancel(): killing a run should stop it at the next real Terminal call
        // (which will throw once the session's force-disconnected), not get stuck inside this
        // sleep - restoring the interrupt flag is enough for that next call's own checks to see it.
        if (stepDelayMs > 0 && !"disconnect".equals(action)) {
            try {
                Thread.sleep(stepDelayMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * Explicit delay, for waiting on something outside the 5250 buffer (a batch job, a queued
     * process) that waitReady()'s keyboard/buffer-stability polling can't detect. Everywhere
     * else, never sleep — this is the one deliberate, opt-in exception, and it's capped so a
     * typo can't wedge a suite for hours.
     */
    private static void doWait(String value) {
        double seconds;
        try {
            seconds = Double.parseDouble(value.trim());
        } catch (NumberFormatException e) {
            throw new RuntimeException("wait value must be a number of seconds, got: " + value);
        }
        if (seconds < 0 || seconds > MAX_WAIT_SECONDS) {
            throw new RuntimeException("wait value must be between 0 and " + (int) MAX_WAIT_SECONDS
                + " seconds, got: " + seconds);
        }
        try {
            Thread.sleep((long) (seconds * 1000));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("wait interrupted");
        }
    }

    private static void doType(Terminal t, String target, String value) {
        if (target.startsWith("label:")) {
            // A label that can't be found at all is a strong signal the flow has landed on a
            // genuinely different screen than expected — worth stopping the case for, so this
            // stays strict (typeLabel throws if no field is found near the label).
            t.typeLabel(new String[]{target.substring(6)}, value);
        } else if (target.contains(",")) {
            // Recorded row/col "type" rows come from live character-by-character typing
            // (Terminal.sendText at the real cursor — see RecordingState.appendTypedChar), which
            // never throws for "nothing registered here": screen.sendKeys() just types wherever
            // the cursor is, silently having no visible effect if that position isn't inside a
            // real field. Replaying through the OLD typeAt()/findByPosition() path — which DOES
            // throw in that case — was stricter than what actually happened live, aborting runs
            // at steps that never failed during recording. Use the same primitives that recorded
            // it (setCursor + sendText) so replay reproduces exactly what happened, including a
            // step quietly doing nothing, not a check the live session never enforced.
            String[] parts = target.split(",");
            int row = Integer.parseInt(parts[0].trim());
            int col = Integer.parseInt(parts[1].trim());
            t.setCursor(row, col);
            t.sendText(value);
        } else {
            throw new RuntimeException("type target must be 'label:<text>' or '<row>,<col>', got: " + target);
        }
    }

    static String doCheck(Terminal t, String target) {
        if (target.equalsIgnoreCase("message")) return t.messageLine();
        if (target.startsWith("label:")) return t.readAfterLabel(target.substring(6));
        if (target.startsWith("row:")) return t.rowText(Integer.parseInt(target.substring(4).trim()));
        try {
            return t.rowText(Integer.parseInt(target.trim()));
        } catch (NumberFormatException e) {
            throw new RuntimeException("check target must be 'message', 'row:<n>', or 'label:<text>', got: " + target);
        }
    }

    /**
     * Multi-row extraction off a subfile/list screen (WRKACTJOB's job list, WRKSPLF's spool
     * list, ...) — target is "rows:<start>-<end>" for the full width of each row, or
     * "rows:<start>-<end>:<colStart>-<colEnd>" to pull just one column (job name, status, ...)
     * out of each row instead of the whole 80-char line.
     */
    /** Ad-hoc extraction for SessionService's live "extract-now" RPC (the GUI's Extract
     * Area/Extract Table buttons used outside recording) — same target syntax the "extract"
     * action step above uses (message/row:/label:/rows:/table:), returns whatever shape matches:
     * a String for a single value, List&lt;String&gt; for "rows:", List&lt;List&lt;String&gt;&gt;
     * for "table:". */
    static Object extractByTarget(Terminal t, String target) {
        if (target.startsWith("table:")) return doExtractTable(t, target);
        if (target.startsWith("rows:")) return doExtractRows(t, target);
        return doCheck(t, target);
    }

    static List<String> doExtractRows(Terminal t, String target) {
        String spec = target.substring(5); // after "rows:"
        String[] parts = spec.split(":");
        int[] rowRange = parseRange(parts[0], target);
        if (rowRange[1] < rowRange[0]) {
            throw new RuntimeException("rows: end must be >= start, got: " + target);
        }

        int[] colRange = parts.length > 1 ? parseRange(parts[1], target) : null;

        List<String> out = new ArrayList<>();
        for (int r = rowRange[0]; r <= rowRange[1]; r++) {
            out.add(colRange != null ? t.rowText(r, colRange[0], colRange[1]) : t.rowText(r));
        }
        return out;
    }

    /**
     * Multi-row, multi-COLUMN extraction off a subfile/list screen — target is
     * "table:<start>-<end>" or "table:<start>-<end>:<colStart>-<colEnd>", same range syntax as
     * "rows:", but instead of one raw text slice per row, each row is split into individual
     * cells at auto-detected column boundaries (see splitIntoColumns) — real structured table
     * data (a value per column, e.g. job name / status / CPU%), not one flat string per row.
     */
    static List<List<String>> doExtractTable(Terminal t, String target) {
        String spec = target.substring(6); // after "table:"
        String[] parts = spec.split(":");
        int[] rowRange = parseRange(parts[0], target);
        if (rowRange[1] < rowRange[0]) {
            throw new RuntimeException("table: end must be >= start, got: " + target);
        }
        int[] colRange = parts.length > 1 ? parseRange(parts[1], target) : null;
        int colStart = colRange != null ? colRange[0] : 1;
        int colEnd = colRange != null ? colRange[1] : Integer.MAX_VALUE; // rowTextRaw clamps to actual screen width

        List<String> rawRows = new ArrayList<>();
        for (int r = rowRange[0]; r <= rowRange[1]; r++) {
            rawRows.add(t.rowTextRaw(r, colStart, colEnd));
        }
        return splitIntoColumns(rawRows);
    }

    /** A single blank column isn't a reliable column boundary on its own — a "Label . . . :
     * Value"-style dot-leader (single spaces between each dot) would otherwise get sliced into
     * five separate one-character "columns". Real subfile field gaps are conventionally 2+
     * spaces wide, so only a gap at least this wide counts as a real separator; anything
     * narrower gets absorbed back into its neighboring column. */
    private static final int MIN_COLUMN_GAP = 2;

    /**
     * Turns fixed-width "ASCII table" text into real columns without the user having to draw
     * each column boundary by hand: any character position that is blank across EVERY selected
     * row is a candidate separator; consecutive separator positions merge into one gap, and gaps
     * narrower than MIN_COLUMN_GAP are discarded (see its doc); each row is split at the
     * remaining gaps into trimmed cell values. This is the standard heuristic for fixed-width
     * text tables (5250 subfiles are exactly that - no HTML, no delimiters) and it degrades
     * gracefully: if no column ever has a wide enough all-blank run (a fully packed or
     * single-space-separated line), the whole range comes back as one column, same as "rows:"
     * would.
     */
    private static List<List<String>> splitIntoColumns(List<String> rawRows) {
        if (rawRows.isEmpty()) return List.of();
        int width = 0;
        for (String r : rawRows) width = Math.max(width, r.length());
        boolean[] blank = new boolean[width];
        Arrays.fill(blank, true);
        for (String r : rawRows) {
            for (int c = 0; c < width; c++) {
                char ch = c < r.length() ? r.charAt(c) : ' ';
                if (ch != ' ') blank[c] = false;
            }
        }
        List<int[]> rawSpans = new ArrayList<>();
        int c = 0;
        while (c < width) {
            if (blank[c]) { c++; continue; }
            int start = c;
            while (c < width && !blank[c]) c++;
            rawSpans.add(new int[]{start, c});
        }
        // Merge spans separated by a gap narrower than MIN_COLUMN_GAP - that gap wasn't a real
        // column boundary, just incidental whitespace inside what's really one column's text.
        List<int[]> spans = new ArrayList<>();
        for (int[] span : rawSpans) {
            if (!spans.isEmpty() && span[0] - spans.get(spans.size() - 1)[1] < MIN_COLUMN_GAP) {
                spans.get(spans.size() - 1)[1] = span[1];
            } else {
                spans.add(span);
            }
        }
        if (spans.isEmpty()) spans.add(new int[]{0, width}); // every row was entirely blank in this range

        List<List<String>> table = new ArrayList<>();
        for (String r : rawRows) {
            List<String> cells = new ArrayList<>();
            for (int[] span : spans) {
                int s = Math.min(span[0], r.length());
                int e = Math.min(span[1], r.length());
                cells.add(s < e ? r.substring(s, e).trim() : "");
            }
            table.add(cells);
        }
        return table;
    }

    /** Flattened representation of an extracted table for suite.vars.NAME - cells within a row
     * joined with " | ", rows joined with a real "\n" (matching how the box looked on screen),
     * so a script reading this back (console.log, saveJson, ...) gets genuine multi-line text.
     * The real structured List&lt;List&lt;String&gt;&gt; stays available via
     * ScenarioResult.extracted for scripts/JSON output regardless. */
    private static String flattenTable(List<List<String>> table) {
        List<String> rowStrings = new ArrayList<>();
        for (List<String> row : table) rowStrings.add(String.join(" | ", row));
        return String.join("\n", rowStrings);
    }

    private static int[] parseRange(String part, String fullTarget) {
        String[] bounds = part.trim().split("-");
        if (bounds.length != 2) {
            throw new RuntimeException("extract 'rows:' target must be 'rows:<start>-<end>' or "
                + "'rows:<start>-<end>:<colStart>-<colEnd>', got: " + fullTarget);
        }
        try {
            return new int[]{Integer.parseInt(bounds[0].trim()), Integer.parseInt(bounds[1].trim())};
        } catch (NumberFormatException e) {
            throw new RuntimeException("non-numeric range in extract target: " + fullTarget);
        }
    }
}
