package com.sparrowlogic.smtptester.core;

import java.util.List;
import java.util.Optional;

/** Retention and retrieval of received messages. */
public interface MailStore {

    /** Stores a message, evicting the oldest if the retention limit is exceeded. */
    void add(MailMessage message);

    /** Looks a message up by id. */
    Optional<MailMessage> find(String id);

    /** Messages matching the query, newest first. */
    List<MailMessage> list(MailQuery query);

    /** How many messages match the query, ignoring its limit and offset. */
    long count(MailQuery query);

    /** Total retained messages. */
    long size();

    /** Every recipient that has received mail, with counts, ordered by most recent activity. */
    List<InboxSummary> inboxes();

    /** Deletes one message, returning it when it was present. */
    Optional<MailMessage> delete(String id);

    /** Deletes every message matching the query and returns how many went. */
    int deleteMatching(MailQuery query);

    /** Deletes everything. */
    int clear();
}
