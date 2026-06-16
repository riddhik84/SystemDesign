package com.systemdesign.dropbox.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request body for POST /files/{fileId}/share.
 *
 * permission must be either "READ" or "WRITE".
 * Defaults to READ if not specified by the client.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ShareRequest {

    @NotBlank(message = "targetUserId is required")
    private String targetUserId;

    @Pattern(regexp = "READ|WRITE", message = "permission must be READ or WRITE")
    @Builder.Default
    private String permission = "READ";
}
