package com.acabes.five250;

import java.util.function.Consumer;

/**
 * Cross-cutting "what's executing right now" reporter. A Flow implementation calls
 * Progress.report(...) before each meaningful step; whoever is driving the run (e.g. the
 * HttpApi background thread) installs a listener first so the GUI can poll it.
 */
public final class Progress {

    private static final ThreadLocal<Consumer<String>> LISTENER = new ThreadLocal<>();

    private Progress() {}

    public static void set(Consumer<String> listener) {
        LISTENER.set(listener);
    }

    public static void clear() {
        LISTENER.remove();
    }

    public static void report(String description) {
        Consumer<String> l = LISTENER.get();
        if (l != null) l.accept(description);
    }
}
