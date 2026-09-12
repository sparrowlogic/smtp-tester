package com.sparrowlogic.smtptester.validation;

import com.sparrowlogic.smtptester.MailFixtures;
import com.sparrowlogic.smtptester.core.Envelope;
import com.sparrowlogic.smtptester.core.MimeParser;
import com.sparrowlogic.smtptester.core.ParsedMessage;
import com.sparrowlogic.smtptester.core.SessionInfo;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class HeaderRulesTest {

    private final MimeParser parser = new MimeParser();
    private final HeaderRules rules = new HeaderRules();

    private List<Finding> check(final String raw, final Envelope envelope) {
        final byte[] bytes = MailFixtures.bytes(raw);
        final ParsedMessage parsed = this.parser.parse(bytes);
        final List<Finding> findings = new ArrayList<>();
        this.rules.check(bytes, envelope, parsed, SessionInfo.plaintext(), findings::add);
        return findings;
    }

    private List<String> rulesOf(final String raw) {
        return this.check(raw, MailFixtures.defaultEnvelope()).stream().map(Finding::rule).toList();
    }

    @Test
    void wellFormedMessagePassesEveryHeaderRule() {
        assertThat(this.rulesOf(MailFixtures.WELL_FORMED)).isEmpty();
    }

    @Test
    void missingDateIsAnError() {
        final Finding finding = this.check(MailFixtures.NO_DATE, MailFixtures.defaultEnvelope()).stream()
                .filter(f -> f.rule().equals("RFC5322_MISSING_DATE"))
                .findFirst()
                .orElseThrow();
        assertThat(finding.severity()).isEqualTo(Severity.ERROR);
        assertThat(finding.reference()).isEqualTo("RFC 5322 section 3.6");
    }

    @Test
    void missingFromIsAnError() {
        final String raw = MailFixtures.join("To: alice@example.com", "Subject: s",
                "Date: Fri, 11 Sep 2026 12:00:00 +0000", "Message-ID: <a@b>", "", "body", "");
        assertThat(this.rulesOf(raw)).contains("RFC5322_MISSING_FROM");
    }

    @Test
    void duplicatedSingleOccurrenceHeaderIsAnError() {
        final Finding finding = this.check(MailFixtures.DUPLICATE_FROM, MailFixtures.defaultEnvelope())
                .stream()
                .filter(f -> f.rule().equals("RFC5322_DUPLICATE_HEADER"))
                .findFirst()
                .orElseThrow();
        assertThat(finding.detail()).isEqualTo("From occurs 2 times");
    }

    @Test
    void unparseableDateIsAnError() {
        final String raw = MailFixtures.join("From: a@b.c", "To: d@e.f", "Subject: s",
                "Date: last tuesday", "Message-ID: <a@b>", "", "body", "");
        assertThat(this.rulesOf(raw)).contains("RFC5322_INVALID_DATE");
    }

    @Test
    void missingMessageIdIsOnlyAWarning() {
        final String raw = MailFixtures.join("From: a@b.c", "To: d@e.f", "Subject: s",
                "Date: Fri, 11 Sep 2026 12:00:00 +0000", "", "body", "");
        final Finding finding = this.check(raw, MailFixtures.defaultEnvelope()).stream()
                .filter(f -> f.rule().equals("RFC5322_MISSING_MESSAGE_ID"))
                .findFirst()
                .orElseThrow();
        assertThat(finding.severity()).isEqualTo(Severity.WARNING);
    }

    @Test
    void malformedMessageIdIsAWarning() {
        final String raw = MailFixtures.join("From: a@b.c", "To: d@e.f", "Subject: s",
                "Date: Fri, 11 Sep 2026 12:00:00 +0000", "Message-ID: not-bracketed", "", "body", "");
        assertThat(this.rulesOf(raw)).contains("RFC5322_INVALID_MESSAGE_ID");
    }

    @Test
    void severalFromMailboxesWithoutSenderIsAnError() {
        final String raw = MailFixtures.join("From: one@example.com, two@example.com",
                "To: alice@example.com", "Subject: s", "Date: Fri, 11 Sep 2026 12:00:00 +0000",
                "Message-ID: <a@b>", "", "body", "");
        assertThat(this.rulesOf(raw)).contains("RFC5322_MULTIPLE_FROM_WITHOUT_SENDER");
    }

    @Test
    void severalFromMailboxesWithSenderIsAccepted() {
        final String raw = MailFixtures.join("From: one@example.com, two@example.com",
                "Sender: one@example.com", "To: alice@example.com", "Subject: s",
                "Date: Fri, 11 Sep 2026 12:00:00 +0000", "Message-ID: <a@b>", "", "body", "");
        assertThat(this.rulesOf(raw)).doesNotContain("RFC5322_MULTIPLE_FROM_WITHOUT_SENDER");
    }

    @Test
    void transmittedBccIsAWarningBecauseItLeaksRecipients() {
        final String raw = MailFixtures.join("From: a@b.c", "To: d@e.f", "Bcc: secret@example.com",
                "Subject: s", "Date: Fri, 11 Sep 2026 12:00:00 +0000", "Message-ID: <a@b>", "",
                "body", "");
        assertThat(this.rulesOf(raw)).contains("RFC5322_BCC_TRANSMITTED");
    }

    @Test
    void noDestinationHeaderIsAWarning() {
        final String raw = MailFixtures.join("From: a@b.c", "Subject: s",
                "Date: Fri, 11 Sep 2026 12:00:00 +0000", "Message-ID: <a@b>", "", "body", "");
        assertThat(this.rulesOf(raw)).contains("RFC5322_NO_DESTINATION_HEADER");
    }

    @Test
    void missingSubjectIsOnlyInformational() {
        final String raw = MailFixtures.join("From: a@b.c", "To: d@e.f",
                "Date: Fri, 11 Sep 2026 12:00:00 +0000", "Message-ID: <a@b>", "", "body", "");
        final Finding finding = this.check(raw, MailFixtures.defaultEnvelope()).stream()
                .filter(f -> f.rule().equals("RFC5322_NO_SUBJECT"))
                .findFirst()
                .orElseThrow();
        assertThat(finding.severity()).isEqualTo(Severity.INFO);
    }

    @Test
    void envelopeSenderNotAligningWithTheFromHeaderIsReportedForDmarc() {
        final Envelope mismatched = MailFixtures.envelope("bounces@mailer.net", "alice@example.com");
        final List<Finding> findings = this.check(MailFixtures.WELL_FORMED, mismatched);
        assertThat(findings).extracting(Finding::rule).contains("DMARC_SPF_ALIGNMENT");
        assertThat(findings).filteredOn(f -> f.rule().equals("DMARC_SPF_ALIGNMENT"))
                .singleElement()
                .satisfies(f -> assertThat(f.severity()).isEqualTo(Severity.INFO));
    }

    @Test
    void matchingEnvelopeAndHeaderDomainsRaiseNoAlignmentFinding() {
        final Envelope aligned = MailFixtures.envelope("bounces@example.com", "alice@example.com");
        assertThat(this.check(MailFixtures.WELL_FORMED, aligned)).extracting(Finding::rule)
                .doesNotContain("DMARC_SPF_ALIGNMENT");
    }
}
