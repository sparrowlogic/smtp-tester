package com.sparrowlogic.smtptester.validation;

import com.sparrowlogic.smtptester.MailFixtures;
import com.sparrowlogic.smtptester.core.MimeParser;
import com.sparrowlogic.smtptester.core.ParsedMessage;
import com.sparrowlogic.smtptester.core.SessionInfo;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MimeRulesTest {

    private final MimeParser parser = new MimeParser();
    private final MimeRules rules = new MimeRules();

    private List<Finding> check(final byte[] bytes) {
        final ParsedMessage parsed = this.parser.parse(bytes);
        final List<Finding> findings = new ArrayList<>();
        this.rules.check(bytes, MailFixtures.defaultEnvelope(), parsed, SessionInfo.plaintext(), findings::add);
        return findings;
    }

    private List<String> rulesOf(final String raw) {
        return this.check(MailFixtures.bytes(raw)).stream().map(Finding::rule).toList();
    }

    @Test
    void wellFormedMessagePassesEveryMimeRule() {
        assertThat(this.rulesOf(MailFixtures.WELL_FORMED)).isEmpty();
    }

    @Test
    void mimeHeadersWithoutMimeVersionIsAWarning() {
        final String raw = MailFixtures.join("From: a@b.c", "To: d@e.f", "Subject: s",
                "Date: Fri, 11 Sep 2026 12:00:00 +0000", "Message-ID: <a@b>",
                "Content-Type: text/plain; charset=utf-8", "", "body", "");
        assertThat(this.rulesOf(raw)).contains("RFC2045_MISSING_MIME_VERSION");
    }

    @Test
    void unexpectedMimeVersionIsAWarning() {
        final String raw = MailFixtures.WELL_FORMED.replace("MIME-Version: 1.0", "MIME-Version: 2.0");
        assertThat(this.rulesOf(raw)).contains("RFC2045_UNEXPECTED_MIME_VERSION");
    }

    @Test
    void unrecognisedTransferEncodingIsAnError() {
        final String raw = MailFixtures.WELL_FORMED.replace("MIME-Version: 1.0",
                "MIME-Version: 1.0\r\nContent-Transfer-Encoding: rot13");
        assertThat(this.rulesOf(raw)).contains("RFC2045_UNKNOWN_TRANSFER_ENCODING");
    }

    /** Declaring 7bit and then sending UTF-8 is how accented characters become mojibake in transit. */
    @Test
    void sevenBitDeclarationWithEightBitBodyIsAnError() {
        final String raw = MailFixtures.join("From: a@b.c", "To: d@e.f", "Subject: s",
                "Date: Fri, 11 Sep 2026 12:00:00 +0000", "Message-ID: <a@b>", "MIME-Version: 1.0",
                "Content-Type: text/plain; charset=utf-8", "Content-Transfer-Encoding: 7bit", "",
                "café", "");
        final Finding finding = this.check(raw.getBytes(StandardCharsets.UTF_8)).stream()
                .filter(f -> f.rule().equals("RFC2045_ENCODING_CONTENT_MISMATCH"))
                .findFirst()
                .orElseThrow();
        assertThat(finding.severity()).isEqualTo(Severity.ERROR);
        assertThat(finding.statusCode()).isEqualTo(EnhancedStatusCode.CONVERSION_REQUIRED_AND_PROHIBITED);
    }

    @Test
    void eightBitDeclarationWithEightBitBodyIsAccepted() {
        final String raw = MailFixtures.join("From: a@b.c", "To: d@e.f", "Subject: s",
                "Date: Fri, 11 Sep 2026 12:00:00 +0000", "Message-ID: <a@b>", "MIME-Version: 1.0",
                "Content-Type: text/plain; charset=utf-8", "Content-Transfer-Encoding: 8bit", "",
                "café", "");
        assertThat(this.rulesOf(raw)).doesNotContain("RFC2045_ENCODING_CONTENT_MISMATCH");
    }

    @Test
    void multipartWithoutBoundaryParameterIsAnError() {
        final String raw = MailFixtures.join("From: a@b.c", "To: d@e.f", "Subject: s",
                "Date: Fri, 11 Sep 2026 12:00:00 +0000", "Message-ID: <a@b>", "MIME-Version: 1.0",
                "Content-Type: multipart/mixed", "", "body", "");
        assertThat(this.rulesOf(raw)).contains("RFC2046_MISSING_BOUNDARY");
    }

    @Test
    void multipartWhoseBoundaryNeverAppearsIsAnError() {
        final String raw = MailFixtures.join("From: a@b.c", "To: d@e.f", "Subject: s",
                "Date: Fri, 11 Sep 2026 12:00:00 +0000", "Message-ID: <a@b>", "MIME-Version: 1.0",
                "Content-Type: multipart/mixed; boundary=\"MISSING\"", "", "body", "");
        assertThat(this.rulesOf(raw)).contains("RFC2046_BOUNDARY_NOT_FOUND");
    }

    @Test
    void multipartWithoutAClosingDelimiterIsAnError() {
        assertThat(this.rulesOf(MailFixtures.UNCLOSED_MULTIPART)).contains("RFC2046_UNCLOSED_BOUNDARY");
    }

    @Test
    void properlyClosedMultipartIsAccepted() {
        assertThat(this.rulesOf(MailFixtures.MULTIPART_WITH_ATTACHMENT))
                .doesNotContain("RFC2046_UNCLOSED_BOUNDARY", "RFC2046_BOUNDARY_NOT_FOUND");
    }

    @Test
    void textPartWithoutCharsetIsAWarning() {
        final String raw = MailFixtures.WELL_FORMED.replace("text/plain; charset=utf-8", "text/plain");
        assertThat(this.rulesOf(raw)).contains("RFC2046_MISSING_CHARSET");
    }

    @Test
    void unsupportedCharsetIsAWarning() {
        final String raw = MailFixtures.WELL_FORMED.replace("charset=utf-8", "charset=utf-99");
        assertThat(this.rulesOf(raw)).contains("RFC2046_UNSUPPORTED_CHARSET");
    }

    @Test
    void htmlOnlyBodyIsFlaggedForDeliverability() {
        assertThat(this.rulesOf(MailFixtures.HTML_ONLY)).contains("DELIVERABILITY_HTML_WITHOUT_TEXT");
    }

    @Test
    void emptyBodyIsAWarning() {
        final String raw = MailFixtures.join("From: a@b.c", "To: d@e.f", "Subject: s",
                "Date: Fri, 11 Sep 2026 12:00:00 +0000", "Message-ID: <a@b>", "", "", "");
        assertThat(this.rulesOf(raw)).contains("MIME_EMPTY_BODY");
    }

    @Test
    void attachmentWithoutFilenameIsInformational() {
        final String raw = MailFixtures.join("From: a@b.c", "To: d@e.f", "Subject: s",
                "Date: Fri, 11 Sep 2026 12:00:00 +0000", "Message-ID: <a@b>", "MIME-Version: 1.0",
                "Content-Type: multipart/mixed; boundary=\"B\"", "",
                "--B", "Content-Type: text/plain; charset=utf-8", "", "body",
                "--B", "Content-Type: application/octet-stream",
                "Content-Disposition: attachment", "", "AAAA", "--B--", "");
        assertThat(this.rulesOf(raw)).contains("RFC2183_ATTACHMENT_WITHOUT_FILENAME");
    }
}
