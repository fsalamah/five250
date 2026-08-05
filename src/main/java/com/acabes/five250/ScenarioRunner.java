package com.acabes.five250;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/** Runs every row (or, for grouped flows, every case) of a CSV scenario file against a live Terminal. */
public final class ScenarioRunner {

    private ScenarioRunner() {}

    public static List<ScenarioResult> run(Flow flow, Terminal t, List<Map<String, String>> rows) {
        return run(flow, t, rows, new LinkedHashMap<>(), StepActions.DEFAULT_STEP_DELAY_MS, r -> {});
    }

    /** Same as run(), but invokes onEach immediately after each scenario finishes, for live
     * progress reporting. vars is LIVE (mutated in place by "extract" steps as the run
     * proceeds) and shared across every group/case in this run - see Flow.runGroup.
     * stepDelayMs is the configurable gap held after each step - see
     * StepActions.executeAction's doc. */
    public static List<ScenarioResult> run(Flow flow, Terminal t, List<Map<String, String>> rows,
                                            Map<String, String> vars, long stepDelayMs, Consumer<ScenarioResult> onEach) {
        List<ScenarioResult> results = new ArrayList<>();
        String groupColumn = flow.groupColumn();
        if (groupColumn == null) {
            for (Map<String, String> row : rows) {
                ScenarioResult r = flow.run(t, row);
                results.add(r);
                onEach.accept(r);
            }
            return results;
        }

        for (Map.Entry<String, List<Map<String, String>>> group : groupBy(rows, groupColumn).entrySet()) {
            ScenarioResult r = flow.runGroup(t, group.getKey(), group.getValue(), vars, stepDelayMs);
            results.add(r);
            onEach.accept(r);
        }
        return results;
    }

    /** Number of scenarios a CSV will produce for a flow (rows for per-row flows, distinct groups for grouped ones). */
    public static int countScenarios(Flow flow, List<Map<String, String>> rows) {
        String groupColumn = flow.groupColumn();
        return groupColumn == null ? rows.size() : groupBy(rows, groupColumn).size();
    }

    private static Map<String, List<Map<String, String>>> groupBy(List<Map<String, String>> rows, String groupColumn) {
        Map<String, List<Map<String, String>>> groups = new LinkedHashMap<>();
        for (Map<String, String> row : rows) {
            String groupId = row.getOrDefault(groupColumn, "");
            groups.computeIfAbsent(groupId, k -> new ArrayList<>()).add(row);
        }
        return groups;
    }

    public static List<ScenarioResult> runFromCsv(Flow flow, Terminal t, File csvFile) throws IOException {
        return run(flow, t, Csv.read(csvFile));
    }

    public static void writeResults(File out, List<ScenarioResult> results) throws IOException {
        List<Map<String, String>> rows = new ArrayList<>();
        for (ScenarioResult r : results) rows.add(r.toResultRow());
        Csv.write(out, rows);
    }

    /** Writes one screen-dump JSON per failed row for debugging, named by row index. */
    public static void writeFailureDumps(File dir, List<ScenarioResult> results) throws IOException {
        dir.mkdirs();
        for (int i = 0; i < results.size(); i++) {
            ScenarioResult r = results.get(i);
            if (r.passed() || r.screenOnFailure == null) continue;
            File f = new File(dir, "row-" + (i + 1) + ".json");
            java.nio.file.Files.writeString(f.toPath(), Json.write(r.toMap()));
        }
    }

    /**
     * Writes one replay JSON per scenario (pass or fail), containing its full step-by-step
     * screen captures — for the "view and replay the execution" viewer, available even after
     * the run's in-memory RunState is gone.
     */
    public static void writeReplays(File dir, List<ScenarioResult> results) throws IOException {
        dir.mkdirs();
        for (int i = 0; i < results.size(); i++) {
            File f = new File(dir, "row-" + (i + 1) + ".json");
            java.nio.file.Files.writeString(f.toPath(), Json.write(results.get(i).toMap()));
        }
    }

    /** Sidecar written alongside the untimestamped "latest" replay dump - the row-N.json files
     * themselves carry no run-level timestamp or count, so a GUI reopened after the run finished
     * (or never watched it live at all) has no other way to know when a suite/script last ran, or
     * how many row-N.json files to fetch to reconstruct that run's full Results table. Timestamped
     * copies don't get their own copy of this - their timestamp is already in their own directory
     * name. */
    public static void writeLastRunInfo(File dir, String timestamp, int count) throws IOException {
        dir.mkdirs();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("timestamp", timestamp);
        m.put("count", count);
        java.nio.file.Files.writeString(new File(dir, "last-run.json").toPath(), Json.write(m));
    }

    /**
     * Dumps every `extract` step's output into its own plain-text file, one per distinct output
     * name (the name an engineer gave that extract step, in its "value" cell) — separate from the
     * results CSV, for feeding straight into another tool without CSV-column bookkeeping. Named
     * "<baseName>.<extractName>[.<ts>].data.txt" (ts omitted when null - the "latest" copy). A
     * "rows:" range (a List) is one line per row; a single value is one line. If more than one
     * scenario in this run produced the same name, each occurrence is one more line, in run order.
     */
    public static void writeExtractedDumps(File dir, String baseName, String ts, List<ScenarioResult> results) throws IOException {
        Map<String, List<String>> lines = new LinkedHashMap<>();
        for (ScenarioResult r : results) {
            for (Map.Entry<String, Object> e : r.extracted.entrySet()) {
                List<String> out = lines.computeIfAbsent(e.getKey(), k -> new ArrayList<>());
                Object v = e.getValue();
                if (v instanceof List) {
                    // A "rows:" extraction is List<String> - one line per row, as before. A
                    // "table:" extraction is List<List<String>> - one line per row too, but each
                    // row's cells need flattening first (same " | " join ScenarioResult's CSV
                    // export uses), or this addAll would try to write raw List objects as lines.
                    for (Object item : (List<?>) v) {
                        if (item instanceof List) {
                            List<String> cells = new ArrayList<>();
                            for (Object cell : (List<?>) item) cells.add(String.valueOf(cell));
                            out.add(String.join(" | ", cells));
                        } else {
                            out.add(String.valueOf(item));
                        }
                    }
                } else {
                    out.add(String.valueOf(v));
                }
            }
        }
        if (lines.isEmpty()) return;
        dir.mkdirs();
        for (Map.Entry<String, List<String>> e : lines.entrySet()) {
            String name = baseName + "." + e.getKey() + (ts == null ? "" : "." + ts) + ".data.txt";
            java.nio.file.Files.writeString(new File(dir, name).toPath(), String.join("\n", e.getValue()) + "\n");
        }
    }
}
