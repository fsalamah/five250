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
        return run(flow, t, rows, r -> {});
    }

    /** Same as run(), but invokes onEach immediately after each scenario finishes, for live progress reporting. */
    public static List<ScenarioResult> run(Flow flow, Terminal t, List<Map<String, String>> rows, Consumer<ScenarioResult> onEach) {
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
            ScenarioResult r = flow.runGroup(t, group.getKey(), group.getValue());
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
}
