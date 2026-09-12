package com.sparrowlogic.smtptester.chaos;

import java.util.List;
import java.util.Map;

/**
 * What the REST API and the MCP tools report about the chaos monkey.
 *
 * @param settings the probabilities currently in force
 * @param injectedFaults how many faults of each kind have been injected since the last reset, which
 *     is what turns "my test failed" into "my test failed because the server dropped the session"
 */
public record ChaosView(ChaosSettings settings, Map<String, Long> injectedFaults) {

    /** Snapshots the monkey's current state. */
    public static ChaosView of(final ChaosMonkey chaos) {
        final Map<String, Long> faults = new java.util.LinkedHashMap<>();
        chaos.injectedFaults().forEach((action, count) -> faults.put(action.name(), count));
        return new ChaosView(chaos.settings(), faults);
    }

    /** Total faults injected, across every kind. */
    public long totalFaults() {
        return this.injectedFaults.values().stream().mapToLong(Long::longValue).sum();
    }

    /** The fault kinds that actually fired, for a one-line summary. */
    public List<String> firedActions() {
        return this.injectedFaults.entrySet().stream()
                .filter(entry -> entry.getValue() > 0)
                .map(Map.Entry::getKey)
                .toList();
    }
}
