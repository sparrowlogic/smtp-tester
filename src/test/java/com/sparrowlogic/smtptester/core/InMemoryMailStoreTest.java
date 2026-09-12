package com.sparrowlogic.smtptester.core;

import com.sparrowlogic.smtptester.MailFixtures;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class InMemoryMailStoreTest {

    private static MailMessage message(final String id, final String to) {
        return MailFixtures.message(id, MailFixtures.WELL_FORMED,
                MailFixtures.envelope("app@example.com", to));
    }

    private InMemoryMailStore store(final int max, final MailRemovalListener listener) {
        return new InMemoryMailStore(max, listener);
    }

    @Test
    void listsNewestFirst() {
        final InMemoryMailStore store = this.store(10, MailRemovalListener.NONE);
        store.add(message("000000000001-00000001", "a@example.com"));
        store.add(message("000000000002-00000002", "a@example.com"));
        store.add(message("000000000003-00000003", "a@example.com"));
        assertThat(store.list(MailQuery.recent(10))).extracting(MailMessage::id)
                .containsExactly("000000000003-00000003", "000000000002-00000002", "000000000001-00000001");
    }

    /**
     * The memory bound is exact and immediate, but eviction stays off the listener on purpose: the
     * listener deletes from disk and this runs on an SMTP session thread with a client waiting.
     * The spool is reconciled in batch by {@code SpoolRetentionTask} instead.
     */
    @Test
    void evictsOldestBeyondTheRetentionLimitWithoutCallingTheListener() {
        final List<String> removed = new ArrayList<>();
        final InMemoryMailStore store = this.store(2, m -> removed.add(m.id()));
        store.add(message("000000000001-00000001", "a@example.com"));
        store.add(message("000000000002-00000002", "a@example.com"));
        store.add(message("000000000003-00000003", "a@example.com"));

        assertThat(store.size()).isEqualTo(2);
        assertThat(store.list(MailQuery.recent(10))).extracting(MailMessage::id)
                .containsExactly("000000000003-00000003", "000000000002-00000002");
        assertThat(removed).as("eviction must not put a filesystem delete on the ingest path")
                .isEmpty();
    }

    /** A burst evicts many at once, and still never reaches the listener. */
    @Test
    void evictsEveryOverflowingMessageInOneAddWithoutCallingTheListener() {
        final List<String> removed = new ArrayList<>();
        final InMemoryMailStore store = this.store(2, m -> removed.add(m.id()));
        for (int i = 1; i <= 50; i++) {
            store.add(message(String.format("%012d-%08d", i, i), "a@example.com"));
        }

        assertThat(store.size()).isEqualTo(2);
        assertThat(removed).isEmpty();
    }

    @Test
    void deletingNotifiesTheListenerSoSpooledFilesGoToo() {
        final List<String> removed = new ArrayList<>();
        final InMemoryMailStore store = this.store(10, m -> removed.add(m.id()));
        store.add(message("000000000001-00000001", "a@example.com"));
        assertThat(store.delete("000000000001-00000001")).isPresent();
        assertThat(removed).containsExactly("000000000001-00000001");
        assertThat(store.delete("nope")).isEmpty();
    }

    @Test
    void clearRemovesEverything() {
        final InMemoryMailStore store = this.store(10, MailRemovalListener.NONE);
        store.add(message("000000000001-00000001", "a@example.com"));
        store.add(message("000000000002-00000002", "b@example.com"));
        assertThat(store.clear()).isEqualTo(2);
        assertThat(store.size()).isZero();
    }

    @Test
    void groupsMessagesIntoInboxesByRecipient() {
        final InMemoryMailStore store = this.store(10, MailRemovalListener.NONE);
        store.add(message("000000000001-00000001", "a@example.com"));
        store.add(message("000000000002-00000002", "a@example.com"));
        store.add(message("000000000003-00000003", "b@example.com"));
        final List<InboxSummary> inboxes = store.inboxes();
        assertThat(inboxes).extracting(InboxSummary::address)
                .containsExactlyInAnyOrder("a@example.com", "b@example.com");
        assertThat(inboxes).filteredOn(i -> i.address().equals("a@example.com"))
                .singleElement()
                .satisfies(i -> assertThat(i.messageCount()).isEqualTo(2));
    }

    @Test
    void inboxFilterMatchesEnvelopeRecipientsCaseInsensitively() {
        final InMemoryMailStore store = this.store(10, MailRemovalListener.NONE);
        store.add(message("000000000001-00000001", "Team@Example.com"));
        assertThat(store.list(MailQuery.inbox("team@example.com", 10))).hasSize(1);
        assertThat(store.list(MailQuery.inbox("other@example.com", 10))).isEmpty();
    }

    @Test
    void deleteMatchingClearsOnlyOneInbox() {
        final InMemoryMailStore store = this.store(10, MailRemovalListener.NONE);
        store.add(message("000000000001-00000001", "a@example.com"));
        store.add(message("000000000002-00000002", "b@example.com"));
        assertThat(store.deleteMatching(MailQuery.inbox("a@example.com", 0))).isEqualTo(1);
        assertThat(store.size()).isEqualTo(1);
    }

    @Test
    void limitAndOffsetPageThroughResults() {
        final InMemoryMailStore store = this.store(10, MailRemovalListener.NONE);
        for (int i = 1; i <= 5; i++) {
            store.add(message("00000000000%d-0000000%d".formatted(i, i), "a@example.com"));
        }
        final List<MailMessage> page =
                store.list(new MailQuery(null, null, null, null, null, false, 2, 1));
        assertThat(page).extracting(MailMessage::id)
                .containsExactly("000000000004-00000004", "000000000003-00000003");
        assertThat(store.count(MailQuery.recent(2))).isEqualTo(5);
    }

    @Test
    void textSearchMatchesSubjectBodyAndAddresses() {
        final InMemoryMailStore store = this.store(10, MailRemovalListener.NONE);
        store.add(message("000000000001-00000001", "a@example.com"));
        assertThat(store.list(new MailQuery(null, null, null, "4711", null, false, 10, 0))).hasSize(1);
        assertThat(store.list(new MailQuery(null, null, null, "ORDER", null, false, 10, 0))).hasSize(1);
        assertThat(store.list(new MailQuery(null, null, null, "nothing", null, false, 10, 0))).isEmpty();
    }

    @Test
    void fromAndSubjectFiltersCombine() {
        final InMemoryMailStore store = this.store(10, MailRemovalListener.NONE);
        store.add(message("000000000001-00000001", "a@example.com"));
        assertThat(store.list(new MailQuery(null, "app@", "Order", null, null, false, 10, 0))).hasSize(1);
        assertThat(store.list(new MailQuery(null, "other@", "Order", null, null, false, 10, 0))).isEmpty();
    }

    @Test
    void sinceFilterExcludesOlderMessages() {
        final InMemoryMailStore store = this.store(10, MailRemovalListener.NONE);
        store.add(message("000000000001-00000001", "a@example.com"));
        final Instant after = Instant.parse("2026-09-11T12:00:01Z");
        final Instant before = Instant.parse("2026-09-11T11:59:59Z");
        assertThat(store.list(new MailQuery(null, null, null, null, after, false, 10, 0))).isEmpty();
        assertThat(store.list(new MailQuery(null, null, null, null, before, false, 10, 0))).hasSize(1);
    }

    @Test
    void onlyFailedFilterSelectsMessagesWithErrors() {
        final InMemoryMailStore store = this.store(10, MailRemovalListener.NONE);
        store.add(MailFixtures.message("000000000001-00000001", MailFixtures.WELL_FORMED));
        store.add(MailFixtures.message("000000000002-00000002", MailFixtures.NO_DATE));
        final List<MailMessage> failing =
                store.list(new MailQuery(null, null, null, null, null, true, 10, 0));
        assertThat(failing).extracting(MailMessage::id).containsExactly("000000000002-00000002");
    }

    @Test
    void findReturnsEmptyForAnUnknownId() {
        assertThat(this.store(10, MailRemovalListener.NONE).find("missing")).isEmpty();
    }

    @Test
    void generatedIdsSortByArrivalTime() {
        final String first = MessageIds.next(Instant.parse("2026-09-11T12:00:00Z"));
        final String second = MessageIds.next(Instant.parse("2026-09-11T12:00:01Z"));
        assertThat(first).isLessThan(second);
    }

    @Test
    void messageExposesDisplayHelpers() {
        final MailMessage message = MailFixtures.message("000000000001-00000001", MailFixtures.WELL_FORMED);
        assertThat(message.displaySubject()).isEqualTo("Order 4711 shipped");
        assertThat(message.displayFrom()).isEqualTo("app@example.com");
        assertThat(message.sha512()).hasSize(128);
        assertThat(message.sizeBytes()).isPositive();
    }

    @Test
    void nullSenderEnvelopeIsRecognised() {
        assertThat(MailFixtures.envelope("", "a@b.c").nullSender()).isTrue();
        assertThat(MailFixtures.envelope("x@y.z", "a@b.c").nullSender()).isFalse();
    }
}
