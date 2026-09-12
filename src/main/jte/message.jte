@import com.sparrowlogic.smtptester.core.AttachmentPart
@import com.sparrowlogic.smtptester.core.ParsedMessage
@import com.sparrowlogic.smtptester.validation.Finding
@import com.sparrowlogic.smtptester.web.MessagePageModel
@param MessagePageModel model

!{var message = model.message();}
!{var parsed = message.parsed();}

@template.layout(basePath = model.basePath(), title = message.displaySubject(), content = @`
    <nav class="crumbs">
        <a href="${model.basePath()}/">&larr; Inbox</a>
        <form method="post" action="${model.basePath()}/messages/${message.id()}/delete"
              data-confirm="Delete this message?">
            <button type="submit" class="danger">Delete</button>
        </form>
    </nav>

    <article class="detail">
        <h1>${message.displaySubject()}</h1>
        <dl class="envelope">
            <dt>Received</dt><dd class="mono">${message.receivedAt().toString()}</dd>
            <dt>MAIL FROM</dt><dd class="mono">${message.envelope().nullSender() ? "<> (null sender)" : message.envelope().from()}</dd>
            <dt>RCPT TO</dt><dd class="mono">${String.join(", ", message.envelope().recipients())}</dd>
            <dt>From header</dt><dd class="mono">${String.join(", ", parsed.from())}</dd>
            <dt>To header</dt><dd class="mono">${String.join(", ", parsed.to())}</dd>
            @if(!parsed.cc().isEmpty())
                <dt>Cc header</dt><dd class="mono">${String.join(", ", parsed.cc())}</dd>
            @endif
            <dt>Peer</dt><dd class="mono">${message.envelope().remoteAddress()} @if(message.envelope().helo() != null)(HELO ${message.envelope().helo()})@endif</dd>
            <dt>Transport</dt>
            <dd class="mono">
                @if(message.session() != null && message.session().tls())
                    TLS ${message.session().protocol()} ${message.session().cipherSuite()}
                    @if(!message.session().sniServerNames().isEmpty())
                        &middot; SNI ${String.join(", ", message.session().sniServerNames())}
                    @endif
                @else
                    plaintext
                @endif
            </dd>
            <dt>Size / SHA-512</dt><dd class="mono break">${message.sizeBytes()} B &middot; ${message.sha512()}</dd>
            @if(model.spoolDirectory() != null)
                <dt>Spooled to</dt><dd class="mono break">${model.spoolDirectory()}</dd>
            @endif
        </dl>

        <section class="validation">
            <h2>
                RFC compliance
                <span class="badge badge-${message.validation().status().name().toLowerCase()}">${message.validation().status().name()}</span>
                @if(message.rejected())
                    <span class="badge badge-rejected">REJECTED AT SMTP</span>
                @endif
            </h2>
            @if(message.validation().findings().isEmpty())
                <p class="ok">No findings. This message is well-formed.</p>
            @else
                <ul class="findings">
                    @for(Finding finding : message.validation().findings())
                        <li class="finding finding-${finding.severity().name().toLowerCase()}">
                            <span class="badge badge-${finding.severity().name().toLowerCase()}">${finding.severity().name()}</span>
                            <strong>${finding.summary()}</strong>
                            <code class="rule">${finding.rule()}</code>
                            @if(finding.detail() != null)
                                <div class="detail">${finding.detail()}</div>
                            @endif
                            <div class="reference">${finding.reference()} &middot; would reject with ${finding.statusCode().replyCode()} ${finding.statusCode().code()}</div>
                        </li>
                    @endfor
                </ul>
            @endif
        </section>

        <section class="tabs" data-tabs>
            <nav>
                <button class="tab active" data-tab="text">Text</button>
                <button class="tab" data-tab="html">HTML</button>
                <button class="tab" data-tab="headers">Headers</button>
                <button class="tab" data-tab="attachments">Attachments (${parsed.attachments().size()})</button>
                <button class="tab" data-tab="source">Source</button>
            </nav>

            <div class="panel active" data-panel="text">
                @if(parsed.text() != null)
                    <pre>${parsed.text()}</pre>
                @else
                    <p class="muted">No text/plain part.</p>
                @endif
            </div>

            <div class="panel" data-panel="html">
                @if(parsed.html() != null)
                    <iframe class="html-preview" sandbox="" referrerpolicy="no-referrer"
                            src="${model.basePath()}/api/v1/messages/${message.id()}/html"
                            title="HTML body preview"></iframe>
                @else
                    <p class="muted">No text/html part.</p>
                @endif
            </div>

            <div class="panel" data-panel="headers">
                <table class="headers">
                    @for(ParsedMessage.Header header : parsed.headers())
                        <tr><th class="mono">${header.name()}</th><td class="mono break">${header.value()}</td></tr>
                    @endfor
                </table>
            </div>

            <div class="panel" data-panel="attachments">
                @if(parsed.attachments().isEmpty())
                    <p class="muted">No attachments.</p>
                @else
                    <table class="attachments">
                        <thead><tr><th>#</th><th>Name</th><th>Type</th><th>Size</th><th>SHA-512</th><th></th></tr></thead>
                        <tbody>
                        @for(AttachmentPart part : parsed.attachments())
                            <tr>
                                <td class="mono">${part.index()}</td>
                                <td>${part.downloadName()}</td>
                                <td class="mono">${part.mimeType()}</td>
                                <td class="mono nowrap">${part.sizeBytes()} B</td>
                                <td class="mono break digest">${part.sha512()}</td>
                                <td><a href="${model.basePath()}/api/v1/messages/${message.id()}/attachments/${part.index()}">Download</a></td>
                            </tr>
                        @endfor
                        </tbody>
                    </table>
                @endif
            </div>

            <div class="panel" data-panel="source">
                <p class="muted">
                    Verbatim, with no <code>Received:</code> header added &mdash; byte-for-byte what was
                    transmitted.
                    <a href="${model.basePath()}/api/v1/messages/${message.id()}/raw">Download .eml</a>
                </p>
                <pre class="source">${model.rawSource()}</pre>
            </div>
        </section>
    </article>
`)
