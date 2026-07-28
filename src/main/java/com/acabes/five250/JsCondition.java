package com.acabes.five250;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.HostAccess;
import org.graalvm.polyglot.Value;

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
 * Sandboxed three ways, because suite CSVs are exactly the kind of file people share/paste from
 * elsewhere: (1) no host access at all (HostAccess.NONE) plus a denied class lookup, so a
 * condition can never reach outside the sandbox (Java.type(...), filesystem, sockets, ...) - only
 * pure ECMAScript is reachable; (2) every evaluation runs on a pooled worker thread under a hard
 * wall-clock timeout, with the CALLING thread force-cancelling the Context (Context.close(true))
 * on timeout - unlike the Nashorn engine used here previously, GraalJS genuinely honors
 * cross-thread cancellation of a running script, verified empirically (an infinite
 * "while(true){}" condition is actually stopped, not just abandoned); (3) a brand new Context
 * every call, never cached or reused - a bare "var"/implicit global in one condition must never
 * be visible to the next evaluation, whether that's the next "if" in the same case or a
 * completely unrelated suite run that happens to reuse the same pooled thread.
 */
final class JsCondition {

    private static final long TIMEOUT_MS = 1000;

    private static final ExecutorService EXECUTOR = Executors.newCachedThreadPool(r -> {
        Thread th = new Thread(r, "js-condition");
        th.setDaemon(true);
        return th;
    });

    private JsCondition() {}

    static boolean evaluate(String expression, Map<String, Object> extracted) {
        if (expression == null || expression.isBlank()) {
            throw new RuntimeException("if/loop condition is empty");
        }
        // The "extracted" object is inlined as real JS source (via a JSON literal), not bound
        // through Java-object bridging - that guarantees native dot-access semantics
        // (extracted.balance) regardless of engine-specific Map-bridging quirks, and sidesteps
        // any ambiguity about what bracket/dot access on a raw java.util.Map actually does.
        String script = "var extracted = " + Json.write(coerce(extracted)) + ";\n(" + expression + ")";

        // Context is BUILT here (on the calling thread, so it's reachable for a cross-thread
        // close(true) below) but only ever EVALUATED on the single executor thread that runs it -
        // GraalJS restricts a Context to one thread at a time, not to whichever thread built it.
        Context context = Context.newBuilder("js")
            .allowHostAccess(HostAccess.NONE)
            .allowHostClassLookup(name -> false)
            .option("engine.WarnInterpreterOnly", "false")
            .build();

        Future<Value> future = EXECUTOR.submit(() -> context.eval("js", script));
        try {
            Value result;
            try {
                result = future.get(TIMEOUT_MS, TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                context.close(true); // real cross-thread cancellation, not just abandonment
                throw new RuntimeException("condition timed out after " + TIMEOUT_MS + "ms: " + expression);
            } catch (Exception e) {
                Throwable cause = e.getCause() != null ? e.getCause() : e;
                throw new RuntimeException("condition failed to evaluate (" + cause.getMessage() + "): " + expression);
            }
            if (!result.isBoolean()) {
                throw new RuntimeException("condition must evaluate to true/false, got '" + result + "' for: " + expression);
            }
            return result.asBoolean();
        } finally {
            try {
                context.close();
            } catch (Exception ignored) {
                // already closed via the timeout branch above, or nothing left to clean up
            }
        }
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
