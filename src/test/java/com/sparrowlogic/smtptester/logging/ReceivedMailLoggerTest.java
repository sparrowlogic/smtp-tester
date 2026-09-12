package com.sparrowlogic.smtptester.logging;

import com.sparrowlogic.smtptester.MailFixtures;
import com.sparrowlogic.smtptester.config.SmtpTesterProperties;
import com.sparrowlogic.smtptester.core.Digests;
import com.sparrowlogic.smtptester.core.MailMessage;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

class ReceivedMailLoggerTest {

    private final ObjectMapper json = JsonMapper.builder().build();

    private ReceivedMailLogger logger(final boolean includeBody, final int maxBody) {
        return new ReceivedMailLogger(this.json,
                new SmtpTesterProperties.Logging(true, includeBody, maxBody));
    }

    @Test
    void buildsTheDocumentedShapeForAMessageWithAnAttachment() {
        final MailMessage message =
                MailFixtures.message("000000000001-00000001", MailFixtures.MULTIPART_WITH_ATTACHMENT);
        final ReceivedMailEvent event = this.logger(true, 2000).toEvent(message);

        assertThat(event.event()).isEqualTo("mail.received");
        assertThat(event.id()).isEqualTo("000000000001-00000001");
        assertThat(event.envelope().from()).isEqualTo("app@example.com");
        assertThat(event.envelope().recipients()).containsExactly("alice@example.com");
        assertThat(event.headers().from()).containsExactly("app@example.com");
        assertThat(event.headers().subject()).isEqualTo("invoice attached");
        assertThat(event.body()).isNotNull();
        assertThat(event.body().text()).contains("See attached.");
        assertThat(event.body().textSha512()).hasSize(128);
        assertThat(event.sha512()).isEqualTo(Digests.sha512(message.raw()));
        assertThat(event.rejected()).isFalse();
    }

    /** The user-facing contract: a count per media type plus a SHA-512 per part, never payloads. */
    @Test
    void reportsAttachmentCountsByMediaTypeAndADigestPerPart() {
        final MailMessage message =
                MailFixtures.message("000000000001-00000001", MailFixtures.MULTIPART_WITH_ATTACHMENT);
        final ReceivedMailEvent event = this.logger(true, 2000).toEvent(message);

        assertThat(event.attachments().count()).isEqualTo(1);
        assertThat(event.attachments().countByMimeType()).containsExactly(
                org.assertj.core.api.Assertions.entry("application/pdf", 1L));
        assertThat(event.attachments().parts()).singleElement().satisfies(part -> {
            assertThat(part.mimeType()).isEqualTo("application/pdf");
            assertThat(part.filename()).isEqualTo("invoice.pdf");
            assertThat(part.sha512()).hasSize(128);
        });
    }

    @Test
    void carriesTheComplianceVerdictForARejectedMessage() {
        final MailMessage message = MailFixtures.message("000000000001-00000001", MailFixtures.NO_DATE);
        final ReceivedMailEvent event = this.logger(true, 2000).toEvent(message);

        assertThat(event.validation().status()).isEqualTo("FAIL");
        assertThat(event.validation().errors()).isEqualTo(1);
        assertThat(event.validation().findings()).extracting(ReceivedMailEvent.FindingView::rule)
                .contains("RFC5322_MISSING_DATE");
        assertThat(event.rejected()).isTrue();
    }

    @Test
    void truncatesLongBodiesButStillDigestsTheWholeThing() {
        final String body = "x".repeat(500);
        final String raw = MailFixtures.join("From: a@b.c", "To: d@e.f", "Subject: s",
                "Date: Fri, 11 Sep 2026 12:00:00 +0000", "Message-ID: <a@b>", "", body, "");
        final ReceivedMailEvent event =
                this.logger(true, 100).toEvent(MailFixtures.message("000000000001-00000001", raw));

        assertThat(event.body()).isNotNull();
        assertThat(event.body().truncated()).isTrue();
        assertThat(event.body().text()).hasSizeLessThan(200).endsWith("[truncated]");
        assertThat(event.body().textSha512()).isNotNull().hasSize(128);
    }

    @Test
    void omitsTheBodyEntirelyWhenConfiguredTo() {
        final ReceivedMailEvent event = this.logger(false, 2000)
                .toEvent(MailFixtures.message("000000000001-00000001", MailFixtures.WELL_FORMED));
        assertThat(event.body()).isNull();
    }

    @Test
    void serialisesToASingleLineJsonDocument() {
        final MailMessage message =
                MailFixtures.message("000000000001-00000001", MailFixtures.MULTIPART_WITH_ATTACHMENT);
        final String line = this.json.writeValueAsString(this.logger(true, 2000).toEvent(message));
        assertThat(line).doesNotContain("\n")
                .contains("\"event\":\"mail.received\"")
                .contains("\"countByMimeType\":{\"application/pdf\":1}");
    }

    @Test
    void loggingCanBeDisabledOutright() {
        final ReceivedMailLogger disabled = new ReceivedMailLogger(this.json,
                new SmtpTesterProperties.Logging(false, true, 2000));
        disabled.log(MailFixtures.message("000000000001-00000001", MailFixtures.WELL_FORMED));
    }
}
