# Working in this repository

smtp-tester is an MIT-licensed, publicly published container image. It is consumed by people who
cannot see this conversation, so the bar is what a stranger can safely depend on, not what happens
to work on this machine.

---

## The completion gate

```bash
make agent-completion
```

**A change is not finished until that command exits 0.** Not "the tests I ran pass", not "it
compiles" — that exact command, run after the last edit. It is the same command CI runs and the
same one the stop hook in `.claude/settings.json` runs, so there is one standard and no version of
"done" that skips it.

It runs `./mvnw verify`, which is:

| Step | Fails the build when |
| --- | --- |
| compile + ErrorProne/NullAway | a nullness contract is violated anywhere in a `@NullMarked` package |
| `surefire` | any test fails |
| `jacoco:check` | line coverage < **85%** or branch coverage < **75%** |
| `spotless:check` | anything is unformatted |
| `checkstyle:check` | any violation at `warning` severity or above |

If it fails, fix the cause. Do not lower a threshold, add a Checkstyle suppression, or mark a test
`@Disabled` to get past it — see *Never weaken the gate* below.

`make help` lists the rest. `make format` fixes formatting; `make fast-test` is the quick loop
(no coverage agent); `make deps-check` and `make audit` reach the network and are therefore
deliberately outside the gate.

---

## What this project is

A local SMTP server that captures mail instead of delivering it, with a web inbox, an RFC
compliance validator, and an MCP server — MailHog/Mailpit's ports (`1025`, `8025`), plus the two
things they don't do.

It ships as **one container**: `smtp-tester-<version>-exec.jar` run by the Dockerfile. That
container serves three surfaces on the same HTTP port — the inbox UI, the REST API, and an MCP
server over streamable HTTP at `/mcp` — plus the SMTP listener on `1025`. There is no library
artifact and no Maven plugin; see the house rule below.

### Layout

| Package | Holds |
| --- | --- |
| `core` | the mail model, MIME parsing, the in-memory store |
| `smtp` | the listening socket, ingestion, STARTTLS |
| `validation` | the RFC rule set and its findings |
| `spool` | write-through of messages to disk |
| `web` | the inbox UI (JTE) and the REST API |
| `mcp` | the agent-facing tool surface |
| `chaos` | transport-level fault injection |
| `logging` | the structured per-message log line |
| `config` | properties and bean wiring |
| `docs` | the agent-readable reference served at `/llms.txt` and by MCP |

---

## House rules

### The container is the only supported shape

This used to ship as a library that auto-configured itself inside a host application's tests. It
does not any more: there is no `AutoConfiguration.imports`, and the jar is not published to Maven
Central. One shape means one code path to reason about, and `docker run` is a smaller thing to ask
of a stranger than a dependency plus a config block. Do not reintroduce the library path — no
`@AutoConfiguration`, no `spring.factories`, no "it also works as a dependency" in the README.

What survives from that era, because it is still worth having on its own merits:

- **No component scanning.** `SmtpTesterApplication` names both configuration classes on
  `@Import`, so the bean graph is exactly what those two files declare. A bean cannot appear
  because a package happened to be scanned. Do not add `@Component` / `@Service` /
  `@RestController`-by-scanning.
- **Beans are named `smtpTester*`.** Cheap, and it keeps the names self-describing in a context
  dump.
- **The base-path filter is scoped to `smtp-tester.web.base-path`, not `/*`**, because
  `base-path` is a real feature: the UI can be mounted under a prefix.
- **Order matters in `@Import`.** `SmtpTesterConfiguration` is listed first and the web layer's
  conditions assume its beans are already registered. Plain `@Configuration` gives no ordering
  guarantee the way `@AutoConfiguration(after = ...)` did, which is why the web layer tests a
  property rather than `@ConditionalOnBean`.

### Null-safety is compile-enforced

Every package is `@NullMarked` via `package-info.java`, and NullAway runs in JSpecify mode at
`ERROR`. A reference is non-null unless annotated `@Nullable`. Do not suppress the check; if the
value really can be null, say so in the type.

### Treat received mail as hostile

Everything the UI displays arrived over SMTP from whatever could reach port 1025, and the UI and
API next to it are unauthenticated by design. When you touch the web or spool layers:

- **Never render a message body on the app's own origin.** `/api/v1/messages/{id}/html` is served
  under `Content-Security-Policy: sandbox` for this reason; `SecurityHeadersTest` pins it.
- **Never let a message-derived string reach the filesystem unchecked.** Ids are validated with
  `MessageIds.isValid` before they become a path; `SpoolPathTraversalTest` pins it.
- **Never log credentials or full bodies by default.** `smtp-tester.logging.include-body` exists so
  the body can be turned off; the SMTP password is never logged at any level.

### Dependencies

Keep them few and current. Before adding one, check whether Spring Boot's BOM already manages an
equivalent. A new direct dependency needs a comment in `pom.xml` saying why it is there — every
existing one has one. Pin the version in `<properties>` unless the parent BOM manages it. Run `make deps-check` before a release, and `make audit` (which needs an OSS Index account — see
the Makefile). Dependabot and the CI dependency-review job cover the routine case without
credentials.

### Never weaken the gate

Lowering a `jacoco` minimum, adding a `checkstyle-suppressions.xml` entry, adding
`@SuppressWarnings("NullAway")`, or disabling a test is a change to the project's standards, not a
step in a task. Do not do it as a side effect of unrelated work. If one is genuinely warranted,
raise it as its own change with the reason, and say so explicitly rather than burying it in a diff.

### Tests

- 85% line coverage is the floor, not the target. Cover the branch that is hard to reach, not the
  getter that is easy.
- Assert on the payload, not just the status code. A 200 with the wrong body is the failure a
  status-only test waves through.
- Integration tests bind port 0. Never hardcode 1025 or 8025 in a test — a MailHog or a real
  smtp-tester is often already on them.
- Test names say what is guaranteed, not what is called:
  `warnsWhenTheRequestedNameIsNotCoveredByThePresentedCertificate`, not `testTls`.

### Comments

Explain *why*, and only where the reason is not recoverable from the code. The existing comments
are the standard: they justify a decision a future reader would otherwise undo. Do not add comments
that restate the line below them.

---

## Things that will trip you up

- **Port 1025/8025 are often already taken** by a MailHog or Mailpit container. Tests use
  ephemeral ports; `make run` does not.
- **JTE templates are precompiled** by `jte-maven-plugin` at `generate-sources`. Editing a
  `.jte` file requires a rebuild — `src/main/jte` does not exist inside the container.
- **ErrorProne needs `--add-exports`/`--add-opens`** and `<fork>true</fork>` to reach javac
  internals. Do not remove those `compilerArgs`; the build stops compiling without them.
- **Java 26.** `java.version` is `26`; ErrorProne and NullAway must stay on versions that support
  it.
- **`insertReceivedHeaders(false)` is load-bearing.** Byte-exactness is the point of the spool; a
  `Received:` header would make every stored `.eml` differ from what was sent.
