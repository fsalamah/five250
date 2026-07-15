package com.acabes.five250;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A flow whose navigation is entirely CSV data, not code. Each scenario is a group of rows
 * sharing the same "case" value, executed in "step" order. Columns:
 *
 *   case      groups rows into one scenario
 *   step      execution order within a case (numeric)
 *   action    type | key | check | extract | include | connect | wait
 *   target    type: "label:<text>" or "<row>,<col>"
 *             check/extract: "label:<text>", "row:<n>", or "message" (row 24).
 *                       "label:" reads straight off the character buffer (Terminal.readAfterLabel),
 *                       so it works for protected/display-only text (a balance, a job count) —
 *                       not just editable fields, unlike the "type" action's label targeting.
 *             include: name of another CSV file in this same flow's folder (no ".csv"), whose
 *                       steps are spliced in at this point — reuse a suite inside another suite
 *             connect: host to connect to, e.g. pub400.com
 *             key/wait: unused
 *   value     type: text to enter. key: AID key name (ENTER, F3, PAGEDOWN, ...).
 *             connect: port (default 23). wait: seconds to sleep (0-120).
 *             extract: output field name to store the read value under (structured output, not
 *                       a pass/fail check — appears in the results CSV/JSON as its own column).
 *             check/include: unused
 *   expected  check: substring the target's actual text must contain to pass.
 *             connect: "true" for SSL, otherwise plain telnet. wait/include/extract: unused
 *
 * "wait" is a deliberate, capped exception to the "never sleep" rule — use it only for delays
 * outside the 5250 buffer (a batch job finishing) that waitReady()'s polling can't see.
 *
 * "connect" is NOT executed by this class — HttpApi.autoConnectIfNeeded() intercepts it before
 * the run even starts, so a whole suite can go from a cold, disconnected session to a finished
 * run with one click. Put it alone in its own case (e.g. case="setup"); that case is stripped
 * before execution either way, whether or not a new connection was actually needed.
 *
 * Any cell may contain ${NAME} placeholders — see Variables.java; substitution happens before
 * these steps run, driven by a name/value CSV alongside the scenario file.
 *
 * Add a new automation by adding CSV rows with a new "case" value — no Java change needed.
 */
public final class GenericStepFlow implements Flow {

    private static final long WAIT_MS = 8000;

    @Override
    public String name() {
        return "custom-steps";
    }

    @Override
    public List<String> csvColumns() {
        return List.of("case", "step", "action", "target", "value", "expected");
    }

    @Override
    public String groupColumn() {
        return "case";
    }

    @Override
    public List<Map<String, String>> preprocess(File flowDir, List<Map<String, String>> rows) {
        boolean hasInclude = rows.stream()
            .anyMatch(r -> "include".equalsIgnoreCase(r.getOrDefault("action", "").trim()));
        if (!hasInclude) return rows;

        List<Map<String, String>> result = new ArrayList<>();
        for (Map.Entry<String, List<Map<String, String>>> group : groupByCase(rows).entrySet()) {
            result.addAll(expandCase(flowDir, group.getKey(), group.getValue(), new HashSet<>()));
        }
        return result;
    }

    private List<Map<String, String>> expandCase(File flowDir, String caseId, List<Map<String, String>> steps, Set<String> chain) {
        List<Map<String, String>> ordered = new ArrayList<>(steps);
        ordered.sort(Comparator.comparingInt(GenericStepFlow::stepNum));

        List<Map<String, String>> expanded = new ArrayList<>();
        for (Map<String, String> row : ordered) {
            String action = row.getOrDefault("action", "").trim().toLowerCase();
            if (!action.equals("include")) {
                expanded.add(row);
                continue;
            }
            String target = row.getOrDefault("target", "").trim();
            if (target.isEmpty()) throw new RuntimeException("include step has no target file name");
            if (chain.contains(target)) throw new RuntimeException("circular include detected: " + target);

            List<Map<String, String>> includedRows;
            try {
                includedRows = Csv.read(new File(flowDir, target + ".csv"));
            } catch (IOException e) {
                throw new RuntimeException("cannot read include target '" + target + "': " + e.getMessage());
            }
            if (includedRows.isEmpty()) {
                throw new RuntimeException("include target '" + target + "' not found or empty in " + flowDir);
            }

            Set<String> nextChain = new HashSet<>(chain);
            nextChain.add(target);
            for (List<Map<String, String>> subSteps : groupByCase(includedRows).values()) {
                expanded.addAll(expandCase(flowDir, caseId, subSteps, nextChain));
            }
        }

        List<Map<String, String>> renumbered = new ArrayList<>();
        int n = 1;
        for (Map<String, String> row : expanded) {
            Map<String, String> copy = new LinkedHashMap<>(row);
            copy.put("case", caseId);
            copy.put("step", String.valueOf(n++));
            renumbered.add(copy);
        }
        return renumbered;
    }

    private static Map<String, List<Map<String, String>>> groupByCase(List<Map<String, String>> rows) {
        Map<String, List<Map<String, String>>> groups = new LinkedHashMap<>();
        for (Map<String, String> row : rows) {
            groups.computeIfAbsent(row.getOrDefault("case", ""), k -> new ArrayList<>()).add(row);
        }
        return groups;
    }

    @Override
    public ScenarioResult runGroup(Terminal t, String caseId, List<Map<String, String>> steps) {
        Map<String, String> summary = new LinkedHashMap<>();
        summary.put("case", caseId);
        summary.put("steps", String.valueOf(steps.size()));
        ScenarioResult result = new ScenarioResult(summary);

        List<Map<String, String>> ordered = new ArrayList<>(steps);
        ordered.sort(Comparator.comparingInt(GenericStepFlow::stepNum));

        try {
            int i = 0;
            int total = ordered.size();
            for (Map<String, String> row : ordered) {
                i++;
                String action = row.getOrDefault("action", "").trim().toLowerCase();
                String target = row.getOrDefault("target", "").trim();
                String value = row.getOrDefault("value", "");
                String expected = row.getOrDefault("expected", "");

                String label = "step " + i + "/" + total + ": " + action
                    + (target.isEmpty() ? "" : " " + target) + (value.isEmpty() ? "" : " = " + value);
                Progress.report("case " + caseId + " - " + label);

                switch (action) {
                    case "type":
                        doType(t, target, value);
                        break;
                    case "key":
                        t.sendKey(KeyMap.resolve(value));
                        t.waitReady(WAIT_MS);
                        break;
                    case "check": {
                        String actual = doCheck(t, target);
                        boolean pass = expected.isBlank() || actual.toUpperCase().contains(expected.toUpperCase());
                        result.check("step" + i + ":" + target, expected, actual, pass);
                        break;
                    }
                    case "wait":
                        doWait(value);
                        break;
                    case "extract": {
                        if (value.isBlank()) {
                            throw new RuntimeException("extract step needs an output field name in 'value', at step " + i);
                        }
                        result.extract(value.trim(), doCheck(t, target));
                        break;
                    }
                    default:
                        throw new RuntimeException("Unknown action '" + action + "' at step " + i
                            + " (expected type, key, check, extract, include, or wait)");
                }

                try {
                    result.step(label, t.snapshot());
                } catch (Exception ignored) {
                    // best-effort — don't let capture failure abort an otherwise-fine run
                }
            }
        } catch (Exception e) {
            result.error = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        }

        if (!result.passed() && result.screenOnFailure == null) {
            try {
                result.screenOnFailure = t.snapshot();
            } catch (Exception ignored) {
                // terminal may be disconnected after a failure; nothing more we can capture
            }
        }
        return result;
    }

    private static final double MAX_WAIT_SECONDS = 120;

    /**
     * Explicit delay, for waiting on something outside the 5250 buffer (a batch job, a queued
     * process) that waitReady()'s keyboard/buffer-stability polling can't detect. Everywhere
     * else, never sleep — this is the one deliberate, opt-in exception, and it's capped so a
     * typo can't wedge a suite for hours.
     */
    private void doWait(String value) {
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

    private void doType(Terminal t, String target, String value) {
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

    private String doCheck(Terminal t, String target) {
        if (target.equalsIgnoreCase("message")) return t.messageLine();
        if (target.startsWith("label:")) return t.readAfterLabel(target.substring(6));
        if (target.startsWith("row:")) return t.rowText(Integer.parseInt(target.substring(4).trim()));
        try {
            return t.rowText(Integer.parseInt(target.trim()));
        } catch (NumberFormatException e) {
            throw new RuntimeException("check target must be 'message', 'row:<n>', or 'label:<text>', got: " + target);
        }
    }

    private static int stepNum(Map<String, String> row) {
        try {
            return Integer.parseInt(row.getOrDefault("step", "0").trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
