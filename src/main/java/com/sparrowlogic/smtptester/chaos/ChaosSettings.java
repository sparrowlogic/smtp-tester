package com.sparrowlogic.smtptester.chaos;

/**
 * The probabilities the chaos monkey runs on.
 *
 * <p>A separate value type from the configuration properties because these are changed at runtime:
 * a test turns chaos on, runs the flow it wants to break, and turns it off again. Being a record
 * makes that swap atomic — a session can never observe half an update.
 *
 * <p>The knobs and their defaults mirror MailHog's "Jim", so an existing Jim configuration
 * translates directly. Every probability is 0.0 to 1.0, and 0.0 disables that behaviour.
 *
 * @param enabled the master switch; everything else is inert while this is false
 * @param acceptConnection chance of <em>accepting</em> an incoming connection, so 0.99 drops one
 *     connection in a hundred. Inverted relative to the others because that is how Jim expresses it
 * @param disconnect chance of dropping a session mid-transaction
 * @param rejectSender chance of refusing a MAIL FROM
 * @param rejectRecipient chance of refusing a RCPT TO
 * @param rejectAuth chance of failing an AUTH
 * @param throttle chance of rate-limiting a message transfer
 * @param minBytesPerSecond slowest rate applied when throttling
 * @param maxBytesPerSecond fastest rate applied when throttling
 */
public record ChaosSettings(
        boolean enabled,
        double acceptConnection,
        double disconnect,
        double rejectSender,
        double rejectRecipient,
        double rejectAuth,
        double throttle,
        int minBytesPerSecond,
        int maxBytesPerSecond) {

    public ChaosSettings {
        acceptConnection = clamp(acceptConnection);
        disconnect = clamp(disconnect);
        rejectSender = clamp(rejectSender);
        rejectRecipient = clamp(rejectRecipient);
        rejectAuth = clamp(rejectAuth);
        throttle = clamp(throttle);
        if (minBytesPerSecond <= 0 || maxBytesPerSecond < minBytesPerSecond) {
            throw new IllegalArgumentException(
                    "minBytesPerSecond must be positive and no greater than maxBytesPerSecond, but was "
                            + minBytesPerSecond + ".." + maxBytesPerSecond);
        }
    }

    /**
     * Out-of-range probabilities are clamped rather than rejected.
     *
     * <p>These arrive from a config file, an HTTP body or a language model, and refusing to start
     * over a 1.5 would be a worse failure than treating it as "always".
     */
    private static double clamp(final double probability) {
        return Math.min(1.0, Math.max(0.0, probability));
    }

    /** Chaos off, with MailHog's Jim defaults left in place for when it is switched on. */
    public static ChaosSettings disabled() {
        return new ChaosSettings(false, 0.99, 0.005, 0.05, 0.05, 0.05, 0.1, 1024, 10240);
    }

    /** The same probabilities, switched on or off. */
    public ChaosSettings withEnabled(final boolean value) {
        return new ChaosSettings(value, this.acceptConnection, this.disconnect, this.rejectSender,
                this.rejectRecipient, this.rejectAuth, this.throttle, this.minBytesPerSecond,
                this.maxBytesPerSecond);
    }
}
