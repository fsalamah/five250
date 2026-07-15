package com.acabes.five250;

import org.tn5250j.keyboard.KeyMnemonic;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Terminal command dispatch, shared by the TCP daemon protocol and the HTTP API.
 * Owns the live session registry: one Terminal per sessionId.
 */
public final class SessionService {

    private static final long DEFAULT_TIMEOUT_MS = 15000;

    private final Map<String, Terminal> sessions = new ConcurrentHashMap<>();
    private final Map<String, RecordingState> recordings = new ConcurrentHashMap<>();

    public Terminal getSession(String sessionId) {
        Terminal t = sessions.get(sessionId);
        if (t == null) throw new RuntimeException("No session '" + sessionId + "'. Call connect first.");
        return t;
    }

    public Map<String, String> sessionStatuses() {
        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<String, Terminal> e : sessions.entrySet()) {
            out.put(e.getKey(), e.getValue().isConnected() ? "connected" : "disconnected");
        }
        return out;
    }

    public Map<String, Object> handle(Map<String, Object> req) {
        String cmd = str(req, "cmd", null);
        if (cmd == null) return errorResponse(new IllegalArgumentException("missing 'cmd'"));

        try {
            switch (cmd) {
                case "ping":
                    return ok(Map.of("pong", true));

                case "shutdown": {
                    Map<String, Object> r = ok(Map.of());
                    new Thread(() -> { sleep(100); System.exit(0); }).start();
                    return r;
                }

                case "connect": {
                    String sid = sessionId(req);
                    String host = str(req, "host", null);
                    long port = num(req, "port", 23);
                    boolean ssl = bool(req, "ssl", false);
                    Terminal t = new Terminal(sid);
                    t.connect(host, (int) port, ssl, DEFAULT_TIMEOUT_MS);
                    sessions.put(sid, t);
                    Map<String, Object> resp = ok(t.snapshot());
                    RecordingState rec = recordings.get(sid);
                    if (rec != null) {
                        Map<String, String> row = rec.addConnectOnce(host, port, ssl);
                        if (row != null) resp.put("recordedRow", row);
                    }
                    return resp;
                }

                case "signon": {
                    Terminal t = getSession(sessionId(req));
                    t.signon(str(req, "user", null), str(req, "pass", null), DEFAULT_TIMEOUT_MS);
                    return ok(t.snapshot());
                }

                case "screen": {
                    Terminal t = getSession(sessionId(req));
                    return ok(t.snapshot());
                }

                case "fields": {
                    Terminal t = getSession(sessionId(req));
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("fields", t.fieldsList());
                    return ok(m);
                }

                // SPIKE: exercises Terminal.inferTarget() directly for recording-feature validation.
                case "infer": {
                    Terminal t = getSession(sessionId(req));
                    String target = t.inferTarget((int) num(req, "row", 0), (int) num(req, "col", 0));
                    return ok(Map.of("target", target));
                }

                case "type": {
                    String sid = sessionId(req);
                    Terminal t = getSession(sid);
                    String value = str(req, "value", "");
                    String recordedTarget;
                    if (req.containsKey("label")) {
                        String label = str(req, "label", "");
                        t.typeLabel(new String[]{label}, value);
                        recordedTarget = "label:" + label;
                    } else if (req.containsKey("row") && req.containsKey("col")) {
                        int row = (int) num(req, "row", 0);
                        int col = (int) num(req, "col", 0);
                        t.typeAt(row, col, value);
                        recordedTarget = t.inferTarget(row, col); // more robust than raw row,col for a saved recording
                    } else {
                        throw new IllegalArgumentException("type requires either 'label' or both 'row' and 'col'");
                    }
                    Map<String, Object> resp = ok(t.snapshot());
                    RecordingState rec = recordings.get(sid);
                    if (rec != null) {
                        resp.put("recordedRow", rec.add("type", recordedTarget, value, ""));
                    }
                    return resp;
                }

                case "key": {
                    String sid = sessionId(req);
                    Terminal t = getSession(sid);
                    String keyName = str(req, "key", null);
                    KeyMnemonic key = KeyMap.resolve(keyName);
                    t.sendKey(key);
                    t.waitReady(DEFAULT_TIMEOUT_MS);
                    Map<String, Object> resp = ok(t.snapshot());
                    RecordingState rec = recordings.get(sid);
                    if (rec != null) {
                        resp.put("recordedRow", rec.add("key", "", keyName, ""));
                    }
                    return resp;
                }

                // Real-terminal-fidelity GUI typing: characters land wherever the cursor
                // currently is (tn5250j's own keyboard semantics), not a pre-selected field.
                case "sendtext": {
                    String sid = sessionId(req);
                    Terminal t = getSession(sid);
                    String text = str(req, "text", "");
                    int[] before = t.cursor();
                    t.sendText(text);
                    Map<String, Object> resp = ok(t.snapshot());
                    RecordingState rec = recordings.get(sid);
                    if (rec != null && !text.isEmpty()) {
                        resp.put("recordedRow", rec.appendTypedChar(before[0], before[1], text, t));
                    }
                    return resp;
                }

                case "setcursor": {
                    String sid = sessionId(req);
                    Terminal t = getSession(sid);
                    t.setCursor((int) num(req, "row", 1), (int) num(req, "col", 1));
                    RecordingState rec = recordings.get(sid);
                    // A click just repositions the cursor ahead of typing — not a CSV action by
                    // itself, but it does end whatever typed run was in progress, so the next
                    // character starts a fresh row anchored at the new position.
                    if (rec != null) rec.endTypedRun();
                    return ok(t.snapshot());
                }

                case "record-start": {
                    String sid = sessionId(req);
                    String caseName = str(req, "case", "recorded");
                    recordings.put(sid, new RecordingState(caseName));
                    return ok(Map.of("recording", true));
                }

                case "record-status": {
                    RecordingState rec = recordings.get(sessionId(req));
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("active", rec != null);
                    m.put("count", rec == null ? 0 : rec.count());
                    return ok(m);
                }

                case "record-mark-check": {
                    RecordingState rec = requireRecording(sessionId(req));
                    Map<String, String> row = rec.add("check", str(req, "target", ""), "", str(req, "expected", ""));
                    return ok(Map.of("recordedRow", row));
                }

                case "record-mark-extract": {
                    RecordingState rec = requireRecording(sessionId(req));
                    Map<String, String> row = rec.add("extract", str(req, "target", ""), str(req, "name", ""), "");
                    return ok(Map.of("recordedRow", row));
                }

                case "record-stop": {
                    String sid = sessionId(req);
                    RecordingState rec = requireRecording(sid);
                    recordings.remove(sid);
                    return ok(Map.of("rows", rec.allRows()));
                }

                case "record-discard": {
                    recordings.remove(sessionId(req));
                    return ok(Map.of());
                }

                case "sessions": {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("sessions", sessionStatuses());
                    return ok(m);
                }

                case "disconnect": {
                    String sid = sessionId(req);
                    Terminal t = sessions.remove(sid);
                    if (t != null) t.disconnect();
                    return ok(Map.of());
                }

                default:
                    return errorResponse(new IllegalArgumentException("unknown cmd: " + cmd));
            }
        } catch (Throwable e) {
            e.printStackTrace();
            return errorResponse(e);
        }
    }

    private static String sessionId(Map<String, Object> req) {
        return str(req, "sessionId", "default");
    }

    private RecordingState requireRecording(String sessionId) {
        RecordingState rec = recordings.get(sessionId);
        if (rec == null) throw new RuntimeException("Not recording session '" + sessionId + "'. Call record-start first.");
        return rec;
    }

    static Map<String, Object> ok(Map<String, Object> data) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", true);
        m.putAll(data);
        return m;
    }

    static Map<String, Object> errorResponse(Throwable e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", false);
        m.put("error", e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        return m;
    }

    static String str(Map<String, Object> req, String key, String def) {
        Object v = req.get(key);
        return v == null ? def : v.toString();
    }

    static long num(Map<String, Object> req, String key, long def) {
        Object v = req.get(key);
        return v == null ? def : ((Number) v).longValue();
    }

    static boolean bool(Map<String, Object> req, String key, boolean def) {
        Object v = req.get(key);
        return v == null ? def : (Boolean) v;
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) {}
    }
}
