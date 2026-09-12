# smtp-tester

A local SMTP server that captures email instead of delivering it — the MailHog / Mailpit pattern,
on the same ports (`1025` SMTP, `8025` HTTP), with a web inbox and a detailed message view.

Two things it does that those don't:

1. **It exposes an MCP server**, so an LLM agent can list inboxes, search messages, read one, and
   clear them — over streamable HTTP at `/mcp`, no adapter process in between.
2. **It validates what you sent against the RFCs** and can refuse non-compliant mail with a
   registered enhanced status code. It is a correctness check on your sending code, not just a
   mailbox. Rejected mail is still stored, so you can see exactly what was refused and why.

MIT licensed. Copyright (c) 2026 Sparrow Logic, Inc.

---

## Quick start

```bash
docker run -p 127.0.0.1:1025:1025 -p 127.0.0.1:8025:8025 ghcr.io/sparrowlogic/smtp-tester:latest
```

Point your application at `localhost:1025`, then open <http://localhost:8025>.

The `127.0.0.1:` prefixes are not decoration. This server takes mail from anyone who can reach it
and serves an unauthenticated inbox and API over the rest, so bind it to loopback unless you are
on a private network you control — see [Security](#security).

### With Docker Compose

Drop this beside your application's service. The ports and SMTP behaviour match MailHog and
Mailpit, so replacing an existing `mailhog:` or `mailpit:` service is usually a one-line change:

```yaml
services:
  smtp-tester:
    image: ghcr.io/sparrowlogic/smtp-tester:latest
    ports:
      - "127.0.0.1:1025:1025"   # SMTP
      - "127.0.0.1:8025:8025"   # inbox UI, REST API and MCP endpoint (/mcp)
    environment:
      # Keep every received message on disk so it can be diffed byte-for-byte between runs.
      SMTP_TESTER_SPOOL_DIRECTORY: /data/mail
      # ERROR refuses mail that breaks a MUST-level rule, which is the point of this server.
      # Set NONE for lenient, MailHog-compatible behaviour while you migrate.
      SMTP_TESTER_VALIDATION_REJECT_ON: ERROR
    volumes:
      - mail:/data/mail

volumes:
  mail:
```

Your application then sends to `smtp-tester:1025` on the compose network, and you read the inbox
at <http://localhost:8025>. The same file ships as [`compose.yml`](compose.yml), annotated —
`docker compose up` in a clone of this repository runs it as-is.

Without compose, keeping messages on disk is a volume mount:

```bash
docker run -p 127.0.0.1:1025:1025 -p 127.0.0.1:8025:8025 -v "$PWD/mail:/data/mail" \
  ghcr.io/sparrowlogic/smtp-tester:latest
```

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
host that asks for confirmation only asks when it matters. Set `smtp-tester.mcp.allow-delete=false`
and the destructive tools disappear from `tools/list` entirely rather than failing at call time.

A typical agent loop: `clear_all_inboxes` → trigger the flow under test → `search_emails` for the
token you expect → `get_email` to pull the confirmation link out of the body.

---

### Documentation the agent can read itself

A model that can list your inboxes still has to guess at everything that is not a tool — which
environment variable changes a port, why a message came back `550`, what the SSE stream emits.
Two routes and one tool remove the guessing:

```bash
curl http://localhost:8025/llms.txt        # the short index
curl http://localhost:8025/llms-full.txt   # every endpoint, key and rule
```

The `get_documentation` MCP tool returns the same text over the same connection the agent is
already using. Both surfaces read one packaged source, and a test walks `SmtpTesterProperties` and
fails the build if a configuration key exists that the reference does not document — so the
documentation an agent is told to trust cannot quietly fall behind the server.

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

Set `smtp-tester.validation.reject-on=NONE` for lenient, MailHog-compatible behaviour, or `WARNING`
to be stricter still. **Rejected messages are stored and flagged either way** — a message you
can't look at is a message you can't debug.

---

## Spooling to disk

**On by default when running standalone**, to `./mail` beside wherever you launched the server, so
captured mail survives a restart without configuring anything. The Docker image overrides it to the
`/data/mail` volume. Set the property to an empty value to turn it off:

```bash
SMTP_TESTER_SPOOL_DIRECTORY= java -jar smtp-tester-exec.jar    # in-memory only
```

Point `smtp-tester.spool.directory` at a directory and every message is written there:

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
one out past `smtp-tester.store.max-messages`. On restart the directory is read back into the inbox.

That last part is what keeps the directory bounded, so it is worth knowing what happens without it.
Retention is enforced through the in-memory store, and the store can only age out what it holds —
so with `smtp-tester.spool.load-on-startup=false`, files from previous runs are never loaded and
would otherwise accumulate forever while the inbox stayed capped. The spool is therefore also swept
back to `max-messages` at every startup, independently of what was restored. With the default
restoring behaviour the directory holds exactly `max-messages`; with restoring off it is swept to
that figure at each start and can reach roughly twice it in between.

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
| `smtp-tester.chaos.enabled` | `false` | master switch |
| `smtp-tester.chaos.accept-connection` | `0.99` | chance of **accepting** a connection — note the inversion |
| `smtp-tester.chaos.disconnect` | `0.005` | chance of dropping a session with no reply |
| `smtp-tester.chaos.reject-sender` | `0.05` | chance of refusing `MAIL FROM` with `451 4.3.0` |
| `smtp-tester.chaos.reject-recipient` | `0.05` | chance of refusing `RCPT TO` with `451 4.3.0` |
| `smtp-tester.chaos.reject-auth` | `0.05` | chance of failing `AUTH` |
| `smtp-tester.chaos.throttle` | `0.1` | chance of rate-limiting a transfer |
| `smtp-tester.chaos.min-bytes-per-second` | `1024` | slowest rate applied when throttling |
| `smtp-tester.chaos.max-bytes-per-second` | `10240` | fastest rate applied when throttling |
| `smtp-tester.chaos.seed` | *(none)* | fixes the random sequence |

Two deliberate differences from Jim:

**Faults are transient (4xx), not permanent.** A `550` just makes a send fail; a `451` exercises
the retry and backoff logic, which is the thing worth testing.

**It is seedable and counted.** `smtp-tester.chaos.seed` makes a run reproducible, and every
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
| `GET` | `/api/v1/messages/stream` | server-sent events, same filters as `/messages` |
| `DELETE` | `/api/v1/messages/{id}` | delete one, files included |
| `DELETE` | `/api/v1/messages?inbox=` | clear one inbox, or all |

### Waiting for a message

`GET /api/v1/messages/stream` holds the connection open and writes one event per arriving message,
so an integration test can stop sleeping and polling. It is the same subscription an `EventSource`
speaks:

```bash
curl -N 'http://localhost:8025/api/v1/messages/stream?inbox=alice@example.com'

event:ready
data:{"replayed":0,"lastEventId":null}

id:01a09948a406-00000002
event:mail.received
data:{"id":"01a09948a406-00000002","receivedAt":"2026-09-13T05:41:09.766322Z","from":"app@example.com","to":["alice@example.com"],"subject":"Confirm your address","sizeBytes":347,"attachmentCount":0,"validationStatus":"PASS","rejected":false}

:keep-alive
```

The stream opens with a **`ready`** event and then carries one **`mail.received`** event per
message, whose payload is the same summary `GET /messages` returns — `id` is the handle for
fetching the full message, the raw `.eml` or the validation report.

Two details make it reliable rather than merely convenient:

- **Wait for `ready`, then send.** The subscription is live before that event is written, so a
  message sent after it cannot be missed. This is what removes the sleep from a test.
- **Ask for history when you have already sent.** `?since=<ISO-8601 instant>` replays matching
  messages received at or after that instant before going live, which covers mail that arrived
  while you were connecting. A browser's `EventSource` sends `Last-Event-ID` automatically on
  reconnect and gets exactly what it missed.

Every filter `GET /messages` takes works here too, so a suite running tests in parallel subscribes
with `?inbox=` and sees only its own mail. In a browser that is the standard three lines:

```js
const events = new EventSource('/api/v1/messages/stream?inbox=alice@example.com');
events.addEventListener('ready', () => sendTheThingUnderTest());
events.addEventListener('mail.received', e => check(JSON.parse(e.data)));
```

From a JUnit test there is no client library to add: `HttpClient.send(request,
BodyHandlers.ofLines())` returns the events as a `Stream<String>` you can read on a background
thread and hand to a `BlockingQueue`, which turns "did the mail arrive" into a `poll` with a
timeout that fails loudly instead of a `Thread.sleep` that hopes. `MailEventStreamTest` in this
repository is a working example of exactly that.

An idle stream gets the `:keep-alive` comment every 20 seconds and is never timed out by the
server, so a subscriber waiting minutes for a nightly job's mail is as safe as one waiting
milliseconds in a unit test.

---

## Configuration

Every key is settable as an environment variable in the usual Spring Boot way
(`smtp-tester.smtp.port` → `SMTP_TESTER_SMTP_PORT`).

| Key | Default | |
| --- | --- | --- |
| `smtp-tester.smtp.port` | `1025` | `0` binds an ephemeral port |
| `smtp-tester.smtp.bind-address` | all interfaces | |
| `smtp-tester.smtp.max-message-size-bytes` | `26214400` | rejected with `552 5.3.4` |
| `smtp-tester.smtp.accept-any-credentials` | `true` | any `AUTH` succeeds, so app configs need no edits |
| `smtp-tester.smtp.tls.mode` | `OFF` | `OFF`, `OPTIONAL` or `REQUIRED` (STARTTLS) |
| `smtp-tester.smtp.tls.hostname` | `localhost` | the name the generated certificate is issued for |
| `smtp-tester.smtp.tls.keystore` | *(generated)* | PKCS#12 or JKS; omit to mint a self-signed cert at startup |
| `smtp-tester.smtp.tls.keystore-password` | *(none)* | password for that keystore |
| `smtp-tester.smtp.tls.key-alias` | *(none)* | which key in the keystore to present |
| `smtp-tester.store.max-messages` | `1000` | oldest evicted beyond this, in memory and on disk |
| `smtp-tester.spool.directory` | `/data/mail` in the container, `./mail` for the jar | write-through to disk; set empty to disable |
| `smtp-tester.spool.retention-interval` | `30s` | how often the background sweep trims the directory |
| `smtp-tester.spool.write-metadata` | `true` | also write `metadata.json` beside the raw message |
| `smtp-tester.spool.write-parts` | `true` | decoded MIME parts under `parts/` |
| `smtp-tester.spool.load-on-startup` | `true` | re-read the directory at boot |
| `smtp-tester.validation.reject-on` | `ERROR` | `NONE`, `ERROR` or `WARNING` |
| `smtp-tester.mcp.allow-delete` | `true` | `false` hides the destructive tools |
| `smtp-tester.mcp.max-body-characters` | `20000` | bodies truncated past this in tool output |
| `smtp-tester.mcp.default-limit` | `20` | page size for `list_recent_emails` when none is given |
| `smtp-tester.logging.enabled` | `true` | the structured per-message log line |
| `smtp-tester.logging.include-body` | `true` | turn off if bodies carry data you'd rather not log |
| `smtp-tester.logging.max-body-characters` | `2000` | truncation point for the logged body |
| `smtp-tester.web.base-path` | `""` | mount the UI under a prefix |
| `smtp-tester.web.stream.enabled` | `true` | the server-sent events feed of arriving mail |
| `smtp-tester.web.stream.heartbeat` | `20s` | keep-alive comment interval on an idle stream |
| `smtp-tester.web.stream.max-subscribers` | `100` | concurrent streams; beyond this, `503` |
| `smtp-tester.web.stream.replay-limit` | `100` | most messages one `since` replay may send |
| `server.port` | `8025` | UI, REST API and MCP |

---

## Running the jar directly

The container is the supported install path, but the executable jar runs anywhere a JDK does:

```bash
java -jar smtp-tester-0.1.0-exec.jar
```

It is self-contained — the JTE templates are precompiled into it, so `src/main/jte` is not needed
at runtime — and every knob in the table above works as a `--flag`, an environment variable or an
entry in an `application.yaml` you place beside it:

```bash
java -jar smtp-tester-0.1.0-exec.jar --smtp-tester.smtp.port=2525 --server.port=9025
```

## Not built (deliberately)

Everything here is a decision, not a gap. Each one has been considered and turned down for the
reason given, so if you are about to open an issue asking for it, this is the argument to engage
with.

- **Running as a library inside your Spring Boot application.** This used to work: the jar carried
  auto-configuration and starting it inside a host application's tests was a supported shape. It
  was removed on purpose. Two shapes meant two bean graphs, two sets of defaults and two ways for
  a feature to work in one and quietly not the other, and all of it existed to save a `docker run`.
  The container is the integration path now, for everyone.
- **A Maven plugin** to start and stop the server around `integration-test`. Same reasoning: it is
  a third shape with its own lifecycle bugs. Start the container in your CI job, or bind port 0
  and run the image from Testcontainers.
- **Publishing to Maven Central.** Follows from the two above — there is no artifact anyone is
  meant to depend on. The image is the deliverable.
- **DKIM verification (RFC 6376).** The enhanced status codes for it
  (`5.7.20`/`5.7.21`/`5.7.22`) are already in the table, so it slots into the validation package as
  another rule set when it's wanted. It has not been wanted yet: verifying a signature needs a live
  DNS lookup, and a validator that reaches the network mid-transaction — and fails differently on a
  laptop, in CI, and on a plane — is a worse default than not checking.
- **Message release / forwarding to a real SMTP server.** MailHog has it. It turns a mail sink into
  a relay, on an HTTP API that is deliberately unauthenticated, which means anyone who can reach
  port 8025 can send mail through whatever upstream you configured. The whole point of this tool is
  that mail *cannot* escape it.
- **HTTP basic auth on the UI and API.** Unnecessary for a local testing tool, and it would be
  security theatre: SMTP on 1025 accepts any credentials by design, so a password on the inbox
  protects nothing that port 1025 does not already give away. Do not expose either port to a
  network you do not trust — that is the actual control, and [SECURITY.md](SECURITY.md) says so.
- **Implicit TLS on a second port.** STARTTLS only, which is what SMTP submission clients expect.

## Status

Version `0.1.0` — the first published release.

```bash
docker pull ghcr.io/sparrowlogic/smtp-tester:0.1.0   # or :latest
```

Published for `linux/amd64` and `linux/arm64`, so it runs natively on an x86 CI box and on an
Apple Silicon laptop. Each architecture is built on its own runner and has to start and serve
`/api/v1/inboxes` before the release tag is attached to it.

There are no Maven coordinates and there will not be — see *Not built (deliberately)*. The image
is the artefact. `./mvnw verify` produces `target/smtp-tester-0.1.0-exec.jar` locally if you would
rather run it on a JDK directly.

Being a `0.x` release, the HTTP API and the configuration keys may still change; the SMTP side is
the stable part, and it is deliberately MailHog-compatible.

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
SMTP_TESTER_VALIDATION_REJECT_ON=NONE   # accept everything, still report every finding
```

Feature differences: message release and HTTP basic auth are not implemented and will not be (the
reasoning is under *Not built (deliberately)*); the chaos monkey is
(with transient codes, a seed and fault counters); the inbox page polls every 5s for live updates
rather than subscribing, though the API does expose an `EventSource` feed at
`/api/v1/messages/stream`; storage is in-memory plus an optional spool directory rather than MongoDB
or maildir.

## Prior art

[MailHog](https://github.com/mailhog/MailHog) and [Mailpit](https://github.com/axllent/mailpit)
established this pattern and the port numbers, which this project deliberately matches.

**smtp-tester is an independent project — not a fork, port or derivative of either.** It shares no
source with them, and the compatibility is deliberate interface compatibility: the same ports, and
an SMTP conversation their clients already speak, so swapping the container does not mean changing
your application. Where this README says "as Mailpit does" or "modelled on MailHog's Jim", it is
naming the behaviour being matched, not a lineage. The implementation here is original and is
copyright Sparrow Logic, Inc., MIT licensed.
