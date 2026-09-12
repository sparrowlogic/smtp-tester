package com.sparrowlogic.smtptester.validation;

import java.util.List;
import java.util.Optional;

/**
 * The full compliance verdict for one message.
 *
 * @param findings every finding, in the order the rules ran
 */
public record ValidationReport(List<Finding> findings) {

    private static final ValidationReport EMPTY = new ValidationReport(List.of());

    public ValidationReport {
        findings = List.copyOf(findings);
    }

    /** A report with no findings, used when validation is disabled. */
    public static ValidationReport empty() {
        return EMPTY;
    }

    public long errorCount() {
        return this.countOf(Severity.ERROR);
    }

    public long warningCount() {
        return this.countOf(Severity.WARNING);
    }

    public long infoCount() {
        return this.countOf(Severity.INFO);
    }

    /** {@code FAIL} when any error, {@code WARN} when any warning, else {@code PASS}. */
    public Status status() {
        if (this.errorCount() > 0) {
            return Status.FAIL;
        }
        return this.warningCount() > 0 ? Status.WARN : Status.PASS;
    }

    /** The most severe finding, which is the one quoted in an SMTP rejection response. */
    public Optional<Finding> mostSevere() {
        return this.findings.stream().min(java.util.Comparator.comparingInt(f -> f.severity().ordinal()));
    }

    /** True when at least one finding meets the given severity threshold. */
    public boolean hasAtLeast(final Severity threshold) {
        return this.findings.stream().anyMatch(f -> f.severity().atLeast(threshold));
    }

    private long countOf(final Severity severity) {
        return this.findings.stream().filter(f -> f.severity() == severity).count();
    }

    /** Overall verdict, used for the badge in the inbox and the {@code status} field in logs. */
    public enum Status {
        /** No findings above {@code INFO}. */
        PASS,
        /** At least one warning, no errors. */
        WARN,
        /** At least one error. */
        FAIL
    }
}
