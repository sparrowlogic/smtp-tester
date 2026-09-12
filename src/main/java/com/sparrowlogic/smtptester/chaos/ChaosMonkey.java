package com.sparrowlogic.smtptester.chaos;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.EnumMap;
import java.util.Map;
import java.util.OptionalInt;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.DoubleSupplier;

/**
 * Injects transport-level faults so a client's error handling can be exercised on purpose.
 *
 * <p>Modelled on MailHog's "Jim", with two deliberate differences. The random source is injectable
 * and can be seeded, because a chaos monkey that cannot reproduce the run that broke your test is
 * a tool for creating mysteries rather than solving them. And every injected fault is counted and
 * logged, so "did chaos cause this, or did my code?" is answerable after the fact instead of by
 * re-running and hoping.
 *
 * <p>Faults are reported with 4xx transient codes. That is the interesting case for a test server:
 * a permanent failure just makes a send fail, whereas a transient one exercises the retry and
 * backoff paths that are otherwise almost impossible to reach locally.
 */
public class ChaosMonkey {

    private static final Logger LOG = LoggerFactory.getLogger(ChaosMonkey.class);

    /**
     * A plain volatile reference rather than an AtomicReference: a record swap is a single
     * reference write, so there is nothing to compare-and-set, and the field is then non-null by
     * construction instead of nullable the way {@code AtomicReference.get()} is.
     */
    private volatile ChaosSettings settings;

    private final DoubleSupplier random;

    /** Indexed by {@link ChaosAction#ordinal()}, so a lookup can never come back null. */
    private final AtomicLong[] counts;

    public ChaosMonkey(final ChaosSettings initial, final DoubleSupplier random) {
        this.settings = initial;
        this.random = random;
        this.counts = new AtomicLong[ChaosAction.values().length];
        for (int i = 0; i < this.counts.length; i++) {
            this.counts[i] = new AtomicLong();
        }
    }

    /** The probabilities currently in force. */
    public ChaosSettings settings() {
        return this.settings;
    }

    /** Replaces the probabilities. Sessions already in flight keep the ones they started with. */
    public ChaosSettings reconfigure(final ChaosSettings replacement) {
        this.settings = replacement;
        LOG.info("Chaos monkey reconfigured: {}", replacement);
        return replacement;
    }

    /** Turns chaos on or off without disturbing the probabilities. */
    public ChaosSettings setEnabled(final boolean enabled) {
        return this.reconfigure(this.settings.withEnabled(enabled));
    }

    /** How many faults of each kind have been injected since startup. */
    public Map<ChaosAction, Long> injectedFaults() {
        final Map<ChaosAction, Long> snapshot = new EnumMap<>(ChaosAction.class);
        for (final ChaosAction action : ChaosAction.values()) {
            snapshot.put(action, this.counts[action.ordinal()].get());
        }
        return snapshot;
    }

    /** Resets the fault counters, so one test's numbers do not leak into the next. */
    public void resetCounters() {
        for (final AtomicLong count : this.counts) {
            count.set(0);
        }
    }

    public boolean enabled() {
        return this.settings.enabled();
    }

    /** True when this connection should be refused outright. */
    public boolean refuseConnection() {
        final ChaosSettings current = this.settings;
        // acceptConnection is the chance of accepting, so the roll is inverted here.
        return this.roll(ChaosAction.REFUSE_CONNECTION, 1.0 - current.acceptConnection());
    }

    /** True when this session should be dropped without a reply. */
    public boolean disconnect() {
        return this.roll(ChaosAction.DISCONNECT, this.settings.disconnect());
    }

    public boolean rejectSender() {
        return this.roll(ChaosAction.REJECT_SENDER, this.settings.rejectSender());
    }

    public boolean rejectRecipient() {
        return this.roll(ChaosAction.REJECT_RECIPIENT, this.settings.rejectRecipient());
    }

    public boolean rejectAuth() {
        return this.roll(ChaosAction.REJECT_AUTH, this.settings.rejectAuth());
    }

    /**
     * The rate limit to apply to this transfer, when one should be applied.
     *
     * <p>Two rolls: one to decide whether to throttle at all, a second to pick a speed in the
     * configured range.
     */
    public OptionalInt throttleBytesPerSecond() {
        final ChaosSettings current = this.settings;
        if (!this.roll(ChaosAction.THROTTLE, current.throttle())) {
            return OptionalInt.empty();
        }
        final int span = current.maxBytesPerSecond() - current.minBytesPerSecond();
        final int speed = current.minBytesPerSecond() + (int) (this.random.getAsDouble() * span);
        return OptionalInt.of(Math.max(1, speed));
    }

    /**
     * Rolls against a probability, counting and logging the fault when it fires.
     *
     * <p>A probability of exactly 0 never fires even if the source returns 0.0, which is what makes
     * "set it to zero to disable this behaviour" strictly true rather than nearly true.
     */
    private boolean roll(final ChaosAction action, final double probability) {
        if (!this.settings.enabled() || probability <= 0.0) {
            return false;
        }
        if (this.random.getAsDouble() >= probability) {
            return false;
        }
        this.counts[action.ordinal()].incrementAndGet();
        LOG.info("Chaos monkey: {}", action.description());
        return true;
    }
}
