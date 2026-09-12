package com.sparrowlogic.smtptester.validation;

import com.sparrowlogic.smtptester.MailFixtures;
import com.sparrowlogic.smtptester.core.MimeParser;
import com.sparrowlogic.smtptester.core.ParsedMessage;
import com.sparrowlogic.smtptester.core.SessionInfo;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TransportRulesTest {

    private final MimeParser parser = new MimeParser();
    private final TransportRules rules = new TransportRules();

    private List<Finding> check(final String raw) {
        final byte[] bytes = MailFixtures.bytes(raw);
        final ParsedMessage parsed = this.parser.parse(bytes);
        final java.util.ArrayList<Finding> findings = new java.util.ArrayList<>();
        this.rules.check(bytes, MailFixtures.defaultEnvelope(), parsed, SessionInfo.plaintext(), findings::add);
        return findings;
    }

    private List<String> rulesOf(final String raw) {
        return this.check(raw).stream().map(Finding::rule).toList();
    }

    @Test
    void wellFormedMessageRaisesNoTransportFindings() {
        assertThat(this.rulesOf(MailFixtures.WELL_FORMED)).isEmpty();
    }

    @Test
    void bareLineFeedsAreAnError() {
        final Finding finding = this.check(MailFixtures.BARE_LF).stream()
                .filter(f -> f.rule().equals("RFC5321_BARE_LF"))
                .findFirst()
                .orElseThrow();
        assertThat(finding.severity()).isEqualTo(Severity.ERROR);
        assertThat(finding.detail()).contains("bare LF");
        assertThat(finding.statusCode()).isEqualTo(EnhancedStatusCode.MEDIA_ERROR);
    }

    @Test
    void bareCarriageReturnIsAnError() {
        final String raw = "From: a@b.c\rTo: d@e.f\r\n\r\nbody\r\n";
        assertThat(this.rulesOf(raw)).contains("RFC5321_BARE_CR");
    }

    @Test
    void linesLongerThanTheTransportLimitAreAnError() {
        final String longLine = "X-Long: " + "a".repeat(1200);
        final String raw = MailFixtures.join("From: a@b.c", "To: d@e.f", longLine, "", "body", "");
        final Finding finding = this.check(raw).stream()
                .filter(f -> f.rule().equals("RFC5321_LINE_TOO_LONG"))
                .findFirst()
                .orElseThrow();
        assertThat(finding.detail()).contains("1208 octets");
    }

    @Test
    void aLineOfExactlyTheLimitIsAccepted() {
        final String exact = "X-Long: " + "a".repeat(998 - "X-Long: ".length());
        final String raw = MailFixtures.join("From: a@b.c", "To: d@e.f", exact, "", "body", "");
        assertThat(this.rulesOf(raw)).doesNotContain("RFC5321_LINE_TOO_LONG");
    }

    @Test
    void emptyMessageIsAnError() {
        assertThat(this.rulesOf("")).contains("RFC5322_EMPTY_MESSAGE");
    }

    @Test
    void missingBlankLineBetweenHeadersAndBodyIsAnError() {
        assertThat(this.rulesOf("From: a@b.c\r\nTo: d@e.f\r\n")).contains("RFC5322_NO_HEADER_BODY_SEPARATOR");
    }

    @Test
    void rawEightBitOctetsInHeadersAreAnError() {
        final String raw = "From: a@b.c\r\nSubject: café latte\r\nTo: d@e.f\r\n\r\nbody\r\n";
        final Finding finding = this.check(raw).stream()
                .filter(f -> f.rule().equals("RFC5322_NON_ASCII_HEADER"))
                .findFirst()
                .orElseThrow();
        assertThat(finding.statusCode()).isEqualTo(EnhancedStatusCode.NON_ASCII_ADDRESSES_NOT_PERMITTED);
    }

    @Test
    void eightBitOctetsInTheBodyAreNotAHeaderProblem() {
        final String raw = "From: a@b.c\r\nTo: d@e.f\r\n\r\ncafé\r\n";
        assertThat(this.rulesOf(raw)).doesNotContain("RFC5322_NON_ASCII_HEADER");
    }

    @Test
    void messageNotEndingInCrlfIsAWarning() {
        final String raw = "From: a@b.c\r\nTo: d@e.f\r\n\r\nno trailing newline";
        final Finding finding = this.check(raw).stream()
                .filter(f -> f.rule().equals("RFC5322_NO_TRAILING_CRLF"))
                .findFirst()
                .orElseThrow();
        assertThat(finding.severity()).isEqualTo(Severity.WARNING);
    }

    @Test
    void malformedEnvelopeAddressesAreReported() {
        final byte[] bytes = MailFixtures.bytes(MailFixtures.WELL_FORMED);
        final ParsedMessage parsed = this.parser.parse(bytes);
        final java.util.ArrayList<Finding> findings = new java.util.ArrayList<>();
        this.rules.check(bytes, MailFixtures.envelope("not-an-address", "also bad"), parsed, SessionInfo.plaintext(), findings::add);
        assertThat(findings).extracting(Finding::rule)
                .contains("RFC5321_INVALID_REVERSE_PATH", "RFC5321_INVALID_FORWARD_PATH");
        assertThat(findings).extracting(Finding::statusCode)
                .contains(EnhancedStatusCode.BAD_SENDER_ADDRESS_SYNTAX,
                        EnhancedStatusCode.BAD_DESTINATION_ADDRESS_SYNTAX);
    }

    @Test
    void nullSenderIsNotTreatedAsAMalformedAddress() {
        final byte[] bytes = MailFixtures.bytes(MailFixtures.WELL_FORMED);
        final ParsedMessage parsed = this.parser.parse(bytes);
        final java.util.ArrayList<Finding> findings = new java.util.ArrayList<>();
        this.rules.check(bytes, MailFixtures.envelope("", "alice@example.com"), parsed, SessionInfo.plaintext(), findings::add);
        assertThat(findings).extracting(Finding::rule).doesNotContain("RFC5321_INVALID_REVERSE_PATH");
    }
}
