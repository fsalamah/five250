package com.acabes.five250;

import java.util.LinkedHashMap;
import java.util.Map;

/** Registered Flow implementations, keyed by name. Add a new Flow class, register it here. */
public final class FlowRegistry {

    private static final Map<String, Flow> FLOWS = new LinkedHashMap<>();

    static {
        register(new RunCommandFlow());
        register(new GenericStepFlow());
    }

    private FlowRegistry() {}

    public static void register(Flow flow) {
        FLOWS.put(flow.name(), flow);
    }

    public static Flow get(String name) {
        Flow f = FLOWS.get(name);
        if (f == null) throw new RuntimeException("No such flow: " + name);
        return f;
    }

    public static Map<String, Flow> all() {
        return FLOWS;
    }
}
