package com.sparrowlogic.smtptester;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import java.util.Map;

/**
 * Standalone entry point: the executable jar and the Docker image.
 *
 * <p>Standalone-only defaults are applied here rather than in an {@code application.yaml} inside
 * the jar. A packaged {@code application.yaml} would be read by any Spring Boot application that
 * added this artifact as a dependency and would silently move its HTTP port to 8025, which is
 * exactly the kind of surprise a test dependency must not spring on its host. Set here, the
 * defaults apply only when this class is the one starting the context, and any of them can still
 * be overridden by a config file, an environment variable or a command-line argument.
 *
 * <p>There is deliberately no {@code @ComponentScan}. Every bean comes from the two
 * auto-configurations, so the set of beans is identical whether this class boots the application or
 * the jar is dropped into someone else's — no feature can work in one mode and quietly not the
 * other.
 */
@SpringBootConfiguration
@EnableAutoConfiguration
public class SmtpTesterApplication {

    private SmtpTesterApplication() {
    }

    public static void main(final String[] args) {
        final SpringApplication application = new SpringApplication(SmtpTesterApplication.class);
        application.setDefaultProperties(defaultProperties());
        application.run(args);
    }

    /**
     * Defaults that apply only when running as the standalone server.
     *
     * <p>Public so a host application embedding this jar can opt into the same defaults
     * deliberately, and so the library-mode test can assert they are declared without being applied.
     */
    public static Map<String, Object> defaultProperties() {
        return Map.of(
                // 8025 for the inbox, the REST API and the MCP endpoint; 1025 for SMTP. Both match
                // MailHog and Mailpit, so existing developer setups need no changes.
                "server.port", "8025",
                "spring.application.name", "smtp-tester",
                "logging.config", "classpath:smtptester-logback.xml",
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
