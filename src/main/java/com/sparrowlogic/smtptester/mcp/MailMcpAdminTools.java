package com.sparrowlogic.smtptester.mcp;

import com.sparrowlogic.smtptester.smtp.MailboxService;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpTool.McpAnnotations;
import org.springframework.ai.mcp.annotation.McpToolParam;

/**
 * The destructive half of the MCP surface, registered only when
 * {@code smtptester.mcp.allow-delete} is true.
 *
 * <p>Separating these from the read tools is what makes that switch meaningful: with the flag off
 * the tools are absent from {@code tools/list} entirely, so a model cannot call them and then be
 * refused. Every deletion removes the spooled files along with the in-memory message, so the
 * directory never drifts from the inbox.
 */
public class MailMcpAdminTools {

    private final MailboxService mailbox;

    public MailMcpAdminTools(final MailboxService mailbox) {
        this.mailbox = mailbox;
    }

    @McpTool(name = "delete_email",
            annotations = @McpAnnotations(readOnlyHint = false, destructiveHint = true,
                    idempotentHint = true, openWorldHint = false),
            generateOutputSchema = true,
            title = "Delete one email",
            description = "Permanently deletes one message from the inbox and removes its spooled "
                    + "files from disk. Use this to keep a test inbox clean between runs. There is "
                    + "no undo.")
    public McpViews.DeletionResult deleteEmail(
            @McpToolParam(description = "The message id to delete.") final String id) {
        final boolean deleted = this.mailbox.delete(id);
        return new McpViews.DeletionResult(deleted ? 1 : 0,
                deleted ? "Deleted message " + id : "No message with id " + id);
    }

    @McpTool(name = "clear_inbox",
            annotations = @McpAnnotations(readOnlyHint = false, destructiveHint = true,
                    idempotentHint = true, openWorldHint = false),
            generateOutputSchema = true,
            title = "Clear one inbox",
            description = "Deletes every message delivered to one recipient address, and their "
                    + "spooled files. Omit 'inbox' to clear every inbox on the server. There is no "
                    + "undo.")
    public McpViews.DeletionResult clearInbox(
            @McpToolParam(required = false,
                    description = "The recipient address to clear. Omit to clear all inboxes.")
            final @Nullable String inbox) {
        if (inbox == null || inbox.isBlank()) {
            final int deleted = this.mailbox.clearAll();
            return new McpViews.DeletionResult(deleted,
                    "Cleared every inbox, deleting " + deleted + " message(s)");
        }
        final int deleted = this.mailbox.clearInbox(inbox);
        return new McpViews.DeletionResult(deleted,
                "Cleared " + inbox + ", deleting " + deleted + " message(s)");
    }

    @McpTool(name = "clear_all_inboxes",
            annotations = @McpAnnotations(readOnlyHint = false, destructiveHint = true,
                    idempotentHint = true, openWorldHint = false),
            generateOutputSchema = true,
            title = "Clear every inbox",
            description = "Deletes every message this server holds and every spooled file. Use it to "
                    + "reset to a known-empty state before a test run. There is no undo.")
    public McpViews.DeletionResult clearAllInboxes() {
        final int deleted = this.mailbox.clearAll();
        return new McpViews.DeletionResult(deleted, "Deleted " + deleted + " message(s) from all inboxes");
    }
}
