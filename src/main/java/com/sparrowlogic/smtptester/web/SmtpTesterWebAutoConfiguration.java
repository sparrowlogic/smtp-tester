package com.sparrowlogic.smtptester.web;

import com.sparrowlogic.smtptester.chaos.ChaosMonkey;
import com.sparrowlogic.smtptester.config.SmtpTesterAutoConfiguration;
import com.sparrowlogic.smtptester.config.SmtpTesterProperties;
import com.sparrowlogic.smtptester.smtp.MailboxService;
import com.sparrowlogic.smtptester.smtp.ServerCertificate;
import gg.jte.TemplateEngine;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;

/**
 * Registers the inbox UI and the REST API.
 *
 * <p>Everything is declared as a bean here rather than found by component scanning, because the
 * standalone application intentionally does not scan: that way the set of beans is identical
 * whether this jar boots itself or is embedded in someone else's application, and there is no
 * second code path that only exists in one of those two modes.
 */
@AutoConfiguration(after = SmtpTesterAutoConfiguration.class)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnProperty(prefix = "smtptester.web", name = "enabled", havingValue = "true",
        matchIfMissing = true)
public class SmtpTesterWebAutoConfiguration {

    /**
     * Scoped to this server's own routes rather than {@code /*}.
     *
     * <p>When the jar is embedded in a host application, a global filter would impose a
     * {@code default-src 'none'} policy on that application's pages, which is not this library's
     * call to make. Mounted at the root — the standalone case — the two are the same thing.
     */
    @Bean
    @ConditionalOnMissingBean(name = "smtpTesterSecurityHeadersFilter")
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
    @ConditionalOnMissingBean
    public MessageViewMapper smtpTesterMessageViewMapper(final MailboxService mailbox,
            final SmtpTesterProperties properties) {
        return new MessageViewMapper(mailbox, properties.mcp());
    }

    @Bean
    @ConditionalOnMissingBean
    public MessageApiController smtpTesterMessageApiController(final MailboxService mailbox,
            final MessageViewMapper mapper) {
        return new MessageApiController(mailbox, mapper);
    }

    @Bean
    @ConditionalOnMissingBean
    public ChaosController smtpTesterChaosController(final ChaosMonkey chaos) {
        return new ChaosController(chaos);
    }

    /** Only present when STARTTLS is on, because there is otherwise no certificate to publish. */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(ServerCertificate.class)
    public TlsInfoController smtpTesterTlsInfoController(final ServerCertificate certificate) {
        return new TlsInfoController(certificate);
    }

    @Bean
    @ConditionalOnMissingBean
    public InboxPageController smtpTesterInboxPageController(final MailboxService mailbox,
            final TemplateEngine smtpTesterTemplateEngine, final SmtpTesterProperties properties) {
        return new InboxPageController(mailbox, smtpTesterTemplateEngine, properties.web().basePath());
    }
}
