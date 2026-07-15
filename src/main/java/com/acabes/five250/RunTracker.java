package com.acabes.five250;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/** Tracks in-progress and finished scenario runs so the GUI can poll for live progress. */
public final class RunTracker {

    public static final class RunState {
        public volatile String status = "running"; // running | done | error
        public volatile String error;
        public volatile String current = "";
        public final int total;
        public final List<Object> results = new CopyOnWriteArrayList<>();

        RunState(int total) {
            this.total = total;
        }

        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("status", status);
            m.put("error", error);
            m.put("current", current);
            m.put("total", total);
            m.put("completed", results.size());
            m.put("results", results);
            return m;
        }
    }

    private final Map<String, RunState> runs = new ConcurrentHashMap<>();

    public String start(int total) {
        String id = UUID.randomUUID().toString();
        runs.put(id, new RunState(total));
        return id;
    }

    public RunState get(String id) {
        RunState s = runs.get(id);
        if (s == null) throw new RuntimeException("No such run: " + id);
        return s;
    }
}
