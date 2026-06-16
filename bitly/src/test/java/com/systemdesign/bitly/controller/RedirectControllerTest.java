package com.systemdesign.bitly.controller;

import com.systemdesign.bitly.exception.GlobalExceptionHandler;
import com.systemdesign.bitly.exception.UrlExpiredException;
import com.systemdesign.bitly.exception.UrlNotFoundException;
import com.systemdesign.bitly.service.UrlRedirectService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(RedirectController.class)
@Import(GlobalExceptionHandler.class)
@DisplayName("RedirectController (MVC slice)")
class RedirectControllerTest {

    @Autowired private MockMvc mockMvc;
    @MockBean  private UrlRedirectService urlRedirectService;

    // -------------------------------------------------------------------------
    // Happy path
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("GET /{shortCode} returns 302 with Location header")
    void redirect_returns302WithLocation() throws Exception {
        when(urlRedirectService.resolveUrl("abc12345"))
            .thenReturn("https://www.example.com/very/long/path");

        mockMvc.perform(get("/abc12345"))
            .andExpect(status().isFound())
            .andExpect(header().string("Location", "https://www.example.com/very/long/path"));
    }

    @Test
    @DisplayName("GET /{shortCode} with custom alias returns 302")
    void redirect_customAlias_returns302() throws Exception {
        when(urlRedirectService.resolveUrl("my-alias"))
            .thenReturn("https://example.com");

        mockMvc.perform(get("/my-alias"))
            .andExpect(status().isFound())
            .andExpect(header().string("Location", "https://example.com"));
    }

    // -------------------------------------------------------------------------
    // Not found
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("GET /{shortCode} returns 404 when code not found")
    void redirect_returns404_whenNotFound() throws Exception {
        when(urlRedirectService.resolveUrl("missing1"))
            .thenThrow(new UrlNotFoundException("missing1"));

        mockMvc.perform(get("/missing1"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.title").value("URL Not Found"));
    }

    // -------------------------------------------------------------------------
    // Expired
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("GET /{shortCode} returns 410 when URL is expired")
    void redirect_returns410_whenExpired() throws Exception {
        when(urlRedirectService.resolveUrl("expired1"))
            .thenThrow(new UrlExpiredException("expired1", LocalDateTime.now().minusDays(1)));

        mockMvc.perform(get("/expired1"))
            .andExpect(status().isGone())
            .andExpect(jsonPath("$.title").value("URL Expired"));
    }

    // -------------------------------------------------------------------------
    // Invalid characters in path variable
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("GET /{shortCode} with special chars does not reach the redirect handler")
    void redirect_invalidChars_isRejected() throws Exception {
        // The path variable regex [a-zA-Z0-9_-]+ rejects paths with characters outside the
        // allowed set before the handler method is invoked. In MockMvc the DispatcherServlet
        // returns a 4xx response (404 when no other handler matches, or 500 from the generic
        // handler depending on Spring MVC's NoHandlerFoundException propagation settings).
        // We assert that the response is NOT a successful redirect (i.e. not 302).
        mockMvc.perform(get("/bad!code"))
            .andExpect(status().is4xxClientError());
    }
}
