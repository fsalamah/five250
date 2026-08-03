package com.acabes.five250;

import java.io.File;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
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
 *   id        optional, freeform, unique across the whole file — lets a
 *             project/scripts/*.js script address a range by name instead of
 *             raw row position, e.g. mySuite.steps("login", "after-login").
 *             Blank on most rows; unused by this flow itself (see
 *             JsSuiteRunner.buildSuiteObject).
 *   action    type | key | check | extract | include | connect | wait | disconnect |
 *             if | else | endif | loop | endloop
 *   target    type: "label:<text>" or "<row>,<col>"
 *             check/extract: "label:<text>", "row:<n>", or "message" (row 24).
 *                       "label:" reads straight off the character buffer (Terminal.readAfterLabel),
 *                       so it works for protected/display-only text (a balance, a job count) —
 *                       not just editable fields, unlike the "type" action's label targeting.
 *             extract also accepts "rows:<start>-<end>" or "rows:<start>-<end>:<colStart>-<colEnd>"
 *                       to pull multiple lines off a subfile/list screen (WRKACTJOB's job list,
 *                       WRKSPLF's spool list, ...) at once — the output value is a list, not a
 *                       single string; see ScenarioResult.extractRows.
 *             include: name of another CSV file in this same flow's folder (no ".csv"), whose
 *                       steps are spliced in at this point — reuse a suite inside another suite
 *             connect: host to connect to, e.g. pub400.com
 *             if: a JS boolean expression, e.g. "extracted.balance > 100" — see JsCondition.
 *             loop: "while" or "count" (which of target/value is the condition; see below).
 *             key/wait/disconnect/else/endif/endloop: unused
 *   value     type: text to enter. key: AID key name (ENTER, F3, PAGEDOWN, ...).
 *             connect: port (default 23). wait: seconds to sleep (0-120).
 *             extract: output field name to store the read value under (structured output, not
 *                       a pass/fail check — appears in the results CSV/JSON as its own column).
 *             loop: the JS condition (target=while) or the iteration count (target=count).
 *             check/include/disconnect/if/else/endif/endloop: unused
 *   expected  check: substring the target's actual text must contain to pass.
 *             connect: "true" for SSL, otherwise plain telnet. wait/include/extract/disconnect: unused
 *
 * "wait" is a deliberate, capped exception to the "never sleep" rule — use it only for delays
 * outside the 5250 buffer (a batch job finishing) that waitReady()'s polling can't see.
 *
 * "if"/"else"/"endif" and "loop"/"endloop" give a case real control flow, evaluated by
 * JsCondition (a sandboxed embedded JS engine — see that class for why JS and how it's sandboxed).
 * ${NAME} vars are already resolved to literal text before a step reaches this class, so a
 * condition referencing a saved variable is just plain JS (e.g. "${BALANCE} > 100" becomes,
 * after substitution, "350 > 100" — valid JS, no extra binding needed). Only "extracted.<name>"
 * needs an explicit binding, since a value pulled off the screen by an earlier "extract" step in
 * THIS SAME case doesn't exist yet at substitution time — see JsCondition.evaluate. "loop"'s
 * "while" mode re-evaluates its condition every time control returns to it (via "endloop"), so it
 * can react to a value an "extract" step inside the loop body just updated (e.g. page through a
 * subfile: PAGE_DOWN, extract a "more" indicator, loop while extracted.more == true). "count" mode
 * runs the body an exact number of times instead. Every "if"/"loop" step, plus every step actually
 * executed while a loop repeats, counts against a per-case execution budget (MAX_STEP_VISITS) —
 * a safety backstop so a condition that's always true fails the scenario with a clear error
 * instead of hanging the run. Blocks may nest; an unmatched or mismatched marker (e.g. "endif"
 * with no "if") is caught before execution starts, not as a confusing mid-run jump bug — see
 * matchBlocks().
 *
 * "disconnect" closes the socket outright (Terminal.disconnect()) - recorded automatically
 * whenever the live session it was captured from actually disconnected (Disconnect button, or a
 * recorded suite's own signoff), so replay reaches the same end state as the recording, not a
 * lingering connection the recording never had. A later step reconnecting mid-suite is not
 * supported - HttpApi.autoConnectIfNeeded() only auto-connects once, before the run starts.
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
        return List.of("case", "step", "id", "action", "target", "value", "expected");
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

    /** Per-case safety backstop: every step actually executed (including repeated loop-body
     * visits) counts against this budget, so a condition that's always true fails the scenario
     * with a clear error instead of hanging the run. Generous for any real suite. */
    private static final int MAX_STEP_VISITS = 2000;

    /** Hard cap on "loop"'s "count" mode, so a typo (an extra zero) can't wedge a suite. */
    private static final int MAX_LOOP_COUNT = 500;

    @Override
    public ScenarioResult runGroup(Terminal t, String caseId, List<Map<String, String>> steps) {
        Map<String, String> summary = new LinkedHashMap<>();
        summary.put("case", caseId);
        summary.put("steps", String.valueOf(steps.size()));
        ScenarioResult result = new ScenarioResult(summary);

        List<Map<String, String>> ordered = new ArrayList<>(steps);
        ordered.sort(Comparator.comparingInt(GenericStepFlow::stepNum));

        try {
            int[] jumpTarget = matchBlocks(ordered, caseId);
            Map<Integer, Integer> loopCounters = new HashMap<>();
            int total = ordered.size();
            int budget = MAX_STEP_VISITS;
            int i = 0;
            while (i < total) {
                if (budget-- <= 0) {
                    throw new RuntimeException("case '" + caseId + "' exceeded " + MAX_STEP_VISITS
                        + " step executions — likely an infinite loop");
                }
                Map<String, String> row = ordered.get(i);
                String action = row.getOrDefault("action", "").trim().toLowerCase();
                String target = row.getOrDefault("target", "").trim();
                String value = row.getOrDefault("value", "");
                int stepNo = stepNum(row);

                // Control-flow actions jump the instruction pointer directly instead of falling
                // through to the linear step-execution/screen-capture logic below.
                switch (action) {
                    case "if": {
                        boolean cond = JsCondition.evaluate(target, result.extracted);
                        Progress.report("case " + caseId + " - step " + stepNo + "/" + total + ": if " + target + " -> " + cond);
                        i = cond ? i + 1 : jumpTarget[i] + 1;
                        continue;
                    }
                    case "else":
                        i = jumpTarget[i] + 1;
                        continue;
                    case "endif":
                        i++;
                        continue;
                    case "loop": {
                        boolean enter = evaluateLoopEntry(row, i, result.extracted, loopCounters);
                        Progress.report("case " + caseId + " - step " + stepNo + "/" + total + ": loop " + target + " " + value + " -> " + (enter ? "enter" : "exit"));
                        i = enter ? i + 1 : jumpTarget[i] + 1;
                        continue;
                    }
                    case "endloop":
                        i = jumpTarget[i];
                        continue;
                    default:
                        break;
                }

                String label = "step " + stepNo + "/" + total + ": " + action
                    + (target.isEmpty() ? "" : " " + target) + (value.isEmpty() ? "" : " = " + value);
                Progress.report("case " + caseId + " - " + label);

                StepActions.executeAction(t, result, null, row, stepNo, label);
                i++;
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

    private boolean evaluateLoopEntry(Map<String, String> row, int index, Map<String, Object> extracted,
                                       Map<Integer, Integer> loopCounters) {
        String mode = row.getOrDefault("target", "").trim().toLowerCase();
        String value = row.getOrDefault("value", "").trim();
        switch (mode) {
            case "while":
                return JsCondition.evaluate(value, extracted);
            case "count": {
                int limit;
                try {
                    limit = Integer.parseInt(value);
                } catch (NumberFormatException e) {
                    throw new RuntimeException("loop 'count' value must be an integer, got: " + value);
                }
                if (limit < 0 || limit > MAX_LOOP_COUNT) {
                    throw new RuntimeException("loop 'count' must be between 0 and " + MAX_LOOP_COUNT + ", got: " + limit);
                }
                int done = loopCounters.getOrDefault(index, 0);
                boolean enter = done < limit;
                if (enter) {
                    loopCounters.put(index, done + 1);
                } else {
                    loopCounters.remove(index); // reset, in case this loop step re-enters (nested in an outer loop)
                }
                return enter;
            }
            default:
                throw new RuntimeException("loop target must be 'while' or 'count', got: " + row.get("target"));
        }
    }

    /** One open "if" or "loop" block awaiting its closing marker, tracked while matchBlocks()
     * walks a case's steps once, top to bottom. */
    private static final class Block {
        final int start;
        final boolean isLoop;
        int elseIndex = -1;

        Block(int start, boolean isLoop) {
            this.start = start;
            this.isLoop = isLoop;
        }
    }

    /**
     * Matches if/else/endif and loop/endloop pairs within one case's ordered steps into a jump
     * table, so the interpreter above can resolve each marker's target in O(1) instead of
     * scanning forward/backward at runtime. Built once per case before execution starts;
     * mismatched or unmatched markers throw immediately, with the exact case/step, rather than
     * surfacing as a confusing mid-run jump bug.
     *
     * jumpTarget[i] means, depending on the action at i:
     *   if     -> its "else" index if it has one, else its "endif" index (used when false)
     *   else   -> its "endif" index (used whenever forward execution naturally reaches it —
     *             i.e. the true-branch just finished, so the false-branch must be skipped)
     *   loop   -> its "endloop" index (used when the loop condition/count says "don't enter")
     *   endloop-> its "loop" index (always — jump back to re-check the condition)
     */
    private static int[] matchBlocks(List<Map<String, String>> ordered, String caseId) {
        int n = ordered.size();
        int[] jumpTarget = new int[n];
        Deque<Block> stack = new ArrayDeque<>();
        for (int i = 0; i < n; i++) {
            String action = ordered.get(i).getOrDefault("action", "").trim().toLowerCase();
            switch (action) {
                case "if":
                    stack.push(new Block(i, false));
                    break;
                case "loop":
                    stack.push(new Block(i, true));
                    break;
                case "else": {
                    Block top = stack.peek();
                    if (top == null || top.isLoop || top.elseIndex != -1) {
                        throw blockError(caseId, ordered, i, "'else' has no matching 'if'");
                    }
                    top.elseIndex = i;
                    break;
                }
                case "endif": {
                    if (stack.isEmpty() || stack.peek().isLoop) {
                        throw blockError(caseId, ordered, i, "'endif' has no matching 'if'");
                    }
                    Block top = stack.pop();
                    jumpTarget[top.start] = top.elseIndex != -1 ? top.elseIndex : i;
                    if (top.elseIndex != -1) jumpTarget[top.elseIndex] = i;
                    break;
                }
                case "endloop": {
                    if (stack.isEmpty() || !stack.peek().isLoop) {
                        throw blockError(caseId, ordered, i, "'endloop' has no matching 'loop'");
                    }
                    Block top = stack.pop();
                    jumpTarget[top.start] = i;
                    jumpTarget[i] = top.start;
                    break;
                }
                default:
                    break;
            }
        }
        if (!stack.isEmpty()) {
            Block top = stack.peek();
            throw blockError(caseId, ordered, top.start, "unmatched '" + (top.isLoop ? "loop" : "if")
                + "' (missing '" + (top.isLoop ? "endloop" : "endif") + "')");
        }
        return jumpTarget;
    }

    private static RuntimeException blockError(String caseId, List<Map<String, String>> ordered, int idx, String msg) {
        return new RuntimeException("case '" + caseId + "', step " + stepNum(ordered.get(idx)) + ": " + msg);
    }

    static int stepNum(Map<String, String> row) {
        try {
            return Integer.parseInt(row.getOrDefault("step", "0").trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
