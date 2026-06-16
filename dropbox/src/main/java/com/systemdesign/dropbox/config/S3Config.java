package com.systemdesign.dropbox.config;

import com.amazonaws.auth.AWSStaticCredentialsProvider;
import com.amazonaws.auth.BasicAWSCredentials;
import com.amazonaws.auth.DefaultAWSCredentialsProviderChain;
import com.amazonaws.client.builder.AwsClientBuilder;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3ClientBuilder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

/**
 * Configures the AWS SDK v1 AmazonS3 client.
 *
 * Production: uses DefaultAWSCredentialsProviderChain (env vars AWS_ACCESS_KEY_ID /
 * AWS_SECRET_ACCESS_KEY, instance profile, ECS task role, etc.).
 *
 * Local development: when app.s3.endpoint-override is set (e.g. http://localhost:4566),
 * the client points to LocalStack using dummy credentials. This lets all
 * S3 operations work against a local container without real AWS credentials.
 */
@Slf4j
@Configuration
public class S3Config {

    @Bean
    public AmazonS3 amazonS3(AppProperties props) {
        AppProperties.S3Properties s3Props = props.getS3();
        String endpointOverride = s3Props.getEndpointOverride();

        if (StringUtils.hasText(endpointOverride)) {
            log.info("S3 using endpoint override: {} (LocalStack / custom S3)", endpointOverride);
            return AmazonS3ClientBuilder.standard()
                    .withEndpointConfiguration(
                            new AwsClientBuilder.EndpointConfiguration(
                                    endpointOverride, s3Props.getRegion()))
                    .withCredentials(new AWSStaticCredentialsProvider(
                            new BasicAWSCredentials("test", "test")))
                    .withPathStyleAccessEnabled(true)   // required for LocalStack
                    .build();
        }

        log.info("S3 using region: {} (production AWS)", s3Props.getRegion());
        return AmazonS3ClientBuilder.standard()
                .withRegion(s3Props.getRegion())
                .withCredentials(DefaultAWSCredentialsProviderChain.getInstance())
                .build();
    }
}
