# Contributing

Thanks for taking a look. Issues and pull requests are welcome.

## Requirements

- **JDK 26.** The build targets `--release 26`; nothing older will compile it.
- **Make**, and Docker if you want to touch the image.

Maven itself comes from the wrapper — use `./mvnw`, not a system `mvn`.

## The one command

```bash
make agent-completion
```

That is the definition of "ready to push". It compiles under ErrorProne/NullAway, runs the whole
suite, enforces **85% line / 75% branch** coverage, and checks formatting and style. CI runs the
same target, so a green local run means a green CI run.

`make help` lists everything else. The loop you will actually use:

```bash
make fast-test      # tests, no coverage agent -- quickest signal
make format         # fix formatting instead of arguing with spotless
make run            # server on 1025/8025
make agent-completion   # before you push
```

## What a good pull request looks like

- **One concern per PR.** A bug fix plus a refactor plus a dependency bump is three reviews in a
  trench coat.
- **Tests that would have caught the bug**, not tests written against the fix. Assert on the
  payload, not just the status code.
- **Comments that say why**, where the reason is not recoverable from the code. Look at the
  existing ones — they justify decisions a future reader would otherwise undo. Don't add comments
  that restate the line below them.
- **Test names that state the guarantee**: `warnsWhenTheCertificateDoesNotCoverTheRequestedName`,
  not `testTls`.

## What will get a PR sent back

- **Weakening the gate.** Lowering a coverage minimum, adding a Checkstyle suppression, adding
  `@SuppressWarnings("NullAway")`, or disabling a test. If one is genuinely warranted, make it its
  own PR with the reasoning, rather than a line inside an unrelated change.
- **Component scanning, or an unconditional bean.** This jar is embedded in other people's
  applications. Every bean is declared in an `@AutoConfiguration` class, is
  `@ConditionalOnMissingBean`, and is named `smtpTester*`. `LibraryModeTest` is what catches a
  regression here.
- **An `application.yaml` in `src/main/resources`.** It would override a host application's own
  `server.port`. Standalone-only defaults go in `SmtpTesterApplication`.
- **A new dependency without a reason.** Check whether Spring Boot's BOM already manages an
  equivalent. If it is genuinely needed, add it with a comment in `pom.xml` saying why — every
  existing one has one.
- **Hardcoded ports in a test.** Bind port 0. A MailHog or a real smtp-tester is often already on
  1025/8025.

[AGENTS.md](AGENTS.md) has the longer version, including the parts that will trip you up.

## Reporting bugs

A `.eml` file that reproduces it is worth more than a paragraph describing it. Include the
smtp-tester version, how you are running it (container, jar, embedded), and what you expected.

For anything security-related, read [SECURITY.md](SECURITY.md) first — a lot of what looks alarming
is the documented design of a test tool, and the genuine issues go through a private advisory
rather than a public issue.

## Releasing

Maintainers only:

```bash
make deps-check                   # any dependency updates to take first?
make audit OSSINDEX_AUTH_ID=ossindex   # anything with a known CVE? (needs an OSS Index account)
make agent-completion             # green
./mvnw -Prelease deploy           # sources + javadoc + GPG + Maven Central
```

## Licence

Contributions are accepted under the [MIT Licence](LICENSE), the same terms as the project.
