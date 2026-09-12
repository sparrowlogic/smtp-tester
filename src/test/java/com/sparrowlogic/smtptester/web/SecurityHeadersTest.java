package com.sparrowlogic.smtptester.web;

import com.sparrowlogic.smtptester.MailFixtures;
import com.sparrowlogic.smtptester.core.SessionInfo;
import com.sparrowlogic.smtptester.smtp.MailboxService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.web.server.LocalServerPort;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.regex.MatchResult;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Asserts the headers that stop captured mail acting on the inbox's own origin.
 *
 * <p>Driven over a real port with a real HTTP client rather than through MockMvc, because the
 * thing under test is a servlet {@code Filter} registered by auto-configuration. MockMvc's
 * {@code webAppContextSetup} builds a chain with no filters in it at all, so every assertion here
 * would pass against a filter that was written but never registered — which is precisely the
 * failure worth catching.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SecurityHeadersTest {

    /** A body that would read the inbox out through the API if it ever ran on this origin. */
    private static final String HOSTILE_HTML = MailFixtures.join(
            "From: attacker@example.com",
            "To: alice@example.com",
            "Subject: hostile",
            "Date: Fri, 11 Sep 2026 12:00:00 +0000",
            "Message-ID: <hostile@example.com>",
            "MIME-Version: 1.0",
            "Content-Type: text/html; charset=utf-8",
            "",
            "<p>hi</p><script>fetch('/api/v1/messages').then(r=>r.text())</script>",
            "");

    @Autowired
    private MailboxService mailbox;

    @LocalServerPort
    private int port;

    private final HttpClient http = HttpClient.newHttpClient();
    private String hostileId = "";

    @BeforeEach
    void setUp() {
        this.mailbox.clearAll();
        this.hostileId = this.mailbox.receive(MailFixtures.bytes(HOSTILE_HTML),
                MailFixtures.envelope("attacker@example.com", "alice@example.com"),
                SessionInfo.plaintext()).message().id();
    }

    private HttpResponse<String> fetch(final String path) throws Exception {
        return this.http.send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + this.port + path)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void inboxPageCarriesAContentSecurityPolicyThatForbidsInlineScript() throws Exception {
        final HttpResponse<String> response = this.fetch("/");

        assertThat(response.statusCode()).isEqualTo(200);
        final String policy = response.headers().firstValue("Content-Security-Policy").orElseThrow();
        assertThat(policy).contains("default-src 'none'").contains("script-src 'self'");
        assertThat(policy).doesNotContain("'unsafe-inline'").doesNotContain("'unsafe-eval'");
    }

    @Test
    void everyResponseCarriesTheTransportHardeningHeaders() throws Exception {
        final HttpResponse<String> response = this.fetch("/api/v1/messages");

        assertThat(response.headers().firstValue("X-Content-Type-Options")).contains("nosniff");
        assertThat(response.headers().firstValue("Referrer-Policy")).contains("no-referrer");
        assertThat(response.headers().firstValue("X-Frame-Options")).contains("SAMEORIGIN");
        assertThat(response.headers().firstValue("Cross-Origin-Opener-Policy")).contains("same-origin");
        assertThat(response.headers().firstValue("Cross-Origin-Resource-Policy")).contains("same-origin");
    }

    @Test
    void htmlPreviewIsSandboxedIntoAnOpaqueOriginWithNoScripting() throws Exception {
        final HttpResponse<String> response =
                this.fetch("/api/v1/messages/" + this.hostileId + "/html");

        assertThat(response.statusCode()).isEqualTo(200);
        // The body is served verbatim -- this is a capture tool, so what arrived is what is shown.
        assertThat(response.body()).contains("<script>");

        final String policy = response.headers().firstValue("Content-Security-Policy").orElseThrow();
        // `sandbox` with no allow-list denies scripts, forms, plugins and same-origin access.
        assertThat(policy).startsWith("sandbox;");
        assertThat(policy).doesNotContain("allow-scripts").doesNotContain("allow-same-origin");
        assertThat(policy).contains("default-src 'none'");
        assertThat(response.headers().firstValue("X-Content-Type-Options")).contains("nosniff");
    }

    @Test
    void htmlPreviewKeepsItsOwnPolicyRatherThanCollectingTheGlobalOneToo() throws Exception {
        final HttpResponse<String> response =
                this.fetch("/api/v1/messages/" + this.hostileId + "/html");

        // Two policies would both be enforced and their intersection would re-block the images the
        // preview is meant to render, so exactly one header is the assertion that matters.
        final List<String> policies = response.headers().allValues("Content-Security-Policy");
        assertThat(policies).hasSize(1);
        assertThat(policies.getFirst()).contains("img-src");
    }

    /**
     * The page policy is only as good as the markup agreeing with it.
     *
     * <p>A {@code script-src 'self'} policy silently drops an inline handler rather than failing
     * loudly, so an {@code onsubmit="return confirm(...)"} on a delete form would stop asking and
     * the button would still look guarded. That is the regression this pins — and it is invisible
     * to a test that only asserts the header is present, because an HTTP client does not enforce
     * CSP. Asserting on the rendered markup is what catches it.
     */
    @ParameterizedTest
    @ValueSource(strings = {"/", "/messages/"})
    void renderedPagesContainNothingTheirOwnPolicyWouldBlock(final String path) throws Exception {
        final String url = "/messages/".equals(path) ? path + this.hostileId : path;
        final HttpResponse<String> response = this.fetch(url);
        assertThat(response.statusCode()).isEqualTo(200);
        final String html = response.body();

        assertThat(inlineScripts(html))
                .as("inline <script> is dropped by script-src 'self'")
                .isEmpty();
        assertThat(html)
                .as("<style> blocks and style= attributes are dropped by style-src 'self'")
                .doesNotContain("<style").doesNotContain("style=\"");
        assertThat(INLINE_HANDLER.matcher(html).results().map(MatchResult::group).toList())
                .as("inline event handlers are dropped by script-src 'self'")
                .isEmpty();
        assertThat(html)
                .as("base-uri 'none' makes a <base> tag ineffective")
                .doesNotContain("<base ");
        assertThat(offOriginReferences(html))
                .as("default-src 'none' allows only same-origin and data: references")
                .isEmpty();
    }

    private static final Pattern INLINE_HANDLER = Pattern.compile("\\son[a-z]+\\s*=\\s*\"");

    private static final Pattern SCRIPT_TAG = Pattern.compile("<script([^>]*)>");

    private static final Pattern RESOURCE = Pattern.compile("(?:src|href)\\s*=\\s*\"([^\"]*)\"");

    private static List<String> inlineScripts(final String html) {
        return SCRIPT_TAG.matcher(html).results()
                .map(match -> match.group(1))
                .filter(attributes -> !attributes.contains("src="))
                .toList();
    }

    /** References the policy would have to name a host to permit. */
    private static List<String> offOriginReferences(final String html) {
        return RESOURCE.matcher(html).results()
                .map(match -> match.group(1))
                .filter(value -> !value.startsWith("data:"))
                .filter(value -> value.startsWith("http://") || value.startsWith("https://")
                        || value.startsWith("//"))
                .toList();
    }

    @Test
    void attachmentsAreDownloadedRatherThanRenderedAndAreNotSniffed() throws Exception {
        final String id = this.mailbox.receive(MailFixtures.bytes(MailFixtures.MULTIPART_WITH_ATTACHMENT),
                MailFixtures.envelope("app@example.com", "alice@example.com"),
                SessionInfo.plaintext()).message().id();

        final HttpResponse<String> response = this.fetch("/api/v1/messages/" + id + "/attachments/1");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Disposition")).get()
                .asString().startsWith("attachment");
        assertThat(response.headers().firstValue("X-Content-Type-Options")).contains("nosniff");
    }
}
