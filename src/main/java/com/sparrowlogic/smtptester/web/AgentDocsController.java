package com.sparrowlogic.smtptester.web;

import com.sparrowlogic.smtptester.docs.AgentDocs;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import java.nio.charset.StandardCharsets;

/**
 * Serves the agent-facing documentation over HTTP.
 *
 * <p>Served as {@code text/plain} rather than {@code text/markdown} on purpose: the audience is a
 * model reading the bytes, and {@code text/plain} is what every HTTP client will hand back as a
 * string without negotiating. It is mounted under {@code base-path} like everything else, so the
 * security headers filter covers it too.
 */
@Controller
@RequestMapping("${smtp-tester.web.base-path:}")
public class AgentDocsController {

    private final AgentDocs docs;

    public AgentDocsController(final AgentDocs docs) {
        this.docs = docs;
    }

    /**
     * Serves the short index, the file an agent is conventionally pointed at first.
     *
     * @return the index document as UTF-8 plain text
     */
    @GetMapping(value = "/llms.txt", produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> index() {
        return this.document(AgentDocs.Document.INDEX);
    }

    /**
     * Serves the complete reference.
     *
     * @return the full document as UTF-8 plain text
     */
    @GetMapping(value = "/llms-full.txt", produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> full() {
        return this.document(AgentDocs.Document.FULL);
    }

    private ResponseEntity<String> document(final AgentDocs.Document document) {
        return ResponseEntity.ok()
                .contentType(new MediaType(MediaType.TEXT_PLAIN, StandardCharsets.UTF_8))
                .body(this.docs.text(document));
    }
}
