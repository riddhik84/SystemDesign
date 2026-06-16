package com.systemdesign.dropbox.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Type-safe binding for application.yml prefix "app".
 *
 * Registered via @EnableConfigurationProperties(AppProperties.class) on
 * DropboxApplication so Spring populates it at startup.
 */
@Data
@ConfigurationProperties(prefix = "app")
public class AppProperties {

    private S3Properties s3 = new S3Properties();

    /** Size of each upload chunk in bytes. Default: 8 MB = 8_388_608 bytes. */
    private long chunkSizeBytes = 8_388_608L;

    /** Public base URL of this service (used for self-referencing links). */
    private String baseUrl = "http://localhost:8080";

    @Data
    public static class S3Properties {

        private String bucketName = "dropbox-files";
        private String region = "us-east-1";

        /**
         * Optional override for the S3 endpoint URL.
         * Set to "http://localhost:4566" to use LocalStack for local development.
         * Leave empty/null to use the real AWS S3 endpoint.
         */
        private String endpointOverride;

        /** Minutes before a presigned URL expires. Default: 5 minutes. */
        private int presignedUrlExpiryMinutes = 5;
    }
}
