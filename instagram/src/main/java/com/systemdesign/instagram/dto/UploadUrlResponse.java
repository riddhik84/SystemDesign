package com.systemdesign.instagram.dto;

import lombok.Data;

@Data
public class UploadUrlResponse {
    private String mediaId;
    private String uploadUrl;
    private String blobKey;
    private String cdnUrl;
    private long maxSizeBytes;
}
