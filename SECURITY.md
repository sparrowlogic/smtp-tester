# Security policy

## The threat model, stated plainly

smtp-tester is a **development and test tool**. It accepts mail from anyone who can reach its SMTP
port, stores every message in full, and exposes an **unauthenticated** web inbox, REST API and MCP
endpoint that can read and delete all of it. That is the design — a capture server that asked for
credentials would not be a drop-in replacement for MailHog or Mailpit, which is the job.

**Run it on loopback or on a private container network. Never expose it to the internet, and never
point production traffic at it.** Anything it receives should be assumed readable by anyone who can
reach port 8025.

Specifically, and by design, not bugs:

- No authentication on the inbox UI, the REST API, or `/mcp`.
- `AUTH` over SMTP accepts any username and password (`smtp-tester.smtp.accept-any-credentials`).
- STARTTLS is off by default and, when on, presents a self-signed certificate.
- Received mail is written to the spool directory in plaintext, including attachments.
- Message bodies are logged to stdout by default (`smtp-tester.logging.include-body`).

## What we do treat as a vulnerability

Anything that lets captured mail escape the boundary above, or act beyond it:

- A message body, header, address or attachment that executes script **on the inbox's own origin**,
  or otherwise reads or destroys stored mail.
- A message that causes a read or write **outside the configured spool directory**.
- Remote code execution, or a crash reachable from a single SMTP message or HTTP request.
- A leak of the STARTTLS private key, or a keystore password appearing in logs or API output.
- A dependency vulnerability reachable through this code.

## Hardening already in place

| Control | Where |
| --- | --- |
| HTML bodies served under `Content-Security-Policy: sandbox` — no script, opaque origin | `MessageApiController#html` |
| `nosniff`, `Referrer-Policy`, `X-Frame-Options`, COOP/CORP and a strict page CSP on every route | `SecurityHeadersFilter` |
| HTML/SVG/JavaScript attachments forced to download rather than rendered | `MessageViewMapper#safeMediaType` |
| Message ids validated before they can name a filesystem path, plus a containment check | `MessageIds#isValid`, `FilesystemMailSpool#resolve` |
| Attachment filenames stripped to `[A-Za-z0-9._-]` before hitting disk | `FilesystemMailSpool#partFileName` |
| Message size, connection, recipient and retention caps, all configurable | `SmtpTesterProperties` |
| Container runs as an unprivileged user | `Dockerfile` |
| No private key is committed; the STARTTLS certificate is generated per start | `TlsContextFactory` |

`SecurityHeadersTest` and `SpoolPathTraversalTest` exist specifically to keep the first four honest.

## Reporting a vulnerability

Report privately through **[GitHub Security Advisories](https://github.com/sparrowlogic/smtp-tester/security/advisories/new)**.
Please do not open a public issue for anything in the "what we do treat as a vulnerability" list.

Include what you have: the affected version, a minimal reproduction (a `.eml` file is ideal), and
what an attacker gets out of it.

Expect an acknowledgement within 5 working days and an assessment within 10. Fixes ship in a patch
release; you will be credited in the advisory and the changelog unless you would rather not be.

## Supported versions

Only the latest release is supported. Fixes go onto `main` and into the next release rather than
being backported.
