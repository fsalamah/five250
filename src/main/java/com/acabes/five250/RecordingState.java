package com.acabes.five250;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Accumulates CSV rows (in the exact custom-steps/GenericStepFlow schema) as a session is
 * driven live — the server is authoritative here, not the browser, so what gets saved is
 * exactly what actually executed, not a client-side guess.
 */
public final class RecordingState {

    private final String caseName;
    private int nextStep = 1;
    private final List<Map<String, String>> rows = new ArrayList<>();
    private Map<String, String> connectRow; // case="setup", recorded separately, at most once

    public RecordingState(String caseName) {
        this.caseName = caseName;
    }

    public synchronized Map<String, String> add(String action, String target, String value, String expected) {
        Map<String, String> row = new LinkedHashMap<>();
        row.put("case", caseName);
        row.put("step", String.valueOf(nextStep++));
        row.put("action", action);
        row.put("target", target == null ? "" : target);
        row.put("value", value == null ? "" : value);
        row.put("expected", expected == null ? "" : expected);
        rows.add(row);
        return row;
    }

    /** Records a "connect" step in its own case="setup", ahead of the recorded case — at most once. */
    public synchronized Map<String, String> addConnectOnce(String host, long port, boolean ssl) {
        if (connectRow != null) return null;
        connectRow = new LinkedHashMap<>();
        connectRow.put("case", "setup");
        connectRow.put("step", "1");
        connectRow.put("action", "connect");
        connectRow.put("target", host);
        connectRow.put("value", String.valueOf(port));
        connectRow.put("expected", ssl ? "true" : "");
        return connectRow;
    }

    public synchronized List<Map<String, String>> allRows() {
        List<Map<String, String>> out = new ArrayList<>();
        if (connectRow != null) out.add(connectRow);
        out.addAll(rows);
        return out;
    }

    public synchronized int count() {
        return rows.size() + (connectRow != null ? 1 : 0);
    }
}
