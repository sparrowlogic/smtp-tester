package com.sparrowlogic.smtptester.mcp;

import com.sparrowlogic.smtptester.chaos.ChaosMonkey;
import com.sparrowlogic.smtptester.chaos.ChaosSettings;
import com.sparrowlogic.smtptester.chaos.ChaosView;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpTool.McpAnnotations;
import org.springframework.ai.mcp.annotation.McpToolParam;

/**
 * Lets an agent turn fault injection on and off around the flow it is testing.
 *
 * <p>This is the shape of the job: reset the counters, enable chaos, trigger the application's
 * send, then read the counters back to find out whether the server actually misbehaved before
 * concluding anything about the application's retry logic. Without the counters an agent would be
 * reasoning about a probabilistic system from a single sample.
 */
public class ChaosMcpTools {

    private final ChaosMonkey chaos;

    public ChaosMcpTools(final ChaosMonkey chaos) {
        this.chaos = chaos;
    }

    @McpTool(name = "get_chaos_status",
            title = "Get the chaos monkey's settings and injected faults",
            annotations = @McpAnnotations(readOnlyHint = true, destructiveHint = false,
                    idempotentHint = true, openWorldHint = false),
            description = "Reports whether fault injection is on, the probability of each fault, and "
                    + "how many of each kind have actually been injected since the counters were last "
                    + "reset. Read the counters after a test run before concluding anything about the "
                    + "application's behaviour: chaos is probabilistic, so a run where nothing was "
                    + "injected proves nothing about retry handling.")
    public ChaosView getChaosStatus() {
        return ChaosView.of(this.chaos);
    }

    @McpTool(name = "configure_chaos",
            title = "Turn fault injection on or off",
            annotations = @McpAnnotations(readOnlyHint = false, destructiveHint = false,
                    idempotentHint = true, openWorldHint = false),
            description = "Switches the chaos monkey on or off and optionally sets the probability of "
                    + "each fault, so an application's retry and backoff paths can be exercised. Any "
                    + "probability left unset keeps its current value; 0 disables that fault, 1 makes "
                    + "it certain, which is what you want when testing one specific failure "
                    + "deterministically. Remember to turn it off afterwards, or later tests will "
                    + "fail for reasons that have nothing to do with them.")
    public ChaosView configureChaos(
            @McpToolParam(description = "Whether fault injection is active.") final boolean enabled,
            @McpToolParam(required = false,
                    description = "Chance of ACCEPTING a connection, 0 to 1. Note the inversion: 0.99 "
                            + "refuses one connection in a hundred, and 0 refuses every connection.")
            final @Nullable Double acceptConnection,
            @McpToolParam(required = false,
                    description = "Chance of dropping a session mid-transaction with no reply, 0 to 1.")
            final @Nullable Double disconnect,
            @McpToolParam(required = false,
                    description = "Chance of refusing MAIL FROM with a transient 451, 0 to 1.")
            final @Nullable Double rejectSender,
            @McpToolParam(required = false,
                    description = "Chance of refusing RCPT TO with a transient 451, 0 to 1.")
            final @Nullable Double rejectRecipient,
            @McpToolParam(required = false, description = "Chance of failing AUTH, 0 to 1.")
            final @Nullable Double rejectAuth,
            @McpToolParam(required = false,
                    description = "Chance of rate-limiting the message transfer, 0 to 1. Use this to "
                            + "make a client's socket timeout fire.")
            final @Nullable Double throttle) {
        final ChaosSettings current = this.chaos.settings();
        this.chaos.reconfigure(new ChaosSettings(
                enabled,
                or(acceptConnection, current.acceptConnection()),
                or(disconnect, current.disconnect()),
                or(rejectSender, current.rejectSender()),
                or(rejectRecipient, current.rejectRecipient()),
                or(rejectAuth, current.rejectAuth()),
                or(throttle, current.throttle()),
                current.minBytesPerSecond(),
                current.maxBytesPerSecond()));
        return ChaosView.of(this.chaos);
    }

    @McpTool(name = "reset_chaos_faults",
            title = "Reset the injected-fault counters",
            annotations = @McpAnnotations(readOnlyHint = false, destructiveHint = false,
                    idempotentHint = true, openWorldHint = false),
            description = "Zeroes the counters of injected faults. Call this immediately before the "
                    + "run you want to measure, so the numbers you read afterwards belong to it alone.")
    public ChaosView resetChaosFaults() {
        this.chaos.resetCounters();
        return ChaosView.of(this.chaos);
    }

    private static double or(final @Nullable Double supplied, final double fallback) {
        return supplied == null ? fallback : supplied;
    }
}
