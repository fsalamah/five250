package com.acabes.five250;

import org.openjdk.nashorn.api.scripting.ClassFilter;
import org.openjdk.nashorn.api.scripting.NashornScriptEngineFactory;

import javax.script.ScriptEngine;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Evaluates a JS boolean expression for "if"/"loop" (while mode) conditions in a custom-steps
 * suite - e.g. {@code extracted.balance > 100 && extracted.status == "ACTIVE"}. {@code ${NAME}}
 * vars are already resolved to literal text before a step reaches GenericStepFlow (see
 * Variables.substituteRows), so a condition referencing a saved variable is just plain JS by the
 * time it gets here - only values extracted DURING this same run (the "extract" action) need an
 * explicit binding, since those don't exist yet at substitution time.
 *
 * Sandboxed two ways, because suite CSVs are exactly the kind of file people share/paste from
 * elsewhere: (1) a ClassFilter that denies every Java class, so a condition can never reach
 * outside the sandbox (Java.type(...), filesystem, sockets, ...) - only pure ECMAScript is
 * reachable; (2) every evaluation runs on a pooled worker thread under a hard wall-clock timeout,
 * so a pathological expression (an accidental infinite loop inside the JS itself) fails the
 * scenario with a clear error instead of hanging the run. Nashorn can't always be interrupted
 * mid-script - see EXECUTOR below - so a truly stuck expression leaks one worker thread rather
 * than actually stopping; an accepted trade-off for a lightweight embedded sandbox.
 */
final class JsCondition {

    private static final NashornScriptEngineFactory FACTORY = new NashornScriptEngineFactory();
    private static final ClassFilter DENY_ALL_CLASSES = className -> false;
    private static final long TIMEOUT_MS = 1000;

    // Cached-thread-pool + daemon threads: a hung evaluation leaks its worker thread (Nashorn
    // doesn't reliably honor Thread.interrupt() mid-script) rather than blocking the pool, and
    // daemon threads never stop the JVM from exiting.
    private static final ExecutorService EXECUTOR = Executors.newCachedThreadPool(r -> {
        Thread th = new Thread(r, "js-condition");
        th.setDaemon(true);
        return th;
    });

    // One Nashorn engine per worker thread, reused across evaluations on that thread - engine
    // creation isn't free, and each worker only ever runs one eval at a time (never shared
    // concurrently), so this is safe.
    private static final ThreadLocal<ScriptEngine> ENGINE =
        ThreadLocal.withInitial(() -> FACTORY.getScriptEngine(DENY_ALL_CLASSES));

    private JsCondition() {}

    static boolean evaluate(String expression, Map<String, Object> extracted) {
        if (expression == null || expression.isBlank()) {
            throw new RuntimeException("if/loop condition is empty");
        }
        // The "extracted" object is inlined as real JS source (via a JSON literal), not bound
        // through Java-object bridging - that guarantees native dot-access semantics
        // (extracted.balance) regardless of Nashorn's Map-bridging quirks, and sidesteps any
        // ambiguity about what bracket/dot access on a raw java.util.Map actually does.
        String script = "var extracted = " + Json.write(coerce(extracted)) + ";\n(" + expression + ")";

        Future<Object> future = EXECUTOR.submit(() -> ENGINE.get().eval(script));
        Object result;
        try {
            result = future.get(TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new RuntimeException("condition timed out after " + TIMEOUT_MS + "ms: " + expression);
        } catch (Exception e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            throw new RuntimeException("condition failed to evaluate (" + cause.getMessage() + "): " + expression);
        }
        if (!(result instanceof Boolean)) {
            throw new RuntimeException("condition must evaluate to true/false, got '" + result + "' for: " + expression);
        }
        return (Boolean) result;
    }

    /** Auto-coerces any value that parses cleanly as a number into a real number before it's
     * serialized into the script - otherwise "9" &gt; "10" does lexicographic STRING comparison
     * (true) instead of numeric (false) in JS, silently breaking every numeric condition on data
     * pulled off a 5250 screen (which is always text). */
    private static Map<String, Object> coerce(Map<String, Object> extracted) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (extracted != null) {
            for (Map.Entry<String, Object> e : extracted.entrySet()) out.put(e.getKey(), coerceValue(e.getValue()));
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static Object coerceValue(Object v) {
        if (v instanceof List) {
            List<Object> out = new ArrayList<>();
            for (Object item : (List<Object>) v) out.add(coerceValue(item));
            return out;
        }
        if (v == null) return null;
        String s = String.valueOf(v).trim();
        if (s.matches("-?\\d+(\\.\\d+)?")) {
            try {
                return Double.parseDouble(s);
            } catch (NumberFormatException ignored) {
                // fall through - keep the original string
            }
        }
        return v;
    }
}
