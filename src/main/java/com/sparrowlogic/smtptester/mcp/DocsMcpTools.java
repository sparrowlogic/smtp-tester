package com.sparrowlogic.smtptester.mcp;

import com.sparrowlogic.smtptester.docs.AgentDocs;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpTool.McpAnnotations;
import org.springframework.ai.mcp.annotation.McpToolParam;

/**
 * Lets a model read this server's own documentation without leaving the MCP session.
 *
 * <p>The other tools describe themselves well enough to be called, but they cannot explain the
 * parts of the server that are not tools — the SMTP side, what validation will reject, which
 * environment variable changes it. A model that has this can answer "why did my mail get a 550"
 * from the same connection it used to look the message up.
 */
public class DocsMcpTools {

    private final AgentDocs docs;

    public DocsMcpTools(final AgentDocs docs) {
        this.docs = docs;
    }

    /**
     * @param full true for the complete reference, false or omitted for the short index
     * @return the documentation as markdown
     */
    @McpTool(name = "get_documentation",
            annotations = @McpAnnotations(readOnlyHint = true, destructiveHint = false,
                    idempotentHint = true, openWorldHint = false),
            title = "Get smtp-tester documentation",
            description = "Returns this server's own documentation as markdown: how to point an "
                    + "application at it, the REST endpoints, every configuration key and its "
                    + "environment-variable form, what the RFC validator rejects and why a message "
                    + "might have been refused with a 550. Call it with full=false for a short index "
                    + "and full=true for the complete reference. Use this before guessing at a URL "
                    + "or a setting name.")
    public String getDocumentation(
            @McpToolParam(description = "True for the complete reference; false for the short index.",
                    required = false) @Nullable final Boolean full) {
        final AgentDocs.Document document =
                Boolean.TRUE.equals(full) ? AgentDocs.Document.FULL : AgentDocs.Document.INDEX;
        return this.docs.text(document);
    }
}
