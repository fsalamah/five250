package com.acabes.five250;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/** Tracks in-progress and finished scenario runs so the GUI can poll for live progress, list
 * everything currently executing (per session), and kill a run on demand. */
public final class RunTracker {

    public static final class RunState {
        public volatile String status = "running"; // running | done | error | cancelled
        public volatile String error;
        public volatile String current = "";
        public final int total;
        public final String sessionId;
        public final String name; // suite/script display name (e.g. "custom-steps/login" or "cli-args-demo")
        public final String kind; // "suite" | "script"
        public final long startedAt;
        public final List<Object> results = new CopyOnWriteArrayList<>();
        /** console.log/console.error lines from a running script, in call order - appended live
         * (see JsSuiteRunner's buildConsole) so the GUI can show them as the script runs, not just
         * after it finishes. Empty for a plain (non-script) suite run. */
        public final List<String> console = new CopyOnWriteArrayList<>();

        /** Set by cancel(); the run thread doesn't poll this itself (ScenarioRunner/JsSuiteRunner
         * have no cooperative cancellation checkpoints) - killing works by force-disconnecting the
         * run's session instead, which unblocks whatever step is in flight. This flag exists so the
         * GUI/poll response can immediately reflect "cancelled" instead of waiting for the run
         * thread to notice its session died and finish unwinding. */
        public volatile boolean cancelRequested = false;
        /** The background thread actually executing this run - interrupted on cancel() so a
         * Thread.sleep (a "wait" step) unblocks immediately instead of running out its full delay. */
        volatile Thread thread;

        RunState(int total, String sessionId, String name, String kind, long startedAt) {
            this.total = total;
            this.sessionId = sessionId;
            this.name = name;
            this.kind = kind;
            this.startedAt = startedAt;
        }

        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("status", status);
            m.put("error", error);
            m.put("current", current);
            m.put("total", total);
            m.put("completed", results.size());
            m.put("results", results);
            m.put("console", console);
            return m;
        }

        public Map<String, Object> toSummaryMap(String runId) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("runId", runId);
            m.put("sessionId", sessionId);
            m.put("name", name);
            m.put("kind", kind);
            m.put("status", status);
            m.put("current", current);
            m.put("total", total);
            m.put("completed", results.size());
            m.put("startedAt", startedAt);
            return m;
        }
    }

    private final Map<String, RunState> runs = new ConcurrentHashMap<>();

    public String start(int total, String sessionId, String name, String kind) {
        String id = UUID.randomUUID().toString();
        runs.put(id, new RunState(total, sessionId, name, kind, System.currentTimeMillis()));
        return id;
    }

    public RunState get(String id) {
        RunState s = runs.get(id);
        if (s == null) throw new RuntimeException("No such run: " + id);
        return s;
    }

    /** Every run still marked "running" right now, across every session - what the side panel
     * polls to show "session X is executing suite Y". */
    public List<Map<String, Object>> listActive() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map.Entry<String, RunState> e : runs.entrySet()) {
            if ("running".equals(e.getValue().status)) out.add(e.getValue().toSummaryMap(e.getKey()));
        }
        return out;
    }

    /** Kills a still-running run. There's no cooperative cancellation checkpoint inside
     * ScenarioRunner/JsSuiteRunner's step loops, so this doesn't ask the run to stop nicely -
     * it interrupts the thread (Terminal.waitReady()'s polling loop and StepActions.doWait's
     * Thread.sleep both actually check for this and bail out promptly, not just eventually) and
     * force-disconnects the run's session via SessionService.forceDisconnect() - Terminal's
     * FORCE close, not its normal synchronized disconnect(), so a step genuinely stuck inside a
     * blocking network call gets its socket closed out from under it instead of the cancelling
     * thread just queuing up behind the same lock and never actually running (see
     * Terminal.forceClose()'s doc for the full reasoning - this combination is what makes kill
     * actually preempt an in-flight step instead of merely being a polite, ignorable request).
     * Returns false if the run is already finished or doesn't exist. */
    public boolean cancel(String id, SessionService sessionService) {
        RunState s = runs.get(id);
        if (s == null || !"running".equals(s.status)) return false;
        s.cancelRequested = true;
        s.status = "cancelled";
        s.error = "Cancelled by user";
        if (s.thread != null) s.thread.interrupt();
        try { sessionService.forceDisconnect(s.sessionId); } catch (Throwable ignored) {}
        return true;
    }
}
