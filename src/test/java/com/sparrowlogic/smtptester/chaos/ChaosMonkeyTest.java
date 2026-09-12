package com.sparrowlogic.smtptester.chaos;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.OptionalInt;
import java.util.Random;
import java.util.function.DoubleSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The random source is injected precisely so these are not flaky.
 *
 * <p>Testing a chaos monkey with real randomness would be self-defeating: the suite would fail
 * occasionally for reasons unrelated to the code, which is the exact problem the feature exists to
 * help developers find.
 */
class ChaosMonkeyTest {

    /** A source that returns a scripted sequence, so each roll's outcome is chosen by the test. */
    private static DoubleSupplier rolls(final double... values) {
        final Deque<Double> queue = new ArrayDeque<>();
        for (final double value : values) {
            queue.add(value);
        }
        return () -> queue.isEmpty() ? 1.0 : queue.poll();
    }

    private static ChaosSettings allCertain() {
        return new ChaosSettings(true, 0.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1024, 10240);
    }

    private static ChaosSettings allImpossible() {
        return new ChaosSettings(true, 1.0, 0.0, 0.0, 0.0, 0.0, 0.0, 1024, 10240);
    }

    @Test
    void everythingIsInertWhileChaosIsDisabled() {
        final ChaosMonkey chaos = new ChaosMonkey(allCertain().withEnabled(false), () -> 0.0);
        assertThat(chaos.enabled()).isFalse();
        assertThat(chaos.refuseConnection()).isFalse();
        assertThat(chaos.disconnect()).isFalse();
        assertThat(chaos.rejectSender()).isFalse();
        assertThat(chaos.rejectRecipient()).isFalse();
        assertThat(chaos.rejectAuth()).isFalse();
        assertThat(chaos.throttleBytesPerSecond()).isEmpty();
        assertThat(chaos.injectedFaults().values()).allMatch(count -> count == 0);
    }

    @Test
    void certainProbabilitiesAlwaysFire() {
        final ChaosMonkey chaos = new ChaosMonkey(allCertain(), () -> 0.0);
        assertThat(chaos.refuseConnection()).isTrue();
        assertThat(chaos.disconnect()).isTrue();
        assertThat(chaos.rejectSender()).isTrue();
        assertThat(chaos.rejectRecipient()).isTrue();
        assertThat(chaos.rejectAuth()).isTrue();
        assertThat(chaos.throttleBytesPerSecond()).isPresent();
    }

    /** "Set it to zero to disable" has to be exactly true, not almost true. */
    @Test
    void zeroProbabilityNeverFiresEvenWhenTheSourceReturnsZero() {
        final ChaosMonkey chaos = new ChaosMonkey(allImpossible(), () -> 0.0);
        assertThat(chaos.refuseConnection()).isFalse();
        assertThat(chaos.disconnect()).isFalse();
        assertThat(chaos.rejectSender()).isFalse();
        assertThat(chaos.rejectRecipient()).isFalse();
        assertThat(chaos.rejectAuth()).isFalse();
        assertThat(chaos.throttleBytesPerSecond()).isEmpty();
    }

    /** acceptConnection is the chance of accepting, so the sense is inverted from the others. */
    @Test
    void acceptConnectionIsTheChanceOfAcceptingNotOfRefusing() {
        final ChaosSettings refuseOneInFive = new ChaosSettings(true, 0.8, 0, 0, 0, 0, 0, 1024, 10240);
        assertThat(new ChaosMonkey(refuseOneInFive, () -> 0.1).refuseConnection())
                .as("a roll below 0.2 refuses").isTrue();
        assertThat(new ChaosMonkey(refuseOneInFive, () -> 0.5).refuseConnection())
                .as("a roll above 0.2 accepts").isFalse();
    }

    @Test
    void aRollAtTheProbabilityBoundaryDoesNotFire() {
        final ChaosSettings half = new ChaosSettings(true, 1.0, 0.5, 0, 0, 0, 0, 1024, 10240);
        assertThat(new ChaosMonkey(half, () -> 0.5).disconnect()).isFalse();
        assertThat(new ChaosMonkey(half, () -> 0.49).disconnect()).isTrue();
    }

    @Test
    void throttlePicksASpeedInTheConfiguredRange() {
        final ChaosSettings settings = new ChaosSettings(true, 1, 0, 0, 0, 0, 1.0, 1000, 2000);
        assertThat(new ChaosMonkey(settings, rolls(0.0, 0.0)).throttleBytesPerSecond())
                .hasValue(1000);
        assertThat(new ChaosMonkey(settings, rolls(0.0, 0.5)).throttleBytesPerSecond())
                .hasValue(1500);
        assertThat(new ChaosMonkey(settings, rolls(0.0, 0.999)).throttleBytesPerSecond())
                .hasValue(1999);
    }

    @Test
    void faultsAreCountedByKindSoAFailedRunCanBeExplained() {
        final ChaosMonkey chaos = new ChaosMonkey(allCertain(), () -> 0.0);
        chaos.rejectSender();
        chaos.rejectSender();
        chaos.disconnect();
        assertThat(chaos.injectedFaults())
                .containsEntry(ChaosAction.REJECT_SENDER, 2L)
                .containsEntry(ChaosAction.DISCONNECT, 1L)
                .containsEntry(ChaosAction.REJECT_AUTH, 0L);

        chaos.resetCounters();
        assertThat(chaos.injectedFaults().values()).allMatch(count -> count == 0);
    }

    @Test
    void reconfiguringTakesEffectImmediately() {
        final ChaosMonkey chaos = new ChaosMonkey(ChaosSettings.disabled(), () -> 0.0);
        assertThat(chaos.rejectSender()).isFalse();
        chaos.reconfigure(allCertain());
        assertThat(chaos.rejectSender()).isTrue();
        chaos.setEnabled(false);
        assertThat(chaos.rejectSender()).isFalse();
        assertThat(chaos.settings().rejectSender()).as("probabilities survive the off switch")
                .isEqualTo(1.0);
    }

    /** A seed is what turns "this test is flaky" into a run you can replay. */
    @Test
    void thesameSeedProducesTheSameSequenceOfFaults() {
        assertThat(this.sequenceFor(42L)).isEqualTo(this.sequenceFor(42L));
        assertThat(this.sequenceFor(42L)).isNotEqualTo(this.sequenceFor(43L));
    }

    private List<Boolean> sequenceFor(final long seed) {
        final Random random = new Random(seed);
        final ChaosMonkey chaos = new ChaosMonkey(
                new ChaosSettings(true, 1.0, 0.5, 0, 0, 0, 0, 1024, 10240), random::nextDouble);
        return java.util.stream.IntStream.range(0, 25)
                .mapToObj(i -> chaos.disconnect())
                .toList();
    }

    @Test
    void outOfRangeProbabilitiesAreClampedRatherThanRejected() {
        final ChaosSettings settings = new ChaosSettings(true, 5.0, -2.0, 0, 0, 0, 0, 1024, 10240);
        assertThat(settings.acceptConnection()).isEqualTo(1.0);
        assertThat(settings.disconnect()).isEqualTo(0.0);
    }

    @Test
    void anImpossibleSpeedRangeIsRejectedAtConstruction() {
        assertThatThrownBy(() -> new ChaosSettings(true, 1, 0, 0, 0, 0, 0, 2000, 1000))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("minBytesPerSecond");
        assertThatThrownBy(() -> new ChaosSettings(true, 1, 0, 0, 0, 0, 0, 0, 1000))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theDefaultSettingsAreOffButCarryJimsProbabilities() {
        final ChaosSettings defaults = ChaosSettings.disabled();
        assertThat(defaults.enabled()).isFalse();
        assertThat(defaults.acceptConnection()).isEqualTo(0.99);
        assertThat(defaults.disconnect()).isEqualTo(0.005);
        assertThat(defaults.rejectSender()).isEqualTo(0.05);
    }

    @Test
    void throttlingActuallySlowsAReadToRoughlyTheTargetRate() throws IOException {
        final byte[] payload = "x".repeat(2000).getBytes(StandardCharsets.UTF_8);
        final long start = System.nanoTime();
        try (ThrottledInputStream stream =
                     new ThrottledInputStream(new ByteArrayInputStream(payload), 20_000)) {
            assertThat(stream.readAllBytes()).hasSize(2000);
        }
        final long elapsedMillis = (System.nanoTime() - start) / 1_000_000L;
        // 2000 bytes at 20 kB/s is 100ms; assert a generous floor so a slow CI box cannot fail it.
        assertThat(elapsedMillis).isGreaterThanOrEqualTo(50L);
    }

    @Test
    void aThrottledStreamStillDeliversEveryByteUnchanged() throws IOException {
        final byte[] payload = "hello chaos".getBytes(StandardCharsets.UTF_8);
        try (ThrottledInputStream stream =
                     new ThrottledInputStream(new ByteArrayInputStream(payload), 1_000_000)) {
            assertThat(stream.readAllBytes()).isEqualTo(payload);
        }
    }

    @Test
    void aNonPositiveRateIsRejected() {
        assertThatThrownBy(() -> new ThrottledInputStream(new ByteArrayInputStream(new byte[0]), 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theViewSummarisesWhichFaultsActuallyFired() {
        final ChaosMonkey chaos = new ChaosMonkey(allCertain(), () -> 0.0);
        chaos.rejectSender();
        chaos.disconnect();
        final ChaosView view = ChaosView.of(chaos);
        assertThat(view.totalFaults()).isEqualTo(2);
        assertThat(view.firedActions()).containsExactlyInAnyOrder("REJECT_SENDER", "DISCONNECT");
        assertThat(view.settings().enabled()).isTrue();
        assertThat(OptionalInt.empty()).isEmpty();
    }
}
