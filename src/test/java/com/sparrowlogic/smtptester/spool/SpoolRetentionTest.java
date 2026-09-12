package com.sparrowlogic.smtptester.spool;

import com.sparrowlogic.smtptester.MailFixtures;
import com.sparrowlogic.smtptester.core.MailMessage;
import com.sparrowlogic.smtptester.core.MessageIds;
import com.sparrowlogic.smtptester.core.MimeParser;
import com.sparrowlogic.smtptester.validation.MailValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code max-messages} is documented as bounding the spool directory, not just memory. It only ever
 * did so as a side effect of eviction from the in-memory store, which can evict only what it holds
 * — so a directory kept across restarts and not read back in grew without limit while the inbox
 * stayed capped, and the documented bound quietly stopped being true. These tests are the
 * directory-side half of the cap.
 */
class SpoolRetentionTest {

    @TempDir
    private Path root;

    private FilesystemMailSpool spool;

    @BeforeEach
    void setUp() {
        this.spool = new FilesystemMailSpool(this.root, JsonMapper.builder().build(),
                new MimeParser(), MailValidator.standard(), true, true);
    }

    /** Stores {@code count} messages a millisecond apart, so arrival order is unambiguous. */
    private List<MailMessage> storeInOrder(final int count) {
        final Instant base = Instant.parse("2026-09-12T12:00:00Z");
        return java.util.stream.IntStream.range(0, count)
                .mapToObj(i -> {
                    final MailMessage message = new MailMessage(
                            MessageIds.next(base.plusMillis(i)),
                            base.plusMillis(i),
                            MailFixtures.defaultEnvelope(),
                            MailFixtures.bytes(MailFixtures.WELL_FORMED),
                            new MimeParser().parse(MailFixtures.bytes(MailFixtures.WELL_FORMED)),
                            MailValidator.standard().validate(
                                    MailFixtures.bytes(MailFixtures.WELL_FORMED),
                                    MailFixtures.defaultEnvelope(),
                                    new MimeParser().parse(MailFixtures.bytes(MailFixtures.WELL_FORMED)),
                                    com.sparrowlogic.smtptester.core.SessionInfo.plaintext()),
                            false,
                            com.sparrowlogic.smtptester.core.SessionInfo.plaintext());
                    this.spool.store(message);
                    return message;
                })
                .toList();
    }

    private List<String> directoriesOnDisk() throws IOException {
        try (Stream<Path> entries = Files.list(this.root)) {
            return entries.filter(Files::isDirectory)
                    .map(path -> path.getFileName().toString())
                    .sorted()
                    .toList();
        }
    }

    @Test
    void deletesTheOldestBeyondTheCap() throws IOException {
        this.storeInOrder(10);

        assertThat(this.spool.enforceRetention(4)).isEqualTo(6);
        assertThat(this.directoriesOnDisk()).hasSize(4);
    }

    /** Which four survive matters: keeping the oldest would throw away the mail just captured. */
    @Test
    void keepsTheNewestRatherThanAnArbitraryFour() throws IOException {
        final List<MailMessage> stored = this.storeInOrder(10);
        final List<String> expected = stored.subList(6, 10).stream().map(MailMessage::id).sorted().toList();

        this.spool.enforceRetention(4);

        assertThat(this.directoriesOnDisk())
                .allSatisfy(name -> assertThat(expected).anySatisfy(id -> assertThat(name).endsWith(id)))
                .hasSize(4);
    }

    @Test
    void deletesTheWholeMessageDirectoryRatherThanLeavingAnEmptyShell() throws IOException {
        this.storeInOrder(3);

        this.spool.enforceRetention(1);

        assertThat(this.directoriesOnDisk()).hasSize(1);
        try (Stream<Path> all = Files.walk(this.root)) {
            assertThat(all.filter(Files::isRegularFile).map(p -> p.getFileName().toString()))
                    .as("no orphaned message.eml or metadata.json from the deleted messages")
                    .allMatch(name -> name.equals("message.eml") || name.equals("metadata.json"));
        }
    }

    @Test
    void doesNothingWhenAlreadyUnderTheCap() throws IOException {
        this.storeInOrder(3);

        assertThat(this.spool.enforceRetention(10)).isZero();
        assertThat(this.directoriesOnDisk()).hasSize(3);
    }

    @Test
    void doesNothingWhenTheCountIsExactlyTheCap() throws IOException {
        this.storeInOrder(5);

        assertThat(this.spool.enforceRetention(5)).isZero();
        assertThat(this.directoriesOnDisk()).hasSize(5);
    }

    @Test
    void emptiesTheSpoolWhenNothingIsToBeKept() throws IOException {
        this.storeInOrder(4);

        assertThat(this.spool.enforceRetention(0)).isEqualTo(4);
        assertThat(this.directoriesOnDisk()).isEmpty();
    }

    /** A negative cap is a misconfiguration, not an instruction to delete everything. */
    @Test
    void refusesToActOnANegativeCap() throws IOException {
        this.storeInOrder(3);

        assertThat(this.spool.enforceRetention(-1)).isZero();
        assertThat(this.directoriesOnDisk()).hasSize(3);
    }

    @Test
    void toleratesASpoolDirectoryThatDoesNotExistYet() {
        final FilesystemMailSpool absent = new FilesystemMailSpool(
                this.root.resolve("nested").resolve("deeper"), JsonMapper.builder().build(),
                new MimeParser(), MailValidator.standard(), true, true);

        assertThat(absent.enforceRetention(5)).isZero();
    }

    /**
     * The accumulation this fixes: each run writes into a spool it never read, so without the
     * directory-side cap the disk keeps climbing while the inbox stays bounded.
     */
    @Test
    void boundsASpoolAcrossRepeatedRunsThatNeverRestoreIt() throws IOException {
        final int cap = 5;
        for (int run = 0; run < 4; run++) {
            this.storeInOrder(cap);
            this.spool.enforceRetention(cap);
            assertThat(this.directoriesOnDisk())
                    .as("disk after run %d", run + 1)
                    .hasSize(cap);
        }
    }

    @Test
    void leavesARestoredSpoolLoadableAfterTrimming() {
        this.storeInOrder(8);

        this.spool.enforceRetention(3);

        assertThat(this.spool.loadAll()).hasSize(3);
    }

    @Test
    void theDisabledSpoolHasNothingToEnforce() {
        assertThat(MailSpool.DISABLED.enforceRetention(5)).isZero();
    }
}
