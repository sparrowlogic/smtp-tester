@import com.sparrowlogic.smtptester.core.InboxSummary
@import com.sparrowlogic.smtptester.core.MailMessage
@import com.sparrowlogic.smtptester.web.InboxPageModel
@param InboxPageModel model

@template.layout(basePath = model.basePath(), title = "Inbox", content = @`
    <section class="toolbar">
        <form class="filters" method="get" action="${model.basePath()}/">
            <input type="search" name="search" placeholder="Search subject, body, addresses…"
                   value="${model.search() == null ? "" : model.search()}">
            <select name="inbox">
                <option value="">All inboxes (${model.totalMessages()})</option>
                @for(InboxSummary inbox : model.inboxes())
                    <option value="${inbox.address()}" selected="${inbox.address().equals(model.selectedInbox())}">
                        ${inbox.address()} (${inbox.messageCount()})
                    </option>
                @endfor
            </select>
            <label class="check">
                <input type="checkbox" name="onlyFailed" value="true" checked="${model.onlyFailed()}">
                Failing only
            </label>
            <button type="submit">Filter</button>
        </form>
        <form method="post" action="${model.basePath()}/messages/delete-all"
              data-confirm="Delete every message in this view?">
            @if(model.selectedInbox() != null)
                <input type="hidden" name="inbox" value="${model.selectedInbox()}">
            @endif
            <button type="submit" class="danger">Delete all</button>
        </form>
    </section>

    @if(model.messages().isEmpty())
        <section class="empty">
            <h2>No messages yet</h2>
            <p>Point your application at <code>localhost:1025</code> and send something.</p>
            <pre>python3 -c "import smtplib; s=smtplib.SMTP('localhost',1025); s.sendmail('app@example.com',['you@example.com'],'From: app@example.com\nTo: you@example.com\nSubject: hello\nDate: Thu, 11 Sep 2026 12:00:00 +0000\nMessage-ID: &lt;1@example.com&gt;\n\nhi'); s.quit()"</pre>
        </section>
    @else
        <table class="messages">
            <thead>
            <tr>
                <th>Received</th>
                <th>From</th>
                <th>To</th>
                <th>Subject</th>
                <th>RFC</th>
                <th>Size</th>
                <th></th>
            </tr>
            </thead>
            <tbody>
            @for(MailMessage message : model.messages())
                <tr class="message-row" data-href="${model.basePath()}/messages/${message.id()}">
                    <td class="mono nowrap">${message.receivedAt().toString()}</td>
                    <td class="nowrap">${message.displayFrom()}</td>
                    <td class="nowrap">${String.join(", ", message.envelope().recipients())}</td>
                    <td class="subject">
                        <a href="${model.basePath()}/messages/${message.id()}">${message.displaySubject()}</a>
                        @if(!message.parsed().attachments().isEmpty())
                            <span class="clip" title="${message.parsed().attachments().size()} attachment(s)">&#128206;</span>
                        @endif
                    </td>
                    <td>
                        <span class="badge badge-${message.validation().status().name().toLowerCase()}">
                            ${message.validation().status().name()}
                        </span>
                        @if(message.rejected())
                            <span class="badge badge-rejected">REJECTED</span>
                        @endif
                    </td>
                    <td class="mono nowrap">${message.sizeBytes()} B</td>
                    <td>
                        <button class="link danger" data-delete="${model.basePath()}/messages/${message.id()}">Delete</button>
                    </td>
                </tr>
            @endfor
            </tbody>
        </table>
    @endif
`)
