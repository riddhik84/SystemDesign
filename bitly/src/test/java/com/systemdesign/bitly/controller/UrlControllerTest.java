package com.systemdesign.bitly.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.systemdesign.bitly.exception.AliasAlreadyExistsException;
import com.systemdesign.bitly.exception.GlobalExceptionHandler;
import com.systemdesign.bitly.model.ShortenRequest;
import com.systemdesign.bitly.model.ShortenResponse;
import com.systemdesign.bitly.service.UrlShorteningService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(UrlController.class)
@Import(GlobalExceptionHandler.class)
@DisplayName("UrlController (MVC slice)")
class UrlControllerTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @MockBean  private UrlShorteningService urlShorteningService;

    // -------------------------------------------------------------------------
    // Happy path
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("POST /urls returns 201 with short URL")
    void createShortUrl_returns201() throws Exception {
        ShortenResponse response = ShortenResponse.builder()
            .shortUrl("http://localhost:8080/abc12345")
            .shortCode("abc12345")
            .longUrl("https://www.example.com/very/long/url")
            .createdAt(LocalDateTime.now())
            .build();
        when(urlShorteningService.shorten(any())).thenReturn(response);

        ShortenRequest request = ShortenRequest.builder()
            .longUrl("https://www.example.com/very/long/url")
            .build();

        mockMvc.perform(post("/urls")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.short_url").value("http://localhost:8080/abc12345"))
            .andExpect(jsonPath("$.short_code").value("abc12345"))
            .andExpect(jsonPath("$.long_url").value("https://www.example.com/very/long/url"));
    }

    @Test
    @DisplayName("POST /urls with custom alias returns 201")
    void createShortUrl_withAlias_returns201() throws Exception {
        ShortenResponse response = ShortenResponse.builder()
            .shortUrl("http://localhost:8080/my-alias")
            .shortCode("my-alias")
            .longUrl("https://www.example.com")
            .createdAt(LocalDateTime.now())
            .build();
        when(urlShorteningService.shorten(any())).thenReturn(response);

        ShortenRequest request = ShortenRequest.builder()
            .longUrl("https://www.example.com")
            .customAlias("my-alias")
            .build();

        mockMvc.perform(post("/urls")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.short_code").value("my-alias"));
    }

    // -------------------------------------------------------------------------
    // Validation errors
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("POST /urls with blank long_url returns 422")
    void createShortUrl_blankUrl_returns422() throws Exception {
        ShortenRequest request = ShortenRequest.builder().longUrl("").build();

        mockMvc.perform(post("/urls")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isUnprocessableEntity());
    }

    @Test
    @DisplayName("POST /urls with invalid URL scheme returns 422")
    void createShortUrl_invalidUrlScheme_returns422() throws Exception {
        ShortenRequest request = ShortenRequest.builder()
            .longUrl("not-a-valid-url")
            .build();

        mockMvc.perform(post("/urls")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isUnprocessableEntity());
    }

    @Test
    @DisplayName("POST /urls with alias too short returns 422")
    void createShortUrl_aliasTooShort_returns422() throws Exception {
        ShortenRequest request = ShortenRequest.builder()
            .longUrl("https://example.com")
            .customAlias("ab")  // min 3
            .build();

        mockMvc.perform(post("/urls")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isUnprocessableEntity());
    }

    @Test
    @DisplayName("POST /urls with alias containing invalid chars returns 422")
    void createShortUrl_aliasInvalidChars_returns422() throws Exception {
        ShortenRequest request = ShortenRequest.builder()
            .longUrl("https://example.com")
            .customAlias("invalid alias!")  // space + ! not allowed
            .build();

        mockMvc.perform(post("/urls")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isUnprocessableEntity());
    }

    // -------------------------------------------------------------------------
    // Conflict
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("POST /urls returns 409 when alias is already taken")
    void createShortUrl_aliasConflict_returns409() throws Exception {
        when(urlShorteningService.shorten(any()))
            .thenThrow(new AliasAlreadyExistsException("taken-alias"));

        ShortenRequest request = ShortenRequest.builder()
            .longUrl("https://example.com")
            .customAlias("taken-alias")
            .build();

        mockMvc.perform(post("/urls")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.title").value("Alias Already Exists"));
    }
}
