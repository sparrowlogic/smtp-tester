package com.sparrowlogic.smtptester.mcp;

import com.sparrowlogic.smtptester.MailFixtures;
import com.sparrowlogic.smtptester.core.SessionInfo;
import com.sparrowlogic.smtptester.smtp.MailboxService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Exercises the tool beans directly. The transport is verified separately; what matters here is
 * that the values an agent would read back are right, since a wrong field in a tool result is
 * invisible until a model acts on it.
 */
@SpringBootTest
class MailMcpToolsTest {

    @Autowired
    private MailboxService mailbox;

    @Autowired
    private MailMcpTools tools;

    @Autowired
    private MailMcpAdminTools adminTools;

    private String shippedId;
    private String brokenId;

    @BeforeEach
    void setUp() {
        this.mailbox.clearAll();
        this.shippedId = this.receive(MailFixtures.MULTIPART_WITH_ATTACHMENT, "alice@example.com");
        this.brokenId = this.receive(MailFixtures.NO_DATE, "bob@example.com");
    }

    private String receive(final String raw, final String recipient) {
        return this.mailbox.receive(MailFixtures.bytes(raw),
                MailFixtures.envelope("app@example.com", recipient),
                SessionInfo.plaintext()).message().id();
    }

    @Test
    void listInboxesReportsEveryRecipientWithCounts() {
        final McpViews.InboxList inboxes = this.tools.listInboxes();
        assertThat(inboxes.count()).isEqualTo(2);
        assertThat(inboxes.inboxes()).extracting(McpViews.InboxView::address)
                .contains("alice@example.com", "bob@example.com");
        assertThat(inboxes.inboxes())
                .filteredOn(i -> i.address().equals("bob@example.com"))
                .singleElement()
                .satisfies(i -> {
                    assertThat(i.messageCount()).isEqualTo(1);
                    assertThat(i.failingCount()).isEqualTo(1);
                });
    }

    @Test
    void listRecentEmailsReturnsNewestFirstWithATotal() {
        final McpViews.EmailList list = this.tools.listRecentEmails(null, null, null);
        assertThat(list.total()).isEqualTo(2);
        assertThat(list.returned()).isEqualTo(2);
        assertThat(list.messages().getFirst().id()).isEqualTo(this.brokenId);
    }

    @Test
    void listRecentEmailsFiltersByInbox() {
        final McpViews.EmailList list = this.tools.listRecentEmails("alice@example.com", null, null);
        assertThat(list.messages()).singleElement()
                .satisfies(m -> assertThat(m.id()).isEqualTo(this.shippedId));
    }

    @Test
    void listRecentEmailsPagesWithLimitAndOffset() {
        assertThat(this.tools.listRecentEmails(null, 1, 0).messages()).hasSize(1);
        assertThat(this.tools.listRecentEmails(null, 1, 1).messages()).singleElement()
                .satisfies(m -> assertThat(m.id()).isEqualTo(this.shippedId));
    }

    @Test
    void searchMatchesFreeTextAcrossSubjectAndBody() {
        assertThat(this.tools.searchEmails("See attached", null, null, null, null, null, null)
                .messages()).singleElement()
                .satisfies(m -> assertThat(m.id()).isEqualTo(this.shippedId));
        assertThat(this.tools.searchEmails("no-such-token", null, null, null, null, null, null)
                .messages()).isEmpty();
    }

    @Test
    void searchCanSelectOnlyFailingMessages() {
        final McpViews.EmailList failing =
                this.tools.searchEmails(null, null, null, null, null, true, null);
        assertThat(failing.messages()).singleElement()
                .satisfies(m -> assertThat(m.validationStatus()).isEqualTo("FAIL"));
    }

    @Test
    void searchRejectsAMalformedSinceValueWithAnActionableMessage() {
        assertThatThrownBy(() ->
                this.tools.searchEmails(null, null, null, null, "yesterday", null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ISO-8601");
    }

    @Test
    void searchAcceptsAnIsoInstantForSince() {
        assertThat(this.tools.searchEmails(null, null, null, null, "2020-01-01T00:00:00Z", null, null)
                .messages()).hasSize(2);
        assertThat(this.tools.searchEmails(null, null, null, null, "2099-01-01T00:00:00Z", null, null)
                .messages()).isEmpty();
    }

    @Test
    void getEmailReturnsHeadersBodyAndAttachmentDigests() {
        final McpViews.EmailDetail detail = this.tools.getEmail(this.shippedId, false, false);
        assertThat(detail.summary().id()).isEqualTo(this.shippedId);
        assertThat(detail.envelopeFrom()).isEqualTo("app@example.com");
        assertThat(detail.text()).contains("See attached.");
        assertThat(detail.html()).isNull();
        assertThat(detail.raw()).isNull();
        assertThat(detail.headers()).isNotEmpty();
        assertThat(detail.attachments()).singleElement()
                .satisfies(a -> assertThat(a.sha512()).hasSize(128));
        assertThat(detail.validation().status()).isEqualTo("PASS");
    }

    @Test
    void getEmailIncludesTheRawSourceOnlyWhenAsked() {
        final McpViews.EmailDetail detail = this.tools.getEmail(this.shippedId, true, true);
        assertThat(detail.raw()).isEqualTo(MailFixtures.MULTIPART_WITH_ATTACHMENT);
    }

    /** Not-found is an error, so an agent is told what to do rather than handed a silent null. */
    @Test
    void getEmailFailsLoudlyForAnUnknownId() {
        assertThatThrownBy(() -> this.tools.getEmail("missing", false, false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("No message with id missing")
                .hasMessageContaining("list_recent_emails");
    }

    @Test
    void validationReportNamesTheRuleAndItsEnhancedStatusCode() {
        final McpViews.ValidationView view = this.tools.getEmailValidation(this.brokenId);
        assertThat(view.status()).isEqualTo("FAIL");
        assertThat(view.findings().getFirst().rule()).isEqualTo("RFC5322_MISSING_DATE");
        assertThat(view.findings().getFirst().smtpStatusCode()).isEqualTo("5.6.0");
    }

    @Test
    void attachmentContentComesBackBase64ForBinaryParts() {
        final McpViews.AttachmentContent content = this.tools.getEmailAttachment(this.shippedId, 1);
        assertThat(content.filename()).isEqualTo("invoice.pdf");
        assertThat(content.text()).isNull();
        assertThat(content.base64()).isNotNull();
        assertThat(new String(java.util.Base64.getDecoder().decode(content.base64())))
                .startsWith("%PDF-1.4");
    }

    @Test
    void attachmentLookupFailsLoudlyForAnUnknownIndex() {
        assertThatThrownBy(() -> this.tools.getEmailAttachment(this.shippedId, 42))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no attachment at index 42");
    }

    @Test
    void deleteEmailRemovesOneMessageAndReportsWhatItDid() {
        final McpViews.DeletionResult result = this.adminTools.deleteEmail(this.brokenId);
        assertThat(result.deleted()).isEqualTo(1);
        assertThat(result.detail()).contains(this.brokenId);
        assertThat(this.mailbox.find(this.brokenId)).isEmpty();
    }

    @Test
    void deletingAnUnknownIdReportsZeroRatherThanFailing() {
        assertThat(this.adminTools.deleteEmail("missing").deleted()).isZero();
    }

    @Test
    void clearInboxEmptiesOnlyThatRecipient() {
        assertThat(this.adminTools.clearInbox("bob@example.com").deleted()).isEqualTo(1);
        assertThat(this.mailbox.size()).isEqualTo(1);
    }

    @Test
    void clearInboxWithoutAnAddressClearsEverything() {
        assertThat(this.adminTools.clearInbox(null).deleted()).isEqualTo(2);
        assertThat(this.mailbox.size()).isZero();
    }

    @Test
    void clearAllInboxesEmptiesTheServer() {
        assertThat(this.adminTools.clearAllInboxes().deleted()).isEqualTo(2);
        assertThat(this.mailbox.size()).isZero();
    }
}
