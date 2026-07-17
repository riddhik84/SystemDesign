package com.systemdesign.instagram.dto;

import com.systemdesign.instagram.model.MediaType;
import lombok.Data;

@Data
public class UploadUrlRequest {
    private String uploaderId;
    private MediaType type;
    private long sizeBytes;
    private String fileName;
}
