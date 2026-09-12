package com.sparrowlogic.smtptester.web;

import com.sparrowlogic.smtptester.MailFixtures;
import com.sparrowlogic.smtptester.core.MailMessage;
import com.sparrowlogic.smtptester.core.SessionInfo;
import com.sparrowlogic.smtptester.smtp.MailboxService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Every assertion here checks the response body, not just the status code. A 200 with an empty or
 * wrong payload is the failure mode that a status-only test waves through, and it is exactly what
 * would break an agent or a CI script reading this API.
 */
@SpringBootTest
class MessageApiControllerTest {

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private MailboxService mailbox;

    private MockMvc mvc;
    private String wellFormedId;
    private String brokenId;

    @BeforeEach
    void setUp() {
        this.mvc = MockMvcBuilders.webAppContextSetup(this.context).build();
        this.mailbox.clearAll();
        this.wellFormedId = this.receive(MailFixtures.MULTIPART_WITH_ATTACHMENT, "alice@example.com");
        this.brokenId = this.receive(MailFixtures.NO_DATE, "bob@example.com");
    }

    private String receive(final String raw, final String recipient) {
        final MailMessage stored = this.mailbox.receive(MailFixtures.bytes(raw),
                MailFixtures.envelope("app@example.com", recipient), SessionInfo.plaintext()).message();
        return stored.id();
    }

    @Test
    void listReturnsOkWithTheStoredMessages() throws Exception {
        this.mvc.perform(get("/api/v1/messages"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.returned").value(2))
                .andExpect(jsonPath("$.messages", org.hamcrest.Matchers.hasSize(2)))
                .andExpect(jsonPath("$.messages[0].id").isNotEmpty())
                .andExpect(jsonPath("$.messages[0].subject").isNotEmpty())
                .andExpect(jsonPath("$.messages[0].validationStatus").isNotEmpty());
    }

    @Test
    void listFiltersByInbox() throws Exception {
        this.mvc.perform(get("/api/v1/messages").param("inbox", "bob@example.com"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.messages[0].id").value(this.brokenId));
    }

    @Test
    void listFiltersToFailingMessagesOnly() throws Exception {
        this.mvc.perform(get("/api/v1/messages").param("onlyFailed", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.messages", org.hamcrest.Matchers.hasSize(1)))
                .andExpect(jsonPath("$.messages[0].validationStatus").value("FAIL"));
    }

    @Test
    void inboxesReturnsOkWithCounts() throws Exception {
        this.mvc.perform(get("/api/v1/inboxes"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", org.hamcrest.Matchers.not(org.hamcrest.Matchers.empty())))
                .andExpect(jsonPath("$[*].address",
                        org.hamcrest.Matchers.hasItem("alice@example.com")))
                .andExpect(jsonPath("$[*].messageCount",
                        org.hamcrest.Matchers.everyItem(org.hamcrest.Matchers.greaterThan(0))));
    }

    @Test
    void getReturnsOkWithHeadersBodyAndAttachmentDigests() throws Exception {
        this.mvc.perform(get("/api/v1/messages/{id}", this.wellFormedId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.summary.id").value(this.wellFormedId))
                .andExpect(jsonPath("$.envelopeFrom").value("app@example.com"))
                .andExpect(jsonPath("$.headerFrom[0]").value("app@example.com"))
                .andExpect(jsonPath("$.text").value(org.hamcrest.Matchers.containsString("See attached.")))
                .andExpect(jsonPath("$.headers", org.hamcrest.Matchers.not(org.hamcrest.Matchers.empty())))
                .andExpect(jsonPath("$.attachments[0].filename").value("invoice.pdf"))
                .andExpect(jsonPath("$.attachments[0].sha512",
                        org.hamcrest.Matchers.hasLength(128)))
                .andExpect(jsonPath("$.validation.status").value("PASS"));
    }

    @Test
    void getReturnsNotFoundForAnUnknownId() throws Exception {
        this.mvc.perform(get("/api/v1/messages/{id}", "does-not-exist"))
                .andExpect(status().isNotFound());
    }

    @Test
    void validationReturnsOkWithRuleIdsAndEnhancedStatusCodes() throws Exception {
        this.mvc.perform(get("/api/v1/messages/{id}/validation", this.brokenId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FAIL"))
                .andExpect(jsonPath("$.errors").value(1))
                .andExpect(jsonPath("$.findings[0].rule").value("RFC5322_MISSING_DATE"))
                .andExpect(jsonPath("$.findings[0].severity").value("ERROR"))
                .andExpect(jsonPath("$.findings[0].reference").value("RFC 5322 section 3.6"))
                .andExpect(jsonPath("$.findings[0].smtpStatusCode").value("5.6.0"));
    }

    /** The raw endpoint is the one a byte-for-byte comparison depends on. */
    @Test
    void rawReturnsTheVerbatimMessageWithNoReceivedHeaderAdded() throws Exception {
        final byte[] body = this.mvc.perform(get("/api/v1/messages/{id}/raw", this.wellFormedId))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.parseMediaType("message/rfc822")))
                .andExpect(header().string("Content-Disposition",
                        org.hamcrest.Matchers.containsString(".eml")))
                .andReturn().getResponse().getContentAsByteArray();
        assertThat(body).isEqualTo(MailFixtures.bytes(MailFixtures.MULTIPART_WITH_ATTACHMENT));
    }

    @Test
    void attachmentReturnsOkWithTheDecodedBytes() throws Exception {
        final byte[] body = this.mvc.perform(
                        get("/api/v1/messages/{id}/attachments/{index}", this.wellFormedId, 1))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition",
                        org.hamcrest.Matchers.containsString("invoice.pdf")))
                .andReturn().getResponse().getContentAsByteArray();
        assertThat(new String(body)).startsWith("%PDF-1.4");
    }

    @Test
    void attachmentReturnsNotFoundForAnUnknownIndex() throws Exception {
        this.mvc.perform(get("/api/v1/messages/{id}/attachments/{index}", this.wellFormedId, 99))
                .andExpect(status().isNotFound());
    }

    @Test
    void htmlEndpointReturnsOkWithAPlaceholderWhenThereIsNoHtmlPart() throws Exception {
        this.mvc.perform(get("/api/v1/messages/{id}/html", this.wellFormedId))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("No HTML body")));
    }

    @Test
    void htmlEndpointReturnsTheHtmlBodyWhenThereIsOne() throws Exception {
        final String id = this.receive(MailFixtures.HTML_ONLY, "carol@example.com");
        this.mvc.perform(get("/api/v1/messages/{id}/html", id))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("<p>hello</p>")));
    }

    @Test
    void deleteReturnsOkWithACountAndRemovesTheMessage() throws Exception {
        this.mvc.perform(delete("/api/v1/messages/{id}", this.brokenId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deleted").value(1))
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString(this.brokenId)));
        assertThat(this.mailbox.find(this.brokenId)).isEmpty();
    }

    @Test
    void deleteReturnsNotFoundForAnUnknownId() throws Exception {
        this.mvc.perform(delete("/api/v1/messages/{id}", "nope")).andExpect(status().isNotFound());
    }

    @Test
    void deleteAllClearsEveryInbox() throws Exception {
        this.mvc.perform(delete("/api/v1/messages"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deleted").value(2));
        assertThat(this.mailbox.size()).isZero();
    }

    @Test
    void deleteAllScopedToOneInboxLeavesTheOthers() throws Exception {
        this.mvc.perform(delete("/api/v1/messages").param("inbox", "bob@example.com"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deleted").value(1));
        assertThat(this.mailbox.size()).isEqualTo(1);
    }
}
