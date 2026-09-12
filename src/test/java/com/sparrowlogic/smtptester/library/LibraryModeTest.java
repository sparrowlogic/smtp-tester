package com.sparrowlogic.smtptester.library;

import com.sparrowlogic.smtptester.SmtpTesterApplication;
import com.sparrowlogic.smtptester.mcp.MailMcpTools;
import com.sparrowlogic.smtptester.smtp.MailboxService;
import com.sparrowlogic.smtptester.smtp.SmtpReceiver;
import com.sparrowlogic.smtptester.web.MessageApiController;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Boots the jar the way a host application would: someone else's
 * {@code @SpringBootConfiguration}, with this artifact merely on the classpath.
 *
 * <p>Nothing here imports the project's own application class, so this fails if the
 * auto-configuration stops being self-sufficient — which is the whole promise of shipping the plain
 * jar as a library. It also pins the reason the jar carries no {@code application.yaml}: the host's
 * own {@code server.port} must survive.
 *
 * <p>It lives in its own package so that {@link HostApplication} does not compete with the real
 * application class when every other test searches upwards for a {@code @SpringBootConfiguration}.
 */
@SpringBootTest(classes = LibraryModeTest.HostApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "smtptester.smtp.port=0")
class LibraryModeTest {

    @Autowired
    private ApplicationContext context;

    @Autowired
    private SmtpReceiver receiver;

    @Test
    void addingTheJarToAHostApplicationStartsAWorkingSmtpServer() {
        assertThat(this.receiver.isRunning()).isTrue();
        assertThat(this.receiver.port()).isPositive();
    }

    @Test
    void theHostGetsTheStoreTheApiAndTheMcpToolsWithoutComponentScanning() {
        assertThat(this.context.getBean(MailboxService.class)).isNotNull();
        assertThat(this.context.getBean(MessageApiController.class)).isNotNull();
        assertThat(this.context.getBean(MailMcpTools.class)).isNotNull();
    }

    /**
     * The artifact must not ship an application.yaml or a logback-spring.xml; either would be read
     * by any host application with this jar on its classpath and would silently move its HTTP port
     * or hijack its logging.
     *
     * <p>This inspects the built output directory rather than the classpath, because the test
     * classpath legitimately has its own application.yaml and would mask the very thing being
     * checked.
     */
    @Test
    void theArtifactShipsNoConfigurationThatCouldOverrideTheHost() throws Exception {
        final Path classes = Path.of(SmtpTesterApplication.class.getProtectionDomain()
                .getCodeSource().getLocation().toURI());
        assertThat(classes).as("built output directory").exists();
        for (final String forbidden : List.of("application.yaml", "application.yml",
                "application.properties", "logback-spring.xml", "logback.xml")) {
            assertThat(classes.resolve(forbidden))
                    .as("%s must not be packaged in the library artifact", forbidden)
                    .doesNotExist();
        }
        // The standalone logging config does ship, under a name Boot will not auto-detect.
        assertThat(classes.resolve("smtptester-logback.xml")).exists();
    }

    /** A host that wants the standalone defaults can still read them; they are not applied here. */
    @Test
    void standaloneDefaultsAreDeclaredButNotAppliedInLibraryMode() {
        assertThat(SmtpTesterApplication.defaultProperties())
                .containsEntry("server.port", "8025")
                .containsEntry("logging.config", "classpath:smtptester-logback.xml");
    }

    /** A stand-in for somebody else's application: no component scan of this project's packages. */
    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class HostApplication {
    }
}
