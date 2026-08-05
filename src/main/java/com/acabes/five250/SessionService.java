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
    /** In-memory, per-session named variables set by the GUI's Extract Area/Extract Table
     * buttons when used OUTSIDE recording (see "extract-now" below) — an immediate value, not a
     * recorded suite step. Session-scoped and gone once the process restarts; nothing persists
     * these to disk, same as the terminal session itself. */
    private final Map<String, Map<String, Object>> sessionVars = new ConcurrentHashMap<>();

    public Terminal getSession(String sessionId) {
        Terminal t = sessions.get(sessionId);
        if (t == null) throw new RuntimeException("No session '" + sessionId + "'. Call connect first.");
        return t;
    }

    /** True only if a session both exists AND its socket is still actually connected — a
     * GenericStepFlow "disconnect" step (or a dropped connection) closes the Terminal's socket
     * without deregistering it here, so a plain map-membership check alone can't tell a live
     * session from a stale, already-dead one still sitting in the registry. */
    public boolean isActuallyConnected(String sessionId) {
        Terminal t = sessions.get(sessionId);
        return t != null && t.isConnected();
    }

    /** Drops a session from the registry without touching its socket — for clearing out a stale
     * entry left behind by a "disconnect" step, ahead of a fresh connect replacing it. */
    public void forget(String sessionId) {
        sessions.remove(sessionId);
    }

    /** Closes the socket AND deregisters the session, same as the "disconnect" RPC — for reuse by
     * both that handler and HttpApi's optional "disconnect when the run finishes" run option. */
    public void disconnect(String sessionId) {
        Terminal t = sessions.remove(sessionId);
        if (t != null) t.disconnect();
    }

    /** Like disconnect(), but for RunTracker.cancel() specifically — calls Terminal.forceClose()
     * instead of the normal (synchronized) disconnect(). A run being killed is, by definition,
     * possibly stuck inside a synchronized Terminal call right now; going through the ordinary
     * disconnect() would just queue up behind it on the same monitor and never actually run
     * until whatever's stuck gives up on its own. See Terminal.forceClose()'s doc. */
    public void forceDisconnect(String sessionId) {
        Terminal t = sessions.remove(sessionId);
        if (t != null) t.forceClose();
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
                    // Only an AID key (ENTER, PF1-24, ...) actually transmits to the host and has
                    // a response worth waiting for. A purely local key (BACK_SPACE, cursor
                    // movement, DELETE, ...) never leaves the client, so there's nothing to wait
                    // for - and waiting anyway risked a real 15-SECOND HANG on exactly the
                    // backspace-past-a-field's-start case this whole thing is about: tn5250j's
                    // keyboard-locked flag from that local X-error doesn't reliably clear within
                    // any bounded window, so waitReady() would spin its full timeout for nothing.
                    if (Terminal.transmitsToHost(key)) {
                        t.waitReady(DEFAULT_TIMEOUT_MS);
                    }
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

                // Generic manual step insert - the "+ Add step..." button in the recording
                // toolbar. Check/Extract/Extract Area/Extract Table are all really this same
                // primitive (insert a row into the pending recording) specialized to one action;
                // this is the general form for anything else (a "type" step referencing a var
                // via ${NAME}, a "wait", a hand-written "key", ...) without live-driving the
                // actual terminal for it.
                case "record-mark-step": {
                    RecordingState rec = requireRecording(sessionId(req));
                    Map<String, String> row = rec.add(str(req, "action", ""), str(req, "target", ""),
                        str(req, "value", ""), str(req, "expected", ""));
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

                // The GUI's Extract Area/Extract Table drag-select, used OUTSIDE recording (while
                // recording, the same drag instead calls record-mark-extract to save a suite
                // step - this command is for grabbing a value RIGHT NOW into an in-memory
                // variable you can immediately reuse, save, or inspect via "session-vars").
                case "extract-now": {
                    String sid = sessionId(req);
                    Terminal t = getSession(sid);
                    String target = str(req, "target", "");
                    String name = str(req, "name", "");
                    if (target.isBlank() || name.isBlank()) {
                        throw new IllegalArgumentException("extract-now requires 'target' and 'name'");
                    }
                    Object value = StepActions.extractByTarget(t, target);
                    sessionVars.computeIfAbsent(sid, k -> new LinkedHashMap<>()).put(name, value);
                    return ok(Map.of("value", value));
                }

                case "session-vars": {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("vars", sessionVars.getOrDefault(sessionId(req), Map.of()));
                    return ok(m);
                }

                case "session-var-delete": {
                    Map<String, Object> vars = sessionVars.get(sessionId(req));
                    if (vars != null) vars.remove(str(req, "name", ""));
                    return ok(Map.of());
                }

                case "sessions": {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("sessions", sessionStatuses());
                    return ok(m);
                }

                case "disconnect": {
                    String sid = sessionId(req);
                    disconnect(sid);
                    Map<String, Object> resp = ok(Map.of());
                    RecordingState rec = recordings.get(sid);
                    if (rec != null) {
                        resp.put("recordedRow", rec.add("disconnect", "", "", ""));
                    }
                    return resp;
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
