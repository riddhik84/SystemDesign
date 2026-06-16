package com.systemdesign.bitly.controller;

import com.systemdesign.bitly.model.ShortenRequest;
import com.systemdesign.bitly.model.ShortenResponse;
import com.systemdesign.bitly.service.UrlShorteningService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Handles the URL write path: {@code POST /urls}.
 *
 * <p>This endpoint is the only mutating operation in the service.  It validates the incoming
 * request, delegates to {@link UrlShorteningService}, and returns HTTP 201 Created with the
 * short URL payload.
 *
 * <p>Rate limiting and authentication are intentionally omitted from this reference
 * implementation but would sit at the API Gateway / load balancer layer in production.
 */
@RestController
@RequestMapping("/urls")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "URL Shortening", description = "Create new shortened URLs")
public class UrlController {

    private final UrlShorteningService urlShorteningService;

    /**
     * Creates a new shortened URL.
     *
     * <p>Request body fields:
     * <ul>
     *   <li>{@code long_url} (required) — must be a valid URL with scheme.</li>
     *   <li>{@code custom_alias} (optional) — alphanumeric slug; unique; 3–100 chars.</li>
     *   <li>{@code expiration_date} (optional) — ISO-8601; link becomes 410 Gone after this.</li>
     * </ul>
     *
     * @param request validated request body
     * @return 201 Created with {@link ShortenResponse}
     */
    @PostMapping(
        consumes = MediaType.APPLICATION_JSON_VALUE,
        produces = MediaType.APPLICATION_JSON_VALUE
    )
    @Operation(
        summary = "Shorten a URL",
        description = "Accepts a long URL and returns a shortened equivalent. " +
                      "Optionally accepts a custom alias and expiration date."
    )
    @ApiResponses({
        @ApiResponse(
            responseCode = "201",
            description = "Short URL created successfully",
            content = @Content(schema = @Schema(implementation = ShortenResponse.class))
        ),
        @ApiResponse(
            responseCode = "409",
            description = "Custom alias is already taken",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class))
        ),
        @ApiResponse(
            responseCode = "422",
            description = "Validation failed (invalid URL, alias format, etc.)",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class))
        )
    })
    public ResponseEntity<ShortenResponse> createShortUrl(@Valid @RequestBody ShortenRequest request) {
        log.info("POST /urls longUrl={}, customAlias={}", request.getLongUrl(), request.getCustomAlias());
        ShortenResponse response = urlShorteningService.shorten(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }
}
