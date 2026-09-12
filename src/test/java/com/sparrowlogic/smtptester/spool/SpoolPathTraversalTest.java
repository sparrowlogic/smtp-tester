package com.sparrowlogic.smtptester.spool;

import com.sparrowlogic.smtptester.MailFixtures;
import com.sparrowlogic.smtptester.core.MailMessage;
import com.sparrowlogic.smtptester.core.MessageIds;
import com.sparrowlogic.smtptester.core.MimeParser;
import com.sparrowlogic.smtptester.validation.MailValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A spool directory is an ordinary host directory — bind-mounted, copied between machines, or
 * hand-populated with fixtures — so everything read back out of it is untrusted input. A message
 * id reaches the filesystem twice over: it names the directory written on receipt, and it names
 * the directory deleted recursively when the message is removed from the inbox. These tests pin
 * the guards that keep a crafted id from escaping the spool root.
 */
class SpoolPathTraversalTest {

    @TempDir
    private Path base;

    private Path root;
    private Path outside;
    private ObjectMapper json;
    private FilesystemMailSpool spool;

    @BeforeEach
    void setUp() throws IOException {
        this.root = Files.createDirectories(this.base.resolve("spool"));
        this.outside = Files.createDirectories(this.base.resolve("precious"));
        Files.writeString(this.outside.resolve("keep.txt"), "do not delete me");
        this.json = JsonMapper.builder().build();
        this.spool = new FilesystemMailSpool(this.root, this.json, new MimeParser(),
                MailValidator.standard(), true, true);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "../../precious",
        "..%2F..%2Fprecious",
        "000000000001-0000000/../../../precious",
        "0000/0001-00000001",
        "000000000001-00000001 ",
        "not-an-id",
        "",
    })
    void refusesToSpoolAMessageWhoseIdCouldNameAPathOutsideTheRoot(final String id) {
        final MailMessage message = MailFixtures.message(id, MailFixtures.WELL_FORMED);

        this.spool.store(message);

        assertThat(this.spool.directoryFor(message)).isEmpty();
        assertThat(this.outside.resolve("keep.txt")).exists();
    }

    @Test
    void removingAMessageWithATraversingIdTouchesNothingOutsideTheRoot() {
        final MailMessage message =
                MailFixtures.message("../../precious", MailFixtures.WELL_FORMED);

        this.spool.remove(message);

        assertThat(this.outside).isDirectory();
        assertThat(this.outside.resolve("keep.txt")).exists();
    }

    /**
     * The attack that motivates the guard: an id is recorded in {@code metadata.json}, and that
     * file is what a restored message's identity comes from. Without validation on the way back in,
     * a hand-edited id would be resolved against the spool root the next time the message was
     * deleted from the inbox.
     */
    @Test
    void ignoresATraversingIdPlantedInSpoolMetadata() throws IOException {
        final MailMessage good =
                MailFixtures.message(MessageIds.next(Instant.now()), MailFixtures.WELL_FORMED);
        this.spool.store(good);
        final Path directory = this.spool.directoryFor(good).orElseThrow();
        final String tampered = Files.readString(directory.resolve("metadata.json"))
                .replace(good.id(), "../../precious");
        Files.writeString(directory.resolve("metadata.json"), tampered);

        final List<MailMessage> restored = this.spool.loadAll();

        assertThat(restored).hasSize(1);
        assertThat(restored.getFirst().id()).isNotEqualTo("../../precious");
        assertThat(MessageIds.isValid(restored.getFirst().id())).isTrue();

        this.spool.remove(restored.getFirst());
        assertThat(this.outside.resolve("keep.txt")).exists();
    }

    @Test
    void stillRoundTripsAMessageWithAWellFormedId() {
        final String id = MessageIds.next(Instant.now());
        final MailMessage message = MailFixtures.message(id, MailFixtures.WELL_FORMED);

        this.spool.store(message);

        assertThat(this.spool.directoryFor(message)).isPresent();
        assertThat(this.spool.loadAll()).singleElement()
                .extracting(MailMessage::id).isEqualTo(id);

        this.spool.remove(message);
        assertThat(this.spool.directoryFor(message)).isEmpty();
    }

    @Test
    void resolvesTheRootToAnAbsolutePathSoARelativeConfigurationIsStillContained() {
        final Path relative = Path.of("target", "relative-spool-" + System.nanoTime());
        final FilesystemMailSpool relativeSpool = new FilesystemMailSpool(relative, this.json,
                new MimeParser(), MailValidator.standard(), false, false);
        final MailMessage message =
                MailFixtures.message(MessageIds.next(Instant.now()), MailFixtures.WELL_FORMED);

        relativeSpool.store(message);

        assertThat(relativeSpool.directoryFor(message)).get()
                .matches(Path::isAbsolute)
                .matches(path -> path.startsWith(relative.toAbsolutePath().normalize()));
        relativeSpool.remove(message);
    }
}
