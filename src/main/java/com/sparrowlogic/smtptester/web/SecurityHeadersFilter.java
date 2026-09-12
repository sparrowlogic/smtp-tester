package com.sparrowlogic.smtptester.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;

/**
 * Adds the response headers that keep captured mail from acting on the inbox's own origin.
 *
 * <p>Everything this server displays arrived over SMTP from whatever could reach port 1025, and the
 * UI and API next to it are unauthenticated by design. That combination is what makes these headers
 * load-bearing rather than a checklist item: a subject line, an address or an HTML body is
 * attacker-controlled content rendered on an origin that can read and delete every stored message.
 * The templates escape and the HTML preview is sandboxed, and this is the layer that holds when one
 * of those is wrong.
 */
public class SecurityHeadersFilter extends OncePerRequestFilter {

    private static final String CONTENT_SECURITY_POLICY = "Content-Security-Policy";

    /** The suffix of the one route that serves attacker-controlled HTML and sets its own policy. */
    private static final String HTML_PREVIEW_SUFFIX = "/html";

    private static final String MESSAGE_ROUTE = "/api/v1/messages/";

    /**
     * The UI's own scripts and styles are served as files under {@code /css} and {@code /js}, so
     * {@code 'self'} is enough and no inline execution has to be allowed. {@code connect-src 'self'}
     * keeps the page's fetch calls working while denying exfiltration to another host.
     */
    private static final String PAGE_POLICY =
            "default-src 'none'; script-src 'self'; style-src 'self'; img-src 'self' data:; "
                    + "font-src 'self' data:; connect-src 'self'; frame-src 'self'; "
                    + "form-action 'self'; base-uri 'none'; frame-ancestors 'none'";

    @Override
    protected void doFilterInternal(final HttpServletRequest request,
            final HttpServletResponse response, final FilterChain chain)
            throws ServletException, IOException {
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("Referrer-Policy", "no-referrer");
        response.setHeader("X-Frame-Options", "SAMEORIGIN");
        response.setHeader("Cross-Origin-Opener-Policy", "same-origin");
        response.setHeader("Cross-Origin-Resource-Policy", "same-origin");
        if (!isHtmlPreview(request)) {
            response.setHeader(CONTENT_SECURITY_POLICY, PAGE_POLICY);
        }
        chain.doFilter(request, response);
    }

    /**
     * Whether this request is the HTML body preview.
     *
     * <p>Decided here, before the handler runs, rather than by checking afterwards whether a policy
     * was already set: headers cannot be changed once the response has been committed, and a
     * second {@code Content-Security-Policy} header does not replace the first — browsers enforce
     * every policy they are given, so the two would intersect and quietly re-block what
     * {@link MessageApiController#html(String)} deliberately allows.
     */
    private static boolean isHtmlPreview(final HttpServletRequest request) {
        final String path = request.getRequestURI();
        return path.endsWith(HTML_PREVIEW_SUFFIX) && path.contains(MESSAGE_ROUTE);
    }
}
