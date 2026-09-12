package com.sparrowlogic.smtptester.core;

import com.sparrowlogic.smtptester.MailFixtures;
import org.junit.jupiter.api.Test;


import static org.assertj.core.api.Assertions.assertThat;

class MimeParserTest {

    private final MimeParser parser = new MimeParser();

    @Test
    void parsesHeadersAddressesAndBody() {
        final ParsedMessage parsed = this.parser.parse(MailFixtures.bytes(MailFixtures.WELL_FORMED));
        assertThat(parsed.from()).containsExactly("app@example.com");
        assertThat(parsed.to()).containsExactly("alice@example.com");
        assertThat(parsed.subject()).isEqualTo("Order 4711 shipped");
        assertThat(parsed.messageId()).isEqualTo("<order-4711@example.com>");
        assertThat(parsed.text()).contains("Your order shipped.");
        assertThat(parsed.html()).isNull();
        assertThat(parsed.parseError()).isNull();
        assertThat(parsed.sentAt()).isNotNull();
    }

    @Test
    void preservesHeaderOrderAndDuplicates() {
        final ParsedMessage parsed = this.parser.parse(MailFixtures.bytes(MailFixtures.DUPLICATE_FROM));
        assertThat(parsed.headerCount("From")).isEqualTo(2);
        assertThat(parsed.headers().getFirst().name()).isEqualTo("From");
        assertThat(parsed.header("from")).isEqualTo("one@example.com");
    }

    @Test
    void headerLookupIsCaseInsensitiveAndNullForAbsent() {
        final ParsedMessage parsed = this.parser.parse(MailFixtures.bytes(MailFixtures.WELL_FORMED));
        assertThat(parsed.header("SUBJECT")).isEqualTo("Order 4711 shipped");
        assertThat(parsed.header("X-Absent")).isNull();
    }

    @Test
    void extractsAttachmentMetadataWithADigestButNotThePayload() {
        final ParsedMessage parsed =
                this.parser.parse(MailFixtures.bytes(MailFixtures.MULTIPART_WITH_ATTACHMENT));
        assertThat(parsed.text()).contains("See attached.");
        assertThat(parsed.attachments()).hasSize(1);
        final AttachmentPart part = parsed.attachments().getFirst();
        assertThat(part.filename()).isEqualTo("invoice.pdf");
        assertThat(part.mimeType()).isEqualTo("application/pdf");
        assertThat(part.disposition()).isEqualTo("attachment");
        assertThat(part.sha512()).hasSize(128);
        assertThat(part.sizeBytes()).isPositive();
        assertThat(part.downloadName()).isEqualTo("invoice.pdf");
    }

    @Test
    void countsAttachmentsByMediaType() {
        final ParsedMessage parsed =
                this.parser.parse(MailFixtures.bytes(MailFixtures.MULTIPART_WITH_ATTACHMENT));
        assertThat(parsed.attachmentCountsByMimeType()).containsEntry("application/pdf", 1L);
    }

    @Test
    void picksUpBothAlternativeBodies() {
        final String raw = MailFixtures.join("From: a@b.c", "To: d@e.f", "Subject: s",
                "Date: Fri, 11 Sep 2026 12:00:00 +0000", "MIME-Version: 1.0",
                "Content-Type: multipart/alternative; boundary=\"B\"", "",
                "--B", "Content-Type: text/plain; charset=utf-8", "", "plain version",
                "--B", "Content-Type: text/html; charset=utf-8", "", "<p>html version</p>",
                "--B--", "");
        final ParsedMessage parsed = this.parser.parse(MailFixtures.bytes(raw));
        assertThat(parsed.text()).contains("plain version");
        assertThat(parsed.html()).contains("<p>html version</p>");
        assertThat(parsed.attachments()).isEmpty();
    }

    /** A named text/plain part is a .txt attachment, not the body; treating it as the body hides it. */
    @Test
    void namedTextPartIsAnAttachmentNotTheBody() {
        final String raw = MailFixtures.join("From: a@b.c", "To: d@e.f", "Subject: s",
                "Date: Fri, 11 Sep 2026 12:00:00 +0000", "MIME-Version: 1.0",
                "Content-Type: multipart/mixed; boundary=\"B\"", "",
                "--B", "Content-Type: text/plain; charset=utf-8", "", "the body",
                "--B", "Content-Type: text/plain; charset=utf-8",
                "Content-Disposition: attachment; filename=\"notes.txt\"", "", "attached notes",
                "--B--", "");
        final ParsedMessage parsed = this.parser.parse(MailFixtures.bytes(raw));
        assertThat(parsed.text()).contains("the body").doesNotContain("attached notes");
        assertThat(parsed.attachments()).singleElement()
                .satisfies(p -> assertThat(p.filename()).isEqualTo("notes.txt"));
    }

    @Test
    void decodesRfc2047EncodedSubjects() {
        final String raw = MailFixtures.join("From: a@b.c", "To: d@e.f",
                "Subject: =?utf-8?q?caf=C3=A9_latte?=",
                "Date: Fri, 11 Sep 2026 12:00:00 +0000", "", "body", "");
        assertThat(this.parser.parse(MailFixtures.bytes(raw)).subject()).isEqualTo("café latte");
    }

    @Test
    void collectsEveryRecipientHeaderWithoutDuplicates() {
        final String raw = MailFixtures.join("From: a@b.c", "To: d@e.f", "Cc: d@e.f, g@h.i",
                "Bcc: j@k.l", "Subject: s", "Date: Fri, 11 Sep 2026 12:00:00 +0000", "", "body", "");
        final ParsedMessage parsed = this.parser.parse(MailFixtures.bytes(raw));
        assertThat(parsed.allRecipients()).containsExactlyInAnyOrder("d@e.f", "g@h.i", "j@k.l");
    }

    /** Garbage in must not throw: the tool's job is to show a developer what they actually sent. */
    @Test
    void unparseableInputYieldsAnEmptyViewRatherThanAnException() {
        final ParsedMessage parsed = this.parser.parse(new byte[] {0, 1, 2, 3});
        assertThat(parsed.from()).isEmpty();
        assertThat(parsed.attachments()).isEmpty();
    }

    @Test
    void malformedAddressHeaderStillYieldsSomethingUsable() {
        final String raw = MailFixtures.join("From: <<broken", "To: d@e.f", "Subject: s",
                "Date: Fri, 11 Sep 2026 12:00:00 +0000", "", "body", "");
        assertThat(this.parser.parse(MailFixtures.bytes(raw)).from()).isNotNull();
    }

    @Test
    void extractsPartContentByIndex() {
        final byte[] raw = MailFixtures.bytes(MailFixtures.MULTIPART_WITH_ATTACHMENT);
        final ParsedMessage parsed = this.parser.parse(raw);
        final int index = parsed.attachments().getFirst().index();
        final byte[] content = new PartExtractor().extract(raw, index).orElseThrow();
        assertThat(new String(content)).startsWith("%PDF-1.4");
        assertThat(Digests.sha512(content)).isEqualTo(parsed.attachments().getFirst().sha512());
    }

    @Test
    void extractingAnUnknownPartIndexReturnsEmpty() {
        final byte[] raw = MailFixtures.bytes(MailFixtures.MULTIPART_WITH_ATTACHMENT);
        assertThat(new PartExtractor().extract(raw, 99)).isEmpty();
        assertThat(new PartExtractor().extract(raw, -1)).isEmpty();
    }

    @Test
    void mimeTypeHelpersNormaliseAndClassify() {
        assertThat(MimeTypes.baseType("TEXT/HTML; charset=UTF-8")).isEqualTo("text/html");
        assertThat(MimeTypes.baseType("  ")).isEqualTo("application/octet-stream");
        assertThat(MimeTypes.extensionFor("application/pdf")).isEqualTo(".pdf");
        assertThat(MimeTypes.extensionFor("application/x-unknown")).isEqualTo(".bin");
        assertThat(MimeTypes.isTextual("application/json")).isTrue();
        assertThat(MimeTypes.isTextual("image/png")).isFalse();
        assertThat(MimeTypes.isTextual("application/ld+json")).isTrue();
    }

    @Test
    void digestsAreStableAndHexEncoded() {
        assertThat(Digests.sha512Utf8("abc")).hasSize(128).isEqualTo(Digests.sha512("abc".getBytes()));
    }

    @Test
    void unnamedAttachmentGetsASyntheticDownloadName() {
        final AttachmentPart part =
                new AttachmentPart(3, null, "image/png", "inline", null, 10, "d");
        assertThat(part.downloadName()).isEqualTo("part-3.png");
        assertThat(part.inline()).isTrue();
    }
}
