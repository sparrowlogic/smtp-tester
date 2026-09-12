package com.sparrowlogic.smtptester.validation;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ValidationReportTest {

    private static Finding finding(final Severity severity, final String rule) {
        return new Finding(rule, severity, "summary", null, "ref", EnhancedStatusCode.MEDIA_ERROR);
    }

    @Test
    void emptyReportPasses() {
        assertThat(ValidationReport.empty().status()).isEqualTo(ValidationReport.Status.PASS);
        assertThat(ValidationReport.empty().mostSevere()).isEmpty();
    }

    @Test
    void anyWarningDowngradesToWarn() {
        final ValidationReport report = new ValidationReport(
                List.of(finding(Severity.INFO, "i"), finding(Severity.WARNING, "w")));
        assertThat(report.status()).isEqualTo(ValidationReport.Status.WARN);
        assertThat(report.warningCount()).isEqualTo(1);
        assertThat(report.infoCount()).isEqualTo(1);
    }

    @Test
    void anyErrorFails() {
        final ValidationReport report = new ValidationReport(
                List.of(finding(Severity.WARNING, "w"), finding(Severity.ERROR, "e")));
        assertThat(report.status()).isEqualTo(ValidationReport.Status.FAIL);
        assertThat(report.errorCount()).isEqualTo(1);
    }

    @Test
    void mostSevereWinsRegardlessOfRuleOrder() {
        final ValidationReport report = new ValidationReport(
                List.of(finding(Severity.INFO, "i"), finding(Severity.ERROR, "e"),
                        finding(Severity.WARNING, "w")));
        assertThat(report.mostSevere()).get().extracting(Finding::rule).isEqualTo("e");
    }

    @Test
    void thresholdComparisonTreatsErrorAsMoreSevereThanWarning() {
        assertThat(Severity.ERROR.atLeast(Severity.WARNING)).isTrue();
        assertThat(Severity.WARNING.atLeast(Severity.ERROR)).isFalse();
        assertThat(Severity.WARNING.atLeast(Severity.WARNING)).isTrue();
    }

    @Test
    void hasAtLeastRespectsTheThreshold() {
        final ValidationReport warnings = new ValidationReport(List.of(finding(Severity.WARNING, "w")));
        assertThat(warnings.hasAtLeast(Severity.ERROR)).isFalse();
        assertThat(warnings.hasAtLeast(Severity.WARNING)).isTrue();
    }

    @Test
    void enhancedStatusCodesFormatAsRfc3463Replies() {
        assertThat(EnhancedStatusCode.MEDIA_ERROR.reply("Missing Date header"))
                .isEqualTo("550 5.6.0 Missing Date header");
        assertThat(EnhancedStatusCode.MESSAGE_TOO_BIG.replyCode()).isEqualTo(552);
        assertThat(EnhancedStatusCode.MESSAGE_TOO_BIG.meaning()).isEqualTo("Message too big for system");
    }
}
