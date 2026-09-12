package com.sparrowlogic.smtptester.web;

import com.sparrowlogic.smtptester.chaos.ChaosMonkey;
import com.sparrowlogic.smtptester.config.SmtpTesterProperties;
import com.sparrowlogic.smtptester.docs.AgentDocs;
import com.sparrowlogic.smtptester.smtp.MailboxService;
import com.sparrowlogic.smtptester.smtp.ServerCertificate;
import gg.jte.TemplateEngine;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import tools.jackson.databind.ObjectMapper;

/**
 * Registers the inbox UI and the REST API.
 *
 * <p>Everything is declared as a bean here rather than found by component scanning, because the
 * application intentionally does not scan: the bean graph is exactly what this file says.
 */
@Configuration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnProperty(prefix = "smtp-tester.web", name = "enabled", havingValue = "true",
        matchIfMissing = true)
public class SmtpTesterWebConfiguration {

    /**
     * Scoped to this server's own routes rather than {@code /*}.
     *
     * <p>{@code base-path} lets the inbox be mounted under a prefix. A filter registered at
     * {@code /*} would then impose a {@code default-src 'none'} policy on everything else served by
     * the same context, not just on the inbox. Mounted at the root the two are the same thing;
     * under a prefix only the scoped version is correct.
     */
    @Bean
    public FilterRegistrationBean<SecurityHeadersFilter> smtpTesterSecurityHeadersFilter(
            final SmtpTesterProperties properties) {
        final String basePath = properties.web().basePath();
        final FilterRegistrationBean<SecurityHeadersFilter> registration =
                new FilterRegistrationBean<>(new SecurityHeadersFilter());
        registration.addUrlPatterns(basePath.isBlank() ? "/*" : basePath + "/*");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 100);
        return registration;
    }

    @Bean
    public MessageViewMapper smtpTesterMessageViewMapper(final MailboxService mailbox,
            final SmtpTesterProperties properties) {
        return new MessageViewMapper(mailbox, properties.mcp());
    }

    @Bean
    public MessageApiController smtpTesterMessageApiController(final MailboxService mailbox,
            final MessageViewMapper mapper) {
        return new MessageApiController(mailbox, mapper);
    }

    /**
     * The live feed, registered with the mailbox as it is built.
     *
     * <p>Registering here rather than passing listeners into {@link MailboxService} keeps the
     * direction of the dependency right: the core of the server knows nothing about servlets, and
     * this bean only exists in a servlet application, so the wiring belongs on this side of the
     * line. Any other listener registers the same way, through
     * {@link MailboxService#addArrivalListener(com.sparrowlogic.smtptester.core.MailArrivalListener)}.
     */
    @Bean
    @ConditionalOnProperty(prefix = "smtp-tester.web.stream", name = "enabled", havingValue = "true",
            matchIfMissing = true)
    public MailEventStream smtpTesterMailEventStream(final MailboxService mailbox,
            final ObjectMapper objectMapper, final SmtpTesterProperties properties) {
        final MailEventStream stream =
                new MailEventStream(mailbox, objectMapper, properties.web().stream());
        mailbox.addArrivalListener(stream);
        return stream;
    }

    @Bean
    @ConditionalOnProperty(prefix = "smtp-tester.web.stream", name = "enabled", havingValue = "true",
            matchIfMissing = true)
    public MessageStreamController smtpTesterMessageStreamController(final MailEventStream stream,
            final SmtpTesterProperties properties) {
        return new MessageStreamController(stream, properties.web().stream());
    }

    @Bean
    public ChaosController smtpTesterChaosController(final ChaosMonkey chaos) {
        return new ChaosController(chaos);
    }

    /** Serves the same text the {@code get_documentation} MCP tool returns. */
    @Bean
    public AgentDocsController smtpTesterAgentDocsController(final AgentDocs docs) {
        return new AgentDocsController(docs);
    }

    /**
     * Only present when STARTTLS is on, because there is otherwise no certificate to publish.
     *
     * <p>This tests the same property as the {@code ServerCertificate} bean itself rather than
     * {@code @ConditionalOnBean}, which would depend on this class being processed after the one
     * declaring that bean. Plain {@code @Configuration} classes carry no such ordering guarantee.
     */
    @Bean
    @ConditionalOnExpression("'${smtp-tester.smtp.tls.mode:OFF}' != 'OFF'")
    public TlsInfoController smtpTesterTlsInfoController(final ServerCertificate certificate) {
        return new TlsInfoController(certificate);
    }

    @Bean
    public InboxPageController smtpTesterInboxPageController(final MailboxService mailbox,
            final TemplateEngine smtpTesterTemplateEngine, final SmtpTesterProperties properties) {
        return new InboxPageController(mailbox, smtpTesterTemplateEngine, properties.web().basePath());
    }
}
