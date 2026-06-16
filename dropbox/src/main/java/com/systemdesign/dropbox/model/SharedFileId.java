package com.systemdesign.dropbox.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * Composite primary key for SharedFile.
 * Must implement Serializable and provide equals/hashCode — Lombok @Data handles both.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SharedFileId implements Serializable {
    private String fileId;
    private String sharedWithUserId;
}
