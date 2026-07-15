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

    // A "type" row currently being live-built from consecutive sendtext() characters — see
    // appendTypedChar(). Real terminal typing arrives one character per RPC call; without this,
    // recording it produces one row per keystroke (unusable) or, worse, nothing at all.
    private Map<String, String> pendingTypeRow;
    private int expectRow = -1, expectCol = -1;

    public RecordingState(String caseName) {
        this.caseName = caseName;
    }

    public synchronized Map<String, String> add(String action, String target, String value, String expected) {
        endTypedRun(); // a key press, click, or explicit type ends whatever run was in progress
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

    /**
     * Records one character typed at the real cursor position (Terminal.sendText/GUI immersive
     * typing). Consecutive characters typed left-to-right without the cursor jumping elsewhere
     * are folded into a single growing "type" row (via Terminal.inferTarget on the run's start
     * position) instead of one row per keystroke; the moment the cursor doesn't match where the
     * previous character should have left it, the run ends and a new one starts. Returns the
     * (possibly just-updated) row so the caller can echo it live, same as add()'s callers do.
     */
    public synchronized Map<String, String> appendTypedChar(int cursorRow, int cursorCol, String text, Terminal t) {
        if (pendingTypeRow != null && cursorRow == expectRow && cursorCol == expectCol) {
            pendingTypeRow.put("value", pendingTypeRow.get("value") + text);
        } else {
            pendingTypeRow = new LinkedHashMap<>();
            pendingTypeRow.put("case", caseName);
            pendingTypeRow.put("step", String.valueOf(nextStep++));
            pendingTypeRow.put("action", "type");
            pendingTypeRow.put("target", t.inferTarget(cursorRow, cursorCol));
            pendingTypeRow.put("value", text);
            pendingTypeRow.put("expected", "");
            rows.add(pendingTypeRow);
        }
        expectRow = cursorRow;
        expectCol = cursorCol + text.length(); // next character position — does not model a field wrapping to the next row
        return pendingTypeRow;
    }

    /** Ends the in-progress typed run, if any, so the next keystroke/click starts a fresh one. */
    public synchronized void endTypedRun() {
        pendingTypeRow = null;
        expectRow = -1;
        expectCol = -1;
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
