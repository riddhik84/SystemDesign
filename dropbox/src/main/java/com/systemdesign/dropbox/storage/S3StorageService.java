package com.systemdesign.dropbox.storage;

import com.amazonaws.HttpMethod;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.model.AbortMultipartUploadRequest;
import com.amazonaws.services.s3.model.CompleteMultipartUploadRequest;
import com.amazonaws.services.s3.model.DeleteObjectRequest;
import com.amazonaws.services.s3.model.GeneratePresignedUrlRequest;
import com.amazonaws.services.s3.model.InitiateMultipartUploadRequest;
import com.amazonaws.services.s3.model.InitiateMultipartUploadResult;
import com.amazonaws.services.s3.model.PartETag;
import com.systemdesign.dropbox.config.AppProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Date;
import java.util.List;

/**
 * Encapsulates all AWS S3 operations.
 *
 * Design decisions:
 * - All S3 exceptions propagate upward as RuntimeExceptions. The service layer
 *   catches them if retry logic is needed; the GlobalExceptionHandler catches
 *   anything that bubbles to the controller tier.
 * - Presigned PUT URLs are generated per chunk/part, matching S3's multipart
 *   upload part numbering (1-based).
 * - Presigned GET URLs are short-lived (5 min default) so leaked URLs expire quickly.
 * - For CDN (CloudFront) signed URLs in production: replace generatePresignedDownloadUrl
 *   with CloudFront's signed URL mechanism using an RSA key pair. The interface
 *   contract (returns a String URL) remains the same, only the implementation changes.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class S3StorageService {

    private final AmazonS3 amazonS3;
    private final AppProperties appProperties;

    /**
     * Starts an S3 multipart upload and returns the uploadId needed for all
     * subsequent part operations and the final CompleteMultipartUpload call.
     */
    public String initiateMultipartUpload(String s3Key) {
        String bucket = appProperties.getS3().getBucketName();
        log.debug("Initiating multipart upload: bucket={}, key={}", bucket, s3Key);

        InitiateMultipartUploadRequest request =
                new InitiateMultipartUploadRequest(bucket, s3Key);
        InitiateMultipartUploadResult result = amazonS3.initiateMultipartUpload(request);

        log.info("Multipart upload initiated: key={}, uploadId={}", s3Key, result.getUploadId());
        return result.getUploadId();
    }

    /**
     * Generates a presigned PUT URL for a single chunk (part).
     *
     * The client uses this URL to upload the chunk bytes directly to S3 via HTTP PUT.
     * S3 returns an ETag in the response header that the client must capture and
     * send back in CompleteUploadRequest.
     *
     * @param s3Key      object key (same for all parts of the same upload)
     * @param uploadId   multipart upload ID from initiateMultipartUpload
     * @param partNumber 1-based part number (max 10,000 per S3 limits)
     * @param expiry     how long the URL remains valid
     * @return presigned URL string
     */
    public String generatePresignedUploadUrl(String s3Key, String uploadId,
                                              int partNumber, Duration expiry) {
        String bucket = appProperties.getS3().getBucketName();
        Date expirationDate = new Date(System.currentTimeMillis() + expiry.toMillis());

        GeneratePresignedUrlRequest request =
                new GeneratePresignedUrlRequest(bucket, s3Key, HttpMethod.PUT)
                        .withExpiration(expirationDate);

        // Attach multipart parameters so S3 routes this PUT to the correct part.
        request.addRequestParameter("uploadId", uploadId);
        request.addRequestParameter("partNumber", String.valueOf(partNumber));

        String url = amazonS3.generatePresignedUrl(request).toString();
        log.debug("Generated presigned upload URL: key={}, part={}", s3Key, partNumber);
        return url;
    }

    /**
     * Generates a presigned GET URL for downloading the object.
     *
     * In production this would be replaced with a CloudFront signed URL for CDN delivery.
     * The short expiry (default 5 min) limits blast radius of URL leakage.
     *
     * @param s3Key  object key
     * @param expiry how long the URL remains valid
     * @return presigned download URL string
     */
    public String generatePresignedDownloadUrl(String s3Key, Duration expiry) {
        String bucket = appProperties.getS3().getBucketName();
        Date expirationDate = new Date(System.currentTimeMillis() + expiry.toMillis());

        GeneratePresignedUrlRequest request =
                new GeneratePresignedUrlRequest(bucket, s3Key, HttpMethod.GET)
                        .withExpiration(expirationDate);

        String url = amazonS3.generatePresignedUrl(request).toString();
        log.debug("Generated presigned download URL: key={}", s3Key);
        return url;
    }

    /**
     * Completes an S3 multipart upload by submitting all part ETags.
     *
     * S3 assembles the parts in chunkNumber order. The partETags list must be
     * sorted by part number — the caller (FileUploadService) is responsible for
     * sorting before calling this method.
     *
     * @param s3Key      object key
     * @param uploadId   multipart upload ID
     * @param partETags  sorted list of (partNumber, etag) pairs from all parts
     */
    public void completeMultipartUpload(String s3Key, String uploadId, List<PartETag> partETags) {
        String bucket = appProperties.getS3().getBucketName();
        log.debug("Completing multipart upload: key={}, uploadId={}, parts={}",
                s3Key, uploadId, partETags.size());

        CompleteMultipartUploadRequest request =
                new CompleteMultipartUploadRequest(bucket, s3Key, uploadId, partETags);
        amazonS3.completeMultipartUpload(request);

        log.info("Multipart upload completed: key={}", s3Key);
    }

    /**
     * Aborts an in-progress multipart upload, freeing the stored parts on S3.
     *
     * Should be called when an upload is cancelled or times out. Failure to abort
     * leaves orphaned parts that incur S3 storage charges.
     */
    public void abortMultipartUpload(String s3Key, String uploadId) {
        String bucket = appProperties.getS3().getBucketName();
        log.info("Aborting multipart upload: key={}, uploadId={}", s3Key, uploadId);

        AbortMultipartUploadRequest request =
                new AbortMultipartUploadRequest(bucket, s3Key, uploadId);
        amazonS3.abortMultipartUpload(request);
    }

    /**
     * Permanently deletes an S3 object.
     * Used when a file is deleted by its owner.
     */
    public void deleteObject(String s3Key) {
        String bucket = appProperties.getS3().getBucketName();
        log.info("Deleting S3 object: key={}", s3Key);
        amazonS3.deleteObject(new DeleteObjectRequest(bucket, s3Key));
    }
}
