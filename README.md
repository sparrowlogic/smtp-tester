# smtp-tester

A local SMTP server that captures email instead of delivering it — the MailHog / Mailpit pattern,
on the same ports (`1025` SMTP, `8025` HTTP), with a web inbox and a detailed message view.

Two things it does that those don't:

1. **It exposes an MCP server**, so an LLM agent can list inboxes, search messages, read one, and
   clear them — over streamable HTTP at `/mcp`, no adapter process in between.
2. **It validates what you sent against the RFCs** and can refuse non-compliant mail with a
   registered enhanced status code. It is a correctness check on your sending code, not just a
   mailbox. Rejected mail is still stored, so you can see exactly what was refused and why.

MIT licensed.

---

## Quick start

```bash
docker run -p 127.0.0.1:1025:1025 -p 127.0.0.1:8025:8025 ghcr.io/jackdpeterson/smtp-tester:latest
```

Point your application at `localhost:1025`, then open <http://localhost:8025>.

The `127.0.0.1:` prefixes are not decoration. This server takes mail from anyone who can reach it
and serves an unauthenticated inbox and API over the rest, so bind it to loopback unless you are
on a private network you control — see [Security](#security).

Keeping every message on disk so it can be diffed between runs:

```bash
docker run -p 127.0.0.1:1025:1025 -p 127.0.0.1:8025:8025 -v "$PWD/mail:/data/mail" \
  ghcr.io/jackdpeterson/smtp-tester:latest
```

Or use the bundled [`compose.yml`](compose.yml) as a drop-in replacement for an existing
MailHog/Mailpit service.

---

## Connecting an agent

The MCP endpoint is streamable HTTP on the same port as the inbox.

```bash
claude mcp add --transport http smtp-tester http://localhost:8025/mcp
```

<details>
<summary>Claude Desktop / any MCP client config</summary>

```json
{
  "mcpServers": {
    "smtp-tester": {
      "type": "http",
      "url": "http://localhost:8025/mcp"
    }
  }
}
```
</details>

### Tools

| Tool | What it does |
| --- | --- |
| `list_inboxes` | Every recipient address that has received mail, with counts and how many failed validation |
| `list_recent_emails` | Newest-first summaries, optionally scoped to one inbox, with paging |
| `get_chaos_status` | Fault-injection settings and how many faults have actually been injected |
| `configure_chaos` | Turn fault injection on/off and set each probability |
| `reset_chaos_faults` | Zero the fault counters before a measured run |
| `search_emails` | Free text across subject, bodies and addresses, plus sender/subject/since/failing-only filters |
| `get_email` | One message in full: headers, bodies, attachment metadata with a SHA-512 per part |
| `get_email_validation` | Just the RFC findings, each with a rule id, spec section and enhanced status code |
| `get_email_attachment` | Decoded content of one part — text inline, binary base64, digest always |
| `delete_email` | Deletes one message and its spooled files |
| `clear_inbox` | Empties one recipient's inbox, or all of them |
| `clear_all_inboxes` | Resets the server to empty |

The read tools are annotated `readOnlyHint`, the three destructive ones `destructiveHint`, so a
host that asks for confirmation only asks when it matters. Set `smtptester.mcp.allow-delete=false`
and the destructive tools disappear from `tools/list` entirely rather than failing at call time.

A typical agent loop: `clear_all_inboxes` → trigger the flow under test → `search_emails` for the
token you expect → `get_email` to pull the confirmation link out of the body.

---

## RFC validation

Every received message is checked at three layers, and the findings appear in the UI, in the REST
API, in the MCP tools and in the structured log.

| Layer | Examples |
| --- | --- |
| **RFC 5321** transport | bare LF instead of CRLF, lines over 998 octets, 8-bit octets in headers, malformed envelope paths |
| **RFC 5322** message format | missing `Date:`/`From:`, a header that must appear at most once appearing twice, unparseable dates, several `From:` mailboxes with no `Sender:`, a transmitted `Bcc:` |
| **RFC 2045/2046** MIME | `Content-Transfer-Encoding: 7bit` over an 8-bit body, missing or unterminated multipart boundaries, unknown encodings, missing charset |

Plus deliverability observations that break no rule but cost you inbox placement: HTML with no
text/plain alternative, and an envelope sender whose domain won't align with `From:` under DMARC.

The transport rules read the **raw octets**, not the parsed message. That is the point: a MIME
parser normalises bare line feeds away, so by the time a message is parsed the most common
real-world defect has already been erased. Bare LF survives a loopback test against a lenient
server and then gets mangled by the first conforming relay in the path.

### Rejection

By default a MUST-level violation is refused at `DATA` with a registered
[RFC 5248](https://www.iana.org/assignments/smtp-enhanced-status-codes/) enhanced status code:

```
250-ENHANCEDSTATUSCODES
...
550 5.6.0 Missing the required Date header (RFC5322_MISSING_DATE)
```

A compliant message is acknowledged with its id, so you can fetch it straight back:

```
250 2.0.0 Ok: queued as 01a09244836e-00000003
```

The server advertises `ENHANCEDSTATUSCODES` in its `EHLO` greeting, which
[RFC 2034](https://www.rfc-editor.org/rfc/rfc2034) requires before a server may use those codes.

Set `smtptester.validation.reject-on=NONE` for lenient, MailHog-compatible behaviour, or `WARNING`
to be stricter still. **Rejected messages are stored and flagged either way** — a message you
can't look at is a message you can't debug.

---

## Spooling to disk

Point `smtptester.spool.directory` at a directory and every message is written there:

```
<root>/20260911T205633206Z_01a09241fcb6-00000001/
    message.eml                 raw bytes, exactly as transferred
    metadata.json               envelope, digests, compliance verdict
    parts/0002-invoice-4711.pdf each decoded MIME part
```

`message.eml` is **byte-exact**: this server never inserts a `Received:` header, so the file
equals what your client put on the wire and `diff` against a known-good capture is meaningful.

The decoded parts under `parts/` are usually what you actually want to compare. A raw message
differs on every send because `Date:` and `Message-ID:` change; the PDF inside it should not.

Deleting a message — from the UI, the REST API or MCP — deletes its directory too, as does ageing
one out past `smtptester.store.max-messages`. On restart the directory is read back into the inbox.

---

## Structured logging

One JSON document per received message on stdout, through a dedicated logger with a bare `%msg%n`
appender, so `docker logs smtp-tester | jq` works with no filtering:

```json
{
  "event": "mail.received",
  "id": "01a09241fcb6-00000001",
  "receivedAt": "2026-09-11T20:56:33.206976Z",
  "envelope": { "from": "app@example.com", "recipients": ["alice@example.com"] },
  "headers": { "from": ["app@example.com"], "to": ["alice@example.com"], "subject": "Order 4711 shipped" },
  "body": { "text": "Your order shipped.", "textSha512": "a9a985..." },
  "sizeBytes": 1261,
  "sha512": "3ef14b...",
  "attachments": {
    "count": 1,
    "countByMimeType": { "application/pdf": 1 },
    "parts": [{ "index": 2, "mimeType": "application/pdf", "filename": "invoice-4711.pdf", "sha512": "7939fb..." }]
  },
  "validation": { "status": "PASS", "errors": 0, "warnings": 0, "findings": [] },
  "rejected": false
}
```

Attachment payloads are never logged — only a count per media type and a SHA-512 per part, which
is what makes "is this the same attachment as yesterday?" answerable from a log aggregator.

---

## Chaos monkey

Off by default. Turn it on to make the server misbehave on purpose, so an application's retry,
backoff and timeout paths get exercised — the code that is otherwise almost unreachable against a
well-behaved localhost.

The knobs and their defaults mirror MailHog's [Jim](https://github.com/mailhog/MailHog/blob/master/docs/JIM.md),
so an existing Jim configuration translates directly:

| Key | Default | |
| --- | --- | --- |
| `smtptester.chaos.enabled` | `false` | master switch |
| `smtptester.chaos.accept-connection` | `0.99` | chance of **accepting** a connection — note the inversion |
| `smtptester.chaos.disconnect` | `0.005` | chance of dropping a session with no reply |
| `smtptester.chaos.reject-sender` | `0.05` | chance of refusing `MAIL FROM` with `451 4.3.0` |
| `smtptester.chaos.reject-recipient` | `0.05` | chance of refusing `RCPT TO` with `451 4.3.0` |
| `smtptester.chaos.reject-auth` | `0.05` | chance of failing `AUTH` |
| `smtptester.chaos.throttle` | `0.1` | chance of rate-limiting a transfer |
| `smtptester.chaos.min-bytes-per-second` / `max-bytes-per-second` | `1024` / `10240` | speed range when throttling |
| `smtptester.chaos.seed` | *(none)* | fixes the random sequence |

Two deliberate differences from Jim:

**Faults are transient (4xx), not permanent.** A `550` just makes a send fail; a `451` exercises
the retry and backoff logic, which is the thing worth testing.

**It is seedable and counted.** `smtptester.chaos.seed` makes a run reproducible, and every
injected fault is counted by kind. A chaos monkey you cannot replay turns a failing test into a
mystery, and without counters you cannot tell "my retry logic is broken" from "nothing was
actually injected this run".

### Runtime control

Chaos is toggled while the server runs, so a suite can keep its happy-path tests on a quiet server
and switch faults on only for the ones that assert retry behaviour:

```bash
curl -X POST   localhost:8025/api/v1/chaos/enable
curl -X DELETE localhost:8025/api/v1/chaos/faults    # zero the counters before the run
curl           localhost:8025/api/v1/chaos           # settings + faults actually injected
curl -X PUT    localhost:8025/api/v1/chaos -H 'Content-Type: application/json' \
  -d '{"enabled":true,"acceptConnection":1,"disconnect":0,"rejectSender":1,"rejectRecipient":0,
       "rejectAuth":0,"throttle":0,"minBytesPerSecond":1024,"maxBytesPerSecond":10240}'
curl -X POST   localhost:8025/api/v1/chaos/disable
```

Setting one probability to `1` and the rest to `0` reproduces exactly one failure every time,
which is how you debug a specific path rather than waiting for chance.

The same controls are MCP tools — `get_chaos_status`, `configure_chaos`, `reset_chaos_faults` —
so an agent can run the whole loop: reset the counters, enable one fault, trigger the send, then
read the counters back before drawing any conclusion.

---

## REST API

Everything the UI and the MCP tools do, over plain HTTP.

| Method | Path | |
| --- | --- | --- |
| `GET` | `/api/v1/inboxes` | recipients with counts |
| `GET` | `/api/v1/messages` | `?inbox=&from=&subject=&text=&since=&onlyFailed=&limit=&offset=` |
| `GET` | `/api/v1/messages/{id}` | full message |
| `GET` | `/api/v1/messages/{id}/validation` | compliance report |
| `GET` | `/api/v1/messages/{id}/raw` | verbatim `.eml` |
| `GET` | `/api/v1/messages/{id}/html` | HTML body (served into a sandboxed iframe) |
| `GET` | `/api/v1/messages/{id}/attachments/{index}` | decoded part |
| `DELETE` | `/api/v1/messages/{id}` | delete one, files included |
| `DELETE` | `/api/v1/messages?inbox=` | clear one inbox, or all |

---

## Configuration

Every key is settable as an environment variable in the usual Spring Boot way
(`smtptester.smtp.port` → `SMTPTESTER_SMTP_PORT`).

| Key | Default | |
| --- | --- | --- |
| `smtptester.smtp.port` | `1025` | `0` binds an ephemeral port |
| `smtptester.smtp.bind-address` | all interfaces | |
| `smtptester.smtp.max-message-size-bytes` | `26214400` | rejected with `552 5.3.4` |
| `smtptester.smtp.accept-any-credentials` | `true` | any `AUTH` succeeds, so app configs need no edits |
| `smtptester.smtp.tls.mode` | `OFF` | `OFF`, `OPTIONAL` or `REQUIRED` (STARTTLS) |
| `smtptester.smtp.tls.hostname` | `localhost` | the name the generated certificate is issued for |
| `smtptester.smtp.tls.keystore` | *(generated)* | PKCS#12 or JKS; omit to mint a self-signed cert at startup |
| `smtptester.store.max-messages` | `500` | oldest evicted beyond this |
| `smtptester.spool.directory` | *(off)* | enables write-through to disk |
| `smtptester.spool.write-parts` | `true` | decoded MIME parts under `parts/` |
| `smtptester.spool.load-on-startup` | `true` | re-read the directory at boot |
| `smtptester.validation.reject-on` | `ERROR` | `NONE`, `ERROR` or `WARNING` |
| `smtptester.mcp.allow-delete` | `true` | `false` hides the destructive tools |
| `smtptester.logging.include-body` | `true` | turn off if bodies carry data you'd rather not log |
| `smtptester.web.base-path` | `""` | mount the UI under a prefix |
| `server.port` | `8025` | UI, REST API and MCP |

---

## Using it as a library

The published jar is a normal library artifact, not a fat jar, and carries Spring Boot
auto-configuration. Adding it to a Spring Boot application starts the SMTP server inside that
application's own context:

```xml
<dependency>
    <groupId>com.sparrowlogic</groupId>
    <artifactId>smtp-tester</artifactId>
    <version>0.1.0</version>
    <scope>test</scope>
</dependency>
```

```yaml
smtptester:
  smtp:
    port: 0          # ephemeral, so concurrent test runs never collide
spring:
  ai:
    mcp:
      server:
        protocol: streamable   # required for /mcp to be mapped; the standalone jar sets this itself
```

Inject `SmtpReceiver` to learn the bound port and `MailboxService` to assert on what arrived.

The jar deliberately ships **no `application.yaml`** and no `logback-spring.xml`, so it cannot move
your application's HTTP port or hijack its logging — the standalone defaults are applied by
`SmtpTesterApplication` and only when that class is the one booting. Use
`smtptester.web.base-path` if the inbox routes would collide with yours.

The executable jar is published alongside it under the `exec` classifier:

```bash
java -jar smtp-tester-0.1.0-exec.jar
```

> Library mode is exercised by `LibraryModeTest`, which boots this jar under a separate
> `@SpringBootConfiguration` with no component scan and asserts the listener, the API and the MCP
> tools all come up. The Docker image is still the supported install path.

---

## TLS and RFC 7817

Off by default — the common case is an application talking to localhost, where TLS only gets in
the way. Turn it on to exercise your client's certificate handling:

```bash
docker run -p 1025:1025 -p 8025:8025 \
  -e SMTPTESTER_SMTP_TLS_MODE=OPTIONAL \
  -e SMTPTESTER_SMTP_TLS_HOSTNAME=mail.test.local \
  ghcr.io/jackdpeterson/smtp-tester:latest
```

With no keystore configured the server **mints a self-signed certificate at startup** rather than
shipping one — a private key committed to a public repository is a private key everybody has. It
carries `subjectAltName` dNSName entries for the configured hostname, `localhost` and
`smtp-tester`, plus the loopback IPs. Fetch it and add it to your client's trust store:

```bash
curl http://localhost:8025/api/v1/tls/certificate > smtp-tester.pem
curl http://localhost:8025/api/v1/tls/identity      # what an RFC 7817 client matches on
```

Supply your own with `smtptester.smtp.tls.keystore` (PKCS#12 or JKS) when you need a specific
chain.

### What the server can honestly tell you

[RFC 7817](https://www.rfc-editor.org/rfc/rfc7817) defines how an email *client* verifies a
*server's* identity: the reference identifier is the DNS name the client set out to reach, matched
against `subjectAltName` dNSName entries, with the CN-ID fallback deprecated. A server sits on the
far side of that check and **cannot observe whether it happened or passed** — only that the
handshake completed. Any tool claiming otherwise is guessing.

What it *can* record, and does, per message:

| Finding | Meaning |
| --- | --- |
| `RFC7817_REFERENCE_IDENTIFIER` | the SNI name the client asked for — its reference identifier |
| `RFC7817_IDENTITY_MISMATCH` | the client asked for a name the presented certificate does not cover, so a conforming client would have aborted; **this one means your client is not verifying certificates** |
| `RFC7817_NO_SUBJECT_ALT_NAME` | the certificate has no dNSName, so RFC 7817 clients must reject it |
| `RFC7817_NO_SNI_REFERENCE_IDENTIFIER` | no SNI sent, so the reference identifier could not be observed |
| `RFC3207_STARTTLS_NOT_USED` | STARTTLS was advertised and the client sent in the clear anyway |
| `TLS_OBSOLETE_PROTOCOL` | negotiated SSLv3/TLS 1.0/1.1 |

The negotiated protocol, cipher suite, SNI names and any client certificate are shown on the
message page and returned by the API.

---

## Building

```bash
make agent-completion   # the gate: everything below, in one command
make help               # everything else
docker build -t smtp-tester .
```

`make agent-completion` runs `./mvnw verify`, which is gated on Checkstyle, Spotless, ErrorProne +
NullAway in JSpecify mode (every package is `@NullMarked`), and JaCoCo at **85% line / 75% branch**.
CI runs the same target, so a green local build and a green CI build mean the same thing. See
[CONTRIBUTING.md](CONTRIBUTING.md) and [AGENTS.md](AGENTS.md).

JTE templates are precompiled by `jte-maven-plugin` into the jar, so the packaged artifact renders
without `src/main/jte` on disk — the container has no source tree.

---

## Security

**This is a development tool. Run it on loopback or a private container network; never expose it to
the internet and never point production traffic at it.**

By design it has no authentication anywhere — the inbox, the REST API and `/mcp` will all read and
delete every captured message for anyone who can reach port 8025 — and SMTP `AUTH` accepts any
credentials. That is what makes it a drop-in for MailHog and Mailpit, and it is also why the
default bindings above are `127.0.0.1`.

Within that boundary, captured mail is treated as hostile, because it is:

- **HTML bodies are served under `Content-Security-Policy: sandbox`** into an opaque origin with no
  scripting, so a message cannot act against the inbox even if you open the preview URL directly.
- **HTML, SVG and JavaScript attachments are forced to download** rather than rendered, and
  everything is served `nosniff`.
- **Message ids are validated before they can name a filesystem path**, with a containment check on
  top, so nothing in a spool directory can cause a read or write outside it.
- **Size, connection, recipient and retention limits** are all configurable and capped by default.
- **The container runs unprivileged**, and no private key is committed — the STARTTLS certificate is
  generated at each start.

[SECURITY.md](SECURITY.md) has the full threat model and how to report a vulnerability.

---

## Not built (deliberately)

- **DKIM verification (RFC 6376).** Not implemented. The enhanced status codes for it
  (`5.7.20`/`5.7.21`/`5.7.22`) are already in the table, so it slots into the validation package as
  another rule set when it's wanted.
- **Message release / forwarding to a real SMTP server.** MailHog has it; not wanted here.
- **HTTP basic auth on the UI and API.** MailHog has it; unnecessary for a local testing tool.
- **A Maven plugin** to start and stop the server around `integration-test`. The Docker image and
  the library dependency cover the same ground today.
- **Implicit TLS on a second port.** STARTTLS only, which is what SMTP submission clients expect.

## Status

Version `0.1.0-SNAPSHOT`, not yet published. Until it is:

- `ghcr.io/jackdpeterson/smtp-tester` does not exist — build locally with `docker build -t smtp-tester .`
  and substitute that tag.
- The Maven coordinates are not on Central. `./mvnw -Prelease deploy` is wired up but has never been
  run, so the publishing path is unverified.

## Coming from MailHog

Ports and SMTP behaviour match, so swapping the container is usually enough. Two things are not
drop-in:

**The HTTP API is a different shape.** MailHog returns `{ID, From:{Mailbox,Domain}, To[], Content,
MIME, Raw}`; this returns the records documented above. Anything written against MailHog's API
needs porting. The SMTP side needs no changes at all.

**Validation rejects by default.** MailHog accepts everything; this refuses mail that breaks a
MUST-level rule, so a test fixture with no `Date:` header starts getting `550`s where it used to
get `250`s. That is the point of the tool, but it can turn a passing suite red on the day you
switch. To migrate without surprises, start lenient and tighten once you have looked at the
findings:

```bash
SMTPTESTER_VALIDATION_REJECTON=NONE   # accept everything, still report every finding
```

Feature differences: message release and HTTP basic auth are not implemented; the chaos monkey is
(with transient codes, a seed and fault counters); live updates poll every 5s rather than using
EventSource; storage is in-memory plus an optional spool directory rather than MongoDB or maildir.

## Prior art

[MailHog](https://github.com/mailhog/MailHog) and [Mailpit](https://github.com/axllent/mailpit)
established this pattern and the port numbers, which this project deliberately matches.
