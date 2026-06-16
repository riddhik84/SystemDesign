package com.systemdesign.bitly.controller;

import com.systemdesign.bitly.service.UrlRedirectService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.net.URISyntaxException;

/**
 * Handles the URL read (redirect) path: {@code GET /{shortCode}}.
 *
 * <h2>HTTP 302 vs 301</h2>
 * We use {@code 302 Found} (temporary redirect) intentionally:
 * <ul>
 *   <li><strong>Server control</strong> — browsers cache 301 permanently, making it impossible
 *       to update or expire a link without clearing every client's cache.</li>
 *   <li><strong>Analytics</strong> — each request hits our servers, enabling click tracking,
 *       geographic analytics, and A/B testing.</li>
 *   <li><strong>Expiration semantics</strong> — with 302, when a link expires (410 Gone) the
 *       browser respects the new response immediately.</li>
 * </ul>
 *
 * <h2>Short code validation</h2>
 * The path variable accepts only alphanumeric characters plus hyphens and underscores (matching
 * the allowed character set for custom aliases and the base62 output).  This prevents injection
 * attacks and limits the blast radius of malformed requests before they reach the service layer.
 */
@RestController
@RequiredArgsConstructor
@Slf4j
@Tag(name = "URL Redirect", description = "Resolve a short code and redirect to the original URL")
public class RedirectController {

    private final UrlRedirectService urlRedirectService;

    /**
     * Resolves {@code shortCode} and returns an HTTP 302 redirect to the original URL.
     *
     * @param shortCode the short code from the URL path (e.g. {@code 0a3Zk8mQ})
     * @return 302 with {@code Location} header set to the long URL
     */
    @GetMapping("/{shortCode:[a-zA-Z0-9_-]+}")
    @Operation(
        summary = "Redirect to original URL",
        description = "Looks up the short code and returns HTTP 302 to the original long URL. " +
                      "Returns 404 if the code does not exist, 410 if it has expired."
    )
    @ApiResponses({
        @ApiResponse(
            responseCode = "302",
            description = "Redirect to the original URL",
            headers = @Header(
                name = "Location",
                description = "The original long URL",
                schema = @Schema(type = "string", format = "uri")
            )
        ),
        @ApiResponse(
            responseCode = "404",
            description = "Short code not found",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class))
        ),
        @ApiResponse(
            responseCode = "410",
            description = "Short code has expired",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class))
        )
    })
    public ResponseEntity<Void> redirect(
        @PathVariable
        @Parameter(description = "The short code to resolve", example = "0a3Zk8mQ")
        String shortCode
    ) {
        log.debug("GET /{}", shortCode);
        String longUrl = urlRedirectService.resolveUrl(shortCode);

        try {
            URI location = new URI(longUrl);
            HttpHeaders headers = new HttpHeaders();
            headers.setLocation(location);
            // HTTP 302: temporary redirect — browsers will not cache this permanently,
            // preserving our ability to update/expire the mapping at any time.
            return new ResponseEntity<>(headers, HttpStatus.FOUND);
        } catch (URISyntaxException e) {
            // This should not happen in practice because we validate long_url on ingestion,
            // but we handle it defensively to avoid a 500 if somehow bad data is in the DB.
            log.error("Stored long URL is not a valid URI: shortCode={}, longUrl={}", shortCode, longUrl, e);
            return ResponseEntity.internalServerError().build();
        }
    }
}
