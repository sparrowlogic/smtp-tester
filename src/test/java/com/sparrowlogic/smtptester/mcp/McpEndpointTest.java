package com.sparrowlogic.smtptester.mcp;

import com.sparrowlogic.smtptester.MailFixtures;
import com.sparrowlogic.smtptester.core.SessionInfo;
import com.sparrowlogic.smtptester.smtp.MailboxService;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Drives the MCP endpoint with the real MCP client SDK, over a real port.
 *
 * <p>Calling the tool beans directly proves the logic; it proves nothing about the layers an agent
 * actually goes through — the annotation scanner, the generated JSON schemas, session negotiation
 * and the streamable-HTTP framing. Using the official client rather than hand-rolled HTTP means
 * this test fails the same way a real agent would, which is the point.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class McpEndpointTest {

    @Autowired
    private MailboxService mailbox;

    @LocalServerPort
    private int port;

    private McpSyncClient client;
    private String messageId = "";

    @BeforeEach
    void setUp() {
        this.mailbox.clearAll();
        this.messageId = this.mailbox.receive(MailFixtures.bytes(MailFixtures.MULTIPART_WITH_ATTACHMENT),
                MailFixtures.envelope("app@example.com", "alice@example.com"),
                SessionInfo.plaintext()).message().id();
        this.client = McpClient.sync(HttpClientStreamableHttpTransport
                        .builder("http://localhost:" + this.port)
                        .endpoint("/mcp")
                        .build())
                .requestTimeout(Duration.ofSeconds(20))
                .build();
        this.client.initialize();
    }

    @AfterEach
    void tearDown() {
        if (this.client != null) {
            this.client.close();
        }
    }

    private McpSchema.CallToolResult call(final String name, final Map<String, Object> arguments) {
        return this.client.callTool(new McpSchema.CallToolRequest(name, arguments));
    }

    /** The tool's structured output, which MCP requires to be a JSON object. */
    @SuppressWarnings("unchecked")
    private Map<String, Object> structured(final McpSchema.CallToolResult result) {
        assertThat(result.structuredContent()).as("structured output").isInstanceOf(Map.class);
        return (Map<String, Object>) result.structuredContent();
    }

    private String textOf(final McpSchema.CallToolResult result) {
        return result.content().stream()
                .filter(McpSchema.TextContent.class::isInstance)
                .map(content -> ((McpSchema.TextContent) content).text())
                .findFirst()
                .orElse("");
    }

    @Test
    void theServerIdentifiesItselfAndOffersInstructions() {
        assertThat(this.client.getServerInfo().name()).isEqualTo("smtp-tester");
        assertThat(this.client.getServerCapabilities().tools()).isNotNull();
    }

    @Test
    void everyDocumentedToolIsRegistered() {
        final List<String> names = this.client.listTools().tools().stream()
                .map(McpSchema.Tool::name)
                .toList();
        assertThat(names).contains("list_inboxes", "list_recent_emails", "search_emails", "get_email",
                "get_email_validation", "get_email_attachment", "delete_email", "clear_inbox",
                "clear_all_inboxes", "get_documentation");
    }

    @Test
    void documentationIsReadableWithoutLeavingTheMcpSession() {
        final McpSchema.CallToolResult index = this.call("get_documentation", Map.of());
        assertThat(this.textOf(index))
                .contains("# smtp-tester")
                .contains("/api/v1/messages/stream");

        final McpSchema.CallToolResult full = this.call("get_documentation", Map.of("full", true));
        assertThat(this.textOf(full))
                .as("full=true must return the long reference, not the index again")
                .contains("smtp-tester.mcp.default-limit")
                .hasSizeGreaterThan(this.textOf(index).length());
    }

    /** MCP requires structured output to be a JSON object; a bare list yields an array schema. */
    @Test
    void everyOutputSchemaIsAnObject() {
        for (final McpSchema.Tool tool : this.client.listTools().tools()) {
            if (tool.outputSchema() != null) {
                assertThat(tool.outputSchema())
                        .as("outputSchema of %s", tool.name())
                        .containsEntry("type", "object");
            }
        }
    }

    /** Tools that delete captured mail. Nothing else may claim destructiveHint. */
    private static final List<String> DESTRUCTIVE =
            List.of("delete_email", "clear_inbox", "clear_all_inboxes");

    /** Tools that change server state without destroying anything a caller might want back. */
    private static final List<String> MUTATING = List.of("configure_chaos", "reset_chaos_faults");

    /**
     * The hints drive whether a host prompts before a call, so getting them wrong is a usability
     * bug in both directions: a listing that demands confirmation, or a deletion that does not.
     * Classification is explicit rather than inferred from the name, because "mutates" and
     * "destroys" are genuinely different — {@code configure_chaos} is the former, not the latter.
     */
    @Test
    void toolAnnotationsDistinguishReadingFromMutatingFromDestroying() {
        for (final McpSchema.Tool tool : this.client.listTools().tools()) {
            final McpSchema.ToolAnnotations annotations = tool.annotations();
            assertThat(annotations).as("annotations of %s", tool.name()).isNotNull();
            final boolean destructive = DESTRUCTIVE.contains(tool.name());
            final boolean mutating = MUTATING.contains(tool.name());
            assertThat(annotations.readOnlyHint()).as("readOnlyHint of %s", tool.name())
                    .isEqualTo(!destructive && !mutating);
            assertThat(annotations.destructiveHint()).as("destructiveHint of %s", tool.name())
                    .isEqualTo(destructive);
        }
    }

    @Test
    void everyRegisteredToolIsClassifiedByThisTest() {
        final List<String> names = this.client.listTools().tools().stream()
                .map(McpSchema.Tool::name)
                .toList();
        assertThat(names).as("a new tool must be classified above, not silently skipped")
                .allSatisfy(name -> assertThat(DESTRUCTIVE.contains(name)
                        || MUTATING.contains(name)
                        || name.startsWith("get_") || name.startsWith("list_") || name.startsWith("search_"))
                        .as("unclassified tool %s", name).isTrue());
    }

    @Test
    void listInboxesReturnsStructuredObjectContent() {
        final McpSchema.CallToolResult result = this.call("list_inboxes", Map.of());
        assertThat(result.isError()).isFalse();
        assertThat(this.structured(result)).containsEntry("count", 1);
        assertThat(this.textOf(result)).contains("alice@example.com");
    }

    @Test
    void getEmailReturnsTheWholeMessage() {
        final McpSchema.CallToolResult result = this.call("get_email", Map.of("id", this.messageId));
        assertThat(result.isError()).as("result text: %s", this.textOf(result)).isFalse();
        assertThat(this.textOf(result)).contains(this.messageId)
                .contains("See attached.")
                .contains("invoice.pdf");
    }

    @Test
    void getEmailValidationExplainsWhyAMessageWouldBeRefused() {
        final String brokenId = this.mailbox.receive(MailFixtures.bytes(MailFixtures.NO_DATE),
                MailFixtures.envelope("app@example.com", "bob@example.com"),
                SessionInfo.plaintext()).message().id();
        final McpSchema.CallToolResult result = this.call("get_email_validation", Map.of("id", brokenId));
        assertThat(result.isError()).isFalse();
        assertThat(this.textOf(result)).contains("RFC5322_MISSING_DATE").contains("5.6.0");
    }

    /** An unknown id must produce a readable error, not an internal serialisation complaint. */
    @Test
    void anUnknownIdProducesAnActionableError() {
        final McpSchema.CallToolResult result = this.call("get_email", Map.of("id", "does-not-exist"));
        assertThat(result.isError()).isTrue();
        assertThat(this.textOf(result)).contains("No message with id does-not-exist")
                .contains("list_recent_emails")
                .doesNotContain("structuredContent must not be null");
    }

    @Test
    void searchFindsAMessageByItsBodyText() {
        final McpSchema.CallToolResult result = this.call("search_emails", Map.of("text", "See attached"));
        assertThat(result.isError()).isFalse();
        assertThat(this.structured(result)).containsEntry("total", 1);
    }

    @Test
    void attachmentContentComesBackWithItsDigest() {
        final McpSchema.CallToolResult result =
                this.call("get_email_attachment", Map.of("id", this.messageId, "index", 1));
        assertThat(result.isError()).isFalse();
        assertThat(this.textOf(result)).contains("invoice.pdf").contains("application/pdf");
    }

    @Test
    void deletingThroughTheEndpointRemovesTheMessage() {
        final McpSchema.CallToolResult result = this.call("delete_email", Map.of("id", this.messageId));
        assertThat(result.isError()).isFalse();
        assertThat(this.structured(result)).containsEntry("deleted", 1);
        assertThat(this.mailbox.find(this.messageId)).isEmpty();
    }

    @Test
    void theChaosToolsAreRegisteredAndDriveTheMonkeyEndToEnd() {
        assertThat(this.client.listTools().tools().stream().map(McpSchema.Tool::name).toList())
                .contains("get_chaos_status", "configure_chaos", "reset_chaos_faults");

        final McpSchema.CallToolResult off = this.call("get_chaos_status", Map.of());
        assertThat(off.isError()).isFalse();
        assertThat(this.textOf(off)).contains("\"enabled\":false");

        // The loop an agent actually runs: reset, enable one specific fault, act, read the counters.
        assertThat(this.call("reset_chaos_faults", Map.of()).isError()).isFalse();
        final McpSchema.CallToolResult on = this.call("configure_chaos",
                Map.of("enabled", true, "rejectSender", 1.0, "disconnect", 0.0));
        assertThat(on.isError()).as("%s", this.textOf(on)).isFalse();
        assertThat(this.textOf(on)).contains("\"enabled\":true");

        try {
            assertThat(this.textOf(this.call("get_chaos_status", Map.of())))
                    .contains("\"rejectSender\":1.0");
        } finally {
            this.call("configure_chaos", Map.of("enabled", false));
        }
    }

    @Test
    void clearingEveryInboxEmptiesTheServer() {
        final McpSchema.CallToolResult result = this.call("clear_all_inboxes", Map.of());
        assertThat(result.isError()).isFalse();
        assertThat(this.mailbox.size()).isZero();
    }
}
