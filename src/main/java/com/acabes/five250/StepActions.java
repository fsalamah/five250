package com.acabes.five250;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Executes one "type"/"key"/"check"/"wait"/"disconnect"/"extract" row against a live Terminal.
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
    private static final double MAX_WAIT_SECONDS = 120;

    private StepActions() {}

    /** Runs one action row, records checks/extracted values into result, and captures a screen
     * snapshot under "label" for the replay viewer. */
    static void executeAction(Terminal t, ScenarioResult result, Map<String, String> vars,
                               Map<String, String> row, int stepNo, String label) {
        String action = row.getOrDefault("action", "").trim().toLowerCase();
        String target = row.getOrDefault("target", "").trim();
        String value = row.getOrDefault("value", "");
        String expected = row.getOrDefault("expected", "");

        switch (action) {
            case "type":
                doType(t, target, value);
                break;
            case "key":
                t.sendKey(KeyMap.resolve(value));
                t.waitReady(WAIT_READY_MS);
                break;
            case "check": {
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
            case "extract": {
                if (value.isBlank()) {
                    throw new RuntimeException("extract step needs an output field name in 'value', at step " + stepNo);
                }
                String name = value.trim();
                if (target.startsWith("rows:")) {
                    List<String> rows = doExtractRows(t, target);
                    result.extractRows(name, rows);
                    if (vars != null) vars.put(name, String.join(" | ", rows));
                } else {
                    String extracted = doCheck(t, target);
                    result.extract(name, extracted);
                    if (vars != null) vars.put(name, extracted);
                }
                break;
            }
            default:
                throw new RuntimeException("Unknown action '" + action + "' at step " + stepNo
                    + " (expected type, key, check, extract, wait, or disconnect)");
        }

        try {
            result.step(label, t.snapshot());
        } catch (Exception ignored) {
            // best-effort — don't let capture failure abort an otherwise-fine run
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

    private static String doCheck(Terminal t, String target) {
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
    private static List<String> doExtractRows(Terminal t, String target) {
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
