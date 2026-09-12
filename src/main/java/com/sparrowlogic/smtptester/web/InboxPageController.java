package com.sparrowlogic.smtptester.web;

import com.sparrowlogic.smtptester.core.MailMessage;
import com.sparrowlogic.smtptester.core.MailQuery;
import com.sparrowlogic.smtptester.smtp.MailboxService;
import gg.jte.TemplateEngine;
import gg.jte.output.StringOutput;
import org.jspecify.annotations.Nullable;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

/**
 * The HTML inbox.
 *
 * <p>Templates are rendered by calling a privately owned, precompiled {@link TemplateEngine}
 * directly rather than through a Spring {@code ViewResolver}. Two reasons: the jte Spring starter
 * refuses to start unless {@code gg.jte.development-mode} or {@code use-precompiled-templates} is
 * set, and going through {@code createPrecompiled} guarantees the packaged artifact renders from
 * classes compiled into the jar rather than from {@code src/main/jte} on disk — which exists on a
 * developer machine and does not exist inside the container.
 */
@Controller
@RequestMapping("${smtp-tester.web.base-path:}")
public class InboxPageController {

    private static final int PAGE_SIZE = 200;

    private final MailboxService mailbox;
    private final TemplateEngine templates;
    private final String basePath;

    public InboxPageController(final MailboxService mailbox, final TemplateEngine templates,
            final String basePath) {
        this.mailbox = mailbox;
        this.templates = templates;
        this.basePath = basePath;
    }

    /** The inbox: a filterable list of everything the server has received. */
    @GetMapping(produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> inbox(
            @RequestParam(required = false) final @Nullable String inbox,
            @RequestParam(required = false) final @Nullable String search,
            @RequestParam(defaultValue = "false") final boolean onlyFailed) {
        final MailQuery query = new MailQuery(inbox, null, null, search, null, onlyFailed, PAGE_SIZE, 0);
        final InboxPageModel model = new InboxPageModel(this.basePath, this.mailbox.inboxes(), inbox,
                search, onlyFailed, this.mailbox.list(query), this.mailbox.size());
        return this.render("inbox.jte", model);
    }

    /** One message, with its bodies, headers, attachments and compliance report. */
    @GetMapping(value = "/messages/{id}", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> message(@PathVariable final String id) {
        return this.mailbox.find(id)
                .map(message -> this.render("message.jte", this.pageModel(message)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    private MessagePageModel pageModel(final MailMessage message) {
        return new MessagePageModel(this.basePath, message,
                new String(message.raw(), StandardCharsets.UTF_8),
                this.mailbox.spoolDirectory(message).map(Path::toString).orElse(null));
    }

    /**
     * Deletion is a POST rather than a link so a browser prefetch or a crawler cannot empty the
     * inbox; the DELETE mapping alongside it is what the page's own fetch() calls use.
     */
    @PostMapping("/messages/{id}/delete")
    public ResponseEntity<Void> deleteMessage(@PathVariable final String id) {
        this.mailbox.delete(id);
        return ResponseEntity.status(org.springframework.http.HttpStatus.SEE_OTHER)
                .location(URI.create(this.basePath + "/"))
                .build();
    }

    @DeleteMapping("/messages/{id}")
    public ResponseEntity<Void> deleteMessageAjax(@PathVariable final String id) {
        return this.mailbox.delete(id) ? ResponseEntity.noContent().build()
                : ResponseEntity.notFound().build();
    }

    /** Empties one inbox, or all of them when no inbox is named. */
    @PostMapping("/messages/delete-all")
    public ResponseEntity<Void> deleteAll(@RequestParam(required = false) final @Nullable String inbox) {
        if (inbox == null || inbox.isBlank()) {
            this.mailbox.clearAll();
        } else {
            this.mailbox.clearInbox(inbox);
        }
        return ResponseEntity.status(org.springframework.http.HttpStatus.SEE_OTHER)
                .location(URI.create(this.basePath + "/"))
                .build();
    }

    private ResponseEntity<String> render(final String template, final Object model) {
        final StringOutput output = new StringOutput();
        this.templates.render(template, model, output);
        return ResponseEntity.ok()
                .contentType(new MediaType(MediaType.TEXT_HTML, StandardCharsets.UTF_8))
                .body(output.toString());
    }
}
