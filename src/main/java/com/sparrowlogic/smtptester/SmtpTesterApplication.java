package com.sparrowlogic.smtptester;

import com.sparrowlogic.smtptester.config.SmtpTesterConfiguration;
import com.sparrowlogic.smtptester.web.SmtpTesterWebConfiguration;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.Import;
import java.util.Map;

/**
 * Standalone entry point: the executable jar and the Docker image.
 *
 * <p>Defaults are applied here rather than in an {@code application.yaml} so that they stay
 * overridable in the ordinary Spring Boot way — a config file, an environment variable or a
 * command-line argument all still win, which is how the Docker image points the spool at its
 * volume.
 *
 * <p>There is deliberately no {@code @ComponentScan}. Both configuration classes are named on
 * {@code @Import} below, so the bean graph is whatever those two classes declare and nothing else —
 * a bean cannot appear because a package happened to be scanned. The order matters: beans in
 * {@code SmtpTesterConfiguration} are registered first, which is what the web layer's conditions
 * assume.
 */
@SpringBootConfiguration
@EnableAutoConfiguration
@Import({SmtpTesterConfiguration.class, SmtpTesterWebConfiguration.class})
public class SmtpTesterApplication {

    private SmtpTesterApplication() {
    }

    public static void main(final String[] args) {
        final SpringApplication application = new SpringApplication(SmtpTesterApplication.class);
        application.setDefaultProperties(defaultProperties());
        application.run(args);
    }

    /** Defaults for the standalone server, overridable by config file, environment or argument. */
    public static Map<String, Object> defaultProperties() {
        return Map.of(
                // 8025 for the inbox, the REST API and the MCP endpoint; 1025 for SMTP. Both match
                // MailHog and Mailpit, so existing developer setups need no changes.
                "server.port", "8025",
                // Captured mail survives a restart without anyone having to know a property name.
                // Relative on purpose: the spool belongs to the project being tested, so it lands
                // beside it rather than somewhere global two projects would share. The default is
                // set here rather than on the property itself so that the bare record still binds
                // to "spool nowhere", which is what the property test pins. Set the property to an
                // empty value to turn spooling back off; the Docker image overrides it with the
                // /data/mail volume.
                "smtp-tester.spool.directory", "./mail",
                "spring.application.name", "smtp-tester",
                "logging.config", "classpath:smtp-tester-logback.xml",
                // The MCP server speaks streamable HTTP at /mcp on the same port as the UI, so one
                // container publishes one HTTP port and an agent needs one URL.
                "spring.ai.mcp.server.name", "smtp-tester",
                "spring.ai.mcp.server.protocol", "streamable",
                "spring.ai.mcp.server.instructions",
                "A test SMTP server that captures email instead of delivering it. Use list_inboxes to "
                        + "discover recipient addresses, search_emails to find a specific message, and "
                        + "get_email to read one. Every message also carries an RFC compliance report "
                        + "explaining anything malformed about how it was sent.");
    }
}
