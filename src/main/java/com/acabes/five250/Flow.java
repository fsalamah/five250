package com.acabes.five250;

import java.io.File;
import java.util.List;
import java.util.Map;

/**
 * A named navigation path against a live Terminal. Two shapes:
 *
 * 1. Per-row (the default): each CSV row is one independent scenario, e.g. RunCommandFlow —
 *    the navigation is fixed code, only the values vary per row.
 *
 * 2. Grouped (groupColumn() non-null): each scenario is a variable-length SEQUENCE of CSV
 *    rows sharing the same value in the group column, executed in row order — e.g.
 *    GenericStepFlow, where the navigation itself is authored entirely as CSV data, no code
 *    change needed to add a new flow.
 */
public interface Flow {

    String name();

    /** Column names this flow expects in each CSV row, for a sample/template file. */
    List<String> csvColumns();

    /**
     * Runs one scenario (per-row flows only). Implementations are expected to leave the
     * terminal on the same "home" screen they started from, so rows can run back-to-back
     * without re-navigating.
     */
    default ScenarioResult run(Terminal t, Map<String, String> row) {
        throw new UnsupportedOperationException(name() + " is a grouped flow; use runGroup()");
    }

    /** Non-null for grouped flows: the CSV column that ties a sequence of rows into one scenario. */
    default String groupColumn() {
        return null;
    }

    /** Runs one scenario for a grouped flow: an ordered sequence of CSV rows sharing one group id. */
    default ScenarioResult runGroup(Terminal t, String groupId, List<Map<String, String>> steps) {
        throw new UnsupportedOperationException(name() + " is a per-row flow; use run()");
    }

    /**
     * Expands the raw CSV rows before grouping/execution — e.g. splicing in another file's
     * rows for an "include" action, so one suite can reuse another. flowDir is the directory
     * holding every scenario file for this flow, for resolving referenced file names.
     * Default: no expansion.
     */
    default List<Map<String, String>> preprocess(File flowDir, List<Map<String, String>> rows) {
        return rows;
    }
}
