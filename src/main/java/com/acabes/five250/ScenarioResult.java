package com.acabes.five250;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Outcome of running one CSV row through a Flow: per-field checks plus a screen dump on failure. */
public final class ScenarioResult {

    public static final class Check {
        public final String name;
        public final String expected;
        public final String actual;
        public final boolean passed;

        /** Exact (case-insensitive, trimmed) match. */
        public Check(String name, String expected, String actual) {
            this(name, expected, actual, (expected == null ? "" : expected).trim()
                .equalsIgnoreCase((actual == null ? "" : actual).trim()));
        }

        /** Explicit pass/fail, e.g. for "contains" or other custom comparisons. */
        public Check(String name, String expected, String actual, boolean passed) {
            this.name = name;
            this.expected = expected == null ? "" : expected;
            this.actual = actual == null ? "" : actual;
            this.passed = passed;
        }

        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", name);
            m.put("expected", expected);
            m.put("actual", actual);
            m.put("passed", passed);
            return m;
        }
    }

    /** One captured screen during execution, for stepping back through a run afterward. */
    public static final class Step {
        public final String label;
        public final Map<String, Object> screen;

        public Step(String label, Map<String, Object> screen) {
            this.label = label;
            this.screen = screen;
        }

        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("label", label);
            m.put("screen", screen);
            return m;
        }
    }

    public final Map<String, String> row;
    public final List<Check> checks = new ArrayList<>();
    public final List<Step> steps = new ArrayList<>();
    /** Data pulled off screens on purpose (the "extract" action) — not a pass/fail check. Each
     * value is either a single String (one field) or a List&lt;String&gt; (a "rows:" range, one
     * entry per line pulled off a subfile/list screen). */
    public final Map<String, Object> extracted = new LinkedHashMap<>();
    public Map<String, Object> screenOnFailure;
    public String error;

    public ScenarioResult(Map<String, String> row) {
        this.row = row;
    }

    /** Records a value pulled off a screen, e.g. via the "extract" action — structured output, not a check. */
    public ScenarioResult extract(String name, String value) {
        extracted.put(name, value == null ? "" : value);
        return this;
    }

    /** Records multiple lines pulled off a subfile/list screen, e.g. via extract's "rows:" target. */
    public ScenarioResult extractRows(String name, List<String> values) {
        extracted.put(name, values == null ? List.of() : values);
        return this;
    }

    public ScenarioResult check(String name, String expected, String actual) {
        checks.add(new Check(name, expected, actual));
        return this;
    }

    public ScenarioResult check(String name, String expected, String actual, boolean passed) {
        checks.add(new Check(name, expected, actual, passed));
        return this;
    }

    /** Records a captured screen at a point in execution, for the replay viewer. */
    public ScenarioResult step(String label, Map<String, Object> screen) {
        steps.add(new Step(label, screen));
        return this;
    }

    public boolean passed() {
        if (error != null) return false;
        for (Check c : checks) if (!c.passed) return false;
        return true;
    }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("row", row);
        m.put("passed", passed());
        m.put("error", error);
        List<Object> checkMaps = new ArrayList<>();
        for (Check c : checks) checkMaps.add(c.toMap());
        m.put("checks", checkMaps);
        m.put("extracted", extracted);
        if (screenOnFailure != null) m.put("screen", screenOnFailure);
        List<Object> stepMaps = new ArrayList<>();
        for (Step s : steps) stepMaps.add(s.toMap());
        m.put("steps", stepMaps);
        return m;
    }

    /** Flattened for CSV export: original columns + result + one expected/actual pair per check.
     * A "rows:" extraction (a List, not a single String) is joined with " | " into one cell —
     * CSV has no native nested-array cell, unlike the JSON output (toMap()), which keeps it as
     * a real array. */
    public Map<String, String> toResultRow() {
        Map<String, String> out = new LinkedHashMap<>(row);
        out.put("result", passed() ? "PASS" : "FAIL");
        out.put("error", error == null ? "" : error);
        for (Check c : checks) {
            out.put(c.name + "_expected", c.expected);
            out.put(c.name + "_actual", c.actual);
        }
        for (Map.Entry<String, Object> e : extracted.entrySet()) {
            Object v = e.getValue();
            out.put(e.getKey(), v instanceof List ? String.join(" | ", (List<String>) v) : String.valueOf(v));
        }
        return out;
    }
}
