package com.sparrowlogic.smtptester.web;

import com.sparrowlogic.smtptester.MailFixtures;
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
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Renders the real precompiled JTE templates. These assertions are what catch a packaging mistake:
 * if the templates were not compiled into the artifact, the engine throws and the page is empty,
 * which a status-only assertion would not notice.
 */
@SpringBootTest
class InboxPageControllerTest {

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private MailboxService mailbox;

    private MockMvc mvc;
    private String messageId;

    @BeforeEach
    void setUp() {
        this.mvc = MockMvcBuilders.webAppContextSetup(this.context).build();
        this.mailbox.clearAll();
        this.messageId = this.mailbox.receive(MailFixtures.bytes(MailFixtures.MULTIPART_WITH_ATTACHMENT),
                MailFixtures.envelope("app@example.com", "alice@example.com"),
                SessionInfo.plaintext()).message().id();
    }

    @Test
    void inboxRendersOkWithTheMessageListed() throws Exception {
        this.mvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
                .andExpect(content().string(containsString("<title>Inbox · smtp-tester</title>")))
                .andExpect(content().string(containsString("invoice attached")))
                .andExpect(content().string(containsString("alice@example.com")))
                .andExpect(content().string(containsString("message-row")));
    }

    @Test
    void emptyInboxRendersTheGettingStartedPanel() throws Exception {
        this.mailbox.clearAll();
        this.mvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("No messages yet")))
                .andExpect(content().string(not(containsString("message-row"))));
    }

    @Test
    void inboxHonoursTheSearchFilter() throws Exception {
        this.mvc.perform(get("/").param("search", "nothing-matches-this"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("No messages yet")));
    }

    @Test
    void messagePageRendersHeadersAttachmentsAndTheComplianceReport() throws Exception {
        this.mvc.perform(get("/messages/{id}", this.messageId))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
                .andExpect(content().string(containsString("RFC compliance")))
                .andExpect(content().string(containsString("No findings")))
                .andExpect(content().string(containsString("invoice.pdf")))
                .andExpect(content().string(containsString("MAIL FROM")))
                .andExpect(content().string(containsString("Content-Type")));
    }

    @Test
    void messagePageShowsFindingsForABrokenMessage() throws Exception {
        final String id = this.mailbox.receive(MailFixtures.bytes(MailFixtures.NO_DATE),
                MailFixtures.envelope("app@example.com", "bob@example.com"),
                SessionInfo.plaintext()).message().id();
        this.mvc.perform(get("/messages/{id}", id))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("RFC5322_MISSING_DATE")))
                .andExpect(content().string(containsString("Missing the required Date header")))
                .andExpect(content().string(containsString("REJECTED AT SMTP")));
    }

    /** HTML must be escaped, not interpolated: a captured message is untrusted input. */
    @Test
    void subjectsAreEscapedRatherThanInterpolated() throws Exception {
        final String raw = MailFixtures.join("From: a@b.c", "To: d@e.f",
                "Subject: <script>alert(1)</script>", "Date: Fri, 11 Sep 2026 12:00:00 +0000",
                "Message-ID: <x@y>", "", "body", "");
        this.mailbox.receive(MailFixtures.bytes(raw),
                MailFixtures.envelope("a@b.c", "d@e.f"), SessionInfo.plaintext());
        this.mvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("<script>alert(1)</script>"))))
                .andExpect(content().string(containsString("&lt;script&gt;")));
    }

    @Test
    void messagePageReturnsNotFoundForAnUnknownId() throws Exception {
        this.mvc.perform(get("/messages/{id}", "missing")).andExpect(status().isNotFound());
    }

    @Test
    void deletingFromThePageRedirectsBackToTheInbox() throws Exception {
        this.mvc.perform(post("/messages/{id}/delete", this.messageId))
                .andExpect(status().isSeeOther())
                .andExpect(header().string("Location", "/"));
        assertThat(this.mailbox.find(this.messageId)).isEmpty();
    }

    @Test
    void deleteAllFromThePageEmptiesTheInbox() throws Exception {
        this.mvc.perform(post("/messages/delete-all"))
                .andExpect(status().isSeeOther());
        assertThat(this.mailbox.size()).isZero();
    }

    @Test
    void ajaxDeleteReturnsNoContent() throws Exception {
        this.mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .delete("/messages/{id}", this.messageId))
                .andExpect(status().isNoContent());
    }
}
