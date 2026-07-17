package com.systemdesign.instagram.dto;

import com.systemdesign.instagram.model.MediaType;
import lombok.Data;

import java.util.List;

@Data
public class CreatePostRequest {
    private String authorId;
    private String caption;
    private MediaType mediaType;
    private List<String> mediaIds;
}
