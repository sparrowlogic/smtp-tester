package com.sparrowlogic.smtptester.web;

import com.sparrowlogic.smtptester.docs.AgentDocs;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Asserts the documentation an agent is pointed at is actually served, and is the same text the
 * MCP tool returns.
 *
 * <p>Driven over a real port because the point of these two routes is that an agent with nothing
 * but a base URL can fetch them.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AgentDocsControllerTest {

    private final AgentDocs expected = new AgentDocs();

    @LocalServerPort
    private int port;

    @Test
    void theIndexIsServedAsPlainTextAtTheConventionalPath() throws Exception {
        final HttpResponse<String> response = this.get("/llms.txt");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("content-type").orElseThrow())
                .startsWith("text/plain");
        assertThat(response.body()).isEqualTo(this.expected.text(AgentDocs.Document.INDEX));
    }

    @Test
    void theFullReferenceIsServedAndIsLongerThanTheIndex() throws Exception {
        final HttpResponse<String> response = this.get("/llms-full.txt");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body())
                .isEqualTo(this.expected.text(AgentDocs.Document.FULL))
                .hasSizeGreaterThan(this.expected.text(AgentDocs.Document.INDEX).length());
    }

    /**
     * The documentation is read at runtime by a model deciding what to call next, so a mangled
     * encoding is a correctness problem rather than a cosmetic one.
     */
    @Test
    void nonAsciiPunctuationSurvivesTheRoundTrip() throws Exception {
        assertThat(this.get("/llms-full.txt").body()).contains("—");
    }

    private HttpResponse<String> get(final String path) throws Exception {
        final HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + this.port + path))
                .GET()
                .build();
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
    }
}
