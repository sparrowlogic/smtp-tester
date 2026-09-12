# Changelog

All notable changes to this project are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and this project adheres to
[Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [0.1.0] - 2026-09-14

First release. A local SMTP server that captures mail instead of delivering it, published as a
multi-architecture container image for `linux/amd64` and `linux/arm64`.

### Added

- **SMTP capture on port 1025.** Accepts mail from anything that can reach it and stores it rather
  than delivering it. `AUTH` accepts any credentials, so an application that insists on
  authenticating needs no change. Matches MailHog and Mailpit's ports and behaviour, so swapping
  the container into an existing setup is usually a one-line change.
- **RFC compliance validation.** Every message is checked against the transport conversation,
  required and malformed headers, address syntax, MIME structure and encoding, and TLS. Findings
  carry a severity and the rule that produced them, and `smtp-tester.validation.reject-on` decides
  whether a violation is refused on the wire with a registered enhanced status code or merely
  recorded. Rejected mail is still stored, so you can see exactly what was refused and why.
- **A web inbox** at port 8025, with a per-message view showing headers, decoded parts,
  attachments and the compliance report. Message bodies render only under a sandboxed
  `Content-Security-Policy`, because everything on display arrived from the network.
- **A REST API** covering inboxes, search, the parsed message, its validation findings, the
  verbatim received bytes, the HTML part, and attachments — plus deletion of one message or all.
- **A server-sent events feed** at `GET /api/v1/messages/stream`, with the same filters as search.
  Subscribe, act, and wait for `mail.received` instead of polling behind a sleep. `?since=` and the
  `Last-Event-ID` header replay what a subscriber missed.
- **An MCP server** over streamable HTTP at `/mcp`, on the same port as the inbox, so an agent
  needs one URL and no adapter process. Tools cover listing, searching, reading and clearing mail,
  driving the chaos monkey, and reading this server's own documentation.
- **Agent-readable documentation** at `GET /llms.txt` and `GET /llms-full.txt`, and a
  `get_documentation` MCP tool returning the same text. Both read one packaged source, and a test
  walks the configuration record and fails the build if a bindable key is undocumented.
- **Write-through spooling to disk.** Received mail is written as byte-exact `.eml` files with no
  `Received:` header inserted, so a capture can be diffed against what was sent. Decoded MIME parts
  are written alongside. The directory is re-read at startup and trimmed back to
  `store.max-messages` by a background sweep.
- **Optional STARTTLS**, with a self-signed certificate minted at startup or a keystore you supply,
  and endpoints that publish what the server presents.
- **A chaos monkey** for transport-level fault injection: refused connections, dropped sessions,
  transient rejections and throttled transfers. Seedable, so a failing run reproduces, and every
  injected fault is counted.
- **A structured log line per message**, with the body included by default and switchable off.

[0.1.0]: https://github.com/sparrowlogic/smtp-tester/releases/tag/v0.1.0
