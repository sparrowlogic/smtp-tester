package com.sparrowlogic.smtptester.web;

import com.sparrowlogic.smtptester.core.InboxSummary;
import com.sparrowlogic.smtptester.core.MailMessage;
import org.jspecify.annotations.Nullable;
import java.util.List;

/**
 * What the inbox page renders.
 *
 * @param basePath prefix every link must carry, so the UI still works when a host application
 *     mounts this jar under a sub-path
 * @param inboxes every recipient address that has received mail
 * @param selectedInbox the inbox currently filtered to, when one is
 * @param search the current free-text query, echoed back into the search box
 * @param onlyFailed whether the failing-only filter is on
 * @param messages the messages to list, newest first
 * @param totalMessages how many messages the server holds in total
 */
public record InboxPageModel(
        String basePath,
        List<InboxSummary> inboxes,
        @Nullable String selectedInbox,
        @Nullable String search,
        boolean onlyFailed,
        List<MailMessage> messages,
        long totalMessages) {
}
